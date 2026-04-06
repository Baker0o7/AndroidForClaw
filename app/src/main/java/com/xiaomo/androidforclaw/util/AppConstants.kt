/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import okhttp3.logging.HttpLoggingInterceptor

object AppConstants {
    // ============= HTTP Logging =============
    val HTTP_LOG_LEVEL: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.NONE

    // ============= API configillustrate =============
    // All API confignowfrombynextconfigfilesRead: 
    // - /sdcard/.androidforclaw/config/models.json (模型提供商config)
    // - /sdcard/.androidforclaw/openclaw.json (OpenClaw mainconfig)
    //
    // please勿inthisfiles中硬Encode API Key and Base URL
    // use configLoader.loadmodelsconfig() and configLoader.loadOpenClawconfig() Readconfig
    //
    // 参考Document: 
    // - CLAUDE.md: configuration System
    // - doc/OpenClaw架构Depthanalyze.md: config系统illustrate

    // ============= EnvironmentVariableConstant(用于configfiles ${VAR_NAME} Replace) =============
    // thissomeConstantwill被 configLoader through反射Read, 用于Replaceconfigfiles中EnvironmentVariable占position符
    // Priority: 系统EnvironmentVariable > AppConstants Constant > MMKV Storage
    //
    // [WARN] 开源Version: pleaseinconfigfiles中Settings API Key, notneedinthis处硬Encode
    // configfilesLocation: /sdcard/.androidforclaw/config/models.json
    const val OPENROUTER_API_KEY = ""  // pleasein /sdcard/.androidforclaw/config/models.json 中config

}
