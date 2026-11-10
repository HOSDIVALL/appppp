package com.example.myapplicationfirstapp.data

import android.content.Context

/** 全局深浅色 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/** 列表排序方式 */
enum class SortMode(val label: String) {
    UPDATED("最近修改"),
    CREATED("创建时间"),
    TITLE("标题")
}

/**
 * 极简的偏好存储。用 SharedPreferences 就够，没必要为此引入 DataStore。
 * 目前存排序方式和主题字体，以后加「字号」「默认视图」也放这里。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sortMode: SortMode
        get() = SortMode.entries.getOrNull(prefs.getInt(KEY_SORT, 0)) ?: SortMode.UPDATED
        set(value) {
            prefs.edit().putInt(KEY_SORT, value.ordinal).apply()
        }

    /** 主题字体（FontOption.id，自定义字体同走 FontOption 的解析），默认 Geom */
    var fontId: String
        get() = prefs.getString(KEY_FONT, null) ?: "geom"
        set(value) {
            prefs.edit().putString(KEY_FONT, value).apply()
        }

    /** 导入的自定义字体文件（filesDir/fonts 下），未导入为 null */
    var customFontPath: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_FONT_PATH, value).apply()
        }

    /** 自定义字体的展示名（去掉扩展名），未导入为 null */
    var customFontName: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_FONT_NAME, value).apply()
        }

    /** 「精确提醒」提示是否已经弹过，避免每次设提醒都唠叨一遍 */
    var exactAlarmHintShown: Boolean
        get() = prefs.getBoolean(KEY_EXACT_HINT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXACT_HINT, value).apply()
        }

    /** 全局深浅色，默认跟随系统 */
    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrNull(prefs.getInt(KEY_THEME_MODE, 0)) ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()
        }

    /**
     * 主题预设 id（见 ui/theme/ThemePresets.kt）。
     * 默认 "system"：Android 12+ 走壁纸取色，低版本回退到紫罗兰。
     */
    var themePresetId: String
        get() = prefs.getString(KEY_THEME_PRESET, "system") ?: "system"
        set(value) {
            prefs.edit().putString(KEY_THEME_PRESET, value).apply()
        }

    /** 界面语言：zh / en，默认中文 */
    var appLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "zh") ?: "zh"
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        }

    /** 录音保存后是否自动转写，默认开 */
    var autoTranscribe: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, value).apply()
        }

    private companion object {
        const val KEY_SORT = "sort_mode"
        const val KEY_FONT = "font_id"
        const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        const val KEY_EXACT_HINT = "exact_alarm_hint"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_PRESET = "theme_preset"
        const val KEY_LANGUAGE = "app_language"
        const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
    }
}
