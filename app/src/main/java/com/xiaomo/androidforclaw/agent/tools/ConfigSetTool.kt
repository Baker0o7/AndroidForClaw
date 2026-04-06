package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.gateway.methods.configMethods
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Write value to /sdcard/.androidforclaw/openclaw.json by path.
 */
class configSettool(
    private val configMethods: configMethods
) : tool {
    override val name = "config_set"
    override val description = "Set a configuration value in openclaw.json by dot path"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "Dot path, e.g. channels.feishu.enabled"),
                        "value" to Propertyschema("string", "Value to write; strings like true/false are also accepted")
                    ),
                    required = listOf("path", "value")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val path = args["path"] as? String
            ?: return toolResult.error("Missing required parameter: path")
        val rawValue = args["value"]
            ?: return toolResult.error("Missing required parameter: value")

        val value: Any? = when (rawValue) {
            is String -> when (rawValue.lowercase()) {
                "true" -> true
                "false" -> false
                else -> rawValue
            }
            else -> rawValue
        }

        val result = configMethods.configSet(mapOf("path" to path, "value" to value))
        return if (result.success) {
            toolResult.success(result.message)
        } else {
            toolResult.error(result.message)
        }
    }
}
