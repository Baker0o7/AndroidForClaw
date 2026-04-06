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
import kotlinx.coroutines.delay

/**
 * Wait skill
 * Wait for specified duration
 */
class Waitskill : skill {
    companion object {
        private const val TAG = "Waitskill"
    }

    override val name = "wait"
    override val description = "Wait for specified duration in seconds"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "seconds" to PropertySchema("number", "Number of seconds to wait")
                    ),
                    required = listOf("seconds")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val seconds = (args["seconds"] as? Number)?.toDouble()

        if (seconds == null) {
            return skillresult.error("Missing required parameter: seconds")
        }

        val milliseconds = (seconds * 1000).toLong()
        Log.d(TAG, "Waiting for $seconds seconds")
        return try {
            delay(milliseconds)
            skillresult.success("Waited for $seconds seconds")
        } catch (e: exception) {
            Log.e(TAG, "Wait failed", e)
            skillresult.error("Wait failed: ${e.message}")
        }
    }
}
