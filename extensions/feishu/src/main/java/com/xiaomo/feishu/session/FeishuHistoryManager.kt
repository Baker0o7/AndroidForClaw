package com.xiaomo.feishu.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import com.xiaomo.feishu.FeishuConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Feishu history manager
 * Aligned with OpenClaw history management
 */
class FeishuHistoryManager(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuHistoryManager"
    }

    private val histories = ConcurrentHashMap<String, MutableList<HistoryEntry>>()
    private val mutex = Mutex()

    /**
     * Add history entry
     */
    suspend fun addHistory(
        chatId: String,
        chatType: String,
        entry: HistoryEntry
    ) = mutex.withLock {
        val key = "$chatType:$chatId"
        val history = histories.getOrPut(key) { mutableListOf() }

        history.add(entry)

        // Limit history size
        val limit = if (chatType == "p2p") config.dmHistoryLimit else config.historyLimit
        while (history.size > limit) {
            history.removeAt(0)
        }

        Log.d(TAG, "Added history: $key (total: ${history.size}/$limit)")
    }

    /**
     * Get history
     */
    fun getHistory(chatId: String, chatType: String, limit: Int? = null): List<HistoryEntry> {
        val key = "$chatType:$chatId"
        val history = histories[key] ?: return emptyList()

        return if (limit != null && limit < history.size) {
            history.takeLast(limit)
        } else {
            history.toList()
        }
    }

    /**
     * Clear history
     */
    suspend fun clearHistory(chatId: String, chatType: String) = mutex.withLock {
        val key = "$chatType:$chatId"
        histories.remove(key)
        Log.d(TAG, "Cleared history: $key")
    }

    /**
     * Clear all history
     */
    suspend fun clearAllHistory() = mutex.withLock {
        histories.clear()
        Log.d(TAG, "Cleared all history")
    }

    /**
     * Get history summary
     */
    fun getHistorySummary(): Map<String, Int> {
        return histories.mapValues { it.value.size }
    }
}

/**
 * History entry
 */
data class HistoryEntry(
    val messageId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
