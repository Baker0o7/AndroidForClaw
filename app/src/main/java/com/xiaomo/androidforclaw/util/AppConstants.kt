/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import okhttp3.logging.HttpLoggingInterceptor

object AppConstants {
    // ============= HTTP Logging =============
    val HTTP_LOG_LEVEL: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE

    // ============= API config description =============
    // All API config is now read from the following config files: 
    // - /sdcard/.androidforclaw/config/models.json (model provider config)
    // - /sdcard/.androidforclaw/openclaw.json (OpenClaw main config)
    //
    // Please do NOT hardcode API Key and Base URL in this file
    // Use configLoader.loadModelsConfig() and configLoader.loadOpenClawConfig() to read config
    //
    // Reference documentation: 
    // - CLAUDE.md: Configuration System
    // - doc/OpenClaw Architecture Deep Dive.md: config system explanation

    // ============= Environment Variable Constant (used for config files ${VAR_NAME} replacement) =============
    // These constants will be read by configLoader through reflection, used to replace environment variable placeholders in config files
    // Priority: System Environment Variable > AppConstants Constant > MMKV Storage
    //
    // [WARN] Open source version: Please set API Key in config files, no need to hardcode here
    // Config file location: /sdcard/.androidforclaw/config/models.json
    const val OPENROUTER_API_KEY = ""  // Configure in /sdcard/.androidforclaw/config/models.json

}
