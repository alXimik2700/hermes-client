package com.aigena.messenger

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Low-latency voice streaming via WebSocket to voice_gateway (:5002).
 * AudioRecord (16kHz) → WS → Whisper → DeepSeek → Piper (22kHz) → WS → AudioTrack.
 * Replaces batch MediaRecorder .m4a upload with real-time PCM streaming (~800ms latency).
 */
class HermesVoiceStreamer(
    private val serverUrl: String,
    private val token: String
) {
    private val TAG = "HermesVoice"

    // Separate sample rates: record at 16kHz (Whisper), play at 22.05kHz (Piper)
    private val RECORD_SAMPLE_RATE = 16000
    private val PLAY_SAMPLE_RATE = 22050
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isActive: Boolean = false
        private set

    // Callbacks for UI
    var onStateChanged: ((Boolean) -> Unit)? = null  // true = listening/speaking
    var onError: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isActive) return
        isActive = true

        // Init playback pipeline
        initAudioTrack()

        val wsUrl = buildWsUrl()
        Log.d(TAG, "Connecting voice stream to $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Voice WebSocket opened, starting mic")
                startMic()
                onStateChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // JSON status messages (done, error)
                try {
                    val json = JSONObject(text)
                    val status = json.optString("status", "")
                    if (status == "done") {
                        val elapsed = json.optInt("elapsed_ms", 0)
                        Log.d(TAG, "Voice response complete in ${elapsed}ms")
                    } else if (json.has("error")) {
                        Log.e(TAG, "Server error: ${json.optString("error")}")
                        onError?.invoke(json.optString("error"))
                    }
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Raw PCM audio from Piper — play immediately
                val audioData = bytes.toByteArray()
                audioTrack?.write(audioData, 0, audioData.size)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Voice WS failure: ${t.message}")
                onError?.invoke(t.message ?: "WebSocket error")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Voice WS closed: $code $reason")
                stop()
            }
        })
    }

    fun stop() {
        isActive = false
        onStateChanged?.invoke(false)

        // Send end-of-utterance marker so server starts processing
        try {
            webSocket?.send("""{"action":"end"}""")
        } catch (_: Exception) {}

        streamingJob?.cancel()
        streamingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord cleanup: ${e.message}")
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack cleanup: ${e.message}")
        }

        try {
            webSocket?.close(1000, "Client stop")
        } catch (_: Exception) {}
        webSocket = null

        Log.d(TAG, "Voice session stopped")
    }

    // ── Internal ─────────────────────────────────────────

    private fun buildWsUrl(): String {
        val base = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')
        val wsBase = if (base.contains(":5001")) {
            base.replace(":5001", ":5002")
        } else {
            "$base:5002"
        }
        return "$wsBase/api/voice-stream?token=$token"
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val bufferSize = AudioRecord.getMinBufferSize(
            RECORD_SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT
        ).coerceAtLeast(2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RECORD_SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            onError?.invoke("Microphone unavailable")
            stop()
            return
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        streamingJob = scope.launch {
            val audioBuffer = ByteArray(bufferSize)
            while (isActive && isActive) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readBytes > 0) {
                    val chunk = audioBuffer.copyOfRange(0, readBytes)
                    try {
                        webSocket?.send(chunk.toByteString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Send error: ${e.message}")
                        break
                    }
                } else if (readBytes < 0) {
                    Log.e(TAG, "AudioRecord read error: $readBytes")
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            PLAY_SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAY_SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack init failed")
        }
    }
}
