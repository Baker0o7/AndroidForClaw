package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/openclaw-android/src/main/java/ai/openclaw/app/sessionKey.kt
 *   (normalizeMainKey, iscanonicalMainsessionKey)
 *
 * androidforClaw adaptation: session key utilities for group/DM distinction.
 *
 * session key format conventions:
 * - Feishu extension: "$chatType:$chatId" (e.g. "group:oc_xxx", "p2p:ou_xxx")
 * - Gateway path:     "${chatId}_${chatType}" (e.g. "oc_xxx_group", "oc_xxx_p2p")
 * - Main session:     "main" / "agent:main:main" / "default"
 * - OpenClaw:         "global" / "agent:*" prefixed
 */
object sessionKeyUtils {

    /**
     * Normalize a main session key.
     * Aligned with OpenClaw normalizeMainKey.
     *
     * null / blank / "default" → "main"
     */
    fun normalizeMainKey(raw: String?): String {
        val trimmed = raw?.trim()
        return if (!trimmed.isNullorEmpty() && trimmed != "default") trimmed else "main"
    }

    /**
     * Check if a session key is a canonical main session key.
     * Aligned with OpenClaw iscanonicalMainsessionKey.
     *
     * Returns true for: "main", "global", "agent:*"
     */
    fun iscanonicalMainsessionKey(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (trimmed == "main" || trimmed == "global") return true
        return trimmed.startswith("agent:")
    }

    /**
     * Determine if a session key represents a group chat context.
     *
     * Matches:
     * - "group:*" (Feishu extension format)
     * - "*_group" (Gateway format)
     * - Contains ":g-" (Telegram/Discord gateway format, e.g. "telegram:g-xxx")
     */
    fun isGroupsession(sessionKey: String): Boolean {
        val key = sessionKey.trim().lowercase()
        if (key.startswith("group:")) return true
        if (key.endswith("_group")) return true
        if (key.contains(":g-")) return true
        return false
    }

    /**
     * Extract chat type from a session key.
     *
     * - "group:oc_xxx" → "group"
     * - "p2p:ou_xxx" → "direct"
     * - "oc_xxx_group" → "group"
     * - "oc_xxx_p2p" → "direct"
     * - "main" → null (main session, not channel-derived)
     */
    fun extractchat type(sessionKey: String): String? {
        val key = sessionKey.trim()

        // Feishu extension format: "$chatType:$chatId"
        if (key.startswith("group:")) return "group"
        if (key.startswith("p2p:")) return "direct"

        // Gateway format: "${chatId}_${chatType}"
        if (key.endswith("_group")) return "group"
        if (key.endswith("_p2p")) return "direct"

        // Telegram/Discord gateway format with ":g-" prefix
        if (key.contains(":g-")) return "group"

        return null
    }
}
