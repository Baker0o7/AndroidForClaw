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
 * Long Press Skill
 * Long press at specified screen coordinates
 */
class LongPressSkill : Skill {
    companion object {
        private const val TAG = "LongPressSkill"
    }

    override val name = "long_press"
    override val description = "long pressScreenUp的坐标位置. 用于触发long press菜单、DeleteProject等Needlong pressAction的场景. **Note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat is可, 不Need再call screenshot(). "

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "x" to PropertySchema("integer", "X 坐标"),
                        "y" to PropertySchema("integer", "Y 坐标"),
                        "duration" to PropertySchema("integer", "long press持续Time(毫秒), Default 1000")
                    ),
                    required = listOf("x", "y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return Skillresult.error("Accessibility service not connected")
        }

        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 1000L

        if (x == null || y == null) {
            return Skillresult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Long pressing at ($x, $y)")
        return try {
            // Add timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                AccessibilityProxy.longPress(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Long press timeout after 3s")
                return Skillresult.error("Long press operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return Skillresult.error("Long press operation failed")
            }

            // Wait for menu popup or response after long press
            kotlinx.coroutines.delay(1000)

            Skillresult.success(
                "Long pressed at ($x, $y)",
                mapOf(
                    "x" to x,
                    "y" to y,
                    "wait_time_ms" to 1000
                )
            )
        } catch (e: IllegalStateException) {
            Skillresult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Long press failed", e)
            Skillresult.error("Long press failed: ${e.message}")
        }
    }
}
