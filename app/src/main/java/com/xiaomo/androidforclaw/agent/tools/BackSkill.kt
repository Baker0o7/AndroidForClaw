package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * back skill
 * Press back button
 */
class backskill : skill {
    companion object {
        private const val TAG = "backskill"
    }

    override val name = "back"
    override val description = "Press back button to go to previous screen"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return skillResult.error("Accessibility service not connected")
        }

        Log.d(TAG, "Pressing back button")
        return try {
            val success = AccessibilityProxy.pressback()
            if (!success) {
                return skillResult.error("back button press failed")
            }

            // Wait for page return animation
            kotlinx.coroutines.delay(1000)

            skillResult.success(
                "back button pressed (waited 1000ms for transition)",
                mapOf("wait_time_ms" to 1000)
            )
        } catch (e: exception) {
            Log.e(TAG, "back button press failed", e)
            skillResult.error("back button press failed: ${e.message}")
        }
    }
}
