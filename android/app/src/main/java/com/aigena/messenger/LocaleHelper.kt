package com.aigena.messenger

import android.content.Context
import android.content.SharedPreferences

/** Runtime locale switcher — does NOT commit to ANDROID resources (strings.xml);
 *  but wraps string resources so you can force-en override independent of device locale. */
object LocaleHelper {

    enum class Lang(val code: String, val display: String) {
        EN("en", "English"),
        RU("ru", "Русский");
    }

    private const val PREFS_NAME = "aigena_locale"
    private const val KEY_LANG = "lang_code"

    fun getLanguage(ctx: Context): Lang {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANG, Lang.EN.code) ?: Lang.EN.code
        return Lang.entries.firstOrNull { it.code == code } ?: Lang.EN
    }

    fun setLanguage(ctx: Context, lang: Lang) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.code).apply()
    }

    /** returns strings.xml value, falling back to RU translation map if lang is RU.
     *  This is a manual lookup — does NOT require Android resource qualifiers at runtime. */
    fun getString(ctx: Context, key: String, lang: Lang): String {
        // Always use localized resources via Configuration override
        return rawString(ctx, key, lang)
    }

    private fun rawString(ctx: Context, key: String, lang: Lang): String {
        val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
        if (resId == 0) return key
        val config = android.content.res.Configuration(ctx.resources.configuration)
        config.setLocale(java.util.Locale(lang.code))
        return ctx.createConfigurationContext(config).resources.getString(resId)
    }

}
