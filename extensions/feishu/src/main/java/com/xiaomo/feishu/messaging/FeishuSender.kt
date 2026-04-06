package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu messaging transport.
 */


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 飞书Messagesend器
 * Aligned with OpenClaw src/send.ts
 */
class FeishuSender(
    private val config: FeishuConfig,
    private val client: FeishuClient
) {
    companion object {
        private const val TAG = "FeishuSender"
    }

    private val gson = Gson()
    private val media = FeishuMedia(config, client)

    /**
     * sendTextMessage
     *
     * Aligned with OpenClaw 逻辑: 
     * - Auto检测代码块和 Markdown Table
     * - use interactive card (schema 2.0) 渲染FormatInside容
     */
    suspend fun sendTextMessage(
        receiveId: String,
        text: String,
        receiveIdType: String = "open_id",
        mentionTargets: List<MentionTarget> = emptyList(),
        renderMode: RenderMode = RenderMode.AUTO
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            // 分块send(if超过Limit)
            val chunks = chunkText(text, config.textChunkLimit)
            if (chunks.size > 1) {
                return@withContext sendChunkedMessages(receiveId, chunks, receiveIdType)
            }

            // DetermineYesNouse卡片(Aligned with OpenClaw)
            val useCard = when (renderMode) {
                RenderMode.CARD -> true
                RenderMode.TEXT -> false
                RenderMode.AUTO -> shouldUseCard(text)
            }

            if (useCard) {
                // use Markdown 卡片send(正确渲染代码块和Table)
                Log.d(TAG, "Using markdown card for formatted content")
                val card = buildMarkdownCard(text, mentionTargets)
                return@withContext sendCard(receiveId, card, receiveIdType)
            } else {
                // use普通TextMessage
                val content = if (mentionTargets.isNotEmpty()) {
                    buildMentionedTextContent(text, mentionTargets)
                } else {
                    buildTextContent(text)
                }
                return@withContext sendMessageInternal(receiveId, "text", content, receiveIdType)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
            result.failure(e)
        }
    }

    /**
     * send卡片Message
     */
    suspend fun sendCard(
        receiveId: String,
        card: String,
        receiveIdType: String = "open_id"
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            sendMessageInternal(receiveId, "interactive", card, receiveIdType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send card message", e)
            result.failure(e)
        }
    }

    /**
     * Update卡片Message
     */
    suspend fun updateCard(
        messageId: String,
        card: String
    ): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf("content" to card)
            val result = client.patch("/open-apis/im/v1/messages/$messageId", body)

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Card updated: $messageId")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update card", e)
            result.failure(e)
        }
    }

    /**
     * 通过 card_id send Card Kit 卡片(用于 Streaming Card)
     * Aligned with OpenClaw: client.im.message.create with card_id reference
     */
    suspend fun sendCardById(
        receiveId: String,
        cardId: String,
        receiveIdType: String = "chat_id"
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val content = gson.toJson(mapOf(
                "type" to "card",
                "data" to mapOf("card_id" to cardId)
            ))
            sendMessageInternal(receiveId, "interactive", content, receiveIdType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send card by id", e)
            result.failure(e)
        }
    }

    /**
     * 通过 card_id send引用回复卡片(Streaming Card + Quote Reply)
     */
    suspend fun sendCardByIdReply(
        replyToMessageId: String,
        cardId: String
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val content = gson.toJson(mapOf(
                "type" to "card",
                "data" to mapOf("card_id" to cardId)
            ))
            sendQuoteReply(replyToMessageId, "interactive", content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send card by id reply", e)
            result.failure(e)
        }
    }

    /**
     * EditMessage
     */
    suspend fun editMessage(
        messageId: String,
        text: String
    ): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val content = buildTextContent(text)
            val body = mapOf(
                "content" to content,
                "msg_type" to "text"
            )

            val result = client.put("/open-apis/im/v1/messages/$messageId", body)

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Message edited: $messageId")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to edit message", e)
            result.failure(e)
        }
    }

    /**
     * DeleteMessage
     */
    suspend fun deleteMessage(messageId: String): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = client.delete("/open-apis/im/v1/messages/$messageId")

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Message deleted: $messageId")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message", e)
            result.failure(e)
        }
    }

    /**
     * sendGraph片Message
     * Aligned with OpenClaw src/send.ts sendImage()
     */
    suspend fun sendImage(
        receiveId: String,
        imageKey: String,
        receiveIdType: String = "open_id"
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val content = gson.toJson(mapOf("image_key" to imageKey))
            sendMessageInternal(receiveId, "image", content, receiveIdType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send image", e)
            result.failure(e)
        }
    }

    /**
     * UploadGraph片Concurrency送
     * Aligned with OpenClaw 逻辑: upload file -> get image_key -> send image
     */
    suspend fun uploadAndSendImage(
        receiveId: String,
        filePath: String,
        receiveIdType: String = "open_id"
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                return@withContext result.failure(Exception("File not found: $filePath"))
            }

            // 1. UploadGraph片Get image_key (useNew的 FeishuImageUploadTool)
            val uploadTool = com.xiaomo.feishu.tools.media.FeishuImageUploadTool(config, client)
            val toolresult = uploadTool.execute(mapOf("image_path" to file.absolutePath))

            if (!toolresult.success) {
                return@withContext result.failure(Exception(toolresult.error ?: "Upload failed"))
            }

            val imageKey = toolresult.data as? String
                ?: return@withContext result.failure(Exception("Missing image_key"))

            Log.d(TAG, "Image uploaded: $imageKey")

            // 2. sendGraph片Message
            sendImage(receiveId, imageKey, receiveIdType)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload and send image", e)
            result.failure(e)
        }
    }

    /**
     * 话题Inside回复(Thread Reply)
     * Aligned with OpenClaw replyInThread
     */
    suspend fun replyInThread(
        messageId: String,
        text: String
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val content = buildTextContent(text)
            val body = mapOf(
                "content" to content,
                "msg_type" to "text",
                "reply_in_thread" to true,
                "root_id" to messageId
            )

            val result = client.post("/open-apis/im/v1/messages", body)

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val newMessageId = data?.get("message_id")?.asString
                ?: return@withContext result.failure(Exception("Missing message_id"))

            Log.d(TAG, "Thread reply sent: $newMessageId")
            result.success(Sendresult(newMessageId, listOf(newMessageId)))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reply in thread", e)
            result.failure(e)
        }
    }

    /**
     * 引用回复(Quote Reply)
     * Aligned with OpenClaw reply-dispatcher: POST /im/v1/messages/{message_id}/reply
     */
    suspend fun sendQuoteReply(
        replyToMessageId: String,
        msgType: String,
        content: String
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "content" to content,
                "msg_type" to msgType
            )

            val result = client.post(
                "/open-apis/im/v1/messages/$replyToMessageId/reply",
                body
            )

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val newMessageId = data?.get("message_id")?.asString
                ?: return@withContext result.failure(Exception("Missing message_id"))

            Log.d(TAG, "Quote reply sent: $newMessageId (reply to $replyToMessageId)")
            result.success(Sendresult(newMessageId, listOf(newMessageId)))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send quote reply", e)
            result.failure(e)
        }
    }

    /**
     * 引用回复TextMessage(高层便捷Method)
     * AutoProcess卡片检测、分块
     */
    suspend fun sendTextReply(
        replyToMessageId: String,
        text: String,
        mentionTargets: List<MentionTarget> = emptyList(),
        renderMode: RenderMode = RenderMode.AUTO
    ): result<Sendresult> = withContext(Dispatchers.IO) {
        try {
            // 分块Process
            val chunks = chunkText(text, config.textChunkLimit)
            if (chunks.size > 1) {
                // First块用引用回复, Back续直接发
                val firstresult = sendSingleTextReply(replyToMessageId, chunks[0], mentionTargets, renderMode)
                if (firstresult.isFailure) return@withContext firstresult

                val messageIds = mutableListOf(firstresult.getOrNull()!!.messageId)
                // Back续 chunks Need receiveId, 从 reply API Cannot直接Get chatId
                // soBack续 chunks 作为普通Messagesend不太合适, 这里简化为All用First条的方式
                for (i in 1 until chunks.size) {
                    kotlinx.coroutines.delay(200)
                    val chunkresult = sendQuoteReply(
                        replyToMessageId,
                        if (shouldUseCard(chunks[i])) "interactive" else "text",
                        if (shouldUseCard(chunks[i])) buildMarkdownCard(chunks[i], mentionTargets)
                        else buildTextContent(chunks[i])
                    )
                    if (chunkresult.isSuccess) {
                        messageIds.add(chunkresult.getOrNull()!!.messageId)
                    }
                }
                return@withContext result.success(Sendresult(messageIds.first(), messageIds))
            }

            sendSingleTextReply(replyToMessageId, text, mentionTargets, renderMode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text reply", e)
            result.failure(e)
        }
    }

    /**
     * send单条引用回复
     */
    private suspend fun sendSingleTextReply(
        replyToMessageId: String,
        text: String,
        mentionTargets: List<MentionTarget>,
        renderMode: RenderMode
    ): result<Sendresult> {
        val useCard = when (renderMode) {
            RenderMode.CARD -> true
            RenderMode.TEXT -> false
            RenderMode.AUTO -> shouldUseCard(text)
        }

        return if (useCard) {
            val card = buildMarkdownCard(text, mentionTargets)
            sendQuoteReply(replyToMessageId, "interactive", card)
        } else {
            val content = if (mentionTargets.isNotEmpty()) {
                buildMentionedTextContent(text, mentionTargets)
            } else {
                buildTextContent(text)
            }
            sendQuoteReply(replyToMessageId, "text", content)
        }
    }

    /**
     * GetMessageDetails
     */
    suspend fun getMessage(messageId: String): result<MessageInfo> = withContext(Dispatchers.IO) {
        try {
            val result = client.get("/open-apis/im/v1/messages/$messageId")

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items")

            if (items == null || items.size() == 0) {
                return@withContext result.failure(Exception("Message not found"))
            }

            val item = items[0].asJsonObject
            val chatId = item.get("chat_id")?.asString ?: ""
            val msgType = item.get("msg_type")?.asString ?: ""
            val body = item.getAsJsonObject("body")
            val content = body?.get("content")?.asString ?: ""
            val senderId = item.getAsJsonObject("sender")?.get("id")?.asString

            val messageInfo = MessageInfo(
                messageId = messageId,
                chatId = chatId,
                senderId = senderId,
                content = extractPlainText(content, msgType),
                contentType = msgType,
                createTime = item.get("create_time")?.asLong
            )

            result.success(messageInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get message", e)
            result.failure(e)
        }
    }

    // ===== InternalMethod =====

    /**
     * sendMessage(Internal)
     */
    private suspend fun sendMessageInternal(
        receiveId: String,
        msgType: String,
        content: String,
        receiveIdType: String
    ): result<Sendresult> {
        val body = mapOf(
            "receive_id" to receiveId,
            "msg_type" to msgType,
            "content" to content
        )

        val result = client.post(
            "/open-apis/im/v1/messages?receive_id_type=$receiveIdType",
            body
        )

        if (result.isFailure) {
            return result.failure(result.exceptionOrNull()!!)
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        val messageId = data?.get("message_id")?.asString
            ?: return result.failure(Exception("Missing message_id"))

        Log.d(TAG, "Message sent: $messageId")
        return result.success(Sendresult(messageId, listOf(messageId)))
    }

    /**
     * send分块Message
     */
    private suspend fun sendChunkedMessages(
        receiveId: String,
        chunks: List<String>,
        receiveIdType: String
    ): result<Sendresult> {
        val messageIds = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            val prefix = if (index > 0) "[续...]\n" else ""
            val suffix = if (index < chunks.size - 1) "\n[未完待续...]" else ""
            val text = "$prefix$chunk$suffix"

            val content = buildTextContent(text)
            val result = sendMessageInternal(receiveId, "text", content, receiveIdType)

            if (result.isSuccess) {
                messageIds.add(result.getOrNull()!!.messageId)
            } else {
                Log.e(TAG, "Failed to send chunk $index")
            }

            // 避免send过快
            kotlinx.coroutines.delay(200)
        }

        if (messageIds.isEmpty()) {
            return result.failure(Exception("All chunks failed to send"))
        }

        return result.success(Sendresult(messageIds.first(), messageIds))
    }

    /**
     * BuildTextInside容
     */
    private fun buildTextContent(text: String): String {
        return gson.toJson(mapOf("text" to text))
    }

    /**
     * Build带提及的TextInside容
     */
    private fun buildMentionedTextContent(
        text: String,
        mentionTargets: List<MentionTarget>
    ): String {
        // Build提及Inside容
        val mentionedText = StringBuilder()
        for (target in mentionTargets) {
            mentionedText.append("<at user_id=\"${target.userId}\"></at> ")
        }
        mentionedText.append(text)

        return gson.toJson(mapOf("text" to mentionedText.toString()))
    }

    /**
     * Text分块
     */
    private fun chunkText(text: String, limit: Int): List<String> {
        if (text.length <= limit) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        when (config.chunkMode) {
            FeishuConfig.ChunkMode.LENGTH -> {
                // 按长度分块
                var start = 0
                while (start < text.length) {
                    val end = minOf(start + limit, text.length)
                    chunks.add(text.substring(start, end))
                    start = end
                }
            }
            FeishuConfig.ChunkMode.NEWLINE -> {
                // 按换Row符分块
                val lines = text.split("\n")
                var currentChunk = StringBuilder()

                for (line in lines) {
                    if (currentChunk.length + line.length + 1 > limit) {
                        if (currentChunk.isNotEmpty()) {
                            chunks.add(currentChunk.toString())
                            currentChunk = StringBuilder()
                        }
                        // if单Row超过Limit, 强制分块
                        if (line.length > limit) {
                            chunks.addAll(chunkText(line, limit))
                        } else {
                            currentChunk.append(line)
                        }
                    } else {
                        if (currentChunk.isNotEmpty()) {
                            currentChunk.append("\n")
                        }
                        currentChunk.append(line)
                    }
                }

                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                }
            }
        }

        return chunks
    }

    /**
     * 提取纯Text
     */
    private fun extractPlainText(content: String, msgType: String): String {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            when (msgType) {
                "text" -> json.get("text")?.asString ?: content
                "post" -> {
                    // 富TextMessage, 提取AllText
                    val contentObj = json.getAsJsonObject("content")
                    val zhCn = contentObj?.getAsJsonArray("zh_cn")
                    zhCn?.joinToString("\n") { element ->
                        element.asJsonArray.joinToString("") { node ->
                            val nodeObj = node.asJsonObject
                            nodeObj.get("text")?.asString ?: ""
                        }
                    } ?: content
                }
                else -> content
            }
        } catch (e: Exception) {
            content
        }
    }

    /**
     * 检测YesNoShoulduse卡片格式
     * Aligned with OpenClaw: 任何 markdown 格式都用卡片渲染, 避免原始符号暴露
     */
    private fun shouldUseCard(text: String): Boolean {
        // 检测代码块 ```
        if (text.contains("```")) return true

        // 检测 Markdown Table |...|
        val tableCount = countMarkdownTables(text)
        if (tableCount > 0) {
            if (tableCount > config.maxTablesPerCard) {
                Log.w(TAG, "⚠️ Table数量 ($tableCount) 超过飞书Limit (${config.maxTablesPerCard}),将use纯Text")
                return false
            }
            return true
        }

        // 检测常见 Markdown 格式(加粗、斜体、Title、List、链接等)
        val markdownPatterns = listOf(
            Regex("\\*\\*.+?\\*\\*"),           // **bold**
            Regex("\\*.+?\\*"),                  // *italic*
            Regex("^#{1,6}\\s", RegexOption.MULTILINE),  // ## heading
            Regex("^[-*+]\\s", RegexOption.MULTILINE),   // - list item
            Regex("^\\d+\\.\\s", RegexOption.MULTILINE), // 1. ordered list
            Regex("\\[.+?\\]\\(.+?\\)"),         // [link](url)
            Regex("^>\\s", RegexOption.MULTILINE),       // > blockquote
            Regex("`[^`]+`"),                     // `inline code`
        )

        for (pattern in markdownPatterns) {
            if (pattern.containsMatchIn(text)) return true
        }

        return false
    }

    /**
     * count Markdown 中的Table数量
     */
    private fun countMarkdownTables(text: String): Int {
        var tableCount = 0
        val lines = text.lines()
        var inTable = false

        for (i in 0 until lines.size - 1) {
            val line = lines[i]
            val nextLine = lines.getOrNull(i + 1) ?: continue

            // CheckYesNoYesTableRow(Contains |)
            if (line.contains("|") && !inTable) {
                // CheckDown一RowYesNoYes分隔符(such as |---|---| 或 |:-:|:-:|)
                if (nextLine.matches(Regex("^\\s*\\|[-:| ]+\\|\\s*$"))) {
                    tableCount++
                    inTable = true
                }
            } else if (inTable && !line.contains("|")) {
                // TableEnd
                inTable = false
            }
        }

        return tableCount
    }

    /**
     * Build Markdown 卡片
     * Aligned with OpenClaw buildMarkdownCard()
     *
     * Uses schema 2.0 format for proper markdown rendering (code blocks, tables, etc.)
     */
    private fun buildMarkdownCard(
        text: String,
        mentionTargets: List<MentionTarget> = emptyList()
    ): String {
        // Process @提及
        val cardText = if (mentionTargets.isNotEmpty()) {
            buildMentionedCardContent(text, mentionTargets)
        } else {
            text
        }

        val card = mapOf(
            "schema" to "2.0",
            "config" to mapOf(
                "wide_screen_mode" to true
            ),
            "body" to mapOf(
                "elements" to listOf(
                    mapOf(
                        "tag" to "markdown",
                        "content" to cardText
                    )
                )
            )
        )

        return gson.toJson(card)
    }

    /**
     * Build带提及的卡片Inside容
     * Aligned with OpenClaw buildMentionedCardContent()
     */
    private fun buildMentionedCardContent(
        text: String,
        mentionTargets: List<MentionTarget>
    ): String {
        // In card, use <at user_id="xxx"></at> 语法
        val mentionedText = StringBuilder()
        for (target in mentionTargets) {
            mentionedText.append("<at user_id=\"${target.userId}\"></at> ")
        }
        mentionedText.append(text)
        return mentionedText.toString()
    }
}

/**
 * 渲染Schema(Aligned with OpenClaw)
 */
enum class RenderMode {
    /** Auto检测(Default) */
    AUTO,
    /** 强制use卡片 */
    CARD,
    /** 强制useText */
    TEXT
}

/**
 * 提及目标
 */
data class MentionTarget(
    val userId: String,
    val userType: String = "open_id",
    val name: String? = null
)

/**
 * sendresult
 */
data class Sendresult(
    val messageId: String,
    val allMessageIds: List<String>
)

/**
 * MessageInfo
 */
data class MessageInfo(
    val messageId: String,
    val chatId: String,
    val senderId: String?,
    val content: String,
    val contentType: String,
    val createTime: Long?
)
