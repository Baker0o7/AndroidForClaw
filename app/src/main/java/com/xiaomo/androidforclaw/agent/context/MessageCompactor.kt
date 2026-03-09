package com.xiaomo.androidforclaw.agent.context

import android.util.Log
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.UnifiedLLMProvider
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.systemMessage
import com.xiaomo.androidforclaw.providers.llm.userMessage

/**
 * Message Compactor - Summarize old messages to reduce context
 * Aligned with OpenClaw's compaction.ts implementation
 */
class MessageCompactor(
    private val llmProvider: UnifiedLLMProvider
) {
    companion object {
        private const val TAG = "MessageCompactor"

        // Configuration parameters (aligned with OpenClaw defaults)
        private const val KEEP_RECENT_TOKENS = 20000  // Keep recent messages
        private const val MIN_MESSAGES_TO_COMPACT = 5  // Minimum messages needed to compact
        private const val CHARS_PER_TOKEN = 4  // Rough estimate: 4 chars ≈ 1 token
    }

    /**
     * Compact message history
     * @param messages Original message list
     * @param keepLastN Keep last N messages uncompacted
     * @return Compacted message list
     */
    suspend fun compactMessages(
        messages: List<LegacyMessage>,
        keepLastN: Int = 3
    ): Result<List<LegacyMessage>> {
        return try {
            Log.d(TAG, "开始压缩消息: 总数=${messages.size}, 保留最后${keepLastN}条")

            // Check if compaction is needed
            if (messages.size < MIN_MESSAGES_TO_COMPACT) {
                Log.d(TAG, "Too few messages, no need to compact")
                return Result.success(messages)
            }

            // Separate system messages, messages to compact, and recent messages
            val systemMessages = messages.filter { it.role == "system" }
            val nonSystemMessages = messages.filter { it.role != "system" }

            if (nonSystemMessages.size <= keepLastN) {
                Log.d(TAG, "非系统消息太少，无需压缩")
                return Result.success(messages)
            }

            val toCompact = nonSystemMessages.dropLast(keepLastN)
            val recentMessages = nonSystemMessages.takeLast(keepLastN)

            Log.d(TAG, "待压缩消息: ${toCompact.size}条")
            Log.d(TAG, "保留消息: ${recentMessages.size}条")

            // Summarize old messages
            val summary = summarizeMessages(toCompact)

            // Build compacted message list
            val compacted = buildList {
                // 1. Keep system prompt
                addAll(systemMessages)

                // 2. Add summary message
                add(LegacyMessage(
                    role = "user",
                    content = "[Earlier conversation summary]\n$summary"
                ))
                add(LegacyMessage(
                    role = "assistant",
                    content = "I understand. I'll continue from where we left off."
                ))

                // 3. Keep recent messages
                addAll(recentMessages)
            }

            Log.d(TAG, "压缩完成: ${messages.size} -> ${compacted.size}条消息")

            Result.success(compacted)

        } catch (e: Exception) {
            Log.e(TAG, "压缩失败", e)
            Result.failure(e)
        }
    }

    /**
     * Summarize message list
     */
    private suspend fun summarizeMessages(messages: List<LegacyMessage>): String {
        if (messages.isEmpty()) return ""

        // Build text to summarize
        val textToSummarize = buildString {
            messages.forEach { msg ->
                appendLine("Role: ${msg.role}")
                appendLine("Content: ${msg.content}")
                if (msg.toolCalls != null) {
                    appendLine("Tool calls: ${msg.toolCalls.size}")
                }
                appendLine()
            }
        }

        Log.d(TAG, "总结文本长度: ${textToSummarize.length} 字符")

        // Use LLM to summarize
        val systemPrompt = """
            You are summarizing a conversation history to save context space.

            Requirements:
            1. Preserve all important facts, decisions, and outcomes
            2. Keep identifiers (UUIDs, IPs, file paths, package names, etc.)
            3. Note any errors or important observations
            4. Keep the summary concise but complete
            5. Use bullet points for clarity

            Format:
            - Task: [what was being done]
            - Key actions: [list of important actions]
            - Outcomes: [results or observations]
            - Important details: [any critical information to remember]
        """.trimIndent()

        val userPrompt = """
            Summarize this conversation history:

            $textToSummarize
        """.trimIndent()

        return try {
            val messagesToSend = listOf(
                systemMessage(systemPrompt),
                userMessage(userPrompt)
            )

            val response = llmProvider.chatWithTools(
                messages = messagesToSend,
                tools = null,  // No tools needed
                reasoningEnabled = true  // Use extended thinking
            )

            response.content ?: "Summary generation failed"

        } catch (e: Exception) {
            Log.e(TAG, "LLM summarization failed", e)
            // Fallback: generate simple summary
            generateSimpleSummary(messages)
        }
    }

    /**
     * Fallback strategy: generate simple summary (without calling LLM)
     */
    private fun generateSimpleSummary(messages: List<LegacyMessage>): String {
        return buildString {
            appendLine("Earlier conversation (${messages.size} messages):")

            // Count message types
            val userMsgCount = messages.count { it.role == "user" }
            val assistantMsgCount = messages.count { it.role == "assistant" }
            val toolCallCount = messages.sumOf { it.toolCalls?.size ?: 0 }

            appendLine("- User messages: $userMsgCount")
            appendLine("- Assistant messages: $assistantMsgCount")
            appendLine("- Tool calls: $toolCallCount")

            // Extract tool names
            val toolNames = messages
                .flatMap { it.toolCalls ?: emptyList() }
                .map { it.function.name }
                .distinct()

            if (toolNames.isNotEmpty()) {
                appendLine("- Tools used: ${toolNames.joinToString(", ")}")
            }
        }
    }

    /**
     * Estimate token count of message list
     */
    fun estimateTokens(messages: List<LegacyMessage>): Int {
        val totalChars = messages.sumOf { msg ->
            (msg.content?.toString()?.length ?: 0) +
            (msg.toolCalls?.sumOf { it.function.arguments.length } ?: 0)
        }
        return totalChars / CHARS_PER_TOKEN
    }

    /**
     * Check if compaction is needed
     */
    fun shouldCompact(
        messages: List<LegacyMessage>,
        contextWindow: Int = 200000
    ): Boolean {
        val estimatedTokens = estimateTokens(messages)
        val threshold = contextWindow * 0.7  // 70% 阈值

        Log.d(TAG, "Token 估算: $estimatedTokens / $contextWindow (阈值: $threshold)")

        return estimatedTokens > threshold
    }
}
