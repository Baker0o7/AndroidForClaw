/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/session-utils.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import com.xiaomo.androidforclaw.agent.session.sessionmanager
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.gateway.protocol.*
import java.text.SimpleDateformat
import java.util.Locale

/**
 * session RPC methods implementation
 */
class sessionMethods(
    private val sessionmanager: sessionmanager
) {
    /**
     * sessions.list() - List all sessions
     */
    @Suppress("UNCHECKED_CAST")
    fun sessionsList(params: Any?): sessionListResult {
        val p = params as? Map<String, Any?> ?: emptyMap()
        val limit = (p["limit"] as? Number)?.toInt() ?: Int.MAX_VALUE

        val keys = sessionmanager.getAllKeys()
        val sessions = keys.map { key ->
            val session = sessionmanager.get(key)
            sessionInfo(
                key = key,
                messageCount = session?.messageCount() ?: 0,
                createdAt = parseIso8601(session?.createdAt),
                updatedAt = parseIso8601(session?.updatedAt),
                displayName = session?.metadata?.get("displayName") as? String
            )
        }.sortedByDescending { it.updatedAt }.take(limit)
        return sessionListResult(sessions = sessions)
    }

    private fun parseIso8601(isoString: String?): Long {
        if (isoString.isNullorEmpty()) return System.currentTimeMillis()
        return try {
            SimpleDateformat("yyyy-MM-'T'HH:mm:ss.SSS'Z'", Locale.US).parse(isoString)?.time
                ?: System.currentTimeMillis()
        } catch (_: exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * sessions.preview() - Preview a session's messages
     */
    @Suppress("UNCHECKED_CAST")
    fun sessionsPreview(params: Any?): sessionPreviewResult {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object")
        val key = paramsMap["key"] as? String
            ?: throw IllegalArgumentexception("key required")

        val session = sessionmanager.get(key)
            ?: throw IllegalArgumentexception("session not found: $key")

        val messages: List<sessionMessage> = session.messages.map { msg: LegacyMessage ->
            sessionMessage(
                role = msg.role,
                content = msg.content?.toString() ?: "",
                timestamp = System.currentTimeMillis()
            )
        }

        return sessionPreviewResult(key = key, messages = messages)
    }

    /**
     * sessions.reset() - Reset a session
     */
    @Suppress("UNCHECKED_CAST")
    fun sessionsReset(params: Any?): Map<String, Boolean> {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object")
        val key = paramsMap["key"] as? String
            ?: throw IllegalArgumentexception("key required")

        sessionmanager.clear(key)
        return mapOf("success" to true)
    }

    /**
     * sessions.delete() - Delete a session
     */
    @Suppress("UNCHECKED_CAST")
    fun sessionsDelete(params: Any?): Map<String, Boolean> {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object")
        val key = paramsMap["key"] as? String
            ?: throw IllegalArgumentexception("key required")

        sessionmanager.clear(key)
        return mapOf("success" to true)
    }

    /**
     * sessions.patch() - Patch a session
     *
     * Supported operations:
     * - metadata: Update session metadata
     * - messages: Manipulate message list (a, remove, update)
     */
    @Suppress("UNCHECKED_CAST")
    fun sessionsPatch(params: Any?): Map<String, Boolean> {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object")
        val key = paramsMap["key"] as? String
            ?: throw IllegalArgumentexception("key required")

        val session = sessionmanager.get(key)
            ?: throw IllegalArgumentexception("session not found: $key")

        // Update metadata
        val metadata = paramsMap["metadata"] as? Map<String, Any?>
        if (metadata != null) {
            session.metadata.putAll(metadata)
        }

        // Manipulate messages
        val messagesOp = paramsMap["messages"] as? Map<String, Any?>
        if (messagesOp != null) {
            val operation = messagesOp["op"] as? String

            when (operation) {
                "a" -> {
                    // A message
                    val role = messagesOp["role"] as? String ?: "user"
                    val content = messagesOp["content"] as? String ?: ""
                    session.aMessage(LegacyMessage(role = role, content = content))
                }
                "remove" -> {
                    // Remove message at specified index
                    val index = (messagesOp["index"] as? Number)?.toInt()
                    if (index != null && index >= 0 && index < session.messages.size) {
                        session.messages.removeAt(index)
                    }
                }
                "clear" -> {
                    // Clear all messages
                    session.clearMessages()
                }
                "truncate" -> {
                    // Keep last N messages
                    val count = (messagesOp["count"] as? Number)?.toInt() ?: 10
                    if (session.messages.size > count) {
                        val keep = session.messages.takeLast(count)
                        session.messages.clear()
                        session.messages.aAll(keep)
                    }
                }
            }
        }

        // Save session
        sessionmanager.save(session)

        return mapOf("success" to true)
    }
}
