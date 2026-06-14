package com.hermes.messenger

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for sensitive config using Android Keystore.
 * Backed by EncryptedSharedPreferences — data encrypted at rest.
 */
object SecureConfig {

    private const val PREFS_NAME = "hermes_secure_prefs"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_SERVER_URL = "server_url"

    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        if (prefs == null) {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return prefs!!
    }

    fun getApiToken(context: Context): String {
        return getPrefs(context).getString(KEY_API_TOKEN, "") ?: ""
    }

    fun setApiToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_API_TOKEN, token).apply()
    }

    fun getServerUrl(context: Context): String {
        return getPrefs(context).getString(KEY_SERVER_URL, "") ?: ""
    }

    fun setServerUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun hasToken(context: Context): Boolean {
        return getPrefs(context).contains(KEY_API_TOKEN) &&
                (getPrefs(context).getString(KEY_API_TOKEN, "")?.isNotEmpty() == true)
    }
}
