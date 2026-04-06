package com.xiaomo.feishu.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Feishu message deduplication
 * Aligned with OpenClaw src/dedup.ts
 */
class FeishuDedup {
    companion object {
        private const val TAG = "FeishuDedup"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
        private const val MAX_CACHE_SIZE = 10000
    }

    private val messageCache = ConcurrentHashMap<String, Long>()

    /**
     * Try record message (returns false if already exists)
     */
    fun tryRecordMessage(messageId: String): Boolean {
        val now = System.currentTimeMillis()

        // Clean up expired cache
        cleanupExpired(now)

        // Check if already exists
        val existing = messageCache.putIfAbsent(messageId, now)
        if (existing != null) {
            Log.d(TAG, "Duplicate message detected: $messageId")
            return false
        }

        // Limit cache size
        if (messageCache.size > MAX_CACHE_SIZE) {
            Log.w(TAG, "Message cache size exceeded, clearing old entries")
            cleanupOldest(MAX_CACHE_SIZE / 2)
        }

        return true
    }

    /**
     * Check if message has been processed
     */
    fun isMessageProcessed(messageId: String): Boolean {
        return messageCache.containsKey(messageId)
    }

    /**
     * Clean up expired cache
     */
    private fun cleanupExpired(now: Long) {
        val expiredKeys = messageCache.entries
            .filter { (now - it.value) > CACHE_TTL_MS }
            .map { it.key }

        expiredKeys.forEach { key ->
            messageCache.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired message records")
        }
    }

    /**
     * Clean up oldest records
     */
    private fun cleanupOldest(keepCount: Int) {
        val sorted = messageCache.entries
            .sortedBy { it.value }
            .take(messageCache.size - keepCount)

        sorted.forEach { entry ->
            messageCache.remove(entry.key)
        }

        Log.d(TAG, "Cleaned up ${sorted.size} oldest message records")
    }

    /**
     * Clear all cache
     */
    fun clearAll() {
        messageCache.clear()
        Log.d(TAG, "Cleared all message records")
    }

    /**
     * Get cache stats
     */
    fun getStats(): DedupStats {
        return DedupStats(
            totalMessages = messageCache.size,
            oldestTimestamp = messageCache.values.minOrNull(),
            newestTimestamp = messageCache.values.maxOrNull()
        )
    }
}

/**
 * Deduplication statistics
 */
data class DedupStats(
    val totalMessages: Int,
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?
)
