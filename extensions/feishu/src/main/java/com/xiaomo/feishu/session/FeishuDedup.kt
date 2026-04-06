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
 * 飞书Message去重
 * Aligned with OpenClaw src/dedup.ts
 */
class FeishuDedup {
    companion object {
        private const val TAG = "FeishuDedup"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10分钟
        private const val MAX_CACHE_SIZE = 10000
    }

    private val messageCache = ConcurrentHashMap<String, Long>()

    /**
     * AttemptRecordMessage(if已Exists则Return false)
     */
    fun tryRecordMessage(messageId: String): Boolean {
        val now = System.currentTimeMillis()

        // 清理过期Cache
        cleanupExpired(now)

        // CheckYesNo已Exists
        val existing = messageCache.putIfAbsent(messageId, now)
        if (existing != null) {
            Log.d(TAG, "Duplicate message detected: $messageId")
            return false
        }

        // LimitCacheSize
        if (messageCache.size > MAX_CACHE_SIZE) {
            Log.w(TAG, "Message cache size exceeded, clearing old entries")
            cleanupOldest(MAX_CACHE_SIZE / 2)
        }

        return true
    }

    /**
     * CheckMessageYesNo已Process
     */
    fun isMessageProcessed(messageId: String): Boolean {
        return messageCache.containsKey(messageId)
    }

    /**
     * 清理过期Cache
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
     * 清理mostOld的Record
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
     * clearAllCache
     */
    fun clearAll() {
        messageCache.clear()
        Log.d(TAG, "Cleared all message records")
    }

    /**
     * GetCachecount
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
 * 去重count
 */
data class DedupStats(
    val totalMessages: Int,
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?
)
