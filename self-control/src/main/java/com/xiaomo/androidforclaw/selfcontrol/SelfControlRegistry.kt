package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.xiaomo.androidforclaw.selfcontrol.Skill
import com.xiaomo.androidforclaw.selfcontrol.SkillResult
import com.xiaomo.androidforclaw.selfcontrol.ToolDefinition

/**
 * Self-Control Skill Registry
 *
 * Centralized management of PhoneForClaw self-control related Skills:
 * - NavigationSkill: Page navigation
 * - ConfigSkill: Configuration management
 * - ServiceControlSkill: Service control
 * - LogQuerySkill: Log query
 *
 * Usage:
 * ```kotlin
 * val registry = SelfControlRegistry(context)
 * val tools = registry.getAllToolDefinitions()
 * val result = registry.execute("navigate_app", mapOf("page" to "config"))
 * ```
 *
 * Integration into main app:
 * ```kotlin
 * // Register in SkillRegistry
 * class SkillRegistry(...) {
 *     private val selfControlRegistry = SelfControlRegistry(context)
 *
 *     fun getAllToolDefinitions(): List<ToolDefinition> {
 *         return baseSkills + selfControlRegistry.getAllToolDefinitions()
 *     }
 *
 *     suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
 *         return selfControlRegistry.execute(name, args)
 *             ?: baseSkills[name]?.execute(args)
 *             ?: SkillResult.error("Unknown skill: $name")
 *     }
 * }
 * ```
 */
class SelfControlRegistry(private val context: Context) {
    companion object {
        private const val TAG = "SelfControlRegistry"
    }

    private val skills: Map<String, Skill> = mapOf(
        // Basic Self-Control Skills
        "navigate_app" to NavigationSkill(context),
        "manage_config" to ConfigSkill(context),
        "control_service" to ServiceControlSkill(context),
        "query_logs" to LogQuerySkill(context),

        // Meta-level Skills (for Agent to call itself)
        "self_control" to InternalSelfControlSkill(context),

        // ADB remote call Skill (for developer machine)
        "adb_self_control" to ADBSelfControlSkill(context)
    )

    /**
     * Get all Self-Control tool definitions
     */
    fun getAllToolDefinitions(): List<ToolDefinition> {
        return skills.values.map { it.getToolDefinition() }
    }

    /**
     * Execute specified Self-Control Skill
     *
     * @param name Skill name
     * @param args Parameters Map
     * @return SkillResult, returns null if Skill doesn't exist
     */
    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult? {
        val skill = skills[name] ?: return null

        Log.d(TAG, "Executing self-control skill: $name")

        return try {
            skill.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Self-control skill execution failed: $name", e)
            SkillResult.error("Skill execution failed: ${e.message}")
        }
    }

    /**
     * Check if contains specified Skill
     */
    fun contains(name: String): Boolean {
        return skills.containsKey(name)
    }

    /**
     * Get all Skill names list
     */
    fun getAllSkillNames(): List<String> {
        return skills.keys.toList()
    }

    /**
     * Get Self-Control feature summary (for system prompt)
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== Self-Control Skills ===")
            appendLine()
            appendLine("PhoneForClaw Self-Control Capabilities (${skills.size} total):")
            appendLine()

            skills.values.forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description.lines().first()}")
            }

            appendLine()
            appendLine("Using these tools, AI Agent can:")
            appendLine("1. Navigate to various pages in the app for configuration")
            appendLine("2. Read and modify runtime configuration parameters")
            appendLine("3. Control floating window show/hide (e.g., hide before screenshot)")
            appendLine("4. Query logs for self-diagnosis")
            appendLine()
            appendLine("Typical usage flow:")
            appendLine("1. navigate_app → config (open config page)")
            appendLine("2. manage_config → get/set (view/modify config)")
            appendLine("3. control_service → hide_float (hide floating window before screenshot)")
            appendLine("4. query_logs → level=E (view error logs)")
        }
    }
}
