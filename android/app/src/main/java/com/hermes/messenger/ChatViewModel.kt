package com.hermes.messenger

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.hermes.messenger.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.messenger.data.AppDatabase
import com.hermes.messenger.data.HermesMessageEntity
import com.hermes.messenger.data.HermesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed interface ConnectionStatus {
    data object Online : ConnectionStatus
    data object Offline : ConnectionStatus
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application.applicationContext
    private val db = AppDatabase.getInstance(application)
    private val repo = HermesRepository(db.messageDao())
    private val connectivityManager = ctx.getSystemService(ConnectivityManager::class.java)

    /** Completed messages from Room — source of truth. */
    val messages: StateFlow<List<HermesMessageEntity>> = repo.messagesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Streaming token overlay: message_id → accumulated partial text.
     * NOT persisted to Room per token — RAM only, zero disk I/O.
     * Updated by HermesWebSocketManager via observeStream().
     * Cleared on stream_complete (final text written to Room once).
     */
    private val _streamTokens = MutableStateFlow<Map<Long, String>>(emptyMap())
    val streamTokens: StateFlow<Map<Long, String>> = _streamTokens.asStateFlow()

    private val _streamingIds = MutableStateFlow<Set<Long>>(emptySet())
    val streamingIds: StateFlow<Set<Long>> = _streamingIds.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    fun setThinking(thinking: Boolean) { _isThinking.value = thinking }

    /**
     * Check real network connectivity status.
     */
    private fun isNetworkAvailable(): Boolean {
        try {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps != null) {
                    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }
            // Fallback: check if any network is available
            val allNetworks = connectivityManager.allNetworks
            for (net in allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(net)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Update connection status based on actual network state.
     * Called periodically and on network changes.
     */
    fun refreshConnectionStatus() {
        val online = isNetworkAvailable()
        _connectionStatus.value = if (online) ConnectionStatus.Online else ConnectionStatus.Offline
    }

    init {
        // Check initial network state
        refreshConnectionStatus()

        // Monitor network changes
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                _connectionStatus.value = ConnectionStatus.Online
            }
            override fun onLost(network: android.net.Network) {
                _connectionStatus.value = ConnectionStatus.Offline
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        viewModelScope.launch {
            _pendingCount.value = repo.pendingCount()
        }
        // Observe streaming tokens from WebSocket via RAM bridge
        viewModelScope.launch {
            StreamBridge.tokens.collect { event ->
                onStreamToken(event.messageId, event.token)
            }
        }
        viewModelScope.launch {
            StreamBridge.complete.collect { event ->
                onStreamComplete(event.messageId, event.text)
            }
        }
    }

    // ── Streaming from WebSocket (NO ROOM DB PER TOKEN) ────

    fun onStreamToken(messageId: Long, token: String) {
        val current = _streamTokens.value.toMutableMap()
        current[messageId] = (current[messageId] ?: "") + token
        _streamTokens.value = current
        _streamingIds.value = _streamingIds.value + messageId
        _isThinking.value = true
    }

    fun onStreamComplete(messageId: Long, finalText: String) {
        // Clear streaming overlay
        val tokens = _streamTokens.value.toMutableMap()
        tokens.remove(messageId)
        _streamTokens.value = tokens
        _streamingIds.value = _streamingIds.value - messageId
        _isThinking.value = false

        // Write final text to Room ONCE — single disk I/O
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveAIMessage(messageId, finalText)
            _pendingCount.value = repo.pendingCount()
        }
    }

    fun sendMessage(text: String, target: String = "hermes") {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val msg = repo.sendMessage(text, target)
                android.util.Log.e("ChatVM", "Message saved to Room: ${msg.id.take(8)}... target=$target")
            } finally {
                _loading.value = false
                _pendingCount.value = repo.pendingCount()
            }
        }
    }

    fun sendVoice(audioUri: Uri, fileName: String = "voice.m4a", target: String = "hermes") {
        sendMediaFile(audioUri, fileName, HermesMessageEntity.TYPE_VOICE, target)
    }

    fun sendImage(imageUri: Uri, fileName: String = "photo.jpg", target: String = "hermes") {
        sendMediaFile(imageUri, fileName, HermesMessageEntity.TYPE_IMAGE, target)
    }

    fun sendFile(fileUri: Uri, fileName: String, mimeType: String, target: String = "hermes") {
        sendMediaFile(fileUri, fileName, HermesMessageEntity.TYPE_FILE, target)
    }

    private fun sendMediaFile(sourceUri: Uri, fileName: String, mediaType: String, target: String = "hermes") {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val cacheFile = File(ctx.cacheDir, "media_${System.currentTimeMillis()}_$fileName")
                withContext(Dispatchers.IO) {
                    if (sourceUri.scheme == "file") {
                        val src = File(sourceUri.path!!)
                        src.inputStream().use { input ->
                            FileOutputStream(cacheFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } else {
                        ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                            FileOutputStream(cacheFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    android.util.Log.e("ChatVM", "sendMedia: failed to cache file from $sourceUri")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.audio_read_error), android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val displayText = when (mediaType) {
                    HermesMessageEntity.TYPE_VOICE -> "[Voice: $fileName]"
                    HermesMessageEntity.TYPE_IMAGE -> "[Image: $fileName]"
                    else -> "[File: $fileName]"
                }
                val msg = repo.sendMedia(mediaType, cacheFile.absolutePath, displayText, target)
                android.util.Log.e("ChatVM", "Media saved: type=$mediaType id=${msg.id.take(8)}... path=${cacheFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("ChatVM", "sendMedia failed", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.send_error, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                _loading.value = false
                _pendingCount.value = repo.pendingCount()
            }
        }
    }

    fun updateConnection(online: Boolean) {
        _connectionStatus.value = if (online) ConnectionStatus.Online else ConnectionStatus.Offline
        if (online) {
            viewModelScope.launch { _pendingCount.value = repo.pendingCount() }
        }
    }
}
