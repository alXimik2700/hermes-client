package com.aigena.messenger

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket manager for real-time message delivery.
 * Replaces HTTP long-poll GET /api/messages with WS /api/messages/stream.
 * Connects to voice_gateway (FastAPI :5002) for sub-second latency.
 */
class HermesWebSocketManager(
    private val serverUrl: String,
    private val token: String
) {
    private val TAG = "HermesWS"

    // Dedicated OkHttp client for WebSocket — infinite read timeout
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(AppConfig.WS_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isConnected: Boolean = false
        private set
    @Volatile private var lastServerId: Long = 0

    // Network binding — socket factory from active network
    private var networkSocketFactory: javax.net.SocketFactory? = null

    fun setNetworkFactory(factory: javax.net.SocketFactory?) {
        networkSocketFactory = factory
        if (isConnected) {
            // Reconnect on network switch
            disconnect()
            connect()
        }
    }

    // ── Incoming message stream ──────────────────────────

    private val _incomingMessages = MutableSharedFlow<WSMessage>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<WSMessage> = _incomingMessages

    data class WSMessage(
        val id: Long,
        val sender: String,
        val text: String,
        val time: String
    )

    // ── Connection lifecycle ─────────────────────────────

    fun connect(initialSinceId: Long = 0) {
        if (isConnected) return
        lastServerId = initialSinceId

        val wsUrl = buildWsUrl()
        Log.d(TAG, "Connecting to $wsUrl")

        val builder = Request.Builder().url(wsUrl)
        // Apply network-specific socket factory for Tailscale binding
        networkSocketFactory?.let { sf ->
            val clientWithNetwork = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(AppConfig.WS_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .socketFactory(sf)
                .build()
            webSocket = clientWithNetwork.newWebSocket(builder.build(), listener)
            return
        }

        webSocket = wsClient.newWebSocket(builder.build(), listener)
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun sendPing() {
        webSocket?.send("{\"ping\":true}")
    }

    // ── Internal ─────────────────────────────────────────

    private fun buildWsUrl(): String {
        // Replace http:// -> ws://, https:// -> wss://
        val base = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')
        // Point to voice_gateway port 5002 instead of main server 5001
        val wsBase = if (base.contains(":5001")) {
            base.replace(":5001", ":5002")
        } else {
            "$base:5002"
        }
        return "$wsBase/api/messages/stream?token=$token"
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnected = true
            // Send initial since ID
            webSocket.send("""{"since":$lastServerId}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val messages = json.optJSONArray("messages") ?: return
                val newMessages = mutableListOf<WSMessage>()
                for (i in 0 until messages.length()) {
                    val obj = messages.getJSONObject(i)
                    val msg = WSMessage(
                        id = obj.getLong("id"),
                        sender = obj.getString("sender"),
                        text = obj.getString("text"),
                        time = obj.optString("time", "")
                    )
                    newMessages.add(msg)
                    if (msg.id > lastServerId) lastServerId = msg.id
                }
                if (newMessages.isNotEmpty()) {
                    Log.d(TAG, "Received ${newMessages.size} messages, lastId=$lastServerId")
                    scope.launch {
                        newMessages.forEach { _incomingMessages.emit(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "Server closing: $code $reason")
            isConnected = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            isConnected = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            isConnected = false
            scheduleReconnect()
        }
    }

    // ── Auto-reconnect with backoff ──────────────────────

    private var reconnectAttempt = 0

    private fun scheduleReconnect() {
        scope.launch {
            val delayMs = AppConfig.WS_RECONNECT_SEQUENCE[
                reconnectAttempt.coerceAtMost(AppConfig.WS_RECONNECT_SEQUENCE.size - 1)
            ]
            Log.d(TAG, "Reconnect attempt ${reconnectAttempt + 1} in ${delayMs}ms")
            delay(delayMs)
            reconnectAttempt++
            if (!isConnected && scope.isActive) {
                connect(lastServerId)
            }
        }
    }

    fun resetReconnect() {
        reconnectAttempt = 0
    }
}
