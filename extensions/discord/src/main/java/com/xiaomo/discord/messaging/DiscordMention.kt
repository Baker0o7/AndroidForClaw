/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.messaging

/**
 * Discord @Mention Processor
 * Reference Feishu FeishuMention.kt
 */
object DiscordMention {
    private const val TAG = "DiscordMention"

    /**
     * Parse Mentions
     * Extract all @ mentions from message content
     */
    fun parseMentions(content: String): List<String> {
        val mentions = mutableListOf<String>()
        val pattern = Regex("<@!?(\\d+)>")

        pattern.findAll(content).forEach { match ->
            val userId = match.groupValues[1]
            mentions.add(userId)
        }

        return mentions
    }

    /**
     * Format User Mention
     */
    fun formatUserMention(userId: String): String {
        return "<@$userId>"
    }

    /**
     * Format Role Mention
     */
    fun formatRoleMention(roleId: String): String {
        return "<@&$roleId>"
    }

    /**
     * Format Channel Mention
     */
    fun formatChannelMention(channelId: String): String {
        return "<#$channelId>"
    }

    /**
     * Remove All Mentions
     */
    fun stripMentions(content: String): String {
        return content
            .replace(Regex("<@!?\\d+>"), "") // User mentions
            .replace(Regex("<@&\\d+>"), "") // Role mentions
            .replace(Regex("<#\\d+>"), "") // Channel mentions
            .trim()
    }

    /**
     * Check if Message Contains Specific User's Mention
     */
    fun containsUserMention(content: String, userId: String): Boolean {
        val pattern = Regex("<@!?$userId>")
        return pattern.containsMatchIn(content)
    }

    /**
     * Replace Mentions with Display Names
     */
    fun replaceMentionsWithNames(
        content: String,
        userNames: Map<String, String>
    ): String {
        var result = content

        userNames.forEach { (userId, userName) ->
            val pattern = Regex("<@!?$userId>")
            result = result.replace(pattern, "@$userName")
        }

        return result
    }

    /**
     * @everyone Mention
     */
    const val MENTION_EVERYONE = "@everyone"

    /**
     * @here Mention
     */
    const val MENTION_HERE = "@here"
}
