/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 语言Manage工具Class
 *
 * SupportDynamicswitchapply语言(中文/英文)
 *
 * use示例: 
 * ```kotlin
 * // switch到中文
 * LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_CHINESE)
 *
 * // switch到英文
 * LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_ENGLISH)
 *
 * // Get当Front语言
 * val currentLang = LocaleHelper.getLanguage(context)
 * ```
 */
object LocaleHelper {
    private const val PREF_LANGUAGE = "pref_language"
    private const val PREF_NAME = "locale_settings"

    const val LANGUAGE_CHINESE = "zh"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_SYSTEM = "system"

    /**
     * Settingsapply语言
     * @param context Context
     * @param language 语言代码(zh/en/system)
     * @return UpdateBack的 Context
     */
    fun setLocale(context: Context, language: String): Context {
        saveLanguage(context, language)
        return updateResources(context, language)
    }

    /**
     * GetSave的语言Settings
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    /**
     * Save语言Settings
     */
    private fun saveLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LANGUAGE, language).apply()
    }

    /**
     * UpdateResourceConfig
     */
    private fun updateResources(context: Context, language: String): Context {
        val locale = when (language) {
            LANGUAGE_CHINESE -> Locale.CHINESE
            LANGUAGE_ENGLISH -> Locale.ENGLISH
            LANGUAGE_SYSTEM -> Locale.getDefault()
            else -> Locale.getDefault()
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * apply已Save的语言Settings
     */
    fun applyLanguage(context: Context): Context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }

    /**
     * Get当Front语言的ShowName
     */
    fun getLanguageDisplayName(context: Context): String {
        return when (getLanguage(context)) {
            LANGUAGE_CHINESE -> "中文"
            LANGUAGE_ENGLISH -> "English"
            LANGUAGE_SYSTEM -> "System / 系统"
            else -> "System / 系统"
        }
    }

    /**
     * GetAllAvailable语言
     */
    fun getAvailableLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption(LANGUAGE_SYSTEM, "System / 系统"),
            LanguageOption(LANGUAGE_ENGLISH, "English"),
            LanguageOption(LANGUAGE_CHINESE, "中文")
        )
    }

    data class LanguageOption(
        val code: String,
        val displayName: String
    )
}
