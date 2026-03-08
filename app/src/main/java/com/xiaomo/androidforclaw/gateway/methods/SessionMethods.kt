package com.xiaomo.androidforclaw.gateway.methods

import com.xiaomo.androidforclaw.agent.session.SessionManager
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.gateway.protocol.*

/**
 * Session RPC methods implementation
 */
class SessionMethods(
    private val sessionManager: SessionManager
) {
    /**
     * sessions.list() - List all sessions
     */
    fun sessionsList(params: Map<String, Any?>?): SessionListResult {
        val keys = sessionManager.getAllKeys()
        val sessions = keys.map { key ->
            val session = sessionManager.get(key)
            SessionInfo(
                key = key,
                messageCount = session?.messageCount() ?: 0,
                createdAt = session?.createdAt ?: "",
                updatedAt = session?.updatedAt ?: ""
            )
        }
        return SessionListResult(sessions = sessions)
    }

    /**
     * sessions.preview() - Preview a session's messages
     */
    fun sessionsPreview(params: Map<String, Any?>?): SessionPreviewResult {
        val key = params?.get("key") as? String
            ?: throw IllegalArgumentException("key required")

        val session = sessionManager.get(key)
            ?: throw IllegalArgumentException("Session not found: $key")

        val messages: List<SessionMessage> = session.messages.map { msg: LegacyMessage ->
            SessionMessage(
                role = msg.role,
                content = msg.content?.toString() ?: "",
                timestamp = System.currentTimeMillis()
            )
        }

        return SessionPreviewResult(key = key, messages = messages)
    }

    /**
     * sessions.reset() - Reset a session
     */
    fun sessionsReset(params: Map<String, Any?>?): Map<String, Boolean> {
        val key = params?.get("key") as? String
            ?: throw IllegalArgumentException("key required")

        sessionManager.clear(key)
        return mapOf("success" to true)
    }

    /**
     * sessions.delete() - Delete a session
     */
    fun sessionsDelete(params: Map<String, Any?>?): Map<String, Boolean> {
        val key = params?.get("key") as? String
            ?: throw IllegalArgumentException("key required")

        sessionManager.clear(key)
        return mapOf("success" to true)
    }

    /**
     * sessions.patch() - Patch a session
     *
     * 支持的操作:
     * - metadata: 更新 session metadata
     * - messages: 操作消息列表 (add, remove, update)
     */
    fun sessionsPatch(params: Map<String, Any?>?): Map<String, Boolean> {
        val key = params?.get("key") as? String
            ?: throw IllegalArgumentException("key required")

        val session = sessionManager.get(key)
            ?: throw IllegalArgumentException("Session not found: $key")

        // 更新 metadata
        val metadata = params["metadata"] as? Map<String, Any?>
        if (metadata != null) {
            session.metadata.putAll(metadata)
        }

        // 操作消息
        val messagesOp = params["messages"] as? Map<String, Any?>
        if (messagesOp != null) {
            val operation = messagesOp["op"] as? String

            when (operation) {
                "add" -> {
                    // 添加消息
                    val role = messagesOp["role"] as? String ?: "user"
                    val content = messagesOp["content"] as? String ?: ""
                    session.addMessage(LegacyMessage(role = role, content = content))
                }
                "remove" -> {
                    // 删除指定索引的消息
                    val index = (messagesOp["index"] as? Number)?.toInt()
                    if (index != null && index >= 0 && index < session.messages.size) {
                        session.messages.removeAt(index)
                    }
                }
                "clear" -> {
                    // 清空所有消息
                    session.clearMessages()
                }
                "truncate" -> {
                    // 保留最后 N 条消息
                    val count = (messagesOp["count"] as? Number)?.toInt() ?: 10
                    if (session.messages.size > count) {
                        val keep = session.messages.takeLast(count)
                        session.messages.clear()
                        session.messages.addAll(keep)
                    }
                }
            }
        }

        // 保存 session
        sessionManager.save(session)

        return mapOf("success" to true)
    }
}
