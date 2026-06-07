package com.hermes.messenger.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class HermesRepository(private val dao: MessageDao) {

    val messagesFlow: Flow<List<HermesMessageEntity>> = dao.getAllMessagesFlow()

    suspend fun sendMessage(text: String): HermesMessageEntity {
        val msg = HermesMessageEntity(
            id = UUID.randomUUID().toString(),
            text = text,
            timestamp = System.currentTimeMillis(),
            isFromAgent = false,
            status = HermesMessageEntity.STATUS_PENDING,
            messageType = HermesMessageEntity.TYPE_TEXT
        )
        dao.insert(msg)
        return msg
    }

    suspend fun sendMedia(
        messageType: String,
        localFilePath: String,
        displayText: String = "[Media]"
    ): HermesMessageEntity {
        val msg = HermesMessageEntity(
            id = UUID.randomUUID().toString(),
            text = displayText,
            timestamp = System.currentTimeMillis(),
            isFromAgent = false,
            status = HermesMessageEntity.STATUS_PENDING,
            messageType = messageType,
            localFilePath = localFilePath
        )
        dao.insert(msg)
        return msg
    }

    /** Add AI reply with server timestamp and serverId for correct ordering. */
    suspend fun addAgentReply(text: String, serverTimestamp: Long = System.currentTimeMillis(), serverId: Long = 0) {
        if (dao.existsByTextAndSender(text, isFromAgent = true)) return
        val msgType = when {
            text.startsWith("[Voice:") || text.startsWith("[VoiceReply:") -> HermesMessageEntity.TYPE_VOICE
            text.startsWith("[File:") -> HermesMessageEntity.TYPE_FILE
            else -> HermesMessageEntity.TYPE_TEXT
        }
        val fileUrl = when (msgType) {
            HermesMessageEntity.TYPE_VOICE -> {
                val fn = text.removePrefix("[Voice:").removePrefix("[VoiceReply:").removeSuffix("]").trim()
                val justName = fn.substringAfterLast('/')
                "/uploads/$justName"
            }
            HermesMessageEntity.TYPE_FILE -> {
                val fn = text.removePrefix("[File:").removeSuffix("]").trim()
                "/media/$fn"
            }
            else -> null
        }
        val msg = HermesMessageEntity(
            id = UUID.randomUUID().toString(),
            text = text,
            timestamp = serverTimestamp,
            isFromAgent = true,
            status = HermesMessageEntity.STATUS_SENT,
            messageType = msgType,
            fileUrl = fileUrl,
            serverId = if (serverId > 0) serverId else null
        )
        dao.insert(msg)
    }

    suspend fun markSent(id: String) {
        dao.updateStatus(id, HermesMessageEntity.STATUS_SENT)
    }

    suspend fun markSentWithServerTime(id: String, serverTimeStr: String, serverId: Long) {
        try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val epoch = fmt.parse(serverTimeStr)?.time ?: System.currentTimeMillis()
            dao.markSentWithTime(id, epoch, serverId)
        } catch (_: Exception) {
            dao.updateStatus(id, HermesMessageEntity.STATUS_SENT)
        }
    }

    suspend fun markSentWithUrl(id: String, fileUrl: String) {
        dao.markSent(id, HermesMessageEntity.STATUS_SENT, fileUrl)
    }

    suspend fun markSentWithUrlAndServerId(id: String, fileUrl: String, serverId: Long) {
        dao.markSent(id, HermesMessageEntity.STATUS_SENT, fileUrl)
        dao.markSentWithServerId(id, serverId)
    }

    suspend fun getPending(): List<HermesMessageEntity> = dao.getPendingMessages()

    suspend fun pendingCount(): Int = dao.pendingCount()

    // --- Network methods for Profile/Settings ---

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class AgentStatus(
        val agentStatus: String = "offline",
        val model: String = "",
        val totalTokens: Long = 0L,
        val lastActive: Long = 0L,
    )

    data class AttachmentMsg(
        val id: Long, val sender: String, val text: String,
        val time: String, val type: String = "file",
        val url: String = "", val filename: String = "",
    )

    suspend fun fetchAgentStatus(): AgentStatus? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${com.hermes.messenger.AppConfig.currentServerUrl}api/agent/status")
                .header("Authorization", "Bearer ${com.hermes.messenger.AppConfig.API_TOKEN}")
                .get().build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            AgentStatus(
                agentStatus = json.optString("agent_status", "offline"),
                model = json.optString("model", ""),
                totalTokens = json.optLong("total_tokens", 0),
                lastActive = json.optLong("last_active", 0),
            )
        } catch (_: Exception) { null }
    }

    suspend fun fetchAttachments(type: String, limit: Int = 30): List<AttachmentMsg> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${com.hermes.messenger.AppConfig.currentServerUrl}api/messages/attachments?type=$type&limit=$limit")
                .header("Authorization", "Bearer ${com.hermes.messenger.AppConfig.API_TOKEN}")
                .get().build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val arr = JSONObject(body).optJSONArray("messages") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val att = m.optJSONObject("attachment")
                AttachmentMsg(
                    id = m.optLong("id"), sender = m.optString("sender"),
                    text = m.optString("text"), time = m.optString("time"),
                    type = att?.optString("type", "file") ?: "file",
                    url = att?.optString("url", "") ?: "",
                    filename = att?.optString("filename", "") ?: "",
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
