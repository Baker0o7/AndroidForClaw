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
 * Allows PhoneForClaw's AI Agent to call its own Self-Control functions.
 *
 * This is a meta-level Skill:
 * - Agent calls other Self-Control Skills through this Skill
 * - Enables "AI controlling AI" self-management capability
 * - Supports chained calls and batch operations
 *
 * Use cases:
 * 1. **Self-diagnosis**
 *    Agent detects issues -> calls query_logs -> analyzes errors -> calls manage_config to adjust parameters
 *
 * 2. **Self-optimization**
 *    Agent finds performance issues -> calls manage_config to read current config -> calculates optimal params -> calls manage_config to update
 *
 * 3. **Self-development**
 *    Agent needs to modify config -> calls navigate_app to open config page -> waits for user confirmation
 *
 * 4. **Task execution optimization**
 *    Before screenshot -> calls control_service to hide floating window -> takes screenshot -> calls control_service to show floating window
 *
 * Examples:
 * ```json
 * // Single call
 * {
 *   "skill": "navigate_app",
 *   "args": {"page": "config"}
 * }
 *
 * // Chained calls (execute in order)
 * {
 *   "skills": [
 *     {"skill": "control_service", "args": {"operation": "hide_float"}},
 *     {"skill": "wait", "args": {"duration": 100}},
 *     {"skill": "screenshot", "args": {}},
 *     {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
 *   ]
 * }
 *
 * // Conditional calls (decide based on result)
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
        Allows AI Agent to call its own Self-Control functions.

        This is a meta-level Skill, allowing Agent to control and manage itself:
        - Call other Self-Control Skills
        - Support single call and chained calls
        - Support batch operations

        Available Skills:
        - navigate_app: Page navigation
        - manage_config: Configuration management (get/set/list/delete)
        - control_service: Service control (hide/show/check_status)
        - query_logs: Log query

        Usage:

        1. Single call
        {
          "skill": "navigate_app",
          "args": {"page": "config"}
        }

        2. Chained calls (execute Skills in order)
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "screenshot", "args": {}},
            {"skill": "control_service", "args": {"operation": "show_float"}}
          ]
        }

        3. Batch calls (parallel execution)
        {
          "parallel": true,
          "skills": [
            {"skill": "query_logs", "args": {"level": "E"}},
            {"skill": "control_service", "args": {"operation": "check_status"}}
          ]
        }

        Typical scenarios:

        **Scenario 1: Pre/post screenshot processing**
        ```
        {
          "skills": [
            {"skill": "control_service", "args": {"operation": "hide_float"}},
            {"skill": "control_service", "args": {"operation": "show_float", "delay_ms": 500}}
          ]
        }
        ```

        **Scenario 2: Self-diagnosis**
        ```
        {
          "skills": [
            {"skill": "query_logs", "args": {"level": "E", "lines": 50}},
            {"skill": "control_service", "args": {"operation": "check_status"}},
            {"skill": "manage_config", "args": {"operation": "list", "category": "feature"}}
          ]
        }
        ```

        **Scenario 3: Configuration tuning**
        ```
        {
          "skills": [
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}},
            {"skill": "manage_config", "args": {"operation": "set", "key": "screenshot_delay", "value": "200"}},
            {"skill": "manage_config", "args": {"operation": "get", "key": "screenshot_delay"}}
          ]
        }
        ```

        Notes:
        - Chained calls execute in order
        - If a Skill fails, you can choose to continue or stop
        - Nested calls are supported (use with caution to avoid infinite recursion)
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
                            description = "Skill arguments"
                        ),
                        "skills" to PropertySchema(
                            type = "array",
                            description = "Multiple Skills (chained or parallel)")
                        ),
                        "parallel" to PropertySchema(
                            type = "string",
                            description = "Whether to execute in parallel (for skills only)")
                        ),
                        "continue_on_error" to PropertySchema(
                            type = "string",
                            description = "Whether to continue on error (for skills only)")
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
                // 单个 Skill 调用
                args.containsKey("skill") -> {
                    executeSingleSkill(args)
                }

                // 多个 Skills 调用
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
     * Execute a single Skill
     */
    private suspend fun executeSingleSkill(args: Map<String, Any?>): SkillResult {
        val skillName = args["skill"] as? String
            ?: return SkillResult.error("Missing 'skill' parameter")

        val skillArgs = (args["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()

        Log.d(TAG, "Executing single skill: $skillName with args: $skillArgs")

        // Prevent recursive self-call
        if (skillName == name) {
            return SkillResult.error("Cannot recursively call self_control skill")
        }

        // 调用目标 Skill
        val result = registry.execute(skillName, skillArgs)
            ?: return SkillResult.error("Unknown skill: $skillName")

        return if (result.success) {
            SkillResult.success(
                buildString {
                    appendLine("[Self-Control] Successfully executed $skillName")
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
     * Execute multiple Skills (chained or parallel)
     */
    private suspend fun executeMultipleSkills(args: Map<String, Any?>): SkillResult {
        val skillsList = args["skills"] as? List<*>
            ?: return SkillResult.error("Invalid 'skills' parameter")

        val parallel = (args["parallel"] as? String)?.toBoolean() ?: false
        val continueOnError = (args["continue_on_error"] as? String)?.toBoolean() ?: true

        Log.d(TAG, "Executing ${skillsList.size} skills (parallel=$parallel, continueOnError=$continueOnError)")

        val results = mutableListOf<Pair<String, SkillResult?>>()

        if (parallel) {
            // 并行执行（简化实现，实际可以用 coroutines）
            skillsList.forEach { skillItem ->
                val skillMap = skillItem as? Map<*, *> ?: return@forEach
                val skillName = skillMap["skill"] as? String ?: return@forEach
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)
            }
        } else {
            // 链式执行（按顺序）
            for (skillItem in skillsList) {
                val skillMap = skillItem as? Map<*, *> ?: continue
                val skillName = skillMap["skill"] as? String ?: continue
                val skillArgs = (skillMap["args"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                    ?: emptyMap()

                Log.d(TAG, "Executing skill ${results.size + 1}/${skillsList.size}: $skillName")

                val result = registry.execute(skillName, skillArgs)
                results.add(skillName to result)

                // 检查是否失败
                if (result?.success == false && !continueOnError) {
                    Log.w(TAG, "Skill $skillName failed, stopping chain")
                    break
                }
            }
        }

            // Summarize results
        val successCount = results.count { it.second?.success == true }
        val failedCount = results.count { it.second?.success == false }

        val summary = buildString {
            appendLine("[Self-Control] Executed ${results.size} Skills")
            appendLine()
            appendLine("Succeeded: $successCount")
            appendLine("Failed: $failedCount")
            appendLine()
            appendLine("Details:")
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
