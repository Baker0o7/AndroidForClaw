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
 * Read value from /sdcard/.androidforclaw/openclaw.json by path.
 */
class configGettool(
    private val configMethods: configMethods
) : tool {
    override val name = "config_get"
    override val description = "Read a configuration value from openclaw.json by dot path"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "path" to Propertyschema("string", "Dot path, e.g. channels.feishu.appId")
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolResult {
        val path = args["path"] as? String
            ?: return toolResult.error("Missing required parameter: path")

        val result = configMethods.configGet(mapOf("path" to path))
        return if (result.success) {
            toolResult.success(result.config?.toString() ?: "null")
        } else {
            toolResult.error(result.error ?: "Failed to read config")
        }
    }
}
