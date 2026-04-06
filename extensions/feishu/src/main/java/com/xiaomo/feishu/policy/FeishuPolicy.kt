package com.xiaomo.feishu.policy

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import com.xiaomo.feishu.FeishuConfig

/**
 * 飞书PolicyManage
 * Aligned with OpenClaw src/policy.ts
 */
class FeishuPolicy(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuPolicy"
    }

    /**
     * Check DM YesNo允许
     */
    fun isDmAllowed(senderId: String, isPaired: Boolean = false): Boolean {
        return when (config.dmPolicy) {
            FeishuConfig.DmPolicy.OPEN -> {
                Log.d(TAG, "DM allowed: OPEN policy")
                true
            }
            FeishuConfig.DmPolicy.PAIRING -> {
                val allowed = isPaired
                Log.d(TAG, "DM allowed=$allowed: PAIRING policy (isPaired=$isPaired)")
                allowed
            }
            FeishuConfig.DmPolicy.ALLOWLIST -> {
                val allowed = isInAllowlist(senderId, config.allowFrom)
                Log.d(TAG, "DM allowed=$allowed: ALLOWLIST policy")
                allowed
            }
        }
    }

    /**
     * CheckGroupYesNo允许
     */
    fun isGroupAllowed(chatId: String): Boolean {
        return when (config.groupPolicy) {
            FeishuConfig.GroupPolicy.OPEN -> {
                Log.d(TAG, "Group allowed: OPEN policy")
                true
            }
            FeishuConfig.GroupPolicy.ALLOWLIST -> {
                val allowed = isInAllowlist(chatId, config.groupAllowFrom)
                Log.d(TAG, "Group allowed=$allowed: ALLOWLIST policy")
                allowed
            }
            FeishuConfig.GroupPolicy.DISABLED -> {
                Log.d(TAG, "Group disabled: DISABLED policy")
                false
            }
        }
    }

    /**
     * CheckGroupMessageYesNoNeed @
     *
     * Aligned with OpenClaw policy.ts:
     * When groupPolicy is "open" and requireMention is not explicitly configured,
     * default to false: an open group should respond to all messages including
     * images and files that cannot carry @-mentions.
     */
    fun requiresMention(chatType: String, isMentioned: Boolean, isSingleBot: Boolean): Boolean {
        if (chatType != "group") {
            return false // DM 不Need @
        }

        // Resolve requireMention: explicit config > groupPolicy-based default
        // OpenClaw: requireMentionDefault = groupPolicy === "open" ? false : true
        val requireMentionDefault = config.groupPolicy != FeishuConfig.GroupPolicy.OPEN
        val requireMention = config.requireMention ?: requireMentionDefault

        if (!requireMention) {
            return false
        }

        // Check bypass Rule
        val bypass = when (config.groupCommandMentionBypass) {
            FeishuConfig.MentionBypass.NEVER -> false
            FeishuConfig.MentionBypass.SINGLE_BOT -> isSingleBot
            FeishuConfig.MentionBypass.ALWAYS -> true
        }

        if (bypass) {
            Log.d(TAG, "Mention bypass: ${config.groupCommandMentionBypass}")
            return false
        }

        // Need @
        val required = !isMentioned
        if (required) {
            Log.d(TAG, "Mention required but not found")
        }
        return required
    }

    /**
     * CheckYesNo在白名单中
     */
    private fun isInAllowlist(id: String, allowlist: List<String>): Boolean {
        if (allowlist.isEmpty()) {
            return false
        }

        return allowlist.any { pattern ->
            when {
                pattern == id -> true
                pattern.endsWith("*") -> {
                    val prefix = pattern.dropLast(1)
                    id.startsWith(prefix)
                }
                else -> false
            }
        }
    }

    /**
     * Parse工具Policy
     */
    fun resolveToolPolicy(chatType: String): ToolPolicy {
        return ToolPolicy(
            allowTools = true, // Default允许All工具
            allowedToolNames = null // null Table示All允许
        )
    }

    /**
     * Check工具YesNo允许use
     */
    fun isToolAllowed(toolName: String, chatType: String): Boolean {
        val policy = resolveToolPolicy(chatType)

        if (!policy.allowTools) {
            return false
        }

        if (policy.allowedToolNames == null) {
            return true // All允许
        }

        return policy.allowedToolNames.contains(toolName)
    }
}

/**
 * 工具Policy
 */
data class ToolPolicy(
    val allowTools: Boolean,
    val allowedToolNames: List<String>? = null
)
