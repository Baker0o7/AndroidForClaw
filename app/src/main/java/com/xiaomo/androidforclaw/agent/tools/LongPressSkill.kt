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
 * Long Press skill
 * Long press at specified screen coordinates
 */
class LongPressskill : skill {
    companion object {
        private const val TAG = "LongPressskill"
    }

    override val name = "long_press"
    override val description = "long pressScreenUp坐标position置. 用于触发long pressmenu、DeleteProject等needlong pressAction场景. **note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat iscan, notneed再call screenshot(). "

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "x" to Propertyschema("integer", "X 坐标"),
                        "y" to Propertyschema("integer", "Y 坐标"),
                        "duration" to Propertyschema("integer", "long press持续Time(毫seconds), Default 1000")
                    ),
                    required = listOf("x", "y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return skillresult.error("Accessibility service not connected")
        }

        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 1000L

        if (x == null || y == null) {
            return skillresult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Long pressing at ($x, $y)")
        return try {
            // A timeout to prevent indefinite blocking
            val success = kotlinx.coroutines.withTimeoutorNull(3000L) {
                AccessibilityProxy.longPress(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Long press timeout after 3s")
                return skillresult.error("Long press operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return skillresult.error("Long press operation failed")
            }

            // Wait for menu popup or response after long press
            kotlinx.coroutines.delay(1000)

            skillresult.success(
                "Long pressed at ($x, $y)",
                mapOf(
                    "x" to x,
                    "y" to y,
                    "wait_time_ms" to 1000
                )
            )
        } catch (e: IllegalStateexception) {
            skillresult.error("service disconnected: ${e.message}")
        } catch (e: exception) {
            Log.e(TAG, "Long press failed", e)
            skillresult.error("Long press failed: ${e.message}")
        }
    }
}
