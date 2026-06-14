package com.hermes.messenger

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.hermes.messenger.R
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermes.messenger.data.AppDatabase
import com.hermes.messenger.data.HermesMessageEntity
import com.hermes.messenger.data.HermesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import java.net.InetAddress
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

class HermesSyncService : Service() {

    private lateinit var repo: HermesRepository
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var activeNetwork: Network? = null
    @Volatile private var isNetworkAvailable: Boolean = false

    // Wake-up channel: signaled on network available to interrupt backoff sleep
    private val networkWakeChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    // MAX PERFORMANCE OkHttp client — text messages (rebuild on network change)
    @Volatile private var client: OkHttpClient = buildClient(null)
    // MEDIA OkHttp client — separate pool, longer timeouts (rebuild on network change)
    @Volatile private var mediaClient: OkHttpClient = buildMediaClient(null)

    // WebSocket manager — real-time message delivery (replaces long-poll)
    private lateinit var wsManager: HermesWebSocketManager

    /** Wrap android.net.Network SocketFactory (java.net) -> javax.net.SocketFactory for OkHttp. */
    private fun javaxSocketFactory(network: Network): SocketFactory {
        val base = network.socketFactory
        return object : SocketFactory() {
            override fun createSocket(): Socket = base.createSocket()
            override fun createSocket(host: String, port: Int): Socket = base.createSocket(host, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                base.createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = base.createSocket(host, port)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                base.createSocket(address, port, localAddress, localPort)
        }
    }

    private fun buildClient(network: Network?): OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .callTimeout(AppConfig.CALL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(AppConfig.READ_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(AppConfig.WRITE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(
                AppConfig.MAX_IDLE_CONNECTIONS,
                AppConfig.KEEP_ALIVE_MINUTES,
                java.util.concurrent.TimeUnit.MINUTES
            ))
            .protocols(listOf(Protocol.HTTP_1_1))
        if (network != null) {
            builder.socketFactory(javaxSocketFactory(network))
        }
        return builder.build()
    }

    private fun buildMediaClient(network: Network?): OkHttpClient {
        val builder = okhttp3.OkHttpClient.Builder()
            .callTimeout(AppConfig.MEDIA_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectTimeout(AppConfig.MEDIA_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(AppConfig.MEDIA_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(AppConfig.MEDIA_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(
                AppConfig.MEDIA_MAX_IDLE_CONNECTIONS,
                AppConfig.MEDIA_KEEP_ALIVE_MINUTES,
                TimeUnit.MINUTES
            ))
            .protocols(listOf(Protocol.HTTP_1_1))
        if (network != null) {
            builder.socketFactory(javaxSocketFactory(network))
        }
        return builder.build()
    }

    /** Switch clients to new network — evicts all stale connections from old network. */
    private fun switchToNetwork(network: Network?) {
        activeNetwork = network
        client.connectionPool.evictAll()
        mediaClient.connectionPool.evictAll()
        client = buildClient(network)
        mediaClient = buildMediaClient(network)
        isNetworkAvailable = network != null
        // Reconnect WebSocket on new network
        if (::wsManager.isInitialized) {
            val sf = network?.let { javaxSocketFactory(it) }
            wsManager.setNetworkFactory(sf)
        }
        // Wake sync loop
        networkWakeChannel.trySend(Unit)
        android.util.Log.e("HermesSync", "Switched to network: $network, available=$isNetworkAvailable")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            android.util.Log.e("HermesSync", "Network available: $network")
            activeNetwork = network
            isNetworkAvailable = true
            client.connectionPool.evictAll()
        }

        override fun onLost(network: Network) {
            android.util.Log.e("HermesSync", "Network lost: $network")
            if (activeNetwork == network) {
                isNetworkAvailable = false
                client.connectionPool.evictAll()
                mediaClient.connectionPool.evictAll()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            android.util.Log.e("HermesSync", "Caps changed: $network internet=$hasInternet")
            if (hasInternet && network == activeNetwork) {
                isNetworkAvailable = true
                networkWakeChannel.trySend(Unit)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var lastMessageId: Long = 0

    override fun onCreate() {
        super.onCreate()
        lastMessageId = getSharedPreferences("hermes_sync", MODE_PRIVATE).getLong("last_id", 0)
        val db = AppDatabase.getInstance(this)
        repo = HermesRepository(db.messageDao())
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        // Init from saved prefs before creating WS manager
        AppConfig.initFromPrefs(this)
        // Init Cronet (Chrome TLS for DPI bypass)
        // Init WebSocket manager (replaces long-poll)
        wsManager = HermesWebSocketManager(
            serverUrl = AppConfig.currentServerUrl,
            token = AppConfig.API_TOKEN
        )
        android.util.Log.e("HermesSync", "Service created [NETWORK-AWARE+WS], lastId=$lastMessageId")
        startForeground()
        scope.launch { checkForUpdate() }
    }

    private fun saveLastId(id: Long) {
        if (id > lastMessageId) {
            lastMessageId = id
            getSharedPreferences("hermes_sync", MODE_PRIVATE).edit().putLong("last_id", id).apply()
        }
    }

    @Volatile private var syncRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.e("HermesSync", "Starting sync loop [NETWORK-AWARE]")
        if (!syncRunning) {
            syncRunning = true
            scope.launch { syncLoop() }
        }
        scope.launch { checkForUpdate() }
        return START_STICKY
    }

    private fun startForeground() {
        scope.launch { checkForUpdate() }
        val channelId = "hermes_sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Hermes Sync",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background sync service"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Hermes")
            .setContentText("Connected")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private suspend fun syncLoop() {
        android.util.Log.e("HermesSync", "Sync loop started [WS+Poll mode], BASE_URL=${AppConfig.currentServerUrl}")
        // Connect WebSocket for real-time message delivery
        wsManager.connect(lastMessageId)

        // Start collectors for incoming WebSocket messages + streaming tokens
        scope.launch { collectWSMessages() }
        scope.launch { collectStreaming() }

        var backoffIndex = 0
        var lastPollTime = 0L
        while (isActive) {
            try {
                // 1. Send all pending messages (HTTP REST — unchanged)
                flushPending()

                // 2. If WebSocket not connected, fallback to long-poll
                if (!wsManager.isConnected) {
                    val now = System.currentTimeMillis()
                    if (now - lastPollTime > 5000) { // Poll every 5 seconds max
                        val (replies, newSinceId) = longPoll(lastMessageId)
                        if (replies.isNotEmpty()) {
                            for ((text, ts, id) in replies) {
                                saveLastId(id)
                                repo.addAgentReply(text, ts, id)
                            }
                            android.util.Log.e("HermesSync", "Poll fallback: got ${replies.size} replies")
                        }
                        lastPollTime = now
                    }
                }

                backoffIndex = 0
            } catch (e: Exception) {
                android.util.Log.e("HermesSync", "Sync error: ${e.message}", e)
                backoffIndex = (backoffIndex + 1).coerceAtMost(AppConfig.BACKOFF_SEQUENCE.size - 1)
            }
            // Periodic flush interval
            val backoffMs = AppConfig.BACKOFF_SEQUENCE[backoffIndex]
            try {
                withTimeout(backoffMs) {
                    networkWakeChannel.receive()  // blocks until network event
                }
                android.util.Log.e("HermesSync", "Woke by network event, resetting backoff")
                backoffIndex = 0
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // Normal timeout — backoff completed, continue loop
            }
        }
    }

    /** Collect WebSocket messages and save to Room DB. */
    private suspend fun collectWSMessages() {
        wsManager.incomingMessages.collect { msg ->
            try {
                if (msg.sender == "ai") {
                    val ts = try {
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        fmt.parse(msg.time)?.time ?: System.currentTimeMillis()
                    } catch (_: Exception) { System.currentTimeMillis() }
                    repo.addAgentReply(msg.text, ts, msg.id)
                }
                if (msg.id > lastMessageId) {
                    saveLastId(msg.id)
                }
            } catch (e: Exception) {
                android.util.Log.e("HermesSync", "WS save error: ${e.message}")
            }
        }
    }

    /** Forward streaming tokens from WS → RAM bridge (NO Room DB writes). */
    private suspend fun collectStreaming() {
        // Launch two coroutines: tokens and complete
        coroutineScope {
            launch {
                wsManager._streamTokens.collect { st ->
                    StreamBridge.onToken(st.messageId, st.token)
                }
            }
            launch {
                wsManager._streamComplete.collect { sc ->
                    StreamBridge.onComplete(sc.messageId, sc.text)
                    if (sc.messageId > lastMessageId) {
                        saveLastId(sc.messageId)
                    }
                }
            }
        }
    }

    /** Flush pending messages — TEXT via JSON, MEDIA via Multipart. */
    private suspend fun flushPending() {
        val pending = repo.getPending()
        if (pending.isNotEmpty()) android.util.Log.e("HermesSync", "Flushing ${pending.size} pending messages")

        for (msg in pending) {
            try {
                val success = when (msg.messageType) {
                    HermesMessageEntity.TYPE_TEXT -> sendTextMessage(msg)
                    else -> sendMediaMessage(msg)
                }
                if (!success) {
                    // Break on first failure — retry on next poll cycle
                    android.util.Log.e("HermesSync", "Flush aborted at ${msg.id.take(8)}... (${msg.messageType})")
                    break
                }
            } catch (e: Exception) {
                android.util.Log.e("HermesSync", "Send error [${msg.messageType}]: ${e.message}")
                break
            }
        }
    }

    /** Send TEXT message via JSON POST /api/send. */
    private suspend fun sendTextMessage(msg: HermesMessageEntity): Boolean {
        val body = JSONObject().apply {
            put("text", msg.text)
            put("client_uuid", msg.id)
            put("target", msg.target)
            put("sender_name", "user")
        }
        val req = okhttp3.Request.Builder()
            .url("${AppConfig.currentServerUrl}api/send")
            .header("Authorization", "Bearer ${AppConfig.API_TOKEN}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        android.util.Log.e("HermesSync", "Sending TEXT ${msg.id.take(8)}...")
        val resp = withTimeout(AppConfig.SEND_TIMEOUT_SEC.seconds) { client.newCall(req).execute() }
        if (resp.isSuccessful) {
            val body = resp.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            val serverTime = json.optString("server_time", "")
            val serverId = json.optLong("user_message_id", 0)
            if (serverTime.isNotEmpty() && serverId > 0) {
                repo.markSentWithServerTime(msg.id, serverTime, serverId)
            } else {
                repo.markSent(msg.id)
            }
            android.util.Log.e("HermesSync", "Sent TEXT: ${msg.id.take(8)}...")
            return true
        } else {
            android.util.Log.e("HermesSync", "TEXT send failed HTTP ${resp.code}")
            return false
        }
    }

    /** Send MEDIA (VOICE/IMAGE/FILE) via Multipart POST /api/upload. */
    private suspend fun sendMediaMessage(msg: HermesMessageEntity): Boolean {
        val localPath = msg.localFilePath
        if (localPath == null) {
            android.util.Log.e("HermesSync", "MEDIA ${msg.id.take(8)}: localFilePath is null, skipping")
            return false
        }
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) {
            android.util.Log.e("HermesSync", "MEDIA ${msg.id.take(8)}: file missing or empty: $localPath")
            return false
        }

        val mimeType = guessMimeType(file.name)
        val fileBody = file.readBytes().toRequestBody(mimeType.toMediaType())
        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
            .addFormDataPart("client_uuid", msg.id)
            .addFormDataPart("target", msg.target)
            .build()

        val req = okhttp3.Request.Builder()
            .url("${AppConfig.currentServerUrl}api/upload")
            .header("Authorization", "Bearer ${AppConfig.API_TOKEN}")
            .post(multipartBody)
            .build()

        android.util.Log.e("HermesSync", "Uploading ${msg.messageType} ${file.length()}B id=${msg.id.take(8)}...")
        val resp = mediaClient.newCall(req).execute()  // no withContext — already on IO thread
        if (!resp.isSuccessful) {
            android.util.Log.e("HermesSync", "Upload failed HTTP ${resp.code}")
            return false
        }

        val json = JSONObject(resp.body?.string() ?: "{}")
        val duplicate = json.optBoolean("duplicate", false)
        val serverUrl = json.optString("url", "")
        val size = json.optLong("size", 0)
        val msgId = json.optLong("message_id", 0)

        if (duplicate) {
            android.util.Log.e("HermesSync", "Upload DUPLICATE ${msg.id.take(8)} — server already has it")
        } else {
            android.util.Log.e("HermesSync", "Upload OK: $size bytes, url=$serverUrl, serverId=$msgId")
        }

        // Mark sent, persist server fileUrl, and assign serverId for correct ordering
        if (msgId > 0) {
            repo.markSentWithUrlAndServerId(msg.id, serverUrl, msgId)
        } else {
            repo.markSentWithUrl(msg.id, serverUrl)
        }
        return true
    }

    /** Simple MIME guess from filename extension. */
    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private suspend fun longPoll(sinceId: Long): Pair<List<Triple<String, Long, Long>>, Long> {
        val req = okhttp3.Request.Builder()
            .url("${AppConfig.currentServerUrl}api/messages?since=$sinceId&limit=50&timeout=${AppConfig.LONG_POLL_TIMEOUT_SEC}")
            .header("Authorization", "Bearer ${AppConfig.API_TOKEN}")
            .get()
            .build()
        val resp = withTimeout(AppConfig.POLL_CLIENT_TIMEOUT_SEC.seconds) { client.newCall(req).execute() }
        if (!resp.isSuccessful) {
            android.util.Log.e("HermesSync", "Long-poll HTTP ${resp.code} since=$sinceId")
            return Pair(emptyList(), sinceId)
        }
        val json = JSONObject(resp.body?.string() ?: "{}")
        val arr: JSONArray = json.optJSONArray("messages") ?: return Pair(emptyList(), sinceId)
        val result = mutableListOf<Triple<String, Long, Long>>()
        var maxId = sinceId
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getLong("id")
            if (id > maxId) maxId = id
            if (obj.getString("sender") == "ai") {
                val text = obj.getString("text")
                val timeStr = obj.optString("time", "")
                val ts = try {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    fmt.parse(timeStr)?.time ?: System.currentTimeMillis()
                } catch (_: Exception) { System.currentTimeMillis() }
                result.add(Triple(text, ts, id))
            }
        }
        if (result.isNotEmpty()) android.util.Log.e("HermesSync", "Poll got ${result.size} AI replies, maxId=$maxId")
        return Pair(result, maxId)
    }

    private val isActive: Boolean get() = scope.isActive

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        wsManager.disconnect()
        client.connectionPool.evictAll()
        mediaClient.connectionPool.evictAll()
        scope.cancel()
        super.onDestroy()
    }
    // === OTA UPDATE ===
    private suspend fun checkForUpdate() {
        try {
            val serverUrl = AppConfig.currentServerUrl
            val request = okhttp3.Request.Builder()
                .url("${serverUrl}api/app/version")
                .header("Authorization", "Bearer ${AppConfig.API_TOKEN}")
                .get()
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (!response.isSuccessful) return
            val body = response.body?.string() ?: return
            val json = org.json.JSONObject(body)
            val serverVersion = json.optLong("versionCode", 0)
            val localVersion = AppConfig.getAppVersionCode(this)
            android.util.Log.e("HermesSync", "OTA: local=$localVersion, server=$serverVersion")
            if (serverVersion > localVersion) {
                downloadAndInstall("${serverUrl}api/app/download")
            }
        } catch (e: Exception) {
            android.util.Log.e("HermesSync", "OTA check failed: ${e.message}")
        }
    }

    private suspend fun downloadAndInstall(url: String) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${AppConfig.API_TOKEN}")
                .get()
                .build()
            val response = withContext(Dispatchers.IO) { mediaClient.newCall(request).execute() }
            if (!response.isSuccessful) return
            val apkFile = java.io.File(cacheDir, "update.apk")
            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            }
            android.util.Log.e("HermesSync", "OTA: downloaded ${apkFile.length()} bytes")
            
            // Verify APK signature before showing install notification
            if (verifyApkSignature(apkFile)) {
                showUpdateNotification(apkFile)
            } else {
                android.util.Log.e("HermesSync", "OTA: APK signature verification failed")
                apkFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("HermesSync", "OTA download failed: ${e.message}")
        }
    }

    private fun verifyApkSignature(apkFile: java.io.File): Boolean {
        return try {
            val pm = packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }
            
            if (packageInfo == null) {
                android.util.Log.e("HermesSync", "OTA: Failed to parse APK")
                return false
            }
            
            // Get our own signing certificate
            val ourSignature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners[0].toByteArray()
                } else {
                    signingInfo.signingCertificateHistory[0].toByteArray()
                }
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures[0].toByteArray()
            }
            
            // Get APK signing certificate
            val apkSignature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners[0].toByteArray()
                } else {
                    signingInfo.signingCertificateHistory[0].toByteArray()
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures[0].toByteArray()
            }
            
            // Compare signatures
            val matches = ourSignature.contentEquals(apkSignature)
            if (!matches) {
                android.util.Log.e("HermesSync", "OTA: Signature mismatch - possible tampered APK")
            }
            matches
        } catch (e: Exception) {
            android.util.Log.e("HermesSync", "OTA: Signature verification error: ${e.message}")
            false
        }
    }

    private fun showUpdateNotification(apkFile: java.io.File) {
        val channelId = "ota_update"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, getString(R.string.ota_channel_name), NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, installIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.ota_title))
            .setContentText(getString(R.string.ota_content))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(999, notification)
    }
}
