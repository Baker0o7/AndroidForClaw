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
 * Tap Skill
 * Tap at specified screen coordinates
 */
class TapSkill : Skill {
    companion object {
        private const val TAG = "TapSkill"
    }

    override val name = "tap"
    override val description: String
        get() {
            val isAccessibilityEnableddd = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
            val statusNote = if (!isAccessibilityEnableddd) {
                "\n\n⚠️ **当FrontStatus: 不Available** - AccessibilityServiceNot connected"
            } else {
                "\n✅ **当FrontStatus: Available**"
            }
            return "clickScreenUp的坐标位置. 用于click按钮、Input field、List项等可交互Element. **Note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat is可, 不Need再call screenshot(). $statusNote"
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
                        "x" to PropertySchema("integer", "X 坐标"),
                        "y" to PropertySchema("integer", "Y 坐标")
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

        if (x == null || y == null) {
            return Skillresult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Tapping at ($x, $y)")
        return try {
            // Add timeout to prevent indefinite blocking on AIDL calls
            val success = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                AccessibilityProxy.tap(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Tap timeout after 3s")
                return Skillresult.error("Tap operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return Skillresult.error("Tap operation failed")
            }

            // Wait for UI response (animation, page transitions, etc.)
            kotlinx.coroutines.delay(1000)

            Skillresult.success(
                "Tapped at ($x, $y)",
                mapOf("x" to x, "y" to y, "wait_time_ms" to 1000)
            )
        } catch (e: IllegalStateException) {
            Skillresult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed", e)
            Skillresult.error("Tap failed: ${e.message}")
        }
    }
}
