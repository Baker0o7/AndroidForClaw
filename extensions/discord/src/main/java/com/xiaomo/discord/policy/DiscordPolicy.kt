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
 * Discord Permission Policy Manager
 * Reference:
 * - OpenClaw Discord security.resolveDmPolicy
 * - Feishu FeishuPolicy.kt
 *
 * Support:
 * - DM (Private Message) Policy: open, pairing, allowlist, denylist
 * - Guild (Server) Policy: open, allowlist, denylist
 * - Channel Allowlist
 * - Mention Requirements (requireMention)
 */
object DiscordPolicy {
    private const val TAG = "DiscordPolicy"

    /**
     * DM Policy Type
     */
    enum class DmPolicyType {
        OPEN,       // Accept All DM
        PAIRING,    // Need Pairing/Approval
        ALLOWLIST,  // Allowlist Only
        DENYLIST    // Denylist Only
    }

    /**
     * Group Policy Type
     */
    enum class GroupPolicyType {
        OPEN,       // Accept All Group Messages (Need Mention)
        ALLOWLIST,  // Allowlist Only Groups/Channels
        DENYLIST    // Denylist Only Groups/Channels
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
     * Parse Group Policy
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
     * Check if DM is Allowed
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
     * Check if Guild Message is Allowed
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
                // Open Scheme: Need Mention
                if (!botMentioned && guildConfig?.requireMention != false) {
                    return false
                }

                // Check Channel Allowlist (if configured)
                val allowedChannels = guildConfig?.channels
                if (allowedChannels != null && channelId !in allowedChannels) {
                    return false
                }

                true
            }

            GroupPolicyType.ALLOWLIST -> {
                // Allowlist Scheme: Must be in allowlist
                if (guildConfig == null) {
                    return false
                }

                // Check Channel Allowlist
                val allowedChannels = guildConfig.channels
                if (allowedChannels != null && channelId !in allowedChannels) {
                    return false
                }

                // Check Mention Requirement
                if (!botMentioned && guildConfig.requireMention != false) {
                    return false
                }

                true
            }

            GroupPolicyType.DENYLIST -> {
                // Denylist Scheme: Not in denylist
                if (guildConfig != null) {
                    val deniedChannels = guildConfig.channels
                    if (deniedChannels != null && channelId in deniedChannels) {
                        return false
                    }
                }

                // Check Mention Requirement
                if (!botMentioned && guildConfig?.requireMention != false) {
                    return false
                }

                true
            }
        }
    }

    /**
     * Parse Guild Mention Requirement
     */
    fun resolveRequireMention(config: DiscordConfig, guildId: String): Boolean {
        return config.guilds?.get(guildId)?.requireMention ?: true
    }

    /**
     * Parse Guild Tool Policy
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
