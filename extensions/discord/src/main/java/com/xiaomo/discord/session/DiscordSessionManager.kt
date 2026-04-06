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
 * Discord SessionManage
 * 参考 Feishu FeishuSessionManager.kt
 */
class DiscordSessionManager {
    companion object {
        private const val TAG = "DiscordSessionManager"
    }

    // SessionStorage (channelId -> Session)
    private val sessions = ConcurrentHashMap<String, DiscordSession>()

    /**
     * Get或CreateSession
     */
    fun getOrCreateSession(channelId: String, chatType: String): DiscordSession {
        return sessions.getOrPut(channelId) {
            Log.d(TAG, "Creating new session: $channelId ($chatType)")
            DiscordSession(
                channelId = channelId,
                chatType = chatType,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * GetSession
     */
    fun getSession(channelId: String): DiscordSession? {
        return sessions[channelId]
    }

    /**
     * 移除Session
     */
    fun removeSession(channelId: String): DiscordSession? {
        Log.d(TAG, "Removing session: $channelId")
        return sessions.remove(channelId)
    }

    /**
     * clearAllSession
     */
    fun clearAll() {
        Log.i(TAG, "Clearing all sessions (${sessions.size})")
        sessions.clear()
    }

    /**
     * Get活跃Session数量
     */
    fun getActiveSessionCount(): Int {
        return sessions.size
    }

    /**
     * ListAllSession
     */
    fun listSessions(): List<DiscordSession> {
        return sessions.values.toList()
    }

    /**
     * Clean expired sessions
     */
    fun cleanupExpiredSessions(maxIdleMs: Long = 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        val expired = sessions.filter { (_, session) ->
            now - session.lastActivityAt > maxIdleMs
        }

        expired.forEach { (channelId, _) ->
            Log.d(TAG, "Removing expired session: $channelId")
            sessions.remove(channelId)
        }

        if (expired.isNotEmpty()) {
            Log.i(TAG, "Cleaned up ${expired.size} expired sessions")
        }
    }
}

/**
 * Discord Session
 */
data class DiscordSession(
    val channelId: String,
    val chatType: String, // "direct", "channel", "thread"
    val createdAt: Long,
    var lastActivityAt: Long = System.currentTimeMillis(),
    var messageCount: Int = 0,
    val context: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Update活跃Time
     */
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
        messageCount++
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
    fun <T> getContext(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return context[key] as? T
    }

    /**
     * clearUpDown文
     */
    fun clearContext() {
        context.clear()
    }
}
