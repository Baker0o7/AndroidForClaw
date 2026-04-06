/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.messaging

import android.util.Log
import com.google.gson.JsonObject
import com.xiaomo.discord.DiscordClient

/**
 * Discord Messagesend封装
 * 参考 Feishu FeishuSender.kt
 */
class DiscordSender(private val client: DiscordClient) {
    companion object {
        private const val TAG = "DiscordSender"
    }

    /**
     * sendTextMessage
     */
    suspend fun sendText(
        channelId: String,
        text: String,
        replyTo: String? = null,
        silent: Boolean = false
    ): result<String> {
        return try {
            val messageReference = replyTo?.let {
                mapOf("message_id" to it)
            }

            val result = client.sendMessage(channelId, text, messageReference = messageReference)
            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id"))

                Log.d(TAG, "Text message sent: $messageId")
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
            result.failure(e)
        }
    }

    /**
     * send嵌入Message (Embed)
     */
    suspend fun sendEmbed(
        channelId: String,
        embed: Map<String, Any>,
        replyTo: String? = null
    ): result<String> {
        return try {
            val embeds = listOf(embed)
            val messageReference = replyTo?.let {
                mapOf("message_id" to it)
            }

            val result = client.sendMessage(
                channelId = channelId,
                content = "",
                embeds = embeds,
                messageReference = messageReference
            )

            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id"))

                Log.d(TAG, "Embed message sent: $messageId")
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send embed message", e)
            result.failure(e)
        }
    }

    /**
     * sendGroup件Message (Buttons, Selects, etc.)
     */
    suspend fun sendWithComponents(
        channelId: String,
        text: String,
        components: List<Map<String, Any>>,
        replyTo: String? = null
    ): result<String> {
        return try {
            val messageReference = replyTo?.let {
                mapOf("message_id" to it)
            }

            val result = client.sendMessage(
                channelId = channelId,
                content = text,
                components = components,
                messageReference = messageReference
            )

            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id"))

                Log.d(TAG, "Component message sent: $messageId")
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send component message", e)
            result.failure(e)
        }
    }

    /**
     * send DM (私聊Message)
     */
    suspend fun sendDirectMessage(userId: String, text: String): result<String> {
        return try {
            val result = client.sendDirectMessage(userId, text)
            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id"))

                Log.d(TAG, "DM sent: $messageId")
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DM", e)
            result.failure(e)
        }
    }

    /**
     * FormatMessageInside容
     * Support Discord Markdown
     */
    fun formatMessage(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        code: Boolean = false,
        codeBlock: String? = null
    ): String {
        var formatted = text

        if (codeBlock != null) {
            return "```$codeBlock\n$text\n```"
        }

        if (code) {
            return "`$text`"
        }

        if (bold) {
            formatted = "**$formatted**"
        }

        if (italic) {
            formatted = "*$formatted*"
        }

        return formatted
    }

    /**
     * Create Embed Build器
     */
    fun buildEmbed(
        title: String? = null,
        description: String? = null,
        color: Int? = null,
        fields: List<EmbedField>? = null,
        footer: String? = null,
        timestamp: String? = null
    ): Map<String, Any> {
        val embed = mutableMapOf<String, Any>()

        title?.let { embed["title"] = it }
        description?.let { embed["description"] = it }
        color?.let { embed["color"] = it }
        footer?.let {
            embed["footer"] = mapOf("text" to it)
        }
        timestamp?.let { embed["timestamp"] = it }

        fields?.let {
            embed["fields"] = it.map { field ->
                mapOf(
                    "name" to field.name,
                    "value" to field.value,
                    "inline" to field.inline
                )
            }
        }

        return embed
    }

    data class EmbedField(
        val name: String,
        val value: String,
        val inline: Boolean = false
    )
}
