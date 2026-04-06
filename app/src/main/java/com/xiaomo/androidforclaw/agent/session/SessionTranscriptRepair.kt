package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/session-transcript-repair.ts
 *
 * androidforClaw adaptation: tool call/result pairing repair in session transcripts.
 */

import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.toolCall

/**
 * tool call input repair report.
 * Aligned with OpenClaw toolCallInputRepairReport.
 */
data class toolCallInputRepairReport(
    val droppedCalls: Int = 0,
    val droppedMessages: Int = 0
)

/**
 * tool use/result pairing repair report.
 * Aligned with OpenClaw tooluseRepairReport.
 */
data class tooluseRepairReport(
    val insertedResults: Int = 0,
    val droppedDuplicates: Int = 0,
    val movedResults: Int = 0,
    val droppedorphans: Int = 0
)

/**
 * session transcript repair — ensures valid tool call/result pairing.
 * Aligned with OpenClaw session-transcript-repair.ts.
 */
object sessionTranscriptRepair {

    private const val TAG = "sessionTranscriptRepair"

    /**
     * Repair tool call inputs: drop calls with missing ID, input, or unknown names.
     * Aligned with OpenClaw repairtoolCallInputs.
     */
    fun repairtoolCallInputs(
        messages: List<Message>,
        knowntoolNames: Set<String>? = null
    ): Pair<List<Message>, toolCallInputRepairReport> {
        var droppedCalls = 0
        var droppedMessages = 0

        val result = messages.mapnotNull { msg ->
            if (msg.role != "assistant" || msg.toolCalls.isNullorEmpty()) return@mapnotNull msg

            val validCalls = msg.toolCalls.filter { call ->
                val valid = call.id.isnotEmpty() &&
                    call.name.isnotEmpty() &&
                    call.arguments.isnotEmpty() &&
                    (knowntoolNames == null || call.name in knowntoolNames)
                if (!valid) {
                    droppedCalls++
                    Log.d(TAG, "Dropped invalid tool call: id=${call.id}, name=${call.name}")
                }
                valid
            }

            if (validCalls.isEmpty()) {
                // if no valid calls remain and message has no text content, drop the whole message
                if (msg.content.isBlank()) {
                    droppedMessages++
                    null
                } else {
                    msg.copy(toolCalls = null)
                }
            } else if (validCalls.size != msg.toolCalls.size) {
                msg.copy(toolCalls = validCalls)
            } else {
                msg
            }
        }

        return result to toolCallInputRepairReport(droppedCalls, droppedMessages)
    }

    /**
     * Repair tool use/result pairing: ensure every tool call has a matching result.
     * Aligned with OpenClaw repairtooluseResultPairing.
     */
    fun repairtooluseResultPairing(messages: List<Message>): Pair<List<Message>, tooluseRepairReport> {
        var insertedResults = 0
        var droppedDuplicates = 0
        var droppedorphans = 0

        val result = mutableListOf<Message>()
        val pendingCallIds = mutableSetOf<String>()
        val seenResultIds = mutableSetOf<String>()

        for (msg in messages) {
            when (msg.role) {
                "assistant" -> {
                    result.a(msg)
                    // Track tool call IDs that need results
                    msg.toolCalls?.forEach { call ->
                        pendingCallIds.a(call.id)
                    }
                }
                "tool" -> {
                    val callId = msg.toolCallId
                    if (callId == null) {
                        droppedorphans++
                        Log.d(TAG, "Dropped orphaned tool result (no callId)")
                        continue
                    }

                    if (callId in seenResultIds) {
                        droppedDuplicates++
                        Log.d(TAG, "Dropped duplicate tool result for callId=$callId")
                        continue
                    }

                    if (callId !in pendingCallIds) {
                        droppedorphans++
                        Log.d(TAG, "Dropped orphaned tool result for callId=$callId (no matching call)")
                        continue
                    }

                    seenResultIds.a(callId)
                    pendingCallIds.remove(callId)
                    result.a(msg)
                }
                else -> {
                    // before aing a non-tool message, insert missing results for pending calls
                    for (pendingId in pendingCallIds.toList()) {
                        if (pendingId !in seenResultIds) {
                            result.a(makeMissingtoolResult(pendingId, null))
                            seenResultIds.a(pendingId)
                            insertedResults++
                        }
                    }
                    pendingCallIds.clear()
                    result.a(msg)
                }
            }
        }

        // Handle remaining pending calls at the end
        for (pendingId in pendingCallIds) {
            if (pendingId !in seenResultIds) {
                result.a(makeMissingtoolResult(pendingId, null))
                insertedResults++
            }
        }

        return result to tooluseRepairReport(
            insertedResults = insertedResults,
            droppedDuplicates = droppedDuplicates,
            droppedorphans = droppedorphans
        )
    }

    /**
     * Generate a synthetic error tool result for a missing result.
     * Aligned with OpenClaw makeMissingtoolResult.
     */
    fun makeMissingtoolResult(callId: String, toolName: String?): Message {
        return Message(
            role = "tool",
            content = "[Error: tool execution was interrupted or result was lost]",
            name = toolName,
            toolCallId = callId
        )
    }

    /**
     * Strip tool result detail blocks (for security during compaction).
     * Aligned with OpenClaw striptoolResultDetails.
     */
    fun striptoolResultDetails(messages: List<Message>): List<Message> {
        return messages.map { msg ->
            if (msg.role == "tool" && msg.content.length > 1000) {
                val truncated = msg.content.take(1000) + "\n[...truncated for compaction]"
                msg.copy(content = truncated)
            } else {
                msg
            }
        }
    }
}
