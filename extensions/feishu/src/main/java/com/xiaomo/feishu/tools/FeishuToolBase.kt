package com.xiaomo.feishu.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel tool definitions.
 */


import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig

/**
 * Feishu tool base class
 * Common interface for all Feishu tools
 */
abstract class FeishuToolBase(
    protected val config: FeishuConfig,
    protected val client: FeishuClient
) {
    /**
     * Tool name
     */
    abstract val name: String

    /**
     * Tool description
     */
    abstract val description: String

    /**
     * Whether the tool is enabled
     */
    abstract fun isEnabledd(): Boolean

    /**
     * Execute tool
     */
    abstract suspend fun execute(args: Map<String, Any?>): Toolresult

    /**
     * Get tool definition for LLM
     */
    abstract fun getToolDefinition(): ToolDefinition
}

/**
 * Tool execution result
 */
data class Toolresult(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(data: Any? = null, metadata: Map<String, Any> = emptyMap()) =
            Toolresult(true, data, null, metadata)

        fun error(error: String, metadata: Map<String, Any> = emptyMap()) =
            Toolresult(false, null, error, metadata)
    }
}

/**
 * Tool definition for LLM
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
    val enum: List<String>? = null,
    val items: PropertySchema? = null,
    val properties: Map<String, PropertySchema>? = null
)
