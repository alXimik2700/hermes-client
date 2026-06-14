package com.hermes.messenger

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import com.hermes.messenger.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OTA check — runs in background while splash video plays
        CoroutineScope(Dispatchers.IO).launch {
            AppUpdater.check(this@SplashActivity)

            // Always load server URL from prefs
            val prefs = getSharedPreferences("hermes_config", MODE_PRIVATE)
            val savedUrl = prefs.getString("server_url", null)
            if (savedUrl != null) {
                AppConfig.currentServerUrl = savedUrl
                AppConfig.mobileServerUrl = prefs.getString("mobile_url", savedUrl) ?: savedUrl
            } else {
                AppConfig.initFromPrefs(this@SplashActivity)
            }
        }

        val videoView = VideoView(this)
        videoView.setVideoURI(Uri.parse("android.resource://${packageName}/${R.raw.splash_video}"))
        videoView.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = false
            videoView.start()
        }
        videoView.setOnCompletionListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        videoView.setOnErrorListener { _, _, _ ->
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            true
        }

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            })
        }
        setContentView(layout)
    }
}
