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
 * 飞书工具基Class
 * All飞书工具的通用Interface
 */
abstract class FeishuToolBase(
    protected val config: FeishuConfig,
    protected val client: FeishuClient
) {
    /**
     * 工具Name
     */
    abstract val name: String

    /**
     * 工具Description
     */
    abstract val description: String

    /**
     * 工具YesNoEnabled
     */
    abstract fun isEnabledd(): Boolean

    /**
     * 执Row工具
     */
    abstract suspend fun execute(args: Map<String, Any?>): Toolresult

    /**
     * GetTool definition(用于 LLM)
     */
    abstract fun getToolDefinition(): ToolDefinition
}

/**
 * 工具执Rowresult
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
 * Tool definition(用于 LLM)
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
