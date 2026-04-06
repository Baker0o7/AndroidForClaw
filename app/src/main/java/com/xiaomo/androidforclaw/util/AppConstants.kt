/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import okhttp3.logging.HttpLoggingInterceptor

object AppConstants {
    // ============= HTTP Logging =============
    val HTTP_LOG_LEVEL: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE

    // ============= API Configillustrate =============
    // All API Confignow从以DownConfig文件Read: 
    // - /sdcard/.androidforclaw/config/models.json (模型提供商Config)
    // - /sdcard/.androidforclaw/openclaw.json (OpenClaw 主Config)
    //
    // 请勿在此文件中硬Encode API Key 和 Base URL
    // use ConfigLoader.loadModelsConfig() 和 ConfigLoader.loadOpenClawConfig() ReadConfig
    //
    // 参考Document: 
    // - CLAUDE.md: Configuration System
    // - doc/OpenClaw架构Depthanalyze.md: Config系统illustrate

    // ============= EnvironmentVariableConstant(用于Config文件的 ${VAR_NAME} Replace) =============
    // 这些Constant会被 ConfigLoader 通过反射Read, 用于ReplaceConfig文件中的EnvironmentVariable占位符
    // Priority: 系统EnvironmentVariable > AppConstants Constant > MMKV Storage
    //
    // ⚠️ 开源Version: 请在Config文件中Settings API Key, 不要在此处硬Encode
    // Config文件Location: /sdcard/.androidforclaw/config/models.json
    const val OPENROUTER_API_KEY = ""  // 请在 /sdcard/.androidforclaw/config/models.json 中Config

}
