/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/(all)
 *
 * AndroidForClaw adaptation: session persistence.
 */
package com.xiaomo.discord.session

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Discord Message Deduplication
 * Reference Feishu FeishuDedup.kt
 */
class DiscordDedup(
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(5)
) {
    companion object {
        private const val TAG = "DiscordDedup"
    }

    // Message ID Cache (messageId -> timestamp)
    private val seenMessages = ConcurrentHashMap<String, Long>()

    /**
     * Check if Message has been Processed
     */
    fun isDuplicate(messageId: String): Boolean {
        val now = System.currentTimeMillis()

        // Clean up expired entries
        cleanupExpired(now)

        // Check if exists
        val seen = seenMessages.putIfAbsent(messageId, now)
        return seen != null
    }

    /**
     * Mark Message as Processed
     */
    fun markSeen(messageId: String) {
        seenMessages[messageId] = System.currentTimeMillis()
    }

    /**
     * Clean up Expired Entries
     */
    private fun cleanupExpired(now: Long) {
        val expired = seenMessages.filter { (_, timestamp) ->
            now - timestamp > ttlMs
        }

        expired.keys.forEach { messageId ->
            seenMessages.remove(messageId)
        }

        if (expired.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expired.size} expired entries")
        }
    }

    /**
     * Clear All Cache
     */
    fun clearAll() {
        val count = seenMessages.size
        seenMessages.clear()
        Log.i(TAG, "Cleared all dedup cache ($count entries)")
    }

    /**
     * Get Cache Size
     */
    fun getCacheSize(): Int {
        return seenMessages.size
    }
}
