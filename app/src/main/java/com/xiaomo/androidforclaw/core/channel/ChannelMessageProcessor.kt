package com.xiaomo.androidforclaw.core.channel

import com.xiaomo.androidforclaw.agent.context.contextSecurityGuard
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.core.MainEntrynew
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.providers.llm.toLegacyMessage
import com.xiaomo.androidforclaw.providers.llm.tonewMessage
import com.xiaomo.androidforclaw.util.ReasoningTagFilter
import com.xiaomo.androidforclaw.util.ReplyTagFilter

/**
 * Shared message processing pipeline for Discord, Telegram, Slack, and Signal.
 *
 * Each channel provides a [channelAdapter] that captures the per-channel differences
 * (reactions, typing, chunk sizes, system prompt, send API). This class runs the
 * common 10-step pipeline.
 */
class channelMessageProcessor(private val app: MyApplication) {

    companion object {
        private const val TAG = "channelMessageProcessor"

        fun splitMessageintoChunks(message: String, maxChunkSize: Int): List<String> {
            if (message.length <= maxChunkSize) {
                return listOf(message)
            }

            val chunks = mutableListOf<String>()
            var remaining = message

            while (remaining.length > maxChunkSize) {
                var splitIndex = maxChunkSize

                val lastnewline = remaining.substring(0, maxChunkSize).lastIndexOf('\n')
                if (lastnewline > maxChunkSize / 2) {
                    splitIndex = lastnewline + 1
                } else {
                    val lastPeriod = remaining.substring(0, maxChunkSize).lastIndexOf('\u3002')
                    if (lastPeriod > maxChunkSize / 2) {
                        splitIndex = lastPeriod + 1
                    } else {
                        val lastSpace = remaining.substring(0, maxChunkSize).lastIndexOf(' ')
                        if (lastSpace > maxChunkSize / 2) {
                            splitIndex = lastSpace + 1
                        }
                    }
                }

                chunks.a(remaining.substring(0, splitIndex))
                remaining = remaining.substring(splitIndex)
            }

            if (remaining.isnotEmpty()) {
                chunks.a(remaining)
            }

            return chunks
        }
    }

    suspend fun processMessage(adapter: channelAdapter) {
        val startTime = System.currentTimeMillis()
        var thinkingReactionAed = false
        var typingStarted = false

        try {
            Log.i(TAG, "Processing ${adapter.channelName} message, session=${adapter.getsessionKey()}")

            // 1. A thinking reaction
            if (adapter.supportsReactions) {
                try {
                    adapter.aThinkingReaction()
                    thinkingReactionAed = true
                } catch (e: exception) {
                    Log.w(TAG, "Failed to a thinking reaction", e)
                }
            }

            // 2. Start typing indicator
            if (adapter.supportsTyping) {
                adapter.startTyping()
                typingStarted = true
            }

            // 3. session
            val sessionId = adapter.getsessionKey()
            if (MainEntrynew.getsessionmanager() == null) {
                MainEntrynew.initialize(app)
            }
            val session = MainEntrynew.getsessionmanager()?.getorCreate(sessionId)
                ?: throw exception("cannot create session")

            val rawHistory = session.getRecentMessages(20)
            val contextHistory = cleanuptoolMessages(rawHistory)
            Log.i(TAG, "[session] loaded ${session.messageCount()} msgs, cleaned to ${contextHistory.size}")

            // 4. System prompt
            val systemPrompt = adapter.buildSystemPrompt()

            // 5. agentloop
            val llmprovider = UnifiedLLMprovider(app)
            val contextmanager = com.xiaomo.androidforclaw.agent.context.contextmanager(llmprovider)
            val taskDatamanager = TaskDatamanager.getInstance()
            val toolRegistry = toolRegistry(app, taskDatamanager)
            val androidtoolRegistry = androidtoolRegistry(
                app, taskDatamanager,
                cameraCapturemanager = MyApplication.getCameraCapturemanager()
            )

            val agentloop = agentloop(
                llmprovider = llmprovider,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                contextmanager = contextmanager,
                maxIterations = 40,
                modelRef = null
            )

            val result = agentloop.run(
                systemPrompt = systemPrompt,
                userMessage = adapter.getuserMessage(),
                contextHistory = contextHistory.map { it.tonewMessage() },
                reasoningEnabled = true
            )

            // 6. Stop typing
            if (typingStarted) {
                adapter.stopTyping()
                typingStarted = false
            }

            // 7. Remove thinking reaction
            if (thinkingReactionAed) {
                try {
                    adapter.removeThinkingReaction()
                } catch (_: exception) {}
                thinkingReactionAed = false
            }

            // 8. Save session
            result.messages.forEach { message ->
                session.aMessage(message.toLegacyMessage())
            }
            MainEntrynew.getsessionmanager()?.save(session)
            Log.i(TAG, "[session] saved, total=${session.messageCount()}")

            // 9. Send reply
            var replyContent = ReplyTagFilter.strip(
                ReasoningTagFilter.stripReasoningTags(
                    result.finalContent ?: "\u62b1\u6b49\uff0c\u6211\u65e0\u6cd5\u5904\u7406\u8fd9\u4e2a\u8bf7\u6c42\u3002"
                )
            )
            if (adapter.isGroupcontext()) {
                replyContent = contextSecurityGuard.redactforSharedcontext(replyContent)
            }

            val chunks = splitMessageintoChunks(replyContent, adapter.messageCharLimit)
            for ((index, chunk) in chunks.withIndex()) {
                adapter.sendMessageChunk(chunk, isfirstChunk = index == 0)
            }

            // 10. A completion reaction
            if (adapter.supportsReactions) {
                try {
                    adapter.aCompletionReaction()
                } catch (_: exception) {}
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "${adapter.channelName} processed in ${elapsed}ms, ${result.iterations} iters, ${replyContent.length} chars, ${chunks.size} chunks")

        } catch (e: exception) {
            Log.e(TAG, "${adapter.channelName} message processing failed", e)

            if (typingStarted) {
                try { adapter.stopTyping() } catch (_: exception) {}
            }
            if (thinkingReactionAed) {
                try { adapter.removeThinkingReaction() } catch (_: exception) {}
            }
            if (adapter.supportsReactions) {
                try { adapter.aErrorReaction() } catch (_: exception) {}
            }
            try {
                adapter.sendErrorMessage("\u62b1\u6b49\uff0c\u5904\u7406\u60a8\u7684\u6d88\u606f\u65f6\u9047\u5230\u9519\u8bef\uff1a${e.message}")
            } catch (_: exception) {}
        }
    }

    private fun cleanuptoolMessages(messages: List<LegacyMessage>): List<LegacyMessage> {
        return messages.filter { message ->
            when (message.role) {
                "user" -> true
                "assistant" -> message.content != null && message.toolCalls == null
                else -> false
            }
        }
    }
}
