package com.aigena.messenger

object AppConfig {
    const val BASE_URL = "http://YOUR_SERVER_IP:5001/"
    const val REMOTE_URL = "https://YOUR_FUNNEL_URL/"
    const val API_TOKEN=""YOUR_API_TOKEN_HERE""
    @Volatile var currentServerUrl: String = BASE_URL

    fun initFromPrefs(ctx: android.content.Context) {
        val prefs = ctx.getSharedPreferences("aigena_config", android.content.Context.MODE_PRIVATE)
        currentServerUrl = prefs.getString("server_url", BASE_URL) ?: BASE_URL
    }

    fun isRemoteMode(ctx: android.content.Context): Boolean {
        return ctx.getSharedPreferences("aigena_config", android.content.Context.MODE_PRIVATE)
            .getBoolean("remote_mode", false)
    }

    fun setRemoteMode(ctx: android.content.Context, enabled: Boolean) {
        ctx.getSharedPreferences("aigena_config", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("remote_mode", enabled).apply()
        currentServerUrl = if (enabled) REMOTE_URL else BASE_URL
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
}
