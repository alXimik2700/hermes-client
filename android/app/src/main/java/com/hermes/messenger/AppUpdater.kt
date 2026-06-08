package com.hermes.messenger

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OTA Updater — checks for new version, downloads APK, triggers install.
 * Call AppUpdater.check(context) from HermesSyncService.onCreate()
 */
object AppUpdater {

    private const val PREFS_NAME = "app_updater"
    private const val KEY_LAST_CHECKED = "last_checked_version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(context: Context) {
        try {
            val baseUrl = AppConfig.REMOTE_URL.trimEnd('/')
            val token = AppConfig.API_TOKEN

            val request = Request.Builder()
                .url("$baseUrl/api/app/version")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                response.close()
                return
            }

            val body = response.body?.string() ?: return
            response.close()

            val json = JSONObject(body)
            val serverVersion = json.getInt("versionCode")
            val apkUrl = json.getString("apkUrl")
            val apkSize = json.optLong("apkSize", 0)

            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
                .toInt() // versionCode as int for comparison

            if (serverVersion <= currentVersion) {
                return // Already up to date
            }

            // Prevent duplicate download for same version
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_LAST_CHECKED, 0) >= serverVersion) return

            // Download APK
            downloadApk(context, apkUrl, serverVersion)

        } catch (e: Exception) {
            // Silently fail — update is not critical
            e.printStackTrace()
        }
    }

    private fun downloadApk(context: Context, apkUrl: String, version: Int) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Replace localhost with actual server if needed
        val url = apkUrl
            .replace("127.0.0.1:5001", AppConfig.REMOTE_URL.trimEnd('/')
                .replace("http://", "")
                .replace("https://", ""))

        val token = AppConfig.API_TOKEN
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Hermes Messenger Update")
            .setDescription("Downloading version $version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "hermes-messenger-v$version.apk"
            )
            .addRequestHeader("Authorization", "Bearer $token")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            request.setRequiresCharging(false)
        }

        val downloadId = dm.enqueue(request)

        // Mark as checked
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_CHECKED, version)
            .apply()

        // Register receiver to open APK after download
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)
                installApk(ctx, "hermes-messenger-v$version.apk")
            }
        }

        context.registerReceiver(
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(context: Context, filename: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            filename
        )
        if (!file.exists()) return

        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }
}
