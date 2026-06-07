package com.hermes.messenger

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the system default notification sound and optional vibration.
 * Initialise once with ApplicationContext, then call play() from anywhere.
 */
object SoundManager {

    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private var enabled = true

    fun init(context: Context) {
        val notificationUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(context.applicationContext, notificationUri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        Log.i("SoundManager", "Initialised — notification sound ready")
    }

    fun play() {
        if (!enabled) return
        try {
            ringtone?.play()
            vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w("SoundManager", "play failed", e)
        }
    }

    fun setEnabled(e: Boolean) { enabled = e }
}
