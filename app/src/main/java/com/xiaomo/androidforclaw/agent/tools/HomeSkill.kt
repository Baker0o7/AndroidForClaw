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
 * Home skill
 * Press Home button to return to main screen
 */
class Homeskill : skill {
    companion object {
        private const val TAG = "Homeskill"
    }

    override val name = "home"
    override val description = "Press Home button to return to launcher"

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

        Log.d(TAG, "Pressing home button")
        return try {
            val success = AccessibilityProxy.pressHome()
            if (!success) {
                return skillResult.error("Home button press failed")
            }

            // Wait for launcher to load
            kotlinx.coroutines.delay(1000)

            skillResult.success(
                "Home button pressed (waited 1000ms for launcher)",
                mapOf("wait_time_ms" to 1000)
            )
        } catch (e: exception) {
            Log.e(TAG, "Home button press failed", e)
            skillResult.error("Home button press failed: ${e.message}")
        }
    }
}
