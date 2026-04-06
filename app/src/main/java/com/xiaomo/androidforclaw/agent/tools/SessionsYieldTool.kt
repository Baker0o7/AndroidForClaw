/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/sessions-yield-tool.ts
 *
 * androidforClaw adaptation: LLM-facing tool to yield the current turn.
 * Sets a CompletableDeferred on the parent agentloop that pauses the loop
 * after the current tool execution round until subagent announcements arrive.
 */
package com.xiaomo.androidforclaw.agent.tools

import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.CompletableDeferred

/**
 * sessions_yield — Yield the current turn to wait for subagent results.
 * Aligned with OpenClaw createsessionsYieldtool.
 *
 * The tool sets a yield signal on the parent agentloop. after this tool execution
 * round completes, the loop pauses until the deferred is completed (by subagent
 * announce) or times out (300s).
 */
class sessionsYieldtool(
    private val parentagentloop: agentloop,
) : tool {

    override val name = "sessions_yield"
    override val description = "Yield the current turn to wait for subagent completion results. " +
        "The agent loop pauses until subagent announcements arrive via the steer channel, " +
        "or until a 300-second timeout. use this when you need to wait for spawned subagents " +
        "before continuing."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "message" to Propertyschema(
                            type = "string",
                            description = "Optional status message (e.g. 'Waiting for research results...')."
                        ),
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val message = args["message"] as? String ?: "Turn yielded."

        // Create a deferred that will be completed by announceToParent or timeout
        val deferred = CompletableDeferred<String?>()
        parentagentloop.yieldSignal = deferred

        // tool returns immediately — the actual pause happens in agentloop main loop
        // when it checks yieldSignal after this tool execution round completes.
        return toolResult(
            success = true,
            content = "Yield requested: $message. The loop will pause after this tool execution " +
                "round and resume when subagent results arrive (timeout: 300s).",
            metadata = mapOf("yielded" to true, "message" to message)
        )
    }
}
