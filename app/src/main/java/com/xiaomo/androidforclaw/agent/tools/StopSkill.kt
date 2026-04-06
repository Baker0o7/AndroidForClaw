package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Stop skill
 * Stop current task execution
 */
class Stopskill(private val taskDatamanager: TaskDatamanager) : skill {
    companion object {
        private const val TAG = "Stopskill"
    }

    override val name = "stop"
    override val description = "Stop current task execution"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "reason" to Propertyschema("string", "StopReason")
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val reason = args["reason"] as? String ?: "Task completed"

        Log.d(TAG, "Stopping task: $reason")
        return try {
            // Set task status to stopped
            val taskData = taskDatamanager.getCurrentTaskData()
            taskData?.stopRunning(reason)
            skillresult.success(
                "Task stopped: $reason",
                mapOf("stopped" to true)
            )
        } catch (e: exception) {
            Log.e(TAG, "Stop failed", e)
            skillresult.error("Stop failed: ${e.message}")
        }
    }
}
