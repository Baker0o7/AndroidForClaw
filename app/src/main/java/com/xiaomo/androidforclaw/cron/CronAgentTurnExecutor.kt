/**
 * androidforClaw CronagentTurnExecutor
 *
 * Executes an agent loop turn from a cron job, then delivers the result
 * via the specified channel (feishu / weixin).
 *
 * Aligned with OpenClaw cron agentTurn execution.
 */
package com.xiaomo.androidforclaw.cron

import android.content.context
import com.xiaomo.androidforclaw.agent.context.contextBuilder
import com.xiaomo.androidforclaw.agent.context.contextmanager
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.session.HistorySanitizer
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.core.MainEntrynew
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.providers.llm.tonewMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext

object CronagentTurnExecutor {
    private const val TAG = "CronagentTurn"

    /**
     * Execute a cron agent turn.
     *
     * @param context Application context
     * @param sessionId session identifier (e.g. "cron_heartbeat" or "cron_isolated_<jobId>")
     * @param userMessage The message to send to the agent
     * @param model Optional model override
     * @param channel Delivery channel ("feishu" / "weixin" / null)
     * @param to Delivery target (chat_id for feishu, user_id for weixin)
     * @param isolated if true, pass empty history (isolated session)
     * @return CronRunResult
     */
    suspend fun execute(
        context: context,
        sessionId: String,
        userMessage: String,
        model: String? = null,
        channel: String? = null,
        to: String? = null,
        isolated: Boolean = false
    ): CronRunResult = withcontext(Dispatchers.IO) {
        try {
            Log.i(TAG, "[TIME] Executing cron turn: session=$sessionId channel=$channel to=$to")

            // Build agent loop (same setup as processFeishuMessage)
            val taskDatamanager = TaskDatamanager.getInstance()
            val toolRegistry = toolRegistry(
                context = context,
                taskDatamanager = taskDatamanager
            )
            val androidtoolRegistry = androidtoolRegistry(
                context = context,
                taskDatamanager = taskDatamanager,
                cameraCapturemanager = null
            )
            val configLoader = configLoader(context)
            val contextBuilder = contextBuilder(
                context = context,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                configLoader = configLoader
            )
            val llmprovider = UnifiedLLMprovider(context)
            val contextmanager = contextmanager(llmprovider)

            val config = configLoader.loadOpenClawconfig()
            val maxIterations = config.agent.maxIterations

            val agentloop = agentloop(
                llmprovider = llmprovider,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                contextmanager = contextmanager,
                maxIterations = maxIterations,
                modelRef = model
            )

            // Build context history
            if (MainEntrynew.getsessionmanager() == null) {
                MainEntrynew.initialize(context as android.app.Application)
            }
            val sessionmanager = MainEntrynew.getsessionmanager()
                ?: return@withcontext CronRunResult(RunStatus.ERROR, "sessionmanager not initialized")
            val session = sessionmanager.getorCreate(sessionId)
            val contextHistory = if (isolated) {
                emptyList()
            } else {
                val rawHistory = session.getRecentMessages(20)
                cleanuptoolMessages(rawHistory).map { it.tonewMessage() }
            }

            // Build system prompt
            val channelCtx = contextBuilder.channelcontext(
                channel = channel ?: "cron",
                chatId = to ?: "",
                chatType = "p2p",
                senderId = "",
                messageId = ""
            )
            val systemPrompt = contextBuilder.buildSystemPrompt(
                userGoal = userMessage,
                packageName = "",
                testMode = "cron",
                channelcontext = channelCtx
            )

            // Run agent loop
            val result = agentloop.run(
                systemPrompt = systemPrompt,
                userMessage = userMessage,
                contextHistory = contextHistory
            )

            // Save to session history
            session.aMessage(LegacyMessage(
                role = "user", content = userMessage
            ))
            session.aMessage(LegacyMessage(
                role = "assistant", content = result.finalContent
            ))

            // Deliver result
            val response = result.finalContent.trim()
            if (response != "NO_REPLY" && response != "HEARTBEAT_OK" && response.isnotBlank()) {
                val sanitized = HistorySanitizer
                    .stripControlTokensfromText(response)
                    .replace(Regex("(?:^|\\s+|\\*+)NO_REPLY\\s*$"), "")
                    .replace(Regex("(?:^|\\s+|\\*+)HEARTBEAT_OK\\s*$"), "")
                    .trim()

                if (sanitized.isnotBlank()) {
                    // Resolve delivery target: explicit → last active chat
                    val (lastchannel, lastChatId) = MyApplication.getLastActiveChat()
                    val deliverychannel = channel ?: lastchannel
                    val deliveryTo = to ?: lastChatId

                    if (deliverychannel != null && deliveryTo != null) {
                        deliver(context, deliverychannel, deliveryTo, sanitized)
                    } else {
                        Log.w(TAG, "No delivery target configured (no 'to' and no last active chat)")
                    }
                }
            }

            CronRunResult(
                status = RunStatus.OK,
                summary = result.finalContent.take(200),
                delivered = channel != null || to != null || MyApplication.getLastActiveChat().second != null
            )
        } catch (e: exception) {
            Log.e(TAG, "Cron agent turn failed", e)
            CronRunResult(
                status = RunStatus.ERROR,
                summary = e.message
            )
        }
    }

    /**
     * Deliver a message to the specified channel.
     */
    private suspend fun deliver(context: context, channel: String, to: String, text: String) {
        when (channel) {
            "feishu" -> {
                val feishuchannel = MyApplication.getFeishuchannel()
                if (feishuchannel != null) {
                    try {
                        feishuchannel.sender.sendTextMessage(to, text)
                        Log.i(TAG, "[MSG] Delivered to feishu $to")
                    } catch (e: exception) {
                        Log.e(TAG, "Failed to deliver to feishu", e)
                    }
                } else {
                    Log.w(TAG, "Feishu channel not available")
                }
            }
            "weixin" -> {
                val weixinchannel = MyApplication.getWeixinchannel()
                if (weixinchannel != null) {
                    try {
                        weixinchannel.sender?.sendText(to, text)
                        Log.i(TAG, "[MSG] Delivered to weixin $to")
                    } catch (e: exception) {
                        Log.e(TAG, "Failed to deliver to weixin", e)
                    }
                } else {
                    Log.w(TAG, "Weixin channel not available")
                }
            }
            else -> Log.w(TAG, "Unknown channel: $channel")
        }
    }

    /**
     * Clean up tool messages from history (same as MyApplication.cleanuptoolMessages).
     */
    private fun cleanuptoolMessages(
        messages: List<LegacyMessage>
    ): List<LegacyMessage> {
        return messages.filter { message ->
            when (message.role) {
                "user" -> true
                "assistant" -> message.content != null && message.toolCalls == null
                else -> false
            }
        }
    }
}
