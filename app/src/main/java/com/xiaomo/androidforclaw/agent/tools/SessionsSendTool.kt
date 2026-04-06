/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/sessions-send-tool.ts
 * - ../openclaw/src/agents/subagent-control.ts (steerControlledSubagentRun, sendControlledSubagentMessage)
 *
 * androidforClaw adaptation: LLM-facing tool to send messages to running subagents.
 * Supports multi-strategy target resolution and fire-and-forget / wait modes.
 * steer semantics: abort current run + restart with new message (aligned with OpenClaw).
 */
package com.xiaomo.androidforclaw.agent.tools

import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.subagent.sessionAccessResult
import com.xiaomo.androidforclaw.agent.subagent.sessionVisibilityGuard
import com.xiaomo.androidforclaw.agent.subagent.SpawnMode
import com.xiaomo.androidforclaw.agent.subagent.SubagentSpawner
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * sessions_send — Send a message to a running subagent (steer = abort + restart).
 * Aligned with OpenClaw steerControlledSubagentRun + sendControlledSubagentMessage.
 *
 * Target resolution supports: session_key, label, or generic target token
 * ("last", numeric index, label prefix, runId prefix).
 */
class sessionsSendtool(
    private val spawner: SubagentSpawner,
    private val parentsessionKey: String,
    private val parentagentloop: agentloop,
) : tool {

    override val name = "sessions_send"
    override val description = "Send a message to a running subagent to steer or redirect its work. " +
        "This aborts the subagent's current run and restarts it with the new message. " +
        "Identify the target via session_key, label, or the generic target token."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "session_key" to Propertyschema(
                            type = "string",
                            description = "Target session key (alternative to target)"
                        ),
                        "label" to Propertyschema(
                            type = "string",
                            description = "Target subagent label (alternative to target)"
                        ),
                        "agent_id" to Propertyschema(
                            type = "string",
                            description = "Target agent ID for cross-agent messaging"
                        ),
                        "target" to Propertyschema(
                            type = "string",
                            description = "Target subagent: 'last', numeric index (1-based), label prefix, run ID, or session key."
                        ),
                        "message" to Propertyschema(
                            type = "string",
                            description = "The message to send to the subagent."
                        ),
                        "timeout_seconds" to Propertyschema(
                            type = "number",
                            description = "Wait timeout in seconds. 0 = fire-and-forget. >0 = wait for completion. Default: 30."
                        ),
                    ),
                    required = listOf("message")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val sessionKey = args["session_key"] as? String
        val label = args["label"] as? String
        val agentId = args["agent_id"] as? String
        val target = args["target"] as? String
        val message = args["message"] as? String
        if (message.isNullorBlank()) {
            return toolResult.error("Missing required parameter: message")
        }
        val timeoutSeconds = (args["timeout_seconds"] as? Number)?.toInt() ?: 30

        // Resolve target: session_key > label > target, error if none provided
        val record = when {
            !sessionKey.isNullorBlank() -> {
                spawner.registry.getRunByChildsessionKey(sessionKey)
                    ?: return toolResult(success = false, content = "No subagent found for session_key: $sessionKey")
            }
            !label.isNullorBlank() -> {
                spawner.registry.resolveTarget(label, parentsessionKey)
                    ?: return toolResult(success = false, content = "No subagent found for label: $label")
            }
            !target.isNullorBlank() -> {
                spawner.registry.resolveTarget(target, parentsessionKey)
                    ?: return toolResult(success = false, content = "No matching subagent found for target: $target")
            }
            else -> {
                return toolResult.error("must provide one of: session_key, label, or target")
            }
        }

        // Visibility guard (aligned with OpenClaw controlScope)
        val visibility = sessionVisibilityGuard.resolveVisibility(parentsessionKey, spawner.registry)
        val access = sessionVisibilityGuard.checkAccess(
            "send to", parentsessionKey, record.childsessionKey, visibility, spawner.registry
        )
        if (access is sessionAccessResult.Denied) {
            return toolResult(success = false, content = access.reason)
        }

        // if target is completed SESSION mode, reactivate instead of steer
        if (!record.isActive && record.spawnMode == SpawnMode.SESSION) {
            val (reactivateSuccess, reactivateInfo) = spawner.reactivatesession(
                childsessionKey = record.childsessionKey,
                message = message,
                callersessionKey = parentsessionKey,
                parentagentloop = parentagentloop,
            )
            if (!reactivateSuccess) {
                return toolResult(success = false, content = "session reactivation failed: $reactivateInfo")
            }
            return toolResult(success = true, content = "session '${record.label}' reactivated: $reactivateInfo")
        }

        // steer (abort + restart, aligned with OpenClaw)
        val (success, info) = spawner.steer(
            runId = record.runId,
            message = message,
            callersessionKey = parentsessionKey,
            parentagentloop = parentagentloop,
        )

        if (!success) {
            return toolResult(success = false, content = "steer failed: $info")
        }

        // Fire-and-forget mode
        if (timeoutSeconds <= 0) {
            return toolResult(success = true, content = "Message sent to ${record.label}: $info")
        }

        // Wait mode: poll for child completion
        val waitMs = timeoutSeconds * 1000L
        val startWait = System.currentTimeMillis()
        while (System.currentTimeMillis() - startWait < waitMs) {
            val latestRecord = spawner.registry.getRunByChildsessionKey(record.childsessionKey)
            if (latestRecord != null && !latestRecord.isActive) {
                return toolResult(
                    success = true,
                    content = buildString {
                        appendLine("Subagent '${latestRecord.label}' completed after steer.")
                        appendLine("Status: ${latestRecord.outcome?.status?.wireValue ?: "unknown"}")
                        latestRecord.frozenResultText?.let { text ->
                            appendLine("Result: ${text.take(4000)}")
                        }
                    }
                )
            }
            kotlinx.coroutines.delay(500)
        }

        return toolResult(
            success = true,
            content = "steer sent to ${record.label}. Child still running after ${timeoutSeconds}s wait."
        )
    }
}
