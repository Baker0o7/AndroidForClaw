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
 * Discord Message去重
 * 参考 Feishu FeishuDedup.kt
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
     * CheckMessageYesNo已Process
     */
    fun isDuplicate(messageId: String): Boolean {
        val now = System.currentTimeMillis()

        // 清理过期条目
        cleanupExpired(now)

        // CheckYesNoExists
        val seen = seenMessages.putIfAbsent(messageId, now)
        return seen != null
    }

    /**
     * 标记Message为已Process
     */
    fun markSeen(messageId: String) {
        seenMessages[messageId] = System.currentTimeMillis()
    }

    /**
     * 清理过期条目
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
     * clearAllCache
     */
    fun clearAll() {
        val count = seenMessages.size
        seenMessages.clear()
        Log.i(TAG, "Cleared all dedup cache ($count entries)")
    }

    /**
     * GetCacheSize
     */
    fun getCacheSize(): Int {
        return seenMessages.size
    }
}
