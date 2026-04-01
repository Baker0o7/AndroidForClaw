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
 * 集中管理 PhoneForClaw 自我控制相关的 Skills：
 * - NavigationSkill: 页面导航
 * - ConfigSkill: 配置管理
 * - ServiceControlSkill: 服务控制
 * - LogQuerySkill: 日志查询
 *
 * 使用方式：
 * ```kotlin
 * val registry = SelfControlRegistry(context)
 * val tools = registry.getAllToolDefinitions()
 * val result = registry.execute("navigate_app", mapOf("page" to "config"))
 * ```
 *
 * 集成到主应用：
 * ```kotlin
 * // 在 SkillRegistry 中注册
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
        // 基础 Self-Control Skills
        "navigate_app" to NavigationSkill(context),
        "manage_config" to ConfigSkill(context),
        "control_service" to ServiceControlSkill(context),
        "query_logs" to LogQuerySkill(context),

        // 元级别 Skills（给 Agent 自己调用）
        "self_control" to InternalSelfControlSkill(context),

        // ADB 远程调用 Skill（给开发电脑使用）
        "adb_self_control" to ADBSelfControlSkill(context)
    )

    /**
     * 获取所有 Self-Control 工具定义
     */
    fun getAllToolDefinitions(): List<ToolDefinition> {
        return skills.values.map { it.getToolDefinition() }
    }

    /**
     * 执行指定的 Self-Control Skill
     *
     * @param name Skill 名称
     * @param args 参数 Map
     * @return SkillResult，如果 Skill 不存在返回 null
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
     * 检查是否包含指定的 Skill
     */
    fun contains(name: String): Boolean {
        return skills.containsKey(name)
    }

    /**
     * 获取所有 Skill 名称列表
     */
    fun getAllSkillNames(): List<String> {
        return skills.keys.toList()
    }

    /**
     * 获取 Self-Control 功能摘要（用于 system prompt）
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== Self-Control Skills ===")
            appendLine()
            appendLine("PhoneForClaw self-control capabilities (${skills.size} total):")
            appendLine()

            skills.values.forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description.lines().first()}")
            }

            appendLine()
            appendLine("Using these tools allows AI Agent to:")
            appendLine("1. Navigate to various app pages for configuration")
            appendLine("2. Read and modify runtime configuration parameters")
            appendLine("3. Control floating window show/hide (e.g., hide before screenshot)")
            appendLine("4. Query runtime logs for self-diagnosis")
            appendLine()
            appendLine("Typical workflow:")
            appendLine("1. navigate_app -> config (open config page)")
            appendLine("2. manage_config -> get/set (view/modify config)")
            appendLine("3. control_service -> hide_float (hide floating window before screenshot)")
            appendLine("4. query_logs -> level=E (view error logs)")
        }
    }
}
