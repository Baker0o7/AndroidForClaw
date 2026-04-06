package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu messaging transport.
 */


/**
 * 飞书 @提及 Process
 * Aligned with OpenClaw mention.ts
 *
 * Feature: 
 * - 提Cancel息中的 @提及 目标
 * - 检测YesNo为转发Request
 * - Format @提及 标签
 */
object FeishuMention {

    /**
     * 提及目标Info
     */
    data class MentionTarget(
        val openId: String,
        val name: String,
        val key: String  // 占位符, e.g. @_user_1
    )

    /**
     * 转义正则Table达式元字符
     */
    private fun escapeRegex(input: String): String {
        return Regex.escape(input)
    }

    /**
     * 从MessageEvent中提取提及目标(exclude机器人自己)
     *
     * @param mentions 提及List
     * @param botOpenId 机器人 open_id
     * @return 提及目标List
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

            // exclude机器人自己 && MustHas open_id
            if (openId != null && openId != botOpenId) {
                MentionTarget(openId, name, key)
            } else {
                null
            }
        }
    }

    /**
     * CheckYesNo为提及转发Request
     *
     * Rule: 
     * - 群聊: Message提及机器人 + 至少一个Its他User
     * - 私聊: Message提及任何User(None需提及机器人)
     *
     * @param mentions 提及List
     * @param chatType ChatType(p2p/group)
     * @param botOpenId 机器人 open_id
     * @return YesNo为转发Request
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
            // 私聊: 提及任何非机器人Userthat is触发
            hasOtherMention
        } else {
            // 群聊: Needat the same time提及机器人和Its他User
            val hasBotMention = mentions.any { mention ->
                val id = mention["id"] as? Map<*, *>
                val openId = id?.get("open_id") as? String
                openId == botOpenId
            }
            hasBotMention && hasOtherMention
        }
    }

    /**
     * 从Text中提Cancel息正文(移除 @ 占位符)
     *
     * @param text 原始Text
     * @param allMentionKeys All @ 占位符List
     * @return 清理Back的Text
     */
    fun extractMessageBody(text: String, allMentionKeys: List<String>): String {
        var result = text

        // 移除All @ 占位符
        for (key in allMentionKeys) {
            result = result.replace(Regex(escapeRegex(key)), "")
        }

        // CompressNull白字符
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Format @提及 标签(TextMessage)
     *
     * @param target 提及目标
     * @return Format的标签
     */
    fun formatMentionForText(target: MentionTarget): String {
        return """<at user_id="${target.openId}">${target.name}</at>"""
    }

    /**
     * Format @All人 标签(TextMessage)
     */
    fun formatMentionAllForText(): String {
        return """<at user_id="all">Everyone</at>"""
    }

    /**
     * Format @提及 标签(卡片Message lark_md)
     *
     * @param target 提及目标
     * @return Format的标签
     */
    fun formatMentionForCard(target: MentionTarget): String {
        return """<at id=${target.openId}></at>"""
    }

    /**
     * Format @All人 标签(卡片Message lark_md)
     */
    fun formatMentionAllForCard(): String {
        return """<at id=all></at>"""
    }

    /**
     * Build带提及的TextMessage
     *
     * @param targets 提及目标List
     * @param messageBody Message正文
     * @return 完整MessageText
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
     * Build带提及的卡片Inside容(lark_md)
     *
     * @param targets 提及目标List
     * @param cardContent 卡片Inside容
     * @return 带提及的卡片Inside容
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
