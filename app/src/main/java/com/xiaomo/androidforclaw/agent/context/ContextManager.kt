package com.xiaomo.androidforclaw.agent.context

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/agent-command.ts (context overflow recovery)
 *
 * androidforClaw adaptation: manage context growth and recovery.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.llm.Message

/**
 * context manager - Handle context overflow
 * Aligned with OpenClaw's multi-layer recovery strategy
 *
 * Strategy order (refer to OpenClaw run.ts lines 687-1055):
 * 1. Detect context overflow error
 * 2. Try Compaction (max 3 times)
 * 3. Try truncating oversized tool results
 * 4. Give up and return error
 */
class contextmanager(
    private val llmprovider: UnifiedLLMprovider
) {
    companion object {
        private const val TAG = "contextmanager"
        private const val MAX_COMPACTION_ATTEMPTS = 3
        private const val DEFAULT_CONTEXT_WINDOW = 200_000  // Aligned with contextWindowGuard default (OpenClaw = 200K)
    }

    private val compactor = MessageCompactor(llmprovider)
    private var compactionAttempts = 0
    private var toolresultTruncationAttempted = false

    /**
     * Reset counters (called at the start of a new run)
     */
    fun reset() {
        compactionAttempts = 0
        toolresultTruncationAttempted = false
        Log.d(TAG, "context manager reset")
    }

    /**
     * Handle context overflow error
     * @return Returns fixed message list if recoverable, otherwise returns null
     */
    suspend fun handlecontextoverflow(
        error: Throwable,
        messages: List<Message>,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW
    ): contextRecoveryresult {
        val errorMessage = contextErrors.extractErrorMessage(error)

        Log.e(TAG, "detected context exceeded limit: $errorMessage")
        Log.d(TAG, "FrontMessage count: ${messages.size}")
        Log.d(TAG, "Already tried compaction: $compactionAttempts times")
        Log.d(TAG, "Already tried truncation: $toolresultTruncationAttempted")

        // 1. Confirm it's a context overflow error
        if (!contextErrors.islikelycontextoverflowError(errorMessage)) {
            Log.w(TAG, "not a context overflow error, cannot handle")
            return contextRecoveryresult.cannotRecover(errorMessage)
        }

        // 2. Try Compaction
        if (compactionAttempts < MAX_COMPACTION_ATTEMPTS) {
            Log.d(TAG, "Trying Compaction (attempt ${compactionAttempts + 1})...")

            // Convert Message to LegacyMessage
            val legacyMessages = convertToLegacyMessages(messages)

            val compactionresult = compactor.compactMessages(
                messages = legacyMessages,
                keepLastN = 3
            )

            if (compactionresult.isSuccess) {
                compactionAttempts++
                val compacted = compactionresult.getorNull()!!

                Log.d(TAG, "Compaction Success: ${legacyMessages.size} -> ${compacted.size} messages")

                // Convertreturn Message
                val newMessages = convertfromLegacyMessages(compacted)

                return contextRecoveryresult.Recovered(
                    messages = newMessages,
                    strategy = "compaction",
                    attempt = compactionAttempts
                )
            } else {
                Log.e(TAG, "Compaction Failed: ${compactionresult.exceptionorNull()?.message}")

                // Compaction failed, check if it's due to compaction itself causing overflow
                if (contextErrors.isCompactionFailureError(errorMessage)) {
                    Log.e(TAG, "Compaction itself failed, cannot recover")
                    return contextRecoveryresult.cannotRecover(
                        "Compaction failed: context overflow during summarization. " +
                        "Try /reset or use a larger-context model."
                    )
                }
            }
        }

        // 3. Try truncating oversized tool results
        if (!toolresultTruncationAttempted) {
            Log.d(TAG, "Trying to truncate oversized tool results...")

            val legacyMessages = convertToLegacyMessages(messages)

            if (toolresultTruncator.hasoversizedtoolresults(legacyMessages)) {
                toolresultTruncationAttempted = true

                val truncated = toolresultTruncator.truncatetoolresults(legacyMessages)
                val newMessages = convertfromLegacyMessages(truncated)

                Log.d(TAG, "Tool result truncation complete")

                return contextRecoveryresult.Recovered(
                    messages = newMessages,
                    strategy = "truncation",
                    attempt = 1
                )
            } else {
                Log.d(TAG, "No oversized tool results detected")
            }
        }

        // 4. Give up
        Log.e(TAG, "All recovery strategies failed")
        return contextRecoveryresult.cannotRecover(
            "context overflow: prompt too large for the model. " +
            "Compaction attempts: $compactionAttempts. " +
            "Try clearing session history or use a larger-context model."
        )
    }

    /**
     * Check if preemptive compaction should be performed
     */
    fun shouldPreemptivelyCompact(
        messages: List<Message>,
        contextWindow: Int = DEFAULT_CONTEXT_WINDOW
    ): Boolean {
        val legacyMessages = convertToLegacyMessages(messages)
        return compactor.shouldCompact(legacyMessages, contextWindow)
    }

    /**
     * Preemptive compaction (before sending request)
     */
    suspend fun preemptivelyCompact(
        messages: List<Message>
    ): List<Message> {
        Log.d(TAG, "Preventivecompress...")

        val legacyMessages = convertToLegacyMessages(messages)
        val result = compactor.compactMessages(legacyMessages, keepLastN = 5)

        return if (result.isSuccess) {
            Log.d(TAG, "Preemptive compress success")
            convertfromLegacyMessages(result.getorNull()!!)
        } else {
            Log.w(TAG, "Preemptive compress failed, using original messages")
            messages
        }
    }

    /**
     * Convert new format to legacy format (for compactor)
     */
    private fun convertToLegacyMessages(messages: List<Message>): List<LegacyMessage> {
        return messages.map { msg ->
            when (msg.role) {
                "system", "user" -> LegacyMessage(msg.role, msg.content)
                "assistant" -> {
                    if (msg.toolCalls != null) {
                        LegacyMessage(
                            role = "assistant",
                            content = msg.content,
                            toolCalls = msg.toolCalls.map { tc ->
                                com.xiaomo.androidforclaw.providers.LegacytoolCall(
                                    id = tc.id,
                                    type = "function",
                                    function = com.xiaomo.androidforclaw.providers.LegacyFunction(
                                        name = tc.name,
                                        arguments = tc.arguments
                                    )
                                )
                            }
                        )
                    } else {
                        LegacyMessage("assistant", msg.content)
                    }
                }
                "tool" -> LegacyMessage(
                    role = "tool",
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    name = msg.name
                )
                else -> LegacyMessage(msg.role, msg.content)
            }
        }
    }

    /**
     * Convert legacy format to new format
     */
    private fun convertfromLegacyMessages(legacyMessages: List<LegacyMessage>): List<Message> {
        return legacyMessages.map { msg ->
            when (msg.role) {
                "system" -> Message(
                    role = "system",
                    content = msg.content?.toString() ?: ""
                )
                "user" -> Message(
                    role = "user",
                    content = msg.content?.toString() ?: ""
                )
                "assistant" -> {
                    if (msg.toolCalls != null) {
                        Message(
                            role = "assistant",
                            content = msg.content?.toString() ?: "",
                            toolCalls = msg.toolCalls.map { tc ->
                                com.xiaomo.androidforclaw.providers.llm.toolCall(
                                    id = tc.id,
                                    name = tc.function.name,
                                    arguments = tc.function.arguments
                                )
                            }
                        )
                    } else {
                        Message(
                            role = "assistant",
                            content = msg.content?.toString() ?: ""
                        )
                    }
                }
                "tool" -> Message(
                    role = "tool",
                    content = msg.content?.toString() ?: "",
                    toolCallId = msg.toolCallId,
                    name = msg.name
                )
                else -> Message(
                    role = msg.role,
                    content = msg.content?.toString() ?: ""
                )
            }
        }
    }
}

/**
 * context Recovery result
 */
sealed class contextRecoveryresult {
    /**
     * Successfully recovered
     */
    data class Recovered(
        val messages: List<Message>,
        val strategy: String,  // "compaction" or "truncation"
        val attempt: Int
    ) : contextRecoveryresult()

    /**
     * cannot recover
     */
    data class cannotRecover(
        val reason: String
    ) : contextRecoveryresult()
}
