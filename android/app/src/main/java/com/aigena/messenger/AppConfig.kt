package com.aigena.messenger

import android.content.Context

object AppConfig {
    const val BASE_URL = "http://YOUR_SERVER_IP:5001/"
    const val REMOTE_URL = "http://YOUR_TAILSCALE_IP:5001/"
    const val API_TOKEN="YOUR_API_TOKEN_HERE"
    // Get current app version from Android package
    fun getAppVersionCode(context: android.content.Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: Exception) { 1L }
    }
    @Volatile var currentServerUrl: String = BASE_URL
    @Volatile var mobileServerUrl: String = REMOTE_URL
    @Volatile var isRemoteMode: Boolean = false

    fun initFromPrefs(ctx: Context) {
        val prefs = ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
        currentServerUrl = prefs.getString("server_url", BASE_URL) ?: BASE_URL
        mobileServerUrl = prefs.getString("mobile_url", REMOTE_URL) ?: REMOTE_URL
        isRemoteMode = prefs.getBoolean("remote_mode", false)
    }

    fun saveServerUrl(ctx: Context, url: String) {
        currentServerUrl = url
        ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
            .edit().putString("server_url", url).apply()
    }

    fun saveMobileUrl(ctx: Context, url: String) {
        mobileServerUrl = url
        ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
            .edit().putString("mobile_url", url).apply()
    }

    fun isRemoteMode(ctx: Context): Boolean {
        return ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
            .getBoolean("remote_mode", false)
    }

    fun setRemoteMode(ctx: Context, enabled: Boolean) {
        isRemoteMode = enabled
        val prefsUrl = ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
            .getString("server_url", BASE_URL)
        currentServerUrl = if (enabled) mobileServerUrl else (prefsUrl ?: BASE_URL)
        ctx.getSharedPreferences("aigena_config", Context.MODE_PRIVATE)
            .edit().putBoolean("remote_mode", enabled).apply()
    }

    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 120L
    const val WRITE_TIMEOUT_SEC = 60L
    const val CALL_TIMEOUT_SEC = 120L
    const val MAX_IDLE_CONNECTIONS = 20
    const val KEEP_ALIVE_MINUTES = 10L
    const val MEDIA_CONNECT_TIMEOUT_SEC = 30L
    const val MEDIA_READ_TIMEOUT_SEC = 180L
    const val MEDIA_WRITE_TIMEOUT_SEC = 120L
    const val MEDIA_CALL_TIMEOUT_SEC = 300L
    const val MEDIA_MAX_IDLE_CONNECTIONS = 5
    const val MEDIA_KEEP_ALIVE_MINUTES = 5L
    const val LONG_POLL_TIMEOUT_SEC = 15
    val BACKOFF_SEQUENCE = longArrayOf(500, 1000, 2000, 4000, 8000)
    const val SEND_TIMEOUT_SEC = 60L
    const val POLL_CLIENT_TIMEOUT_SEC = 60L

    // WebSocket config (voice_gateway :5002)
    const val WS_CONNECT_TIMEOUT_SEC = 30L
    val WS_RECONNECT_SEQUENCE = longArrayOf(1000, 2000, 5000, 10000, 30000)
}
