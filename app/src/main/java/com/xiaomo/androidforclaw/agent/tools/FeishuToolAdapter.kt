package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.logging.Log

/**
 * Adapter: Bridges Feishu extension tools into the main toolRegistry
 *
 * Problem: FeishutoolBase (extensions/feishu) and tool (app) have similar interfaces
 * but different type systems (different toolDefinition, toolResult classes).
 * This adapter converts between them so feishu tools (doc, wiki, drive, bitable, etc.)
 * are available to the agentloop.
 *
 * Aligned with OpenClaw: extension tools are automatically registered when the channel starts.
 */
class FeishutoolAdapter(
    private val feishutool: com.xiaomo.feishu.tools.FeishutoolBase
) : tool {

    companion object {
        private const val TAG = "FeishutoolAdapter"
    }

    override val name: String = feishutool.name

    override val description: String = feishutool.description

    override fun gettoolDefinition(): com.xiaomo.androidforclaw.providers.toolDefinition {
        val feishuDef = feishutool.gettoolDefinition()
        return com.xiaomo.androidforclaw.providers.toolDefinition(
            type = feishuDef.type,
            function = com.xiaomo.androidforclaw.providers.FunctionDefinition(
                name = feishuDef.function.name,
                description = feishuDef.function.description,
                parameters = convertParametersschema(feishuDef.function.parameters)
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        return try {
            val feishuResult = feishutool.execute(args)

            if (feishuResult.success) {
                skillResult.success(
                    content = feishuResult.data?.toString() ?: "OK",
                    metadata = feishuResult.metadata.mapValues { it.value as Any? }
                )
            } else {
                val meta = feishuResult.metadata
                val detailedError = feishuResult.error
                    ?: (meta["message"] as? String)
                    ?: (meta["error"] as? String)
                    ?: (meta["status"]?.toString()?.let { "HTTP $it" })
                    ?: feishuResult.data?.toString()
                    ?: "Unknown error"

                skillResult.error(message = detailedError)
            }
        } catch (e: exception) {
            Log.e(TAG, "Feishu tool execution failed: $name", e)
            skillResult.error("Feishu tool error: ${e.message}")
        }
    }

    private fun convertParametersschema(
        feishuschema: com.xiaomo.feishu.tools.Parametersschema
    ): com.xiaomo.androidforclaw.providers.Parametersschema {
        return com.xiaomo.androidforclaw.providers.Parametersschema(
            type = feishuschema.type,
            properties = feishuschema.properties.mapValues { (_, prop) ->
                convertPropertyschema(prop)
            },
            required = feishuschema.required
        )
    }

    private fun convertPropertyschema(
        prop: com.xiaomo.feishu.tools.Propertyschema
    ): com.xiaomo.androidforclaw.providers.Propertyschema {
        return com.xiaomo.androidforclaw.providers.Propertyschema(
            type = prop.type,
            description = prop.description,
            enum = prop.enum,
            items = prop.items?.let { convertPropertyschema(it) },
            properties = prop.properties?.mapValues { (_, child) -> convertPropertyschema(child) }
        )
    }
}

/**
 * Register all enabled feishu tools into a toolRegistry
 *
 * @param registry The main toolRegistry to register into
 * @param feishutoolRegistry The feishu extension's tool registry
 * @return Number of tools registered
 */
fun registerFeishutools(
    registry: toolRegistry,
    feishutoolRegistry: com.xiaomo.feishu.tools.FeishutoolRegistry
): Int {
    var count = 0
    for (tool in feishutoolRegistry.getAlltools()) {
        if (tool.isEnabled()) {
            val adapter = FeishutoolAdapter(tool)
            registry.register(adapter)
            count++
        }
    }
    Log.i("FeishutoolAdapter", "[OK] Registered $count feishu tools into toolRegistry")
    return count
}
