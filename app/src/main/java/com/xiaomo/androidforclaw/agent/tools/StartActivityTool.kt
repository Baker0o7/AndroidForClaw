package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import android.content.Intent
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Start Activity skill
 * Start specified Activity (supports full ComponentName)
 */
class StartActivitytool(private val context: context) : skill {
    companion object {
        private const val TAG = "StartActivitytool"
    }

    override val name = "start_activity"
    override val description = "Start android Activity by component name"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "component" to Propertyschema(
                            "string",
                            "Full component name (e.g., 'package.name/.ActivityName'). if provided, package and activity are ignored."
                        ),
                        "package" to Propertyschema(
                            "string",
                            "Package name (e.g., 'com.example.app'). used with activity parameter."
                        ),
                        "activity" to Propertyschema(
                            "string",
                            "Activity name (e.g., '.MainActivity' or 'com.example.app.MainActivity'). used with package parameter."
                        ),
                        "wait_ms" to Propertyschema(
                            "number",
                            "Wait time in milliseconds after starting (default: 1000)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val component = args["component"] as? String
        val packageName = args["package"] as? String
        val activityName = args["activity"] as? String
        val waitMs = (args["wait_ms"] as? Number)?.toLong() ?: 1000L

        return try {
            val intent = when {
                // Prefer using component (full format)
                !component.isNullorBlank() -> {
                    Log.d(TAG, "Starting activity with component: $component")
                    Intent.parseUri("intent://#Intent;component=$component;end", 0)
                }
                // use package + activity
                !packageName.isNullorBlank() && !activityName.isNullorBlank() -> {
                    Log.d(TAG, "Starting activity: $packageName/$activityName")
                    Intent().app {
                        setClassName(packageName, activityName)
                    }
                }
                else -> {
                    return skillResult.error("must provide either 'component' or both 'package' and 'activity'")
                }
            }

            intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Log.d(TAG, "Activity started, waiting ${waitMs}ms...")
            kotlinx.coroutines.delay(waitMs)

            skillResult.success(
                "Activity started successfully (waited ${waitMs}ms)",
                mapOf(
                    "component" to (component ?: "$packageName/$activityName"),
                    "wait_time_ms" to waitMs
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "Failed to start activity", e)
            skillResult.error("Failed to start activity: ${e.message}")
        }
    }
}
