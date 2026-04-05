package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log

/**
 * Internal Self-Control Skill
 *
 * Enables PhoneForClaw's AI Agent to call its own Self-Control functions.
 *
 * This is a meta-level Skill:
 * - Agent calls other Self-Control Skills through this Skill
 * - Implements "AI controlling AI" self-management capability
 * - Supports chain calls and batch operations
 *
 * Use Cases:
 * 1. **Self-Diagnosis**
 *    Agent detects issue -> calls query_logs -> analyzes errors -> calls manage_config to adjust parameters
 *
 * 2. **Self-Tuning**
 *    Agent discovers performance issue -> calls manage_config to read current config -> calculates optimal parameters -> calls manage_config to update
 *
 * 3. **Self-Development**
 *    Agent needs to modify config -> calls navigate_app to open config page -> waits for user confirmation
 *
 * 4. **Task Execution Optimization**
 *    Before screenshot -> calls control_service to hide floating window -> execute screenshot -> calls control_service to show floating window
 *
 * Examples:
 * ```json
 * // Single call
 * {
 *   "skill": "navigate_app",
 *   "args": {"page": "config"}
 * }
 *
 * // Chain call (execute in sequence)
 * {
 *   "skills": [
 *     {"skill": "control_service", "args": {"operation": "hide_float"}},
 *     {"skill": "wait", "args": {"duration": 100}},
 *     {"skill": "screenshot", "args": {}},
 *     {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
 *   ]
 * }
 *
 * // Conditional call (decide based on result)
 * {
 *   "skill": "query_logs",
 *   "args": {"level": "E", "lines": 50},
 *   "on_success": {
 *     "skill": "analyze_and_fix"
 *   }
 * }
 * ```
 */
class InternalSelfControlSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "InternalSelfControl"
    }

    // Internal Registry instance (lazy initialization)
    private val registry by lazy { SelfControlRegistry(context) }

    override val name = "self_control"

    override val description = """
        Enables AI Agent to call its own Self-Control functions.

        This is a meta-level Skill that allows Agent to control and manage itself:
        - Call other Self-Control Skills
        - Support single calls and chain calls
        - Support batch operations

        Available Skills:
        - navigate_app: Page navigation
        - manage_config: Config management (get/set/list/delete)
        - control_service: Service control (hide/show/check_status)
        - query_logs: Log query

        Usage:

        1. Single call
        {
          "skill": "navigate_app",
          "args": {"page": "config"}
        }

        2. Chain call (execute multiple Skills in sequence)
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "screenshot", "args": {}},
            {"skill": "control_service", "args": {"operation": "show_float"}}
          ]
        }

        3. Batch call (parallel execution)
        {
          "parallel": true,
          "skills": [
            {"skill": "query_logs", "args": {"level": "E"}},
            {"skill": "control_service", "args": {"operation": "check_status"}}
          ]
        }

        Typical scenarios:

        **Scenario 1: Pre/Post Screenshot Processing**
        ```
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
          ]
        }
        ```

        **Scenario 2: Self-Diagnosis**
        ```
        {
          "skills": [
            {"skill": "query_logs", "args": {"level": "E", "lines": 50}},
            {"skill": "control_service", "args": {"operation": "check_status"}},
            {"skill": "manage_config", "args": {"operation": "list", "category": "feature"}}
          ]
        }
        ```

        **Scenario 3: Config Tuning**
        ```
        {
          "skills": [
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}},
            {"skill": "manage_config", "args": {"operation": "set", "key": "screenshot_delay", "value": "200"}},
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}}
          ]
        }
        ```

        Note:
        - Chain calls execute in sequence
        - If a Skill fails, you can choose to continue or stop
        - Supports nested calls (use carefully to avoid infinite recursion)
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "skill" to PropertySchema(
                            type = "string",
                            description = "Single Skill name"
                        ),
                        "args" to PropertySchema(
                            type = "object",
                            description = "Skill parameters"
                        ),
                        "skills" to PropertySchema(
                            type = "array",
                            description = "Multiple Skills (chain or parallel call)"
                        ),
                        "parallel" to PropertySchema(
                            type = "string",
                            description = "Whether to execute in parallel (only for skills)"
                        ),
                        "continue_on_error" to PropertySchema(
                            type = "string",
                            description = "Whether to continue on error (only for skills)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        return try {
            when {
                // Single Skill call
                args.containsKey("skill") -> {
                    executeSingleSkill(args)
                }

                // Multiple Skills call
                args.containsKey("skills") -> {
                    executeMultipleSkills(args)
                }

                else -> {
                    SkillResult.error("Missing 'skill' or 'skills' parameter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Self-control execution failed", e)
            SkillResult.error("Self-control failed: ${e.message}")
        }
    }

    /**
     * Execute single Skill
     */
    private suspend fun executeSingleSkill(args: Map<String, Any?>): SkillResult {
        val skillName = args["skill"] as? String
            ?: return SkillResult.error("Missing 'skill' parameter")

        val skillArgs = (args["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()

        Log.d(TAG, "Executing single skill: $skillName with args: $skillArgs")

        // Prevent recursive call to self
        if (skillName == name) {
            return SkillResult.error("Cannot recursively call self_control skill")
        }

        // Call target Skill
        val result = registry.execute(skillName, skillArgs)
            ?: return SkillResult.error("Unknown skill: $skillName")

        return if (result.success) {
            SkillResult.success(
                buildString {
                    appendLine("【Self-Control】Successfully executed $skillName")
                    appendLine()
                    appendLine(result.content)
                },
                mapOf(
                    "skill" to skillName,
                    "result" to result.metadata
                )
            )
        } else {
            SkillResult.error("Skill $skillName failed: ${result.content}")
        }
    }

    /**
     * Execute multiple Skills (chain or parallel)
     */
    private suspend fun executeMultipleSkills(args: Map<String, Any?>): SkillResult {
        val skillsList = args["skills"] as? List<*>
            ?: return SkillResult.error("Invalid 'skills' parameter")

        val parallel = (args["parallel"] as? String)?.toBoolean() ?: false
        val continueOnError = (args["continue_on_error"] as? String)?.toBoolean() ?: true

        Log.d(TAG, "Executing ${skillsList.size} skills (parallel=$parallel, continueOnError=$continueOnError)")

        val results = mutableListOf<Pair<String, SkillResult?>>()

        if (parallel) {
            // Parallel execution (simplified implementation, could use coroutines)
            skillsList.forEach { skillItem ->
                val skillMap = skillItem as? Map<*, *> ?: return@forEach
                val skillName = skillMap["skill"] as? String ?: return@forEach
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)
            }
        } else {
            // Chain execution (in sequence)
            for (skillItem in skillsList) {
                val skillMap = skillItem as? Map<*, *> ?: continue
                val skillName = skillMap["skill"] as? String ?: continue
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                Log.d(TAG, "Executing skill ${results.size + 1}/${skillsList.size}: $skillName")

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)

                // Check for failure
                if (result?.success == false && !continueOnError) {
                    Log.w(TAG, "Skill $skillName failed, stopping chain")
                    break
                }
            }
        }

        // Aggregate results
        val successCount = results.count { it.second?.success == true }
        val failedCount = results.count { it.second?.success == false }

        val summary = buildString {
            appendLine("【Self-Control】Executed ${results.size} Skills")
            appendLine()
            appendLine("Success: $successCount")
            appendLine("Failed: $failedCount")
            appendLine()
            appendLine("Detailed results:")
            appendLine()

            results.forEachIndexed { index, (skillName, result) ->
                val status = if (result?.success == true) "✅" else "❌"
                appendLine("${index + 1}. $status $skillName")
                if (result != null) {
                    appendLine("   ${result.content.lines().firstOrNull() ?: ""}")
                }
                appendLine()
            }
        }

        return if (failedCount == 0) {
            SkillResult.success(
                summary,
                mapOf(
                    "total" to results.size,
                    "success" to successCount,
                    "failed" to failedCount,
                    "results" to results.map { it.first to it.second?.metadata }
                )
            )
        } else {
            SkillResult(
                success = successCount > 0,
                content = summary,
                metadata = mapOf(
                    "total" to results.size,
                    "success" to successCount,
                    "failed" to failedCount,
                    "results" to results.map { it.first to it.second?.metadata }
                )
            )
        }
    }
}
