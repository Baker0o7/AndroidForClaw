/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

/**
 * MMKV configuration keys
 * new architecture focus: agentloop + tools
 */
enum class MMKVKeys(val key: String) {
    BUG_SWITCH("bug_switch"),

    // ========== Retained features ==========
    // Floating window display switch (EasyFloat)
    FLOAT_WINDOW_ENABLED("float_window_enabled"),

    // Exploration mode switch (false: Planning mode, true: Exploration mode)
    EXPLORATION_MODE("exploration_mode"),

    // Gateway URL (canCustom, Default ws://127.0.0.1:8765)
    GATEWAY_URL("gateway_url")
}
