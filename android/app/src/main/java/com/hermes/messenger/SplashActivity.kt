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

class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
