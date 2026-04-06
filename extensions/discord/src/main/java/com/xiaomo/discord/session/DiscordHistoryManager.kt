/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/(all)
 *
 * AndroidForClaw adaptation: session persistence.
 */
package com.xiaomo.discord.session

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Discord 历史MessageManage
 * 参考 Feishu FeishuHistoryManager.kt
 */
class DiscordHistoryManager(
    private val maxHistoryPerChannel: Int = 100
) {
    companion object {
        private const val TAG = "DiscordHistoryManager"
    }

    // 历史MessageStorage (channelId -> List<Message>)
    private val history = ConcurrentHashMap<String, MutableList<HistoryMessage>>()

    /**
     * AddMessage到历史
     */
    fun addMessage(
        channelId: String,
        messageId: String,
        authorId: String,
        content: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val messages = history.getOrPut(channelId) { mutableListOf() }

        synchronized(messages) {
            // AddMessage
            messages.add(
                HistoryMessage(
                    messageId = messageId,
                    authorId = authorId,
                    content = content,
                    timestamp = timestamp
                )
            )

            // 保持历史SizeLimit
            if (messages.size > maxHistoryPerChannel) {
                val toRemove = messages.size - maxHistoryPerChannel
                repeat(toRemove) {
                    messages.removeAt(0)
                }
            }
        }

        Log.d(TAG, "Added message to history: $channelId (${messages.size} messages)")
    }

    /**
     * GetChannel历史
     */
    fun getHistory(channelId: String, limit: Int = maxHistoryPerChannel): List<HistoryMessage> {
        val messages = history[channelId] ?: return emptyList()

        synchronized(messages) {
            return messages.takeLast(limit)
        }
    }

    /**
     * clearChannel历史
     */
    fun clearHistory(channelId: String) {
        history.remove(channelId)
        Log.d(TAG, "Cleared history for channel: $channelId")
    }

    /**
     * clearAll历史
     */
    fun clearAll() {
        val count = history.size
        history.clear()
        Log.i(TAG, "Cleared all history ($count channels)")
    }

    /**
     * Getmost近的Message
     */
    fun getRecentMessage(channelId: String): HistoryMessage? {
        val messages = history[channelId] ?: return null

        synchronized(messages) {
            return messages.lastOrNull()
        }
    }

    /**
     * FindMessage
     */
    fun findMessage(channelId: String, messageId: String): HistoryMessage? {
        val messages = history[channelId] ?: return null

        synchronized(messages) {
            return messages.find { it.messageId == messageId }
        }
    }

    /**
     * GetChannelMessage数量
     */
    fun getMessageCount(channelId: String): Int {
        val messages = history[channelId] ?: return 0

        synchronized(messages) {
            return messages.size
        }
    }

    /**
     * ListAllChannel
     */
    fun listChannels(): List<String> {
        return history.keys.toList()
    }
}

/**
 * 历史Message
 */
data class HistoryMessage(
    val messageId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long,
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Settings元Data
     */
    fun setMetadata(key: String, value: Any) {
        metadata[key] = value
    }

    /**
     * Get元Data
     */
    fun <T> getMetadata(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return metadata[key] as? T
    }
}
