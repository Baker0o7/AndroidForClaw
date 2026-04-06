package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Log skill
 * Record log information
 */
class Logskill : skill {
    companion object {
        private const val TAG = "Logskill"
    }

    override val name = "log"
    override val description = "Record log information"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "message" to Propertyschema("string", "LogMessage"),
                        "level" to Propertyschema("string", "Loglevel别: debug, info, warn, error, Default info")
                    ),
                    required = listOf("message")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val message = args["message"] as? String
        val level = args["level"] as? String ?: "info"

        if (message == null) {
            return skillresult.error("Missing required parameter: message")
        }

        return try {
            when (level.lowercase()) {
                "debug" -> Log.d(TAG, message)
                "info" -> Log.i(TAG, message)
                "warn" -> Log.w(TAG, message)
                "error" -> Log.e(TAG, message)
                else -> Log.i(TAG, message)
            }
            skillresult.success("Logged: $message")
        } catch (e: exception) {
            Log.e(TAG, "Log failed", e)
            skillresult.error("Log failed: ${e.message}")
        }
    }
}
