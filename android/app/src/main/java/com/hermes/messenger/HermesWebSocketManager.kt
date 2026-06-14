package com.hermes.messenger

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

    private val wsClient: OkHttpClient = run {
        val tlsFactory = CamouflageSSLSocketFactory()
        val defaultTrustManager = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(null as java.security.KeyStore?) }.trustManagers.first { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(defaultTrustManager), java.security.SecureRandom())
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(AppConfig.WS_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .sslSocketFactory(tlsFactory, defaultTrustManager)
            .build()
    }

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

    @Volatile var _streamTokens = MutableSharedFlow<WSStreamToken>(replay = 0, extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile var _streamComplete = MutableSharedFlow<WSStreamComplete>(replay = 0, extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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

    data class WSStreamToken(
        val messageId: Long,
        val token: String
    )

    data class WSStreamComplete(
        val messageId: Long,
        val text: String
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
            val clientWithNetwork = wsClient.newBuilder()
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
        // Map port 5001→5002 for direct local connections.
        // Funnel URLs (no explicit port) use default 443 + path routing.
        val wsBase = if (base.contains(":5001")) {
            base.replace(":5001", ":5002")
        } else {
            base  // Funnel URL — no port, path /api/messages routes to :5002
        }
        return "$wsBase/api/messages/stream"
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnected = true
            resetReconnect()
            // Send auth token as first message (not in URL)
            webSocket.send("""{"token":"$token"}""")
            // Send initial since ID
            webSocket.send("""{"since":$lastServerId}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                // Process messages array
                val messages = json.optJSONArray("messages")
                if (messages != null) {
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
                }
                // Streaming tokens
                val streamToken = json.optJSONObject("stream_token")
                if (streamToken != null) {
                    val st = WSStreamToken(
                        messageId = streamToken.getLong("message_id"),
                        token = streamToken.getString("token")
                    )
                    scope.launch { _streamTokens.emit(st) }
                }
                // Stream complete
                val streamComplete = json.optJSONObject("stream_complete")
                if (streamComplete != null) {
                    val sc = WSStreamComplete(
                        messageId = streamComplete.getLong("message_id"),
                        text = streamComplete.getString("text")
                    )
                    scope.launch { _streamComplete.emit(sc) }
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
