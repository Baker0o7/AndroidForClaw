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
 * Discord History Message Manager
 * Reference Feishu FeishuHistoryManager.kt
 */
class DiscordHistoryManager(
    private val maxHistoryPerChannel: Int = 100
) {
    companion object {
        private const val TAG = "DiscordHistoryManager"
    }

    // History Message Storage (channelId -> List<Message>)
    private val history = ConcurrentHashMap<String, MutableList<HistoryMessage>>()

    /**
     * Add Message to History
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
            // Add Message
            messages.add(
                HistoryMessage(
                    messageId = messageId,
                    authorId = authorId,
                    content = content,
                    timestamp = timestamp
                )
            )

            // Keep History Size Limit
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
     * Get Channel History
     */
    fun getHistory(channelId: String, limit: Int = maxHistoryPerChannel): List<HistoryMessage> {
        val messages = history[channelId] ?: return emptyList()

        synchronized(messages) {
            return messages.takeLast(limit)
        }
    }

    /**
     * Clear Channel History
     */
    fun clearHistory(channelId: String) {
        history.remove(channelId)
        Log.d(TAG, "Cleared history for channel: $channelId")
    }

    /**
     * Clear All History
     */
    fun clearAll() {
        val count = history.size
        history.clear()
        Log.i(TAG, "Cleared all history ($count channels)")
    }

    /**
     * Get Most Recent Message
     */
    fun getRecentMessage(channelId: String): HistoryMessage? {
        val messages = history[channelId] ?: return null

        synchronized(messages) {
            return messages.lastOrNull()
        }
    }

    /**
     * Find Message
     */
    fun findMessage(channelId: String, messageId: String): HistoryMessage? {
        val messages = history[channelId] ?: return null

        synchronized(messages) {
            return messages.find { it.messageId == messageId }
        }
    }

    /**
     * Get Channel Message Count
     */
    fun getMessageCount(channelId: String): Int {
        val messages = history[channelId] ?: return 0

        synchronized(messages) {
            return messages.size
        }
    }

    /**
     * List All Channels
     */
    fun listChannels(): List<String> {
        return history.keys.toList()
    }
}

/**
 * History Message
 */
data class HistoryMessage(
    val messageId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long,
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Set Metadata
     */
    fun setMetadata(key: String, value: Any) {
        metadata[key] = value
    }

    /**
     * Get Metadata
     */
    fun <T> getMetadata(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return metadata[key] as? T
    }
}
