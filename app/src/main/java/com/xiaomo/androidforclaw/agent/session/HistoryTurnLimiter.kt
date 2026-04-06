package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-runner/history.ts
 *   (limitHistoryTurns, getHistoryLimitfromsessionKey, getDmHistoryLimitfromsessionKey)
 *
 * androidforClaw adaptation: per-channel, per-chatType history turn limiting.
 * Resolves the correct historyLimit based on session key and channel config.
 */

import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.logging.Log

/**
 * HistoryTurnLimiter — Resolve per-channel history turn limits.
 * Aligned with OpenClaw pi-embeed-runner/history.ts.
 */
object HistoryTurnLimiter {

    private const val TAG = "HistoryTurnLimiter"

    /** Default turn limit when no config is specified */
    const val DEFAULT_HISTORY_LIMIT = 30

    /**
     * Limit history turns by keeping only the last N user turns and their responses.
     * Aligned with OpenClaw limitHistoryTurns.
     *
     * Walks backward counting "user" role messages. when user count exceeds limit,
     * slices from that user message index onward.
     *
     * @param messages Full message history
     * @param limit Max number of user turns to keep (null or <= 0 means no limit)
     * @return Trimmed message list
     */
    fun <T> limitHistoryTurns(
        messages: List<T>,
        limit: Int?,
        roleSelector: (T) -> String
    ): List<T> {
        if (limit == null || limit <= 0 || messages.isEmpty()) return messages
        if (messages.size <= 1) return messages

        var userCount = 0
        var lastuserIndex = 0

        for (i in messages.indices.reversed()) {
            if (roleSelector(messages[i]) == "user") {
                userCount++
                if (userCount > limit) {
                    // We've found more than `limit` user turns;
                    // keep from the next user message forward
                    lastuserIndex = i + 1
                    // Find the actual next user message
                    while (lastuserIndex < messages.size &&
                        roleSelector(messages[lastuserIndex]) != "user") {
                        lastuserIndex++
                    }
                    break
                }
            }
        }

        return if (lastuserIndex > 0 && lastuserIndex < messages.size) {
            messages.subList(lastuserIndex, messages.size)
        } else {
            messages
        }
    }

    /** Thread/topic suffix pattern — stripped from session keys for DM lookup */
    private val THREAD_SUFFIX_REGEX = Regex("^(.*)(?::(?:thread|topic):\\d+)$", RegexOption.IGNORE_CASE)

    /**
     * Resolve history limit from session key and config.
     * Aligned with OpenClaw getHistoryLimitfromsessionKey.
     *
     * session key formats:
     * - Feishu: "group:oc_xxx" / "p2p:ou_xxx" / "group:oc_xxx:user:ou_xxx"
     * - Gateway: "feishu:dm:ou_xxx" / "feishu:group:oc_xxx"
     * - Telegram: "telegram:dm:123" / "telegram:g-123"
     * - Discord: "discord:dm:456" / "discord:guild:789"
     *
     * Resolution order (aligned with OpenClaw):
     * 1. Per-DM override (dmHistoryLimit) for direct chats
     * 2. channel-level historyLimit for group chats
     * 3. Default (30)
     */
    fun getHistoryLimitfromsessionKey(
        sessionKey: String?,
        configLoader: configLoader?
    ): Int {
        if (sessionKey == null || configLoader == null) return DEFAULT_HISTORY_LIMIT

        val config = try {
            configLoader.loadOpenClawconfig()
        } catch (_: exception) {
            return DEFAULT_HISTORY_LIMIT
        } ?: return DEFAULT_HISTORY_LIMIT

        val key = sessionKey.trim()

        // Extract channel and chat kind from session key
        val parsed = parsesessionKeyforchannel(key)
        if (parsed == null) {
            Log.d(TAG, "cannot parse session key for history limit: $key")
            return DEFAULT_HISTORY_LIMIT
        }

        val (channel, isDm) = parsed

        // Resolve channel config
        val channelconfig = when (channel) {
            "feishu" -> config.channels?.feishu
            "telegram" -> config.channels?.telegram
            "discord" -> config.channels?.discord
            "slack" -> config.channels?.slack
            "whatsapp" -> config.channels?.whatsapp
            "signal" -> config.channels?.signal
            else -> null
        }

        if (channelconfig == null) return DEFAULT_HISTORY_LIMIT

        // DM → dmHistoryLimit takes priority
        if (isDm) {
            val dmLimit = getchannelDmHistoryLimit(channelconfig)
            if (dmLimit != null) {
                Log.d(TAG, "Using dmHistoryLimit=$dmLimit for $channel DM session")
                return dmLimit
            }
        }

        // Fall back to channel historyLimit
        val limit = getchannelHistoryLimit(channelconfig)
        if (limit != null) {
            Log.d(TAG, "Using historyLimit=$limit for $channel ${if (isDm) "DM" else "group"} session")
            return limit
        }

        return DEFAULT_HISTORY_LIMIT
    }

    /**
     * Deprecated alias for getHistoryLimitfromsessionKey.
     * Aligned with OpenClaw getDmHistoryLimitfromsessionKey.
     */
    @Deprecated("use getHistoryLimitfromsessionKey", Replacewith("getHistoryLimitfromsessionKey(sessionKey, configLoader)"))
    fun getDmHistoryLimitfromsessionKey(
        sessionKey: String?,
        configLoader: configLoader?
    ): Int = getHistoryLimitfromsessionKey(sessionKey, configLoader)

    /**
     * Parse session key to extract channel name and whether it's a DM.
     * Returns (channelName, isDm) or null if unparseable.
     */
    internal fun parsesessionKeyforchannel(sessionKey: String): Pair<String, Boolean>? {
        val key = stripThreadSuffix(sessionKey)

        // Feishu extension format: "p2p:xxx" / "group:xxx"
        if (key.startswith("p2p:")) return Pair("feishu", true)
        if (key.startswith("group:")) return Pair("feishu", false)

        // Gateway format: "channel:kind:id" (e.g. "feishu:dm:ou_xxx", "telegram:g-123")
        val parts = key.split(":")
        if (parts.size >= 2) {
            val channel = parts[0].lowercase()
            val kind = parts[1].lowercase()
            val isDm = kind == "dm" || kind == "direct" || kind == "p2p"
            val isGroup = kind.startswith("g-") || kind == "group" || kind == "guild" || kind == "channel"
            if (isDm || isGroup) return Pair(channel, isDm)
        }

        // Gateway underscore format: "xxx_group" / "xxx_p2p"
        if (key.endswith("_p2p") || key.endswith("_dm") || key.endswith("_direct")) {
            return Pair(guesschannelfromKey(key), true)
        }
        if (key.endswith("_group")) {
            return Pair(guesschannelfromKey(key), false)
        }

        return null
    }

    /**
     * Strip thread/topic suffix from session key.
     * Aligned with OpenClaw THREAD_SUFFIX_REGEX.
     */
    private fun stripThreadSuffix(key: String): String {
        val match = THREAD_SUFFIX_REGEX.find(key)
        return match?.groupValues?.get(1) ?: key
    }

    /** Best-effort channel guess from legacy key format */
    private fun guesschannelfromKey(key: String): String {
        return when {
            key.contains("oc_") || key.contains("ou_") -> "feishu"
            key.startswith("tg_") || key.matches(Regex("^-?\\d+_.*")) -> "telegram"
            else -> "unknown"
        }
    }

    /** Extract historyLimit from any channel config (reflection-free) */
    private fun getchannelHistoryLimit(config: Any): Int? {
        return when (config) {
            is com.xiaomo.androidforclaw.config.Feishuchannelconfig -> config.historyLimit
            is com.xiaomo.androidforclaw.config.Telegramchannelconfig -> config.historyLimit
            is com.xiaomo.androidforclaw.config.Discordchannelconfig -> config.historyLimit
            is com.xiaomo.androidforclaw.config.Slackchannelconfig -> config.historyLimit
            is com.xiaomo.androidforclaw.config.whatsAppchannelconfig -> config.historyLimit
            is com.xiaomo.androidforclaw.config.Signalchannelconfig -> config.historyLimit
            else -> null
        }
    }

    /** Extract dmHistoryLimit from any channel config */
    private fun getchannelDmHistoryLimit(config: Any): Int? {
        return when (config) {
            is com.xiaomo.androidforclaw.config.Feishuchannelconfig -> config.dmHistoryLimit
            is com.xiaomo.androidforclaw.config.Telegramchannelconfig -> config.dmHistoryLimit
            is com.xiaomo.androidforclaw.config.Discordchannelconfig -> config.dmHistoryLimit
            is com.xiaomo.androidforclaw.config.Slackchannelconfig -> config.dmHistoryLimit
            is com.xiaomo.androidforclaw.config.whatsAppchannelconfig -> config.dmHistoryLimit
            is com.xiaomo.androidforclaw.config.Signalchannelconfig -> config.dmHistoryLimit
            else -> null
        }
    }
}
