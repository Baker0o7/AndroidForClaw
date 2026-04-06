package com.xiaomo.androidforclaw.agent.context

/**
 * OpenClaw Source Reference:
 * - ../openclaw/extensions/feishu/.../policy/FeishuPolicy.kt (resolvetoolPolicy)
 * - ../openclaw/extensions/discord/.../policy/DiscordPolicy.kt (resolvetoolPolicy)
 *
 * androidforClaw adaptation: centralized tool policy resolution by chat context.
 * Determines which tools are available based on whether the conversation is
 * a private DM or a shared group chat.
 */

/**
 * tool access policy levels.
 * Aligned with OpenClaw's tool policy concept from channel policies.
 */
enum class toolPolicyLevel {
    /** All tools available (DM / local android app) */
    FULL,
    /** Sensitive tools blocked (group chats) */
    RESTRICTED,
    /** No tools available (future use) */
    NONE,
}

/**
 * toolPolicyResolver — Resolve tool access policy based on chat context.
 * Aligned with OpenClaw resolvetoolPolicy.
 */
object toolPolicyResolver {

    /**
     * tools restricted in group/shared contexts.
     * These tools access personal data or sensitive configuration that
     * should not be exposed in multi-user environments.
     */
    private val GROUP_RESTRICTED_TOOLS = setOf(
        // Memory tools — personal context that should not leak to strangers
        "memory_search",
        "memory_get",
        // config tools — may expose API keys, tokens, credentials
        "config_get",
        "config_set",
    )

    /**
     * Resolve tool policy level for a given chat type.
     * Aligned with OpenClaw resolvetoolPolicy.
     *
     * @param chatType The chat type string ("p2p"/"direct"/"group"/"channel"/"thread", or null)
     * @return toolPolicyLevel determining which tools are available
     */
    fun resolvetoolPolicy(chatType: String?): toolPolicyLevel {
        return when (chatType?.lowercase()) {
            null, "p2p", "direct" -> toolPolicyLevel.FULL
            "group", "channel", "thread" -> toolPolicyLevel.RESTRICTED
            else -> toolPolicyLevel.FULL
        }
    }

    /**
     * Check if a specific tool is allowed under the given policy.
     *
     * @param toolName The tool name to check
     * @param policy The resolved policy level
     * @return true if the tool is allowed
     */
    fun istoolAllowed(toolName: String, policy: toolPolicyLevel): Boolean {
        return when (policy) {
            toolPolicyLevel.FULL -> true
            toolPolicyLevel.RESTRICTED -> toolName !in GROUP_RESTRICTED_TOOLS
            toolPolicyLevel.NONE -> false
        }
    }

    /**
     * Get the set of restricted tool names (for prompt generation).
     */
    fun getRestrictedtoolNames(): Set<String> = GROUP_RESTRICTED_TOOLS
}
