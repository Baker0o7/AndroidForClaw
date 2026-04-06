package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


/**
 * Skill Interface (Self-Control Module InternalReplica)
 *
 * To avoid LoopDependency, the Skill Interface from the app module is copied here.
 * During app module integration, these classes will be replaced with the actual types from the app module.
 */

/**
 * Tool Definition (simplified version, compatible with OpenAI function calling)
 */
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: ParametersSchema
)

data class ParametersSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema>,
    val required: List<String> = emptyList()
)

data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

/**
 * Skill Interface
 */
interface Skill {
    val name: String
    val description: String

    fun getToolDefinition(): ToolDefinition
    suspend fun execute(args: Map<String, Any?>): SkillResult
}

/**
 * Skill execution result
 */
data class SkillResult(
    val success: Boolean,
    val content: String,
    val metadata: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun success(content: String, metadata: Map<String, Any?> = emptyMap()) =
            SkillResult(true, content, metadata)

        fun error(message: String) =
            SkillResult(false, "Error: $message")
    }

    override fun toString(): String = content
}
