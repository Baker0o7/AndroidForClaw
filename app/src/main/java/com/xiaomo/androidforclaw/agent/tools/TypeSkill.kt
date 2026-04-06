package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

/**
 * Type Skill
 * Type text into the currently focused input field
 */
class TypeSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "TypeSkill"
    }

    override val name = "type"
    override val description: String
        get() {
            val isAccessibilityEnableddd = com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isConnected.value == true &&
                                        com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isServiceReady()
            val statusNote = if (!isAccessibilityEnableddd) " ⚠️ **不Available**-AccessibilityServiceNot connected" else " ✅"
            return "Type text into focused input field (must tap input first)$statusNote"
        }

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "text" to PropertySchema("string", "要Input的TextInside容")
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val text = args["text"] as? String

        if (text == null) {
            return Skillresult.error("Missing required parameter: text")
        }

        Log.d(TAG, "Typing text: $text")
        return try {
            // Type text
            DeviceController.inputText(text, context)

            // Wait for input completion + IME response
            val waitTime = (100L + (text.length * 5L).coerceAtMost(300L)).coerceAtLeast(1000L)
            kotlinx.coroutines.delay(waitTime)

            Skillresult.success(
                "Typed: $text (${text.length} chars)",
                mapOf(
                    "text" to text,
                    "length" to text.length,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Type failed", e)
            Skillresult.error("Type failed: ${e.message}")
        }
    }
}
