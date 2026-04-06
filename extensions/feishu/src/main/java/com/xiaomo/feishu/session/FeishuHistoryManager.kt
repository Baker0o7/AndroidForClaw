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
 * 飞书历史RecordManage器
 * Aligned with OpenClaw 历史RecordManage
 */
class FeishuHistoryManager(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuHistoryManager"
    }

    private val histories = ConcurrentHashMap<String, MutableList<HistoryEntry>>()
    private val mutex = Mutex()

    /**
     * Add历史Record
     */
    suspend fun addHistory(
        chatId: String,
        chatType: String,
        entry: HistoryEntry
    ) = mutex.withLock {
        val key = "$chatType:$chatId"
        val history = histories.getOrPut(key) { mutableListOf() }

        history.add(entry)

        // Limit历史Record数量
        val limit = if (chatType == "p2p") config.dmHistoryLimit else config.historyLimit
        while (history.size > limit) {
            history.removeAt(0)
        }

        Log.d(TAG, "Added history: $key (total: ${history.size}/$limit)")
    }

    /**
     * Get历史Record
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
     * clear历史Record
     */
    suspend fun clearHistory(chatId: String, chatType: String) = mutex.withLock {
        val key = "$chatType:$chatId"
        histories.remove(key)
        Log.d(TAG, "Cleared history: $key")
    }

    /**
     * clearAll历史Record
     */
    suspend fun clearAllHistory() = mutex.withLock {
        histories.clear()
        Log.d(TAG, "Cleared all history")
    }

    /**
     * Get历史Record摘要
     */
    fun getHistorySummary(): Map<String, Int> {
        return histories.mapValues { it.value.size }
    }
}

/**
 * 历史Record条目
 */
data class HistoryEntry(
    val messageId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
