/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.context
import android.content.res.configuration
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
 * Localehelper.setLocale(context, Localehelper.LANGUAGE_CHINESE)

 * // Switch to English
 * Localehelper.setLocale(context, Localehelper.LANGUAGE_ENGLISH)

 * // Get current language
 * val currentLang = Localehelper.getLanguage(context)
 * ```
 */
object Localehelper {
    private const val PREF_LANGUAGE = "pref_language"
    private const val PREF_NAME = "locale_settings"

    const val LANGUAGE_CHINESE = "zh"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_SYSTEM = "system"

    /**
     * Set application language
     * @param context context
     * @param language Language code (zh/en/system)
     * @return Updated context
     */
    fun setLocale(context: context, language: String): context {
        saveLanguage(context, language)
        return updateResources(context, language)
    }

    /**
     * Get saved language settings
     */
    fun getLanguage(context: context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, context.MODE_PRIVATE)
        return prefs.getString(PREF_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    /**
     * Save language settings
     */
    private fun saveLanguage(context: context, language: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LANGUAGE, language).app()
    }

    /**
     * Update resource configuration
     */
    private fun updateResources(context: context, language: String): context {
        val locale = when (language) {
            LANGUAGE_CHINESE -> Locale.CHINESE
            LANGUAGE_ENGLISH -> Locale.ENGLISH
            LANGUAGE_SYSTEM -> Locale.getDefault()
            else -> Locale.getDefault()
        }

        Locale.setDefault(locale)

        val config = configuration(context.resources.configuration)

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
            context.createconfigurationcontext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateconfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Apply saved language settings
     */
    fun appLanguage(context: context): context {
        val language = getLanguage(context)
        return updateResources(context, language)
    }

    /**
     * Get current language display name
     */
    fun getLanguageDisplayName(context: context): String {
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