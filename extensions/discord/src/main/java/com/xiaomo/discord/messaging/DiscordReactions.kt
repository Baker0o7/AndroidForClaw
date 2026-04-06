/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.messaging

import android.util.Log
import com.xiaomo.discord.DiscordClient

/**
 * Discord Table情反应Manage
 * 参考 Feishu FeishuReactions.kt
 */
class DiscordReactions(private val client: DiscordClient) {
    companion object {
        private const val TAG = "DiscordReactions"

        // 常用Table情
        const val EMOJI_THUMBS_UP = "👍"
        const val EMOJI_THUMBS_DOWN = "👎"
        const val EMOJI_HEART = "❤️"
        const val EMOJI_FIRE = "🔥"
        const val EMOJI_CHECK = "✅"
        const val EMOJI_CROSS = "❌"
        const val EMOJI_EYES = "👀"
        const val EMOJI_THINKING = "🤔"
    }

    /**
     * Add反应
     */
    suspend fun add(
        channelId: String,
        messageId: String,
        emoji: String
    ): result<Unit> {
        return try {
            val result = client.addReaction(channelId, messageId, emoji)
            if (result.isSuccess) {
                Log.d(TAG, "Reaction added: $emoji")
            } else {
                Log.w(TAG, "Failed to add reaction: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reaction", e)
            result.failure(e)
        }
    }

    /**
     * 移除反应
     */
    suspend fun remove(
        channelId: String,
        messageId: String,
        emoji: String
    ): result<Unit> {
        return try {
            val result = client.removeReaction(channelId, messageId, emoji)
            if (result.isSuccess) {
                Log.d(TAG, "Reaction removed: $emoji")
            } else {
                Log.w(TAG, "Failed to remove reaction: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error removing reaction", e)
            result.failure(e)
        }
    }

    /**
     * AddMultiple反应
     */
    suspend fun addMultiple(
        channelId: String,
        messageId: String,
        emojis: List<String>
    ): result<Unit> {
        return try {
            for (emoji in emojis) {
                add(channelId, messageId, emoji)
                // 避免速率Limit
                kotlinx.coroutines.delay(250)
            }
            result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding multiple reactions", e)
            result.failure(e)
        }
    }

    /**
     * AddConfirm标记 (✅)
     */
    suspend fun addCheck(channelId: String, messageId: String): result<Unit> {
        return add(channelId, messageId, EMOJI_CHECK)
    }

    /**
     * AddError标记 (❌)
     */
    suspend fun addCross(channelId: String, messageId: String): result<Unit> {
        return add(channelId, messageId, EMOJI_CROSS)
    }

    /**
     * Addthink标记 (🤔)
     */
    suspend fun addThinking(channelId: String, messageId: String): result<Unit> {
        return add(channelId, messageId, EMOJI_THINKING)
    }

    /**
     * Add关注标记 (👀)
     */
    suspend fun addEyes(channelId: String, messageId: String): result<Unit> {
        return add(channelId, messageId, EMOJI_EYES)
    }
}
