package com.xiaomo.androidforclaw.agent.tools

import android.util.Log
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
    override val description = "长按屏幕上的坐标位置。用于触发长按菜单、删除项目等需要长按操作的场景。"

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
                        "duration" to PropertySchema("integer", "长按持续时间（毫秒），默认 1000")
                    ),
                    required = listOf("x", "y")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return SkillResult.error("Accessibility service not connected")
        }

        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        val duration = (args["duration"] as? Number)?.toLong() ?: 1000L

        if (x == null || y == null) {
            return SkillResult.error("Missing required parameters: x, y")
        }

        Log.d(TAG, "Long pressing at ($x, $y)")
        return try {
            val success = AccessibilityProxy.longPress(x, y)
            if (!success) {
                return SkillResult.error("Long press operation failed")
            }

            // Wait for menu popup or response after long press
            kotlinx.coroutines.delay(200)

            SkillResult.success(
                "Long pressed at ($x, $y)",
                mapOf(
                    "x" to x,
                    "y" to y,
                    "wait_time_ms" to 200
                )
            )
        } catch (e: IllegalStateException) {
            SkillResult.error("Service disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Long press failed", e)
            SkillResult.error("Long press failed: ${e.message}")
        }
    }
}
