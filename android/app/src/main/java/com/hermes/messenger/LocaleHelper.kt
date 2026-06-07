package com.hermes.messenger

import android.content.Context
import android.content.SharedPreferences

/** Runtime locale switcher — does NOT commit to ANDROID resources (strings.xml);
 *  but wraps string resources so you can force-en override independent of device locale. */
object LocaleHelper {

    enum class Lang(val code: String, val display: String) {
        EN("en", "English"),
        RU("ru", "Русский");
    }

    private const val PREFS_NAME = "hermes_locale"
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
        // Manual translation map — simple, no complex Android locale APIs.
        if (lang == Lang.RU) {
            return ruMap[key] ?: rawString(ctx, key)
        }
        return rawString(ctx, key)
    }

    private fun rawString(ctx: Context, key: String): String {
        val resId = ctx.resources.getIdentifier(key, "string", ctx.packageName)
        return if (resId != 0) ctx.getString(resId) else key
    }

    private val ruMap: Map<String, String> = mapOf(
        "nav_chat" to "Чат",
        "nav_profile" to "Профиль",
        "nav_settings" to "Настройки",
        "chat_title" to "Hermes",
        "chat_online" to "Онлайн",
        "chat_connecting" to "Подключение...",
        "chat_offline" to "Офлайн",
        "chat_placeholder" to "Введите сообщение...",
        "chat_empty" to "Начните разговор...",
        "chat_waiting" to "Ожидание подключения...",
        "chat_typing" to "Hermes typing...",
        "chat_welcome" to "Hermes Messenger",
        "profile_name" to "Hermes",
        "profile_role" to "AI Ассистент",
        "profile_bio" to "Всегда рядом, чтобы помочь",
        "tab_photos" to "Фото",
        "tab_videos" to "Видео",
        "tab_files" to "Файлы",
        "tab_links" to "Ссылки",
        "tab_voice" to "Голосовые",
        "empty_photos" to "Фото пока нет",
        "empty_videos" to "Видео пока нет",
        "empty_files" to "Файлов пока нет",
        "empty_links" to "Ссылок пока нет",
        "empty_voice" to "Голосовых сообщений пока нет",
        "settings_title" to "Настройки",
        "settings_server" to "Подключение к серверу",
        "settings_server_url" to "Адрес сервера",
        "settings_server_url_hint" to "http://ip:5000",
        "settings_remote_mode" to "Мобильный интернет (через интернет)",
        "settings_connection_mode" to "Режим подключения",
        "settings_mode_http" to "HTTP опрос (по умолчанию)",
        "settings_mode_ws" to "WebSocket (скоро)",
        "settings_test_connection" to "Проверить соединение",
        "settings_polling" to "Опрос",
        "settings_poll_interval" to "Интервал опроса (мс)",
        "settings_fast" to "Быстро",
        "settings_slow" to "Медленно",
        "settings_language" to "Язык",
        "settings_about" to "О приложении",
        "about_version" to "Версия",
        "about_build" to "Сборка",
        "about_min_sdk" to "Мин. SDK",
        "lang_en" to "English",
        "lang_ru" to "Русский",
        "lang_switched" to "Язык изменён",
        "connection_testing" to "Проверка...",
        "connection_ok" to "Подключено — сервер доступен",
        "connection_fail" to "Ошибка подключения — проверьте адрес и сеть",
        "send" to "Отправить",
        "attach" to "Прикрепить файл",
        "file_uploaded" to "Файл отправлен",
        "upload_failed" to "Ошибка отправки файла",
        "retry" to "Повторить",
        // Agent status
        "agent_status_title" to "Статус агента",
        "agent_online" to "Онлайн",
        "agent_online_desc" to "Готов к работе",
        "agent_thinking" to "Думает...",
        "agent_thinking_desc" to "Генерирует ответ в веб-интерфейсе",
        "agent_offline" to "Не в сети",
        "agent_offline_desc" to "Сервер недоступен — проверьте подключение",
        "agent_connecting" to "Подключение...",
        "agent_connecting_desc" to "Установка соединения с сервером",
        // System info
        "system_info_title" to "Информация",
        "sys_model" to "Модель",
        "sys_tokens" to "Токенов",
        "sys_server" to "Сервер",
        "sys_app_version" to "Версия",
    )
}
