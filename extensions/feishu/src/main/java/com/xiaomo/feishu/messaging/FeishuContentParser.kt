package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/bot-content.ts
 * - ../openclaw/src/channels/feishu/post.ts
 *
 * Parses all Feishu message types into text/Markdown for the agent.
 */

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parse result
 */
data class Parseresult(
    val text: String,
    val mediaKeys: MediaKeys? = null
)

/**
 * Media key info
 */
data class MediaKeys(
    val imageKey: String? = null,
    val fileKey: String? = null,
    val fileName: String? = null,
    val mediaType: String // "image", "file", "audio", "video", "sticker"
)

/**
 * Feishu message content parser
 * Aligned with OpenClaw bot-content.ts + post.ts
 */
object FeishuContentParser {

    private const val TAG = "FeishuContentParser"
    private val gson = Gson()

    /**
     * Parse message content (top-level route)
     * Aligned with OpenClaw parseMessageContent()
     */
    fun parseMessageContent(msgType: String, content: String): Parseresult {
        return try {
            when (msgType) {
                "text" -> parseTextContent(content)
                "post" -> Parseresult(text = parsePostContent(content))
                "image" -> parseImageContent(content)
                "file" -> parseFileContent(content)
                "audio" -> parseAudioContent(content)
                "video" -> parseVideoContent(content)
                "sticker" -> parseStickerContent(content)
                "share_chat" -> parseShareChatContent(content)
                "share_user" -> parseShareUserContent(content)
                "merge_forward" -> Parseresult(text = parseMergeForwardContent(content))
                else -> {
                    Log.w(TAG, "Unknown message type: $msgType")
                    Parseresult(text = content)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $msgType content", e)
            Parseresult(text = content)
        }
    }

    // ===== Text =====

    private fun parseTextContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val text = json.get("text")?.asString ?: content
            Parseresult(text = text)
        } catch (e: Exception) {
            Parseresult(text = content)
        }
    }

    // ===== Post (Rich Text) =====

    /**
     * Convert Feishu rich text (post) to Markdown
     * Aligned with OpenClaw parsePostContent() + post.ts
     */
    fun parsePostContent(content: String): String {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)

            // Find payload: direct {title, content} or post.zh_cn/en_us down
            val payload = resolvePostPayload(json) ?: return content

            val title = payload.get("title")?.asString
            val paragraphs = payload.getAsJsonArray("content") ?: return content

            val sb = StringBuilder()

            // Title -> Markdown heading
            if (!title.isNullOrBlank()) {
                sb.appendLine("## $title")
                sb.appendLine()
            }

            // Each paragraph is an element array
            for (i in 0 until paragraphs.size()) {
                val paragraph = paragraphs[i]
                if (paragraph is JsonArray) {
                    val line = renderParagraph(paragraph)
                    sb.appendLine(line)
                }
            }

            sb.toString().trimEnd()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse post content", e)
            content
        }
    }

    /**
     * Locate post payload (supports multiple JSON structures)
     */
    private fun resolvePostPayload(json: JsonObject): JsonObject? {
        // Direct {title, content} structure
        if (json.has("content") && json.get("content") is JsonArray) {
            return json
        }

        // Nested post.zh_cn / post.en_us structure
        val post = json.getAsJsonObject("post") ?: json

        // Try multiple locales
        for (locale in listOf("zh_cn", "en_us", "ja_jp")) {
            val localePayload = post.getAsJsonObject(locale)
            if (localePayload != null) return localePayload
        }

        // Take first available locale
        for (key in post.keySet()) {
            val child = post.get(key)
            if (child is JsonObject && child.has("content")) {
                return child
            }
        }

        return null
    }

    /**
     * Render paragraph elements to Markdown
     */
    private fun renderParagraph(elements: JsonArray): String {
        val sb = StringBuilder()
        for (element in elements) {
            if (element is JsonObject) {
                sb.append(renderElement(element))
            }
        }
        return sb.toString()
    }

    /**
     * Render single rich text element
     * Aligned with OpenClaw post.ts element rendering
     */
    private fun renderElement(element: JsonObject): String {
        val tag = element.get("tag")?.asString ?: return ""

        return when (tag) {
            "text" -> renderTextElement(element)
            "a" -> {
                val text = element.get("text")?.asString ?: ""
                val href = element.get("href")?.asString ?: ""
                if (href.isNotEmpty()) "[$text]($href)" else text
            }
            "at" -> {
                val userName = element.get("user_name")?.asString
                    ?: element.get("name")?.asString ?: "user"
                "@$userName"
            }
            "img" -> {
                val imageKey = element.get("image_key")?.asString ?: ""
                "[image:$imageKey]"
            }
            "media" -> {
                val fileKey = element.get("file_key")?.asString ?: ""
                "[file:$fileKey]"
            }
            "emotion" -> {
                val emojiType = element.get("emoji_type")?.asString ?: ""
                "[$emojiType]"
            }
            "code_block", "pre" -> {
                val language = element.get("language")?.asString ?: ""
                val text = element.get("text")?.asString ?: ""
                "\n```$language\n$text\n```\n"
            }
            "code" -> {
                val text = element.get("text")?.asString ?: ""
                "`$text`"
            }
            "hr" -> "\n---\n"
            "br" -> "\n"
            else -> element.get("text")?.asString ?: ""
        }
    }

    /**
     * Render text element (supports styles)
     */
    private fun renderTextElement(element: JsonObject): String {
        val text = element.get("text")?.asString ?: return ""
        val style = element.getAsJsonObject("style") ?: return text

        var result = text
        if (style.get("bold")?.asBoolean == true) result = "**$result**"
        if (style.get("italic")?.asBoolean == true) result = "*$result*"
        if (style.get("strikethrough")?.asBoolean == true) result = "~~$result~~"
        if (style.get("underline")?.asBoolean == true) result = "<u>$result</u>"
        if (style.get("code")?.asBoolean == true) result = "`$result`"

        return result
    }

    // ===== Media types =====

    private fun parseImageContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val imageKey = json.get("image_key")?.asString
            Parseresult(
                text = "[Image]",
                mediaKeys = imageKey?.let { MediaKeys(imageKey = it, mediaType = "image") }
            )
        } catch (e: Exception) {
            Parseresult(text = "[Image]")
        }
    }

    private fun parseFileContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val fileKey = json.get("file_key")?.asString
            val fileName = json.get("file_name")?.asString ?: "Unknown file"
            Parseresult(
                text = "[File: $fileName]",
                mediaKeys = fileKey?.let { MediaKeys(fileKey = it, fileName = fileName, mediaType = "file") }
            )
        } catch (e: Exception) {
            Parseresult(text = "[File]")
        }
    }

    private fun parseAudioContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val fileKey = json.get("file_key")?.asString
            Parseresult(
                text = "[Audio]",
                mediaKeys = fileKey?.let { MediaKeys(fileKey = it, mediaType = "audio") }
            )
        } catch (e: Exception) {
            Parseresult(text = "[Audio]")
        }
    }

    private fun parseVideoContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val fileKey = json.get("file_key")?.asString
            val imageKey = json.get("image_key")?.asString
            Parseresult(
                text = "[Video]",
                mediaKeys = fileKey?.let {
                    MediaKeys(fileKey = it, imageKey = imageKey, mediaType = "video")
                }
            )
        } catch (e: Exception) {
            Parseresult(text = "[Video]")
        }
    }

    private fun parseStickerContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val fileKey = json.get("file_key")?.asString
            Parseresult(
                text = "[Sticker]",
                mediaKeys = fileKey?.let { MediaKeys(fileKey = it, mediaType = "sticker") }
            )
        } catch (e: Exception) {
            Parseresult(text = "[Sticker]")
        }
    }

    // ===== Share types =====

    private fun parseShareChatContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val name = json.get("chat_name")?.asString
                ?: json.get("name")?.asString
            val chatId = json.get("share_chat_id")?.asString ?: ""
            val display = if (!name.isNullOrBlank()) "[Shared Chat: $name]" else "[Shared Chat: $chatId]"
            Parseresult(text = display)
        } catch (e: Exception) {
            Parseresult(text = "[Shared Chat]")
        }
    }

    private fun parseShareUserContent(content: String): Parseresult {
        return try {
            val json = gson.fromJson(content, JsonObject::class.java)
            val userId = json.get("user_id")?.asString ?: ""
            Parseresult(text = "[Shared User: $userId]")
        } catch (e: Exception) {
            Parseresult(text = "[Shared User]")
        }
    }

    // ===== Merge Forward =====

    /**
     * Parse merge forwarded message
     * Aligned with OpenClaw parseMergeForwardContent()
     */
    fun parseMergeForwardContent(content: String): String {
        return try {
            val json = gson.fromJson(content, JsonElement::class.java)

            val messages: JsonArray = when {
                json is JsonArray -> json
                json is JsonObject && json.has("messages") -> json.getAsJsonArray("messages")
                json is JsonObject && json.has("combine") -> json.getAsJsonArray("combine")
                else -> return "[Merge Forward Message]"
            }

            if (messages.size() == 0) return "[Merge Forward Message (Empty)]"

            val sb = StringBuilder("[Merge Forward Message]\n")
            val limit = minOf(messages.size(), 50)

            for (i in 0 until limit) {
                val msg = messages[i]
                if (msg is JsonObject) {
                    val msgType = msg.get("msg_type")?.asString ?: "text"
                    val body = msg.getAsJsonObject("body")
                    val msgContent = body?.get("content")?.asString ?: ""
                    val summary = formatSubMessage(msgType, msgContent)
                    sb.appendLine("- $summary")
                }
            }

            if (messages.size() > 50) {
                sb.appendLine("... (total ${messages.size()} messages)")
            }

            sb.toString().trimEnd()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse merge forward", e)
            "[Merge Forward Message]"
        }
    }

    /**
     * Format sub-message summary
     */
    private fun formatSubMessage(msgType: String, content: String): String {
        return when (msgType) {
            "text" -> {
                try {
                    val json = gson.fromJson(content, JsonObject::class.java)
                    val text = json.get("text")?.asString ?: content
                    if (text.length > 100) text.take(100) + "..." else text
                } catch (e: Exception) {
                    if (content.length > 100) content.take(100) + "..." else content
                }
            }
            "image" -> "[Image]"
            "file" -> "[File]"
            "audio" -> "[Audio]"
            "video" -> "[Video]"
            "sticker" -> "[Sticker]"
            "post" -> "[Rich Text]"
            "share_chat" -> "[Shared Chat]"
            "share_user" -> "[Shared User]"
            else -> "[$msgType]"
        }
    }
}