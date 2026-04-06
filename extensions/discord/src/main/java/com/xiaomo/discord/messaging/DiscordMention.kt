/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.messaging

/**
 * Discord @提及Process
 * 参考 Feishu FeishuMention.kt
 */
object DiscordMention {
    private const val TAG = "DiscordMention"

    /**
     * Parse提及
     * 从MessageInside容中提取All @提及
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
     * FormatUser提及
     */
    fun formatUserMention(userId: String): String {
        return "<@$userId>"
    }

    /**
     * FormatRole提及
     */
    fun formatRoleMention(roleId: String): String {
        return "<@&$roleId>"
    }

    /**
     * FormatChannel提及
     */
    fun formatChannelMention(channelId: String): String {
        return "<#$channelId>"
    }

    /**
     * 移除All提及
     */
    fun stripMentions(content: String): String {
        return content
            .replace(Regex("<@!?\\d+>"), "") // User提及
            .replace(Regex("<@&\\d+>"), "") // Role提及
            .replace(Regex("<#\\d+>"), "") // Channel提及
            .trim()
    }

    /**
     * CheckMessageYesNoContains指定User的提及
     */
    fun containsUserMention(content: String, userId: String): Boolean {
        val pattern = Regex("<@!?$userId>")
        return pattern.containsMatchIn(content)
    }

    /**
     * Replace提及为ShowName
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
     * @everyone 提及
     */
    const val MENTION_EVERYONE = "@everyone"

    /**
     * @here 提及
     */
    const val MENTION_HERE = "@here"
}
