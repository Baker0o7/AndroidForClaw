package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Type skill
 * Type text into the currently focused input field
 */
class Typeskill(private val context: context) : skill {
    companion object {
        private const val TAG = "Typeskill"
    }

    override val name = "type"
    override val description: String
        get() {
            val isAccessibilityEnabled = com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isConnected.value == true &&
                                        com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isserviceReady()
            val statusnote = if (!isAccessibilityEnabled) " [WARN] **notAvailable**-Accessibilityservicenot connected" else " [OK]"
            return "Type text into focused input field (must tap input first)$statusnote"
        }

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "text" to Propertyschema("string", "needInputTextcontent")
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val text = args["text"] as? String

        if (text == null) {
            return skillresult.error("Missing required parameter: text")
        }

        Log.d(TAG, "Typing text: $text")
        return try {
            // Type text
            DeviceController.inputText(text, context)

            // Wait for input completion + IME response
            val waitTime = (100L + (text.length * 5L).coerceAtMost(300L)).coerceAtLeast(1000L)
            kotlinx.coroutines.delay(waitTime)

            skillresult.success(
                "Typed: $text (${text.length} chars)",
                mapOf(
                    "text" to text,
                    "length" to text.length,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "Type failed", e)
            skillresult.error("Type failed: ${e.message}")
        }
    }
}
