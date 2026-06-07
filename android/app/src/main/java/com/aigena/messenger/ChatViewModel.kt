package com.aigena.messenger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigena.messenger.data.AppDatabase
import com.aigena.messenger.data.HermesMessageEntity
import com.aigena.messenger.data.HermesRepository
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

    /** Messages from Room — single source of truth. */
    val messages: StateFlow<List<HermesMessageEntity>> = repo.messagesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Online)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    init {
        viewModelScope.launch {
            _pendingCount.value = repo.pendingCount()
        }
    }

    fun sendMessage(text: String) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val msg = repo.sendMessage(text)
                android.util.Log.e("ChatVM", "Message saved to Room: ${msg.id.take(8)}...")
            } finally {
                _loading.value = false
                _pendingCount.value = repo.pendingCount()
            }
        }
    }

    /**
     * Offline-First media send:
     * 1. Copy file to app cache (so Uri permissions survive process death).
     * 2. Insert PENDING message into Room with localFilePath.
     * 3. UI picks it up via Flow — no network call.
     */
    fun sendVoice(audioUri: Uri, fileName: String = "voice.m4a") {
        sendMediaFile(audioUri, fileName, HermesMessageEntity.TYPE_VOICE)
    }

    fun sendImage(imageUri: Uri, fileName: String = "photo.jpg") {
        sendMediaFile(imageUri, fileName, HermesMessageEntity.TYPE_IMAGE)
    }

    fun sendFile(fileUri: Uri, fileName: String, mimeType: String) {
        sendMediaFile(fileUri, fileName, HermesMessageEntity.TYPE_FILE)
    }

    private fun sendMediaFile(sourceUri: Uri, fileName: String, mediaType: String) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                // Copy to app cache — Uri permissions may expire
                val cacheFile = File(ctx.cacheDir, "media_${System.currentTimeMillis()}_$fileName")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    android.util.Log.e("ChatVM", "sendMedia: failed to cache file")
                    return@launch
                }
                val displayText = when (mediaType) {
                    HermesMessageEntity.TYPE_VOICE -> "[Voice: $fileName]"
                    HermesMessageEntity.TYPE_IMAGE -> "[Image: $fileName]"
                    else -> "[File: $fileName]"
                }
                val msg = repo.sendMedia(mediaType, cacheFile.absolutePath, displayText)
                android.util.Log.e("ChatVM", "Media saved: type=$mediaType id=${msg.id.take(8)}... path=${cacheFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e("ChatVM", "sendMedia failed", e)
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
