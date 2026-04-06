package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

/**
 * Swipe Skill
 * Swipe on screen
 */
class SwipeSkill : Skill {
    companion object {
        private const val TAG = "SwipeSkill"
    }

    override val name = "swipe"
    override val description: String
        get() {
            val isAccessibilityEnableddd = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
            val statusNote = if (!isAccessibilityEnableddd) " ⚠️ **不Available**-AccessibilityServiceNot connected" else " ✅"
            return "在ScreenUpswipe. 用于滚动页面、Switch tabs, etc. Support swipe up/down/left/right. **Note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat is可, 不Need再call screenshot(). $statusNote"
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
                        "start_x" to PropertySchema("integer", "起始 X 坐标"),
                        "start_y" to PropertySchema("integer", "起始 Y 坐标"),
                        "end_x" to PropertySchema("integer", "End X 坐标"),
                        "end_y" to PropertySchema("integer", "End Y 坐标"),
                        "duration" to PropertySchema("integer", "swipe持续Time(毫秒), Default 300")
                    ),
                    required = listOf("start_x", "start_y", "end_x", "end_y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return Skillresult.error("Accessibility service not connected")
        }

        val startX = (args["start_x"] as? Number)?.toInt()
        val startY = (args["start_y"] as? Number)?.toInt()
        val endX = (args["end_x"] as? Number)?.toInt()
        val endY = (args["end_y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 300L

        if (startX == null || startY == null || endX == null || endY == null) {
            return Skillresult.error("Missing required parameters: start_x, start_y, end_x, end_y")
        }

        Log.d(TAG, "Swiping from ($startX, $startY) to ($endX, $endY) in ${duration}ms")
        return try {
            // Add timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                AccessibilityProxy.swipe(startX, startY, endX, endY, duration)
            }

            if (success == null) {
                Log.e(TAG, "Swipe timeout after 5s")
                return Skillresult.error("Swipe operation timeout after 5s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return Skillresult.error("Swipe operation failed")
            }

            // Wait for swipe completion + UI stabilization (swipe animation + inertial scrolling)
            val waitTime = (duration + 1000L).coerceAtLeast(1000L)
            kotlinx.coroutines.delay(waitTime)

            Skillresult.success(
                "Swiped from ($startX, $startY) to ($endX, $endY)",
                mapOf(
                    "start" to "$startX,$startY",
                    "end" to "$endX,$endY",
                    "duration_ms" to duration,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: IllegalStateException) {
            Skillresult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed", e)
            Skillresult.error("Swipe failed: ${e.message}")
        }
    }
}
