package com.hermes.messenger

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * RAM-only bridge between HermesSyncService (WebSocket) and ChatViewModel (UI).
 * Tokens pass through memory — zero disk I/O until stream_complete.
 */
object StreamBridge {
    data class StreamToken(val messageId: Long, val token: String)
    data class StreamComplete(val messageId: Long, val text: String)

    private val _tokens = MutableSharedFlow<StreamToken>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val tokens: SharedFlow<StreamToken> = _tokens

    private val _complete = MutableSharedFlow<StreamComplete>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val complete: SharedFlow<StreamComplete> = _complete

    fun onToken(messageId: Long, token: String) {
        _tokens.tryEmit(StreamToken(messageId, token))
    }

    fun onComplete(messageId: Long, text: String) {
        _complete.tryEmit(StreamComplete(messageId, text))
    }
}
