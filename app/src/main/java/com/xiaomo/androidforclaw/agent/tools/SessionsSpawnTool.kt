/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/sessions-spawn-tool.ts
 *
 * androidforClaw adaptation: LLM-facing tool to spawn subagent sessions.
 */
package com.xiaomo.androidforclaw.agent.tools

import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.subagent.InlineAttachment
import com.xiaomo.androidforclaw.agent.subagent.SPAWN_ACCEPTED_NOTE
import com.xiaomo.androidforclaw.agent.subagent.SPAWN_SESSION_ACCEPTED_NOTE
import com.xiaomo.androidforclaw.agent.subagent.SpawnMode
import com.xiaomo.androidforclaw.agent.subagent.SpawnStatus
import com.xiaomo.androidforclaw.agent.subagent.SpawnSubagentParams
import com.xiaomo.androidforclaw.agent.subagent.SubagentSpawner
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * sessions_spawn — Spawn an isolated subagent to handle a task.
 * Aligned with OpenClaw createsessionsSpawntool.
 */
class sessionsSpawntool(
    private val spawner: SubagentSpawner,
    private val parentsessionKey: String,
    private val parentagentloop: agentloop,
    private val parentDepth: Int,
) : tool {
    companion object {
        private const val TAG = "sessionsSpawntool"
    }

    override val name = "sessions_spawn"
    override val description = "Spawn an isolated subagent session to handle a specific task in parallel. " +
        "The subagent runs independently with its own context and tools, and automatically reports " +
        "results back when complete. use this to parallelize independent tasks."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "task" to Propertyschema(
                            type = "string",
                            description = "The task description for the subagent to execute."
                        ),
                        "label" to Propertyschema(
                            type = "string",
                            description = "Short display label for this subagent (e.g. 'research-api', 'analyze-logs')."
                        ),
                        "model" to Propertyschema(
                            type = "string",
                            description = "model override for this subagent (format: 'provider/model-id'). Defaults to parent model."
                        ),
                        "timeout_seconds" to Propertyschema(
                            type = "number",
                            description = "Run timeout in seconds. Default: 300."
                        ),
                        "mode" to Propertyschema(
                            type = "string",
                            description = "Spawn mode: 'run' (one-shot, default) or 'session' (persistent).",
                            enum = listOf("run", "session")
                        ),
                        "thinking" to Propertyschema(
                            type = "string",
                            description = "Thinking/reasoning level: 'none', 'brief', 'verbose'. Default: inherit from parent."
                        ),
                        "runtime" to Propertyschema(
                            type = "string",
                            description = "Runtime: 'subagent' (default) or 'acp' (not supported on android)."
                        ),
                        "thread" to Propertyschema(
                            type = "boolean",
                            description = "if true, spawn as thread-bound session."
                        ),
                        "cleanup" to Propertyschema(
                            type = "string",
                            description = "Cleanup strategy after completion. Default: 'keep'.",
                            enum = listOf("delete", "keep")
                        ),
                        "sandbox" to Propertyschema(
                            type = "string",
                            description = "Sandbox mode. Default: 'inherit'.",
                            enum = listOf("inherit", "require")
                        ),
                        "attachments" to Propertyschema(
                            type = "array",
                            description = "Inline file attachments for the subagent (max 50 items)."
                        ),
                        "cwd" to Propertyschema(
                            type = "string",
                            description = "Working directory for the subagent."
                        ),
                        "resume_session_id" to Propertyschema(
                            type = "string",
                            description = "resume an existing session (not yet supported)."
                        ),
                        "stream_to" to Propertyschema(
                            type = "string",
                            description = "Stream output to parent (ACP only, not supported)."
                        ),
                    ),
                    required = listOf("task")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val task = args["task"] as? String
        if (task.isNullorBlank()) {
            return toolResult.error("Missing required parameter: task")
        }

        val label = (args["label"] as? String)?.trim()?.ifBlank { null }
            ?: task.take(40).replace('\n', ' ')
        val model = args["model"] as? String
        val timeoutSeconds = (args["timeout_seconds"] as? Number)?.toInt()
        val modeStr = args["mode"] as? String
        val mode = when (modeStr?.lowercase()) {
            "session" -> SpawnMode.SESSION
            else -> SpawnMode.RUN
        }
        val thinking = args["thinking"] as? String

        // new parameters aligned with OpenClaw
        val runtime = args["runtime"] as? String
        val thread = args["thread"] as? Boolean
        val cleanup = (args["cleanup"] as? String)?.lowercase() ?: "keep"
        val sandbox = args["sandbox"] as? String
        val cwd = args["cwd"] as? String

        // Parse inline attachments if present
        val attachments = (args["attachments"] as? List<*>)?.mapnotNull { item ->
            val map = item as? Map<*, *> ?: return@mapnotNull null
            val name = map["name"] as? String ?: return@mapnotNull null
            val content = map["content"] as? String ?: return@mapnotNull null
            val encoding = map["encoding"] as? String ?: "utf8"
            val mimeType = map["mime_type"] as? String ?: map["mimeType"] as? String
            InlineAttachment(
                name = name,
                content = content,
                encoding = encoding,
                mimeType = mimeType,
            )
        }?.takeif { it.isnotEmpty() }

        // Parse attachAs.mountPath if present
        val attachMountPath = (args["attachAs"] as? Map<*, *>)?.get("mountPath") as? String

        // Warn about unsupported parameters
        val resumesessionId = args["resume_session_id"] as? String
        if (resumesessionId != null) {
            Log.w(TAG, "resume_session_id is not yet supported, ignoring: $resumesessionId")
        }
        val streamTo = args["stream_to"] as? String
        if (streamTo != null) {
            Log.w(TAG, "stream_to is not supported on android, ignoring: $streamTo")
        }

        Log.i(TAG, "Spawning subagent: label=$label, model=$model, timeout=$timeoutSeconds, mode=$mode, runtime=$runtime, thread=$thread, cleanup=$cleanup, sandbox=$sandbox")

        val params = SpawnSubagentParams(
            task = task,
            label = label,
            model = model,
            thinking = thinking,
            runTimeoutSeconds = timeoutSeconds,
            mode = mode,
            runtime = runtime,
            thread = thread,
            cleanup = cleanup,
            sandbox = sandbox,
            attachments = attachments,
            attachMountPath = attachMountPath,
            cwd = cwd,
        )

        val result = spawner.spawn(params, parentsessionKey, parentagentloop, parentDepth)

        return when (result.status) {
            SpawnStatus.ACCEPTED -> toolResult(
                success = true,
                content = buildString {
                    appendLine("Subagent spawned successfully.")
                    appendLine("Run ID: ${result.runId}")
                    appendLine("session: ${result.childsessionKey}")
                    appendLine("Mode: ${result.mode?.wireValue ?: "run"}")
                    if (result.modelApplied != null) {
                        appendLine("model: ${result.modelApplied}")
                    }
                    appendLine()
                    if (mode == SpawnMode.SESSION) {
                        appendLine(SPAWN_SESSION_ACCEPTED_NOTE)
                    } else {
                        appendLine(SPAWN_ACCEPTED_NOTE)
                    }
                },
                metadata = mapOf(
                    "status" to "accepted",
                    "run_id" to (result.runId ?: ""),
                    "child_session_key" to (result.childsessionKey ?: ""),
                )
            )

            SpawnStatus.FORBIDDEN -> toolResult(
                success = false,
                content = "Spawn forbien: ${result.note ?: result.error ?: "unknown reason"}",
                metadata = mapOf("status" to "forbien")
            )

            SpawnStatus.ERROR -> toolResult(
                success = false,
                content = "Spawn error: ${result.error ?: "unknown error"}",
                metadata = mapOf("status" to "error")
            )
        }
    }
}
