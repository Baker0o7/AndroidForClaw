package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu messaging transport.
 */


/**
 * Feishu @mention handling
 * Aligned with OpenClaw mention.ts
 *
 * Feature: 
 * - Extract @mention targets from messages
 * - Detect if it's a forward request
 * - Format @mention tags
 */
object FeishuMention {

    /**
     * Mention target info
     */
    data class MentionTarget(
        val openId: String,
        val name: String,
        val key: String  // placeholder, e.g. @_user_1
    )

    /**
     * Escape regex metacharacters
     */
    private fun escapeRegex(input: String): String {
        return Regex.escape(input)
    }

    /**
     * Extract mention targets from MessageEvent (excluding the bot itself)
     *
     * @param mentions List of mentions
     * @param botOpenId Bot's open_id
     * @return List of mention targets
     */
    fun extractMentionTargets(
        mentions: List<Map<String, Any?>>,
        botOpenId: String? = null
    ): List<MentionTarget> {
        return mentions.mapNotNull { mention ->
            val id = mention["id"] as? Map<*, *>
            val openId = id?.get("open_id") as? String
            val name = mention["name"] as? String ?: ""
            val key = mention["key"] as? String ?: ""

            // Exclude bot itself && must have open_id
            if (openId != null && openId != botOpenId) {
                MentionTarget(openId, name, key)
            } else {
                null
            }
        }
    }

    /**
     * Check if it's a mention forward request
     *
     * Rule: 
     * - Group chat: Message mentions bot + at least one other user
     * - Direct message: Message mentions any user (no need to mention bot)
     *
     * @param mentions List of mentions
     * @param chatType Chat type (p2p/group)
     * @param botOpenId Bot's open_id
     * @return Whether it's a forward request
     */
    fun isMentionForwardRequest(
        mentions: List<Map<String, Any?>>,
        chatType: String,
        botOpenId: String?
    ): Boolean {
        if (mentions.isEmpty()) {
            return false
        }

        val isDirectMessage = chatType != "group"
        val hasOtherMention = mentions.any { mention ->
            val id = mention["id"] as? Map<*, *>
            val openId = id?.get("open_id") as? String
            openId != botOpenId
        }

        return if (isDirectMessage) {
            // DM: mention any non-bot user triggers
            hasOtherMention
        } else {
            // Group: need to mention both bot and other users
            val hasBotMention = mentions.any { mention ->
                val id = mention["id"] as? Map<*, *>
                val openId = id?.get("open_id") as? String
                openId == botOpenId
            }
            hasBotMention && hasOtherMention
        }
    }

    /**
     * Extract message body (removing @ placeholders)
     *
     * @param text Original text
     * @param allMentionKeys List of all @ placeholders
     * @return Cleaned text
     */
    fun extractMessageBody(text: String, allMentionKeys: List<String>): String {
        var result = text

        // Remove all @ placeholders
        for (key in allMentionKeys) {
            result = result.replace(Regex(escapeRegex(key)), "")
        }

        // Compress whitespace
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Format @mention tag (text message)
     *
     * @param target Mention target
     * @return Formatted tag
     */
    fun formatMentionForText(target: MentionTarget): String {
        return """<at user_id="${target.openId}">${target.name}</at>"""
    }

    /**
     * Format @all tag (text message)
     */
    fun formatMentionAllForText(): String {
        return """<at user_id="all">Everyone</at>"""
    }

    /**
     * Format @mention tag (card message lark_md)
     *
     * @param target Mention target
     * @return Formatted tag
     */
    fun formatMentionForCard(target: MentionTarget): String {
        return """<at id=${target.openId}></at>"""
    }

    /**
     * Format @all tag (card message lark_md)
     */
    fun formatMentionAllForCard(): String {
        return """<at id=all></at>"""
    }

    /**
     * Build text message with mentions
     *
     * @param targets List of mention targets
     * @param messageBody Message body
     * @return Complete message text
     */
    fun buildMentionedMessage(targets: List<MentionTarget>, messageBody: String): String {
        val mentions = targets.joinToString(" ") { formatMentionForText(it) }
        return if (mentions.isNotEmpty()) {
            "$mentions $messageBody"
        } else {
            messageBody
        }
    }

    /**
     * Build card content with mentions (lark_md)
     *
     * @param targets List of mention targets
     * @param cardContent Card content
     * @return Card content with mentions
     */
    fun buildMentionedCardContent(targets: List<MentionTarget>, cardContent: String): String {
        val mentions = targets.joinToString(" ") { formatMentionForCard(it) }
        return if (mentions.isNotEmpty()) {
            "$mentions\n\n$cardContent"
        } else {
            cardContent
        }
    }
}

    /**
     * Format @all tag (text message)
     */
    fun formatMentionAllForText(): String {
        return """<at user_id="all">Everyone</at>"""
    }

    /**
     * Format @mention tag (card message lark_md)
     *
     * @param target Mention target
     * @return Formatted tag
     */
    fun formatMentionForCard(target: MentionTarget): String {
        return """<at id=${target.openId}></at>"""
    }

    /**
     * Format @all tag (card message lark_md)
     */
    fun formatMentionAllForCard(): String {
        return """<at id=all></at>"""
    }

    /**
     * Build text message with mentions
     *
     * @param targets List of mention targets
     * @param messageBody Message body
     * @return Complete message text
     */
    fun buildMentionedMessage(targets: List<MentionTarget>, messageBody: String): String {
        val mentions = targets.joinToString(" ") { formatMentionForText(it) }
        return if (mentions.isNotEmpty()) {
            "$mentions $messageBody"
        } else {
            messageBody
        }
    }

    /**
     * Build card content with mentions (lark_md)
     *
     * @param targets List of mention targets
     * @param cardContent Card content
     * @return Card content with mentions
     */
    fun buildMentionedCardContent(targets: List<MentionTarget>, cardContent: String): String {
        val mentions = targets.joinToString(" ") { formatMentionForCard(it) }
        return if (mentions.isNotEmpty()) {
            "$mentions\n\n$cardContent"
        } else {
            cardContent
        }
    }
}
