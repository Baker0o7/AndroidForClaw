/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.policy

import android.util.Log
import com.xiaomo.discord.DiscordConfig

/**
 * Discord PermissionPolicyManage
 * 参考:
 * - OpenClaw Discord security.resolveDmPolicy
 * - Feishu FeishuPolicy.kt
 *
 * Support:
 * - DM (私聊) Policy: open, pairing, allowlist, denylist
 * - Guild (Service器) Policy: open, allowlist, denylist
 * - Channel 白名单
 * - 提及要求 (requireMention)
 */
object DiscordPolicy {
    private const val TAG = "DiscordPolicy"

    /**
     * DM PolicyType
     */
    enum class DmPolicyType {
        OPEN,       // acceptAll DM
        PAIRING,    // Need配对/审批
        ALLOWLIST,  // 仅允许白名单
        DENYLIST    // deny黑名单
    }

    /**
     * GroupPolicyType
     */
    enum class GroupPolicyType {
        OPEN,       // acceptAllGroupMessage (需提及)
        ALLOWLIST,  // 仅允许白名单Group/Channel
        DENYLIST    // deny黑名单Group/Channel
    }

    /**
     * Parse DM Policy
     */
    fun resolveDmPolicy(config: DiscordConfig): DmPolicy {
        val policyStr = config.dm?.policy ?: "pairing"
        val policyType = when (policyStr.lowercase()) {
            "open" -> DmPolicyType.OPEN
            "pairing" -> DmPolicyType.PAIRING
            "allowlist" -> DmPolicyType.ALLOWLIST
            "denylist" -> DmPolicyType.DENYLIST
            else -> {
                Log.w(TAG, "Unknown DM policy: $policyStr, defaulting to PAIRING")
                DmPolicyType.PAIRING
            }
        }

        return DmPolicy(
            type = policyType,
            allowFrom = config.dm?.allowFrom ?: emptyList()
        )
    }

    /**
     * ParseGroupPolicy
     */
    fun resolveGroupPolicy(config: DiscordConfig): GroupPolicy {
        val policyStr = config.groupPolicy ?: "open"
        val policyType = when (policyStr.lowercase()) {
            "open" -> GroupPolicyType.OPEN
            "allowlist" -> GroupPolicyType.ALLOWLIST
            "denylist" -> GroupPolicyType.DENYLIST
            else -> {
                Log.w(TAG, "Unknown group policy: $policyStr, defaulting to OPEN")
                GroupPolicyType.OPEN
            }
        }

        return GroupPolicy(
            type = policyType,
            guilds = config.guilds ?: emptyMap()
        )
    }

    /**
     * Check DM YesNo被允许
     */
    fun isDmAllowed(policy: DmPolicy, userId: String): Boolean {
        return when (policy.type) {
            DmPolicyType.OPEN -> true
            DmPolicyType.PAIRING -> userId in policy.allowFrom
            DmPolicyType.ALLOWLIST -> userId in policy.allowFrom
            DmPolicyType.DENYLIST -> userId !in policy.allowFrom
        }
    }

    /**
     * Check Guild MessageYesNo被允许
     */
    fun isGuildMessageAllowed(
        policy: GroupPolicy,
        guildId: String,
        channelId: String,
        botMentioned: Boolean
    ): Boolean {
        val guildConfig = policy.guilds[guildId]

        return when (policy.type) {
            GroupPolicyType.OPEN -> {
                // Open Schema: Need提及
                if (!botMentioned && guildConfig?.requireMention != false) {
                    return false
                }

                // CheckChannel白名单 (ifConfig了)
                val allowedChannels = guildConfig?.channels
                if (allowedChannels != null && channelId !in allowedChannels) {
                    return false
                }

                true
            }

            GroupPolicyType.ALLOWLIST -> {
                // Allowlist Schema: Must在白名单中
                if (guildConfig == null) {
                    return false
                }

                // CheckChannel白名单
                val allowedChannels = guildConfig.channels
                if (allowedChannels != null && channelId !in allowedChannels) {
                    return false
                }

                // Check提及要求
                if (!botMentioned && guildConfig.requireMention != false) {
                    return false
                }

                true
            }

            GroupPolicyType.DENYLIST -> {
                // Denylist Schema: 不在黑名单中
                if (guildConfig != null) {
                    val deniedChannels = guildConfig.channels
                    if (deniedChannels != null && channelId in deniedChannels) {
                        return false
                    }
                }

                // Check提及要求
                if (!botMentioned && guildConfig?.requireMention != false) {
                    return false
                }

                true
            }
        }
    }

    /**
     * Parse Guild 的提及要求
     */
    fun resolveRequireMention(config: DiscordConfig, guildId: String): Boolean {
        return config.guilds?.get(guildId)?.requireMention ?: true
    }

    /**
     * Parse Guild 的工具Policy
     */
    fun resolveToolPolicy(config: DiscordConfig, guildId: String): String {
        return config.guilds?.get(guildId)?.toolPolicy ?: "default"
    }

    /**
     * DM Policy
     */
    data class DmPolicy(
        val type: DmPolicyType,
        val allowFrom: List<String>
    )

    /**
     * GroupPolicy
     */
    data class GroupPolicy(
        val type: GroupPolicyType,
        val guilds: Map<String, DiscordConfig.GuildConfig>
    )
}
