package com.hermes.messenger

import android.util.Log

/**
 * Debug-only logging utility.
 * In release builds, all log calls are no-ops.
 * Usage: LogUtil.d("TAG", "message") or LogUtil.e("TAG", "error", exception)
 */
object LogUtil {
    private val enabled = BuildConfig.DEBUG

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun e(tag: String, msg: String) {
        if (enabled) Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable?) {
        if (enabled) Log.e(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }
}
