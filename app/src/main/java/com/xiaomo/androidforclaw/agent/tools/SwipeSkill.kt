package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Swipe skill
 * Swipe on screen
 */
class Swipeskill : skill {
    companion object {
        private const val TAG = "Swipeskill"
    }

    override val name = "swipe"
    override val description: String
        get() {
            val isAccessibilityEnabled = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isserviceReady()
            val statusnote = if (!isAccessibilityEnabled) " [WARN] **notAvailable**-Accessibilityservicenot connected" else " [OK]"
            return "inScreenUpswipe. 用于滚动页面、Switch tabs, etc. Support swipe up/down/left/right. **note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat iscan, notneed再call screenshot(). $statusnote"
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
                        "start_x" to Propertyschema("integer", "up始 X 坐标"),
                        "start_y" to Propertyschema("integer", "up始 Y 坐标"),
                        "end_x" to Propertyschema("integer", "End X 坐标"),
                        "end_y" to Propertyschema("integer", "End Y 坐标"),
                        "duration" to Propertyschema("integer", "swipe持续Time(毫seconds), Default 300")
                    ),
                    required = listOf("start_x", "start_y", "end_x", "end_y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return skillresult.error("Accessibility service not connected")
        }

        val startX = (args["start_x"] as? Number)?.toInt()
        val startY = (args["start_y"] as? Number)?.toInt()
        val endX = (args["end_x"] as? Number)?.toInt()
        val endY = (args["end_y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 300L

        if (startX == null || startY == null || endX == null || endY == null) {
            return skillresult.error("Missing required parameters: start_x, start_y, end_x, end_y")
        }

        Log.d(TAG, "Swiping from ($startX, $startY) to ($endX, $endY) in ${duration}ms")
        return try {
            // A timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutorNull(5000L) {
                AccessibilityProxy.swipe(startX, startY, endX, endY, duration)
            }

            if (success == null) {
                Log.e(TAG, "Swipe timeout after 5s")
                return skillresult.error("Swipe operation timeout after 5s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return skillresult.error("Swipe operation failed")
            }

            // Wait for swipe completion + UI stabilization (swipe animation + inertial scrolling)
            val waitTime = (duration + 1000L).coerceAtLeast(1000L)
            kotlinx.coroutines.delay(waitTime)

            skillresult.success(
                "Swiped from ($startX, $startY) to ($endX, $endY)",
                mapOf(
                    "start" to "$startX,$startY",
                    "end" to "$endX,$endY",
                    "duration_ms" to duration,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: IllegalStateexception) {
            skillresult.error("service disconnected: ${e.message}")
        } catch (e: exception) {
            Log.e(TAG, "Swipe failed", e)
            skillresult.error("Swipe failed: ${e.message}")
        }
    }
}
