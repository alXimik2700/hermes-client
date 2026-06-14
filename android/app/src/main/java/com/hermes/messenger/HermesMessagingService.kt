package com.hermes.messenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Push notification service.
 * Requires Firebase project setup + google-services.json.
 * Currently a placeholder — will be enabled when Firebase is configured.
 */
class HermesMessagingService {

    companion object {
        private const val TAG = "HermesPush"
        private const val CHANNEL_ID = "hermes_messages"
        private const val CHANNEL_NAME = "Messages"
    }

    // Uncomment when Firebase is configured:
    // class HermesMessagingService : FirebaseMessagingService() {
    //     override fun onNewToken(token: String) { ... }
    //     override fun onMessageReceived(message: RemoteMessage) { ... }
    // }
}
