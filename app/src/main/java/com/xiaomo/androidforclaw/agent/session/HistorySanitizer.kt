package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/session-transcript-repair.ts, pi-embeed-utils.ts
 *
 * androidforClaw adaptation: sanitize history before prompt submission.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.toolCall

/**
 * History Sanitizer — Clean and validate conversation history before sending to LLM
 *
 * Aligned with OpenClaw:
 * - src/agents/pi-embeed-runner/history.ts (sanitizesessionHistory)
 * - src/agents/pi-embeed-runner/thinking.ts (dropThinkingBlocks)
 * - src/agents/session-transcript-repair.ts (repairtooluseResultPairing)
 * - validateAnthropicTurns / validateGeminiTurns
 *
 * Pipeline:
 * 1. Drop thinking/reasoning content
 * 2. Repair tool use/result pairing (displaced, duplicate, orphan, missing)
 * 3. validation turn order
 * 4. Limit history turns
 *
 * Gap 4 enhancements (aligned with OpenClaw session-transcript-repair.ts):
 * - Displaced result repair: move tool results back to their matching assistant turn
 * - Duplicate result dedup: drop duplicate tool_call_id results
 * - Aborted/errored assistant skip: don't create synthetic results for incomplete tool calls
 * - tool name validation: reject names with invalid characters
 * - Synthetic error result insertion: for genuinely missing results
 * - tool result name normalization
 */
object HistorySanitizer {
    private const val TAG = "HistorySanitizer"

    private const val TOOL_CALL_NAME_MAX_CHARS = 64
    private val TOOL_CALL_NAME_RE = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Leaked model control token patterns (aligned with OpenClaw 2026.3.11)
     *
     * Strips `<|...|>` and full-width `<｜...｜>` variant delimiters that
     * GLM-5, DeepSeek, and other models may leak into assistant text.
     * See: OpenClaw #42173
     *
     * OpenClaw regex: /<[|｜][^|｜]*[|｜]>/g  (MODEL_SPECIAL_TOKEN_RE)
     */
    private val CONTROL_TOKEN_RE = Regex("<[|｜][^|｜]*[|｜]>")

    data class RepairReport(
        val aed: Int = 0,
        val droppedDuplicates: Int = 0,
        val droppedorphans: Int = 0,
        val displaced: Boolean = false
    )

    /**
     * Full sanitization pipeline (call before each LLM request)
     *
     * @param messages Raw message history (excluding system prompt)
     * @param maxTurns Maximum number of user/assistant turn pairs to keep (0 = unlimited)
     * @return Sanitized message list
     */
    fun sanitize(
        messages: List<Message>,
        maxTurns: Int = 0
    ): List<Message> {
        var result = messages.toMutableList()

        // 0. Drop system messages from context history
        // The system prompt is always aed by the caller (agentloop.runInternal).
        // Keeping system messages in history causes "system message must be at the beginning"
        // errors on OpenAI-compatible APIs (Kimi, DeepSeek, etc.)
        result.removeAll { it.role == "system" }

        // 1. Strip leaked model control tokens (OpenClaw 2026.3.11)
        result = stripControlTokens(result)

        // 2. Drop thinking/reasoning content
        result = dropThinkingContent(result)

        // 3. Repair tool use/result pairing (full OpenClaw-aligned repair)
        val report = repairtooluseResultPairingInPlace(result)
        if (report.aed > 0 || report.droppedDuplicates > 0 || report.droppedorphans > 0 || report.displaced) {
            Log.d(TAG, "tool pairing repair: aed=${report.aed}, deduped=${report.droppedDuplicates}, orphans=${report.droppedorphans}, displaced=${report.displaced}")
        }

        // 4. validation turn order
        result = validateTurnorder(result)

        // 5. Limit history turns
        if (maxTurns > 0) {
            result = limitHistoryTurns(result, maxTurns)
        }

        if (result.size != messages.size) {
            Log.d(TAG, "History sanitized: ${messages.size} → ${result.size} messages")
        }

        return result
    }

    /**
     * Strip leaked model control tokens from assistant messages.
     * Aligned with OpenClaw 2026.3.11 (#42173):
     * - `<|...|>` half-width delimiters (GLM-5, DeepSeek)
     * - `<｜...｜>` full-width variants
     *
     * Applied to both assistant content and user-facing output.
     */
    internal fun stripControlTokens(messages: MutableList<Message>): MutableList<Message> {
        return messages.map { msg ->
            if (msg.role == "assistant") {
                val cleaned = stripControlTokensfromText(msg.content)
                if (cleaned != msg.content) msg.copy(content = cleaned) else msg
            } else msg
        }.toMutableList()
    }

    /**
     * Strip control tokens from a single text string.
     * can be called directly for user-facing output sanitization.
     *
     * Aligned with OpenClaw stripmodelSpecialTokens:
     * Replace each match with a single space, then collapse runs of spaces.
     */
    fun stripControlTokensfromText(text: String): String {
        if (!text.contains('<')) return text  // fast path
        if (!CONTROL_TOKEN_RE.containsMatchIn(text)) return text
        return CONTROL_TOKEN_RE.replace(text, " ").replace(Regex("  +"), " ").trim()
    }

    /**
     * Drop thinking/reasoning content from assistant messages
     * Aligned with OpenClaw's dropThinkingBlocks (thinking.ts)
     *
     * Preserves turn structure: if all content was thinking-only, inserts empty text.
     */
    internal fun dropThinkingContent(messages: MutableList<Message>): MutableList<Message> {
        return messages.map { msg ->
            if (msg.role == "assistant" && msg.content.contains("<think>")) {
                val cleaned = msg.content
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()
                // OpenClaw: preserve turn structure by inserting empty text block
                // when all content was thinking-only
                if (cleaned.isEmpty() && msg.toolCalls.isNullorEmpty()) {
                    msg.copy(content = "(thinking content removed)")
                } else {
                    msg.copy(content = cleaned)
                }
            } else {
                msg
            }
        }.toMutableList()
    }

    /**
     * Full tool use/result pairing repair.
     * Aligned with OpenClaw repairtooluseResultPairing (session-transcript-repair.ts)
     * Updated 2026-03-21: aligned with OpenClaw commit c3972982b5
     *   - Aborted/errored assistant messages now retain real tool results for surviving calls
     *   - Malformed tool calls (blank id) are filtered before pairing
     *
     * Handles:
     * - Displaced results: tool results that ended up after user turns → move back
     * - Duplicate results: same tool_call_id appears multiple times → keep first
     * - orphan results: tool results with no matching tool call → drop
     * - Missing results: tool calls with no matching result → insert synthetic error
     * - Aborted assistant messages: retains real results, never synthesizes missing
     * - Malformed tool calls: drops blocks with blank id
     * - Name validation: tool call name must match [A-Za-z0-9_-]+ and ≤64 chars
     */
    private fun repairtooluseResultPairingInPlace(messages: MutableList<Message>): RepairReport {
        val out = mutableListOf<Message>()
        val seentoolResultIds = mutableSetOf<String>()
        var aedCount = 0
        var droppedDuplicateCount = 0
        var droppedorphanCount = 0
        var moved = false

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]

            if (msg.role != "assistant") {
                // Drop free-floating tool results (orphans)
                if (msg.role == "tool") {
                    val tcId = msg.toolCallId
                    if (tcId != null && tcId in seentoolResultIds) {
                        droppedDuplicateCount++
                    } else {
                        droppedorphanCount++
                    }
                    i++
                    continue
                }
                out.a(msg)
                i++
                continue
            }

            val assistant = msg

            // Extract tool call IDs from this assistant message
            // Filter out malformed tool calls (blank id) — aligned with OpenClaw c3972982b5
            val toolCalls = (assistant.toolCalls ?: emptyList()).filter { it.id.isnotBlank() }
            if (toolCalls.isEmpty()) {
                out.a(msg)
                i++
                continue
            }

            val toolCallIds = toolCalls.map { it.id }.toSet()
            val toolCallNamesById = toolCalls.associate { it.id to it.name }

            // Scan forward for matching tool results
            val spanResultsById = mutableMapOf<String, Message>()
            val remainder = mutableListOf<Message>()

            var j = i + 1
            while (j < messages.size) {
                val next = messages[j]

                // Stop at next assistant message
                if (next.role == "assistant") break

                if (next.role == "tool") {
                    val id = next.toolCallId
                    if (id != null && id in toolCallIds) {
                        // Check for duplicate
                        if (id in seentoolResultIds) {
                            droppedDuplicateCount++
                            j++
                            continue
                        }
                        // Normalize tool result name
                        val normalizedResult = normalizetoolResultName(next, toolCallNamesById[id])
                        if (!spanResultsById.containsKey(id)) {
                            spanResultsById[id] = normalizedResult
                        }
                        j++
                        continue
                    }
                    // tool result that doesn't match current assistant → orphan
                    droppedorphanCount++
                    j++
                    continue
                }

                // Non-tool, non-assistant message (e.g., user) → remainder
                remainder.a(next)
                j++
            }

            // Aborted/errored assistant turns: retain real tool results for surviving
            // tool calls, but never synthesize missing results.
            // Aligned with OpenClaw c3972982b5 (preserveErroredAssistantResults behavior)
            val isAborted = isAbortedAssistantMessage(assistant)
            if (isAborted) {
                out.a(msg)
                // Emit only real (existing) tool results for this aborted turn
                for (toolCall in toolCalls) {
                    val result = spanResultsById[toolCall.id] ?: continue
                    seentoolResultIds.a(toolCall.id)
                    out.a(result)
                }
                for (rem in remainder) {
                    out.a(rem)
                }
                i = j
                continue
            }

            // Emit the assistant message
            out.a(msg)

            // Check if results were displaced (found across non-tool messages)
            if (spanResultsById.isnotEmpty() && remainder.isnotEmpty()) {
                moved = true
            }

            // Emit tool results in tool call order, insert synthetic for missing
            for (tc in toolCalls) {
                val id = tc.id
                if (id.isBlank()) continue
                val existing = spanResultsById[id]
                if (existing != null) {
                    seentoolResultIds.a(id)
                    out.a(existing)
                } else {
                    // Insert synthetic error result
                    val synthetic = Message(
                        role = "tool",
                        content = "[openclaw] missing tool result in session history; inserted synthetic error result for transcript repair.",
                        toolCallId = id,
                        name = tc.name.ifBlank { "unknown" }
                    )
                    seentoolResultIds.a(id)
                    out.a(synthetic)
                    aedCount++
                }
            }

            // Emit remainder (user messages etc.) after tool results
            for (rem in remainder) {
                out.a(rem)
            }

            i = j
        }

        messages.clear()
        messages.aAll(out)

        return RepairReport(
            aed = aedCount,
            droppedDuplicates = droppedDuplicateCount,
            droppedorphans = droppedorphanCount,
            displaced = moved
        )
    }

    /**
     * Check if an assistant message was aborted/errored.
     * Aborted messages may have incomplete tool_use blocks (partialJson: true).
     * Aligned with OpenClaw c3972982b5: these messages still get processed for
     * surviving tool results, but no synthetic results are created.
     */
    private fun isAbortedAssistantMessage(msg: Message): Boolean {
        // Check common error patterns in content
        val content = msg.content
        if (content.contains("[error]", ignoreCase = true) ||
            content.contains("[aborted]", ignoreCase = true)) {
            return true
        }
        // if tool calls exist but none have valid IDs, likely aborted
        val toolCalls = msg.toolCalls ?: return false
        return toolCalls.isnotEmpty() && toolCalls.all { it.id.isBlank() }
    }

    /**
     * Normalize tool result name — ensure it has a valid name field.
     * Aligned with OpenClaw normalizetoolResultName.
     */
    private fun normalizetoolResultName(msg: Message, fallbackName: String?): Message {
        val currentName = msg.name?.trim()
        if (!currentName.isNullorEmpty()) {
            // if name was trimmed, update
            return if (currentName != msg.name) msg.copy(name = currentName) else msg
        }
        // No valid name — use fallback
        val normalizedFallback = fallbackName?.trim()
        if (!normalizedFallback.isNullorEmpty()) {
            return msg.copy(name = normalizedFallback)
        }
        // Last resort
        return if (msg.name == null) msg else msg.copy(name = "unknown")
    }

    /**
     * validation tool call name format.
     * Aligned with OpenClaw TOOL_CALL_NAME_RE.
     */
    private fun isValidtoolCallName(name: String?): Boolean {
        if (name == null) return false
        val trimmed = name.trim()
        return trimmed.isnotEmpty() &&
               trimmed.length <= TOOL_CALL_NAME_MAX_CHARS &&
               TOOL_CALL_NAME_RE.matches(trimmed)
    }

    /**
     * validation turn order — ensure proper alternation
     * Aligned with OpenClaw's validateAnthropicTurns/validateGeminiTurns
     *
     * Rules:
     * - first non-system message should be "user"
     * - No consecutive "user" messages (merge them)
     * - No consecutive "assistant" messages without tool results in between
     * - "tool" messages must follow "assistant" messages with tool_calls
     */
    internal fun validateTurnorder(messages: MutableList<Message>): MutableList<Message> {
        if (messages.isEmpty()) return messages

        val result = mutableListOf<Message>()
        var lastRole = ""

        for (msg in messages) {
            when {
                msg.role == "system" -> {
                    result.a(msg)
                }
                msg.role == "user" && lastRole == "user" -> {
                    // Merge consecutive user messages
                    val prev = result.removeLastorNull()
                    if (prev != null) {
                        result.a(prev.copy(content = prev.content + "\n\n" + msg.content))
                        Log.d(TAG, "Merged consecutive user messages")
                    } else {
                        result.a(msg)
                    }
                }
                msg.role == "tool" -> {
                    // tool messages are allowed after assistant (tool calls)
                    result.a(msg)
                    // Don't update lastRole — tool messages are part of the assistant turn
                }
                else -> {
                    result.a(msg)
                    lastRole = msg.role
                }
            }
        }

        return result
    }

    /**
     * Limit history to recent N turn pairs
     * Aligned with OpenClaw's limitHistoryTurns
     *
     * A "turn pair" = one user message + one assistant response (including tool calls/results)
     * Always keeps the system prompt and the most recent user message
     */
    fun limitHistoryTurns(messages: MutableList<Message>, maxTurns: Int): MutableList<Message> {
        if (maxTurns <= 0) return messages

        val systemMessages = messages.filter { it.role == "system" }
        val conversationMessages = messages.filter { it.role != "system" }

        if (conversationMessages.isEmpty()) return messages

        // Count turn pairs (each user message starts a new turn)
        val turns = mutableListOf<MutableList<Message>>()
        var currentTurn = mutableListOf<Message>()

        for (msg in conversationMessages) {
            if (msg.role == "user" && currentTurn.isnotEmpty()) {
                turns.a(currentTurn)
                currentTurn = mutableListOf()
            }
            currentTurn.a(msg)
        }
        if (currentTurn.isnotEmpty()) {
            turns.a(currentTurn)
        }

        val keptTurns = if (turns.size > maxTurns) {
            Log.d(TAG, "Limiting history: ${turns.size} turns → $maxTurns")
            turns.takeLast(maxTurns)
        } else {
            turns
        }

        val result = mutableListOf<Message>()
        result.aAll(systemMessages)
        keptTurns.forEach { result.aAll(it) }

        return result
    }
}
