/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/session-status-tool.ts
 *
 * androidforClaw adaptation: LLM-facing tool to display session status.
 * Shows model info, runtime, subagent count, and optional model override.
 * Simplified for android (no usage provider, no queue settings, no session store).
 */
package com.xiaomo.androidforclaw.agent.tools

import com.xiaomo.androidforclaw.agent.subagent.sessionAccessResult
import com.xiaomo.androidforclaw.agent.subagent.sessionVisibilityGuard
import com.xiaomo.androidforclaw.agent.subagent.SubagentRegistry
import com.xiaomo.androidforclaw.agent.subagent.resolveSubagentLabel
import com.xiaomo.androidforclaw.agent.subagent.resolveSubagentsessionStatus
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.text.SimpleDateformat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * session_status — Show session status card (model, runtime, subagents).
 * Aligned with OpenClaw createsessionStatustool.
 */
class sessionStatustool(
    private val registry: SubagentRegistry,
    private val callersessionKey: String,
    private val configLoader: configLoader,
) : tool {

    override val name = "session_status"
    override val description = "Show session status card with model info, runtime, and subagent summary. " +
        "Optional: set per-session model override (model=default resets overrides)."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "sessionKey" to Propertyschema(
                            type = "string",
                            description = "session key to query. Defaults to current session."
                        ),
                        "model" to Propertyschema(
                            type = "string",
                            description = "Set model override for this session. use 'default' to reset."
                        ),
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val requestedKey = (args["sessionKey"] as? String)?.trim() ?: callersessionKey

        // Visibility guard
        if (requestedKey != callersessionKey) {
            val visibility = sessionVisibilityGuard.resolveVisibility(callersessionKey, registry)
            val access = sessionVisibilityGuard.checkAccess(
                "view status of", callersessionKey, requestedKey, visibility, registry
            )
            if (access is sessionAccessResult.Denied) {
                return toolResult(success = false, content = access.reason)
            }
        }

        // model override handling
        val modelParam = (args["model"] as? String)?.trim()
        var changedmodel = false
        if (modelParam != null) {
            // On android, model override is informational only — no persistent session store
            changedmodel = true
        }

        // Build status card
        val config = try {
            configLoader.loadOpenClawconfig()
        } catch (_: exception) { null }

        val defaultmodel = config?.resolveDefaultmodel() ?: "unknown"
        val activeRuns = registry.countActiveRunsforsession(requestedKey)
        val allRuns = registry.listRunsforController(requestedKey)
        val recentCompleted = allRuns.count { !it.isActive }

        // Check if requestedKey is a subagent
        val subagentRun = registry.getRunByChildsessionKey(requestedKey)
        val isSubagent = subagentRun != null

        // Time display
        val tz = TimeZone.getDefault()
        val sdf = SimpleDateformat("yyyy-MM- HH:mm:ss z", Locale.US)
        sdf.timeZone = tz
        val timeStr = sdf.format(Date())

        val statusText = buildString {
            appendLine("## session Status")
            appendLine()
            appendLine("session: $requestedKey")
            if (isSubagent) {
                appendLine("Role: subagent (depth=${subagentRun!!.depth})")
                appendLine("Status: ${resolveSubagentsessionStatus(subagentRun)}")
                appendLine("Task: ${subagentRun.task.take(100)}")
            } else {
                appendLine("Role: main")
            }
            appendLine()
            appendLine("model: $defaultmodel")
            if (changedmodel && modelParam != null) {
                appendLine("model override requested: $modelParam (note: per-session model override is not yet supported on android)")
            }
            appendLine()
            appendLine("Subagents: $activeRuns active, $recentCompleted completed (${allRuns.size} total)")
            if (activeRuns > 0) {
                val activeList = allRuns.filter { it.isActive }
                for (run in activeList.take(10)) {
                    val runtime = sessionsListtool.formatDurationCompact(run.runtimeMs)
                    appendLine("  - ${resolveSubagentLabel(run)} ($runtime, model=${run.model ?: "default"})")
                }
            }
            appendLine()
            appendLine("Time: $timeStr")
        }.trimEnd()

        return toolResult(
            success = true,
            content = statusText,
            metadata = mapOf(
                "sessionKey" to requestedKey,
                "changedmodel" to changedmodel,
                "activeSubagents" to activeRuns,
            )
        )
    }
}
