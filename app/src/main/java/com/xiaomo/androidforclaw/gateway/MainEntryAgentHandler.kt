/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-chat.ts
 */
package com.xiaomo.androidforclaw.gateway

import android.app.Application
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.context.contextBuilder
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.loop.ProgressUpdate
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * agentHandler implementation - connects Gatewayservice and agentloop
 *
 * Responsibilities:
 * 1. Receive Gateway RPC requests
 * 2. Call agentloop to execute tasks
 * 3. Send back progress and results
 */
class MainEntryagentHandler(
    private val application: Application
) : agentHandler {

    companion object {
        private const val TAG = "MainEntryagentHandler"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskDatamanager: TaskDatamanager = TaskDatamanager.getInstance()

    // Core components - use unified LLM provider
    private val llmprovider: UnifiedLLMprovider by lazy {
        UnifiedLLMprovider(application)
    }

    private val toolRegistry: toolRegistry by lazy {
        toolRegistry(
            context = application,
            taskDatamanager = taskDatamanager
        )
    }

    private val androidtoolRegistry: androidtoolRegistry by lazy {
        androidtoolRegistry(
            context = application,
            taskDatamanager = taskDatamanager,
            cameraCapturemanager = com.xiaomo.androidforclaw.core.MyApplication.getCameraCapturemanager(),
        )
    }

    private val contextBuilder: contextBuilder by lazy {
        contextBuilder(
            context = application,
            toolRegistry = toolRegistry,
            androidtoolRegistry = androidtoolRegistry
        )
    }

    override fun executeagent(
        sessionId: String,
        userMessage: String,
        systemPrompt: String?,
        tools: List<Any>?,
        maxIterations: Int,
        progressCallback: (Map<String, Any>) -> Unit,
        completeCallback: (Map<String, Any>) -> Unit
    ) {
        Log.d(TAG, "executeagent called: session=$sessionId, message=$userMessage")

        scope.launch {
            try {
                // 1. Build system prompt (if not provided)
                val finalSystemPrompt = systemPrompt ?: contextBuilder.buildSystemPrompt(
                    userGoal = userMessage,
                    packageName = "",
                    testMode = "exploration"
                )

                Log.d(TAG, "System prompt ready (${finalSystemPrompt.length} chars)")

                // 2. Create agentloop (with context management)
                val contextmanager = com.xiaomo.androidforclaw.agent.context.contextmanager(llmprovider)
                val agentloop = agentloop(
                    llmprovider = llmprovider,
                    toolRegistry = toolRegistry,
                    androidtoolRegistry = androidtoolRegistry,
                    contextmanager = contextmanager,
                    maxIterations = maxIterations,
                    modelRef = null  // use default model, can be read from config
                )

                // 3. Listen to progress
                val progressJob = launch {
                    agentloop.progressFlow.collect { update ->
                        val progressData = convertProgressToMap(update)
                        progressCallback(progressData)
                    }
                }

                // 4. Execute agent
                Log.d(TAG, "Starting agentloop execution...")
                val result = agentloop.run(
                    systemPrompt = finalSystemPrompt,
                    userMessage = userMessage,
                    reasoningEnabled = true  // Enable reasoning by default
                )

                Log.d(TAG, "agentloop completed: ${result.iterations} iterations")

                // 5. Return result
                completeCallback(mapOf(
                    "success" to true,
                    "iterations" to result.iterations,
                    "toolsused" to result.toolsused,
                    "finalContent" to result.finalContent,
                    "sessionId" to sessionId
                ))

                progressJob.cancel()

            } catch (e: exception) {
                Log.e(TAG, "agent execution failed", e)
                completeCallback(mapOf(
                    "success" to false,
                    "error" to (e.message ?: "Unknown error"),
                    "sessionId" to sessionId
                ))
            }
        }
    }

    /**
     * Convert ProgressUpdate to Map (for JSON serialization)
     */
    private fun convertProgressToMap(update: ProgressUpdate): Map<String, Any> {
        return when (update) {
            is ProgressUpdate.Iteration -> mapOf(
                "type" to "iteration",
                "number" to update.number
            )

            is ProgressUpdate.Thinking -> mapOf(
                "type" to "thinking",
                "iteration" to update.iteration
            )

            is ProgressUpdate.Reasoning -> mapOf(
                "type" to "reasoning",
                "content" to update.content,
                "duration" to update.llmDuration
            )

            is ProgressUpdate.toolCall -> mapOf(
                "type" to "tool_call",
                "name" to update.name,
                "arguments" to update.arguments
            )

            is ProgressUpdate.toolResult -> mapOf(
                "type" to "tool_result",
                "result" to update.result,
                "duration" to update.execDuration
            )

            is ProgressUpdate.IterationComplete -> mapOf(
                "type" to "iteration_complete",
                "number" to update.number,
                "iterationDuration" to update.iterationDuration,
                "llmDuration" to update.llmDuration,
                "execDuration" to update.execDuration
            )

            is ProgressUpdate.contextoverflow -> mapOf(
                "type" to "context_overflow",
                "message" to update.message
            )

            is ProgressUpdate.contextRecovered -> mapOf(
                "type" to "context_recovered",
                "strategy" to update.strategy,
                "attempt" to update.attempt
            )

            is ProgressUpdate.loopDetected -> mapOf(
                "type" to "loop_detected",
                "detector" to update.detector,
                "count" to update.count,
                "message" to update.message,
                "critical" to update.critical
            )

            is ProgressUpdate.Error -> mapOf(
                "type" to "error",
                "message" to update.message
            )
            is ProgressUpdate.BlockReply -> mapOf(
                "type" to "block_reply",
                "text" to update.text,
                "iteration" to update.iteration
            )
            is ProgressUpdate.steerMessageInjected -> mapOf(
                "type" to "steer_message_injected",
                "content" to update.content
            )
            is ProgressUpdate.SubagentSpawned -> mapOf(
                "type" to "subagent_spawned",
                "runId" to update.runId,
                "label" to update.label,
                "childsessionKey" to update.childsessionKey
            )
            is ProgressUpdate.SubagentAnnounced -> mapOf(
                "type" to "subagent_announced",
                "runId" to update.runId,
                "label" to update.label,
                "status" to update.status
            )
            is ProgressUpdate.Yielded -> mapOf(
                "type" to "yielded"
            )
            is ProgressUpdate.ReasoningDelta -> mapOf(
                "type" to "reasoning_delta",
                "text" to update.text
            )
            is ProgressUpdate.ContentDelta -> mapOf(
                "type" to "content_delta",
                "text" to update.text
            )
        }
    }
}
