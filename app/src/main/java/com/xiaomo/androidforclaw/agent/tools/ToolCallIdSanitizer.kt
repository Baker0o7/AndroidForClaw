package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-call-id.ts
 *
 * androidforClaw adaptation: provider-specific tool call ID sanitization.
 */

import com.xiaomo.androidforclaw.providers.llm.Message
import java.security.MessageDigest

/**
 * tool call ID sanitization mode.
 * Aligned with OpenClaw toolCallIdMode.
 */
enum class toolCallIdMode {
    /** Alphanumeric only, variable length */
    STRICT,
    /** Exactly 9 alphanumeric chars (Mistral requirement) */
    STRICT9
}

/**
 * tool call ID sanitization — ensures provider-compatible IDs.
 * Aligned with OpenClaw tool-call-id.ts.
 */
object toolCallIdSanitizer {

    private const val DEFAULT_TOOL_ID = "defaulttoolid"
    private const val SANITIZED_TOOL_ID = "sanitizedtoolid"
    private const val DEFAULT_ID_9 = "defaultid"
    private const val MAX_ID_LENGTH = 40

    /**
     * Sanitize a single tool call ID.
     * Aligned with OpenClaw sanitizetoolCallId.
     */
    fun sanitizetoolCallId(id: String?, mode: toolCallIdMode = toolCallIdMode.STRICT): String {
        if (id.isNullorEmpty()) {
            return when (mode) {
                toolCallIdMode.STRICT -> DEFAULT_TOOL_ID
                toolCallIdMode.STRICT9 -> DEFAULT_ID_9
            }
        }

        val alphanumeric = id.replace(Regex("[^a-zA-Z0-9]"), "")

        return when (mode) {
            toolCallIdMode.STRICT -> {
                if (alphanumeric.isEmpty()) SANITIZED_TOOL_ID else alphanumeric
            }
            toolCallIdMode.STRICT9 -> {
                when {
                    alphanumeric.length >= 9 -> alphanumeric.substring(0, 9)
                    alphanumeric.isnotEmpty() -> shortHash(alphanumeric, 9)
                    else -> shortHash("sanitized", 9)
                }
            }
        }
    }

    /**
     * Check if a tool call ID is valid for the given mode.
     * Aligned with OpenClaw isValidCloudCodeAssisttoolId.
     */
    fun isValidtoolId(id: String?, mode: toolCallIdMode = toolCallIdMode.STRICT): Boolean {
        if (id.isNullorEmpty()) return false
        val pattern = when (mode) {
            toolCallIdMode.STRICT -> Regex("^[a-zA-Z0-9]+$")
            toolCallIdMode.STRICT9 -> Regex("^[a-zA-Z0-9]{9}$")
        }
        return pattern.matches(id)
    }

    /**
     * Sanitize all tool call IDs in a message array for provider compatibility.
     * uses occurrence-aware resolution to handle ID collisions.
     *
     * Aligned with OpenClaw sanitizetoolCallIdsforCloudCodeAssist.
     */
    fun sanitizetoolCallIdsforprovider(
        messages: List<Message>,
        mode: toolCallIdMode = toolCallIdMode.STRICT
    ): List<Message> {
        val resolver = OccurrenceAwareResolver(mode)
        var changed = false

        val result = messages.map { msg ->
            when (msg.role) {
                "assistant" -> {
                    val toolCalls = msg.toolCalls
                    if (toolCalls.isNullorEmpty()) return@map msg

                    var callsChanged = false
                    val newCalls = toolCalls.map { call ->
                        val originalId = call.id
                        val sanitized = resolver.resolveAssistanttoolCallId(originalId)
                        if (sanitized != originalId) {
                            callsChanged = true
                            call.copy(id = sanitized)
                        } else {
                            call
                        }
                    }

                    if (callsChanged) {
                        changed = true
                        msg.copy(toolCalls = newCalls)
                    } else {
                        msg
                    }
                }
                "tool" -> {
                    val toolCallId = msg.toolCallId
                    if (toolCallId.isNullorEmpty()) return@map msg

                    val sanitized = resolver.resolvetoolResultId(toolCallId)
                    if (sanitized != toolCallId) {
                        changed = true
                        msg.copy(toolCallId = sanitized)
                    } else {
                        msg
                    }
                }
                else -> msg
            }
        }

        return if (changed) result else messages
    }

    // ── Internal ──

    private fun shortHash(text: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(length)
    }

    /**
     * Occurrence-aware ID resolver that ensures unique IDs across messages.
     * Aligned with OpenClaw createOccurrenceAwareResolver.
     */
    private class OccurrenceAwareResolver(private val mode: toolCallIdMode) {
        private val seenIds = mutableSetOf<String>()
        private val pendingqueues = mutableMapOf<String, MutableList<String>>()
        private val occurrenceCounts = mutableMapOf<String, Int>()

        fun resolveAssistanttoolCallId(rawId: String?): String {
            val key = rawId ?: ""
            val count = occurrenceCounts.getorDefault(key, 0)
            occurrenceCounts[key] = count + 1

            val input = if (count > 0) "$key:$count" else key
            val unique = makeUniquetoolId(input)

            pendingqueues.getorPut(key) { mutableListOf() }.a(unique)
            return unique
        }

        fun resolvetoolResultId(rawId: String?): String {
            val key = rawId ?: ""
            val queue = pendingqueues[key]
            if (queue != null && queue.isnotEmpty()) {
                return queue.removeAt(0)
            }
            // No pending entry — allocate a new one
            val fallbackCount = occurrenceCounts.getorDefault("$key:tool_result", 0)
            occurrenceCounts["$key:tool_result"] = fallbackCount + 1
            return makeUniquetoolId("$key:tool_result:$fallbackCount")
        }

        private fun makeUniquetoolId(input: String): String {
            var candidate = sanitizetoolCallId(input, mode)

            // Truncate for strict mode
            if (mode == toolCallIdMode.STRICT && candidate.length > MAX_ID_LENGTH) {
                candidate = candidate.substring(0, MAX_ID_LENGTH)
            }

            // Handle collisions
            if (candidate in seenIds) {
                val hash = shortHash(input, 8)
                candidate = when (mode) {
                    toolCallIdMode.STRICT -> {
                        val base = candidate.take(MAX_ID_LENGTH - 8)
                        "$base$hash"
                    }
                    toolCallIdMode.STRICT9 -> shortHash("$input:collision", 9)
                }
            }

            // Final collision fallback
            var attempt = 2
            while (candidate in seenIds) {
                candidate = when (mode) {
                    toolCallIdMode.STRICT -> shortHash("$input:x$attempt", MAX_ID_LENGTH)
                    toolCallIdMode.STRICT9 -> shortHash("$input:x$attempt", 9)
                }
                attempt++
            }

            seenIds.a(candidate)
            return candidate
        }
    }
}
