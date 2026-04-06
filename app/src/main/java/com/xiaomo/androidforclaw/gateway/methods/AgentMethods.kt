/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-chat.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.loop.agentresult
import com.xiaomo.androidforclaw.agent.session.sessionmanager
import com.xiaomo.androidforclaw.gateway.protocol.*
import com.xiaomo.androidforclaw.gateway.websocket.GatewayWebSocketServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutorNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.xiaomo.androidforclaw.agent.loop.ProgressUpdate

/**
 * agent RPC methods implementation with async execution
 */
class agentMethods(
    private val context: context,
    private val agentloop: agentloop,
    private val sessionmanager: sessionmanager,
    private val gateway: GatewayWebSocketServer,
    private val externalActiveJobs: ConcurrentHashMap<String, kotlinx.coroutines.Job>? = null
) {
    private val TAG = "agentMethods"
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Store running agent tasks
    private val runningTasks = ConcurrentHashMap<String, agentTask>()

    /**
     * agent() - Execute an agent run asynchronously
     */
    suspend fun agent(params: agentParams): agentRunResponse {
        val runId = "run_${UUID.randomUUID()}"
        val acceptedAt = System.currentTimeMillis()

        // Create task
        val task = agentTask(
            runId = runId,
            sessionKey = params.sessionKey,
            message = params.message,
            status = "running"
        )
        runningTasks[runId] = task

        // Send agent.start event
        broadcastEvent("agent.start", mapOf(
            "runId" to runId,
            "sessionKey" to params.sessionKey,
            "message" to params.message,
            "acceptedAt" to acceptedAt
        ))

        // Execute agent asynchronously
        agentScope.launch {
            try {
                executeagent(runId, params)
            } catch (e: exception) {
                Log.e(TAG, "agent execution failed: $runId", e)
                task.status = "error"
                task.error = e.message

                // Send agent.error event
                broadcastEvent("agent.error", mapOf(
                    "runId" to runId,
                    "error" to e.message
                ))
            } finally {
                // Keep task for a while after completion for wait() queries
                // should have TTL cleanup mechanism
            }
        }

        return agentRunResponse(
            runId = runId,
            acceptedAt = acceptedAt
        )
    }

    /**
     * agent.wait() - Wait for agent run completion
     *
     * Checks both internal runningTasks (from agent()) and externalActiveJobs
     * (from chat.send in GatewayController) so callers can wait on runs
     * started through either path.
     */
    suspend fun agentWait(params: agentWaitParams): agentWaitResponse {
        val timeout = params.timeout ?: 30000L

        // 1. Check internal runningTasks first (agent() path)
        val task = runningTasks[params.runId]
        if (task != null) {
            val result = withTimeoutorNull(timeout) {
                task.resultchannel.receive()
            }

            return if (result != null) {
                agentWaitResponse(
                    runId = params.runId,
                    status = "completed",
                    result = mapOf(
                        "content" to result.finalContent,
                        "iterations" to result.iterations,
                        "toolsused" to result.toolsused
                    )
                )
            } else {
                agentWaitResponse(
                    runId = params.runId,
                    status = if (task.status == "error") "error" else "timeout",
                    result = if (task.status == "error") mapOf("error" to task.error) else null
                )
            }
        }

        // 2. Check externalActiveJobs (chat.send path in GatewayController)
        val externalJob = externalActiveJobs?.get(params.runId)
        if (externalJob != null) {
            if (!externalJob.isActive) {
                // Job already finished
                return agentWaitResponse(
                    runId = params.runId,
                    status = "completed",
                    result = null
                )
            }
            // Suspend until the coroutine Job completes, with timeout
            val completed = withTimeoutorNull(timeout) {
                externalJob.join()
                true
            }
            return agentWaitResponse(
                runId = params.runId,
                status = if (completed == true) "completed" else "timeout",
                result = null
            )
        }

        // 3. Not found in either map — already completed or never existed
        return agentWaitResponse(
            runId = params.runId,
            status = "completed",
            result = null
        )
    }

    /**
     * agent.identity() - Get agent identity
     */
    fun agentIdentity(): agentIdentityresult {
        return agentIdentityresult(
            name = "androidforclaw",
            version = "1.0.0",
            platform = "android",
            capabilities = listOf(
                "screenshot",
                "tap",
                "swipe",
                "type",
                "navigation",
                "app_control",
                "accessibility"
            )
        )
    }

    /**
     * Execute agent task
     */
    private suspend fun executeagent(runId: String, params: agentParams) {
        val task = runningTasks[runId] ?: return

        try {
            // use simple system prompt
            val systemPrompt = """
You are an AI agent controlling an android device.

Available tools:
- screenshot(): Capture screen
- tap(x, y): Tap at coordinates
- swipe(startX, startY, endX, endY, duration): Swipe gesture
- type(text): Input text
- home(): Press home button
- back(): Press back button
- open_app(package): Open application

Instructions:
1. Always screenshot before and after actions
2. Verify results after each operation
3. Be precise with coordinates
4. use stop() when task is complete
            """.trimIndent()

            // Get or create session
            val session = sessionmanager.getorCreate(params.sessionKey)

            // Subscribe to agentloop progress updates and forward as Gateway Events
            val progressJob = agentloop.progressFlow
                .onEach { progress ->
                    when (progress) {
                        is ProgressUpdate.Iteration -> {
                            broadcastEvent("agent.iteration", mapOf(
                                "runId" to runId,
                                "iteration" to progress.number
                            ))
                        }
                        is ProgressUpdate.Thinking -> {
                            // Intermediate feedback: thinking at step X
                            broadcastEvent("agent.thinking", mapOf(
                                "runId" to runId,
                                "iteration" to progress.iteration,
                                "message" to "currently processing step ${progress.iteration}..."
                            ))
                        }
                        is ProgressUpdate.toolCall -> {
                            broadcastEvent("agent.tool_call", mapOf(
                                "runId" to runId,
                                "tool" to progress.name,
                                "arguments" to progress.arguments
                            ))
                        }
                        is ProgressUpdate.toolresult -> {
                            broadcastEvent("agent.tool_result", mapOf(
                                "runId" to runId,
                                "tool" to progress.name,
                                "result" to progress.result,
                                "duration" to progress.execDuration
                            ))
                        }
                        is ProgressUpdate.Reasoning -> {
                            // Extended thinking progress (optional)
                            broadcastEvent("agent.thinking", mapOf(
                                "runId" to runId,
                                "content" to progress.content.take(200), // Limit length
                                "duration" to progress.llmDuration
                            ))
                        }
                        is ProgressUpdate.IterationComplete -> {
                            // Iteration completion statistics (optional)
                            Log.d(TAG, "Iteration ${progress.number} complete: ${progress.iterationDuration}ms")
                        }
                        is ProgressUpdate.contextoverflow -> {
                            broadcastEvent("agent.context_overflow", mapOf(
                                "runId" to runId,
                                "message" to progress.message
                            ))
                        }
                        is ProgressUpdate.contextRecovered -> {
                            broadcastEvent("agent.context_recovered", mapOf(
                                "runId" to runId,
                                "strategy" to progress.strategy,
                                "attempt" to progress.attempt
                            ))
                        }
                        is ProgressUpdate.loopDetected -> {
                            broadcastEvent("agent.loop_detected", mapOf(
                                "runId" to runId,
                                "detector" to progress.detector,
                                "count" to progress.count,
                                "message" to progress.message,
                                "critical" to progress.critical
                            ))
                        }
                        is ProgressUpdate.Error -> {
                            // Error already sent via agent.error event
                            Log.w(TAG, "Progress error: ${progress.message}")
                        }
                        is ProgressUpdate.BlockReply -> {
                            Log.d(TAG, "[SEND] Block reply: ${progress.text.take(100)}")
                            com.xiaomo.androidforclaw.gateway.GatewayServer.getInstance()?.broadcast("agent.block_reply", mapOf(
                                "text" to progress.text,
                                "iteration" to progress.iteration
                            ))
                        }
                        is ProgressUpdate.steerMessageInjected -> {
                            Log.d(TAG, "[TARGET] steer message injected: ${progress.content.take(100)}")
                            broadcastEvent("agent.steer_injected", mapOf(
                                "runId" to runId,
                                "content" to progress.content.take(200)
                            ))
                        }
                        is ProgressUpdate.SubagentSpawned -> {
                            broadcastEvent("agent.subagent_spawned", mapOf(
                                "runId" to runId,
                                "subagentRunId" to progress.runId,
                                "label" to progress.label,
                                "childsessionKey" to progress.childsessionKey
                            ))
                        }
                        is ProgressUpdate.SubagentAnnounced -> {
                            broadcastEvent("agent.subagent_announced", mapOf(
                                "runId" to runId,
                                "subagentRunId" to progress.runId,
                                "label" to progress.label,
                                "status" to progress.status
                            ))
                        }
                        is ProgressUpdate.Yielded -> {
                            broadcastEvent("agent.yielded", mapOf(
                                "runId" to runId
                            ))
                        }
                        is ProgressUpdate.ReasoningDelta -> {
                            broadcastEvent("agent.reasoning_delta", mapOf(
                                "runId" to runId,
                                "text" to progress.text
                            ))
                        }
                        is ProgressUpdate.ContentDelta -> {
                            broadcastEvent("agent.content_delta", mapOf(
                                "runId" to runId,
                                "text" to progress.text
                            ))
                        }
                    }
                }
                .launchIn(agentScope)

            // Execute agent loop
            val result = agentloop.run(
                systemPrompt = systemPrompt,
                userMessage = params.message,
                contextHistory = emptyList(),
                reasoningEnabled = true
            )

            // cancel progress subscription
            progressJob.cancel()

            // Update task status
            task.status = "completed"
            task.result = result

            // Send completion signal
            task.resultchannel.send(result)

            // Send agent.complete event
            broadcastEvent("agent.complete", mapOf(
                "runId" to runId,
                "status" to "completed",
                "iterations" to result.iterations,
                "toolsused" to result.toolsused,
                "content" to result.finalContent
            ))

            Log.i(TAG, "agent completed: $runId, iterations=${result.iterations}")

        } catch (e: exception) {
            task.status = "error"
            task.error = e.message
            throw e
        }
    }

    /**
     * Broadcast event (OpenClaw Protocol v3: uses "payload" not "data")
     */
    private var eventSeq = 0L

    private fun broadcastEvent(event: String, data: Any?) {
        try {
            gateway.broadcast(EventFrame(
                event = event,
                payload = data,  // OpenClaw uses "payload" not "data"
                seq = eventSeq++  // A sequence number
            ))
        } catch (e: exception) {
            Log.w(TAG, "Failed to broadcast event: $event", e)
        }
    }
}

/**
 * agent task
 */
private data class agentTask(
    val runId: String,
    val sessionKey: String,
    val message: String,
    var status: String,
    var result: agentresult? = null,
    var error: String? = null,
    val resultchannel: channel<agentresult> = channel(1)
)
