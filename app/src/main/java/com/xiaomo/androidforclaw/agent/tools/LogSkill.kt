package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

/**
 * Log Skill
 * Record log information
 */
class LogSkill : Skill {
    companion object {
        private const val TAG = "LogSkill"
    }

    override val name = "log"
    override val description = "Record log information"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "message" to PropertySchema("string", "LogMessage"),
                        "level" to PropertySchema("string", "Log级别: debug, info, warn, error, Default info")
                    ),
                    required = listOf("message")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val message = args["message"] as? String
        val level = args["level"] as? String ?: "info"

        if (message == null) {
            return Skillresult.error("Missing required parameter: message")
        }

        return try {
            when (level.lowercase()) {
                "debug" -> Log.d(TAG, message)
                "info" -> Log.i(TAG, message)
                "warn" -> Log.w(TAG, message)
                "error" -> Log.e(TAG, message)
                else -> Log.i(TAG, message)
            }
            Skillresult.success("Logged: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Log failed", e)
            Skillresult.error("Log failed: ${e.message}")
        }
    }
}
