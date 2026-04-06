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
 * Tap skill
 * Tap at specified screen coordinates
 */
class Tapskill : skill {
    companion object {
        private const val TAG = "Tapskill"
    }

    override val name = "tap"
    override val description: String
        get() {
            val isAccessibilityEnabled = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isserviceReady()
            val statusnote = if (!isAccessibilityEnabled) {
                "\n\n[WARN] **whenFrontStatus: notAvailable** - Accessibilityservicenot connected"
            } else {
                "\n[OK] **whenFrontStatus: Available**"
            }
            return "clickScreenUp坐标position置. 用于clickbutton、Input field、List项等caninteractionElement. **note**: ActionScreenFrontuse get_view_tree() Get UI ElementInfothat iscan, notneed再call screenshot(). $statusnote"
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
                        "x" to Propertyschema("integer", "X 坐标"),
                        "y" to Propertyschema("integer", "Y 坐标")
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

        if (x == null || y == null) {
            return skillresult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Tapping at ($x, $y)")
        return try {
            // A timeout to prevent indefinite blocking on AIDL calls
            val success = kotlinx.coroutines.withTimeoutorNull(3000L) {
                AccessibilityProxy.tap(x, y)
            }

            if (success == null) {
                Log.e(TAG, "Tap timeout after 3s")
                return skillresult.error("Tap operation timeout after 3s. Accessibility service may be unresponsive.")
            }

            if (!success) {
                return skillresult.error("Tap operation failed")
            }

            // Wait for UI response (animation, page transitions, etc.)
            kotlinx.coroutines.delay(1000)

            skillresult.success(
                "Tapped at ($x, $y)",
                mapOf("x" to x, "y" to y, "wait_time_ms" to 1000)
            )
        } catch (e: IllegalStateexception) {
            skillresult.error("service disconnected: ${e.message}")
        } catch (e: exception) {
            Log.e(TAG, "Tap failed", e)
            skillresult.error("Tap failed: ${e.message}")
        }
    }
}
