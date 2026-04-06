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
 * 飞书SessionManage器
 * Aligned with OpenClaw SessionManage逻辑
 */
class FeishuSessionManager(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuSessionManager"
        const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30分钟
    }

    private val sessions = ConcurrentHashMap<String, FeishuSession>()
    private val mutex = Mutex()

    /**
     * Get或CreateSession
     */
    suspend fun getOrCreateSession(
        chatId: String,
        chatType: String,
        senderId: String? = null
    ): FeishuSession = mutex.withLock {
        val sessionKey = buildSessionKey(chatId, chatType, senderId)

        // Check现HasSession
        val existing = sessions[sessionKey]
        if (existing != null && !existing.isExpired()) {
            existing.updateLastActivity()
            return existing
        }

        // CreateNewSession
        val session = FeishuSession(
            sessionId = sessionKey,
            chatId = chatId,
            chatType = chatType,
            senderId = senderId,
            createdAt = System.currentTimeMillis(),
            lastActivityAt = System.currentTimeMillis()
        )

        sessions[sessionKey] = session
        Log.d(TAG, "Created session: $sessionKey (total: ${sessions.size})")

        return session
    }

    /**
     * GetSession
     */
    fun getSession(chatId: String, chatType: String): FeishuSession? {
        val sessionKey = buildSessionKey(chatId, chatType)
        return sessions[sessionKey]?.takeIf { !it.isExpired() }
    }

    /**
     * DeleteSession
     */
    suspend fun removeSession(chatId: String, chatType: String) = mutex.withLock {
        val sessionKey = buildSessionKey(chatId, chatType)
        sessions.remove(sessionKey)
        Log.d(TAG, "Removed session: $sessionKey")
    }

    /**
     * Clean expired sessions
     */
    suspend fun cleanupExpiredSessions() = mutex.withLock {
        val expiredKeys = sessions.entries
            .filter { it.value.isExpired() }
            .map { it.key }

        expiredKeys.forEach { key ->
            sessions.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired sessions")
        }
    }

    /**
     * GetAll活跃Session
     */
    fun getActiveSessions(): List<FeishuSession> {
        return sessions.values
            .filter { !it.isExpired() }
            .toList()
    }

    /**
     * BuildSession key
     * Aligned with OpenClaw session key convention:
     * - Standard: "$chatType:$chatId" (e.g. "group:oc_xxx", "p2p:ou_xxx")
     * - Per-user scope: "group:$chatId:user:$senderId" (isolate per sender in groups)
     */
    private fun buildSessionKey(chatId: String, chatType: String, senderId: String? = null): String {
        return when {
            chatType == "group" && config.groupSessionScope == "per-user" && !senderId.isNullOrBlank() ->
                "group:$chatId:user:$senderId"
            else ->
                "$chatType:$chatId"
        }
    }
}

/**
 * 飞书Session
 */
data class FeishuSession(
    val sessionId: String,
    val chatId: String,
    val chatType: String,
    val senderId: String?,
    val createdAt: Long,
    var lastActivityAt: Long,
    val context: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * UpdatemostBack活动Time
     */
    fun updateLastActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * YesNo过期
     */
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastActivityAt) > FeishuSessionManager.SESSION_TIMEOUT_MS
    }

    /**
     * SettingsUpDown文
     */
    fun setContext(key: String, value: Any) {
        context[key] = value
    }

    /**
     * GetUpDown文
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getContext(key: String): T? {
        return context[key] as? T
    }

    /**
     * clearUpDown文
     */
    fun clearContext() {
        context.clear()
    }
}
