package com.hermes.messenger

import android.content.Context
import com.hermes.messenger.BuildConfig

object AppConfig {
    const val BASE_URL = "http://YOUR_SERVER_IP:5001/"
    val REMOTE_URL: String get() = BuildConfig.SERVER_URL.ifEmpty { "https://your-server.tailnet.ts.net/" }
    const val TAILSCALE_URL = "http://100.X.X.X:5001/"  // Direct WireGuard, no DPI

    /**
     * API token stored in Android Keystore via EncryptedSharedPreferences.
     * On first launch, migrates from BuildConfig to Keystore.
     */
    val API_TOKEN: String get() = _apiToken
    private var _apiToken: String = ""

    fun initToken(context: Context) {
        // First launch: migrate from BuildConfig to Keystore
        if (!SecureConfig.hasToken(context) && BuildConfig.API_TOKEN.isNotEmpty()) {
            SecureConfig.setApiToken(context, BuildConfig.API_TOKEN)
        }
        _apiToken = SecureConfig.getApiToken(context)
    }

    // Get current app version from Android package
    fun getAppVersionCode(context: android.content.Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: Exception) { 1L }
    }
    @Volatile var currentServerUrl: String = REMOTE_URL
    @Volatile var mobileServerUrl: String = REMOTE_URL
    @Volatile var isRemoteMode: Boolean = false

    fun initFromPrefs(ctx: Context) {
        // Initialize token from Keystore
        initToken(ctx)
        val prefs = ctx.getSharedPreferences("hermes_config", Context.MODE_PRIVATE)
        // Read saved URLs from preferences, fallback to defaults
        currentServerUrl = prefs.getString("server_url", REMOTE_URL) ?: REMOTE_URL
        mobileServerUrl = prefs.getString("mobile_url", REMOTE_URL) ?: REMOTE_URL
        isRemoteMode = prefs.getBoolean("remote_mode", false)
    }

    /**
     * Validate server URL — must be https:// or http://localhost
     * Returns true if valid, false if rejected.
     */
    fun validateUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        // Must start with http:// or https://
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        // Must not contain spaces
        if (trimmed.contains(" ")) return false
        // Must have a host (not just http://)
        val host = trimmed.removePrefix("https://").removePrefix("http://").split("/")[0]
        if (host.isEmpty() || host == "localhost") return true // localhost OK for dev
        // Must have a dot in hostname (basic validation)
        if (!host.contains(".")) return false
        return true
    }

    fun saveServerUrl(ctx: Context, url: String) {
        if (!validateUrl(url)) return
        currentServerUrl = url
        ctx.getSharedPreferences("hermes_config", Context.MODE_PRIVATE)
            .edit().putString("server_url", url).apply()
    }

    fun saveMobileUrl(ctx: Context, url: String) {
        if (!validateUrl(url)) return
        mobileServerUrl = url
        ctx.getSharedPreferences("hermes_config", Context.MODE_PRIVATE)
            .edit().putString("mobile_url", url).apply()
    }

    fun isRemoteMode(ctx: Context): Boolean {
        return ctx.getSharedPreferences("hermes_config", Context.MODE_PRIVATE)
            .getBoolean("remote_mode", false)
    }

    fun setRemoteMode(ctx: Context, enabled: Boolean) {
        isRemoteMode = enabled
        val prefs = ctx.getSharedPreferences("hermes_config", Context.MODE_PRIVATE)
        currentServerUrl = if (enabled) {
            prefs.getString("mobile_url", REMOTE_URL) ?: REMOTE_URL
        } else {
            prefs.getString("server_url", REMOTE_URL) ?: REMOTE_URL
        }
        prefs.edit().putBoolean("remote_mode", enabled).apply()
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
