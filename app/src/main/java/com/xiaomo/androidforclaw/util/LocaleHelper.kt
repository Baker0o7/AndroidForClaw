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
 * Language management utility class
 *
 * Supports dynamic language switching (Chinese/English)
 *
 * Usage example:
 * ```kotlin
 * // Switch to Chinese
 * LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_CHINESE)

 * // Switch to English
 * LocaleHelper.setLocale(context, LocaleHelper.LANGUAGE_ENGLISH)

 * // Get current language
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
     * Set application language
     * @param context Context
     * @param language Language code (zh/en/system)
     * @return Updated Context
     */
    fun setLocale(context: Context, language: String): Context {
        saveLanguage(context, language)
        return updateResources(context, language)
    }

    /**
     * Get saved language settings
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    /**
     * Save language settings
     */
    private fun saveLanguage(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LANGUAGE, language).apply()
    }

    /**
     * Update resource configuration
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
     * Apply saved language settings
     */
    fun applyLanguage(context: Context): Context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }

    /**
     * Get current language display name
     */
    fun getLanguageDisplayName(context: Context): String {
        return when (getLanguage(context)) {
            LANGUAGE_CHINESE -> "Chinese"
            LANGUAGE_ENGLISH -> "English"
            LANGUAGE_SYSTEM -> "System"
            else -> "System"
        }
    }

    /**
     * Get all available languages
     */
    fun getAvailableLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption(LANGUAGE_SYSTEM, "System"),
            LanguageOption(LANGUAGE_ENGLISH, "English"),
            LanguageOption(LANGUAGE_CHINESE, "Chinese")
        )
    }

    data class LanguageOption(
        val code: String,
        val displayName: String
    )
}