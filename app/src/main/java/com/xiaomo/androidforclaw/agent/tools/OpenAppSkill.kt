package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Open App Skill
 * Open a specified app
 */
class OpenAppSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "OpenAppSkill"
    }

    override val name = "open_app"
    override val description = "Open指定的apply程序. Need提供apply的Package name. "

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "package_name" to PropertySchema("string", "apply的Package name, e.g. 'com.android.settings'")
                    ),
                    required = listOf("package_name")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val packageName = args["package_name"] as? String

        if (packageName == null) {
            return Skillresult.error("Missing required parameter: package_name")
        }

        Log.d(TAG, "Opening app: $packageName")
        return try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                // Add flags to handle background launch restrictions
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                // Use withTimeout to prevent blocking indefinitely
                try {
                    withTimeout(5000L) { // 5 second timeout
                        try {
                            context.startActivity(intent)
                            Log.d(TAG, "Activity started successfully")
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Background launch blocked: ${e.message}")
                            // If blocked by BAL restrictions, return error with guidance
                            return@withTimeout Skillresult.error(
                                "Cannot launch app from background due to Android restrictions. " +
                                "Suggestion: Use 'home' tool first to go to launcher, then use 'tap' to click the app icon."
                            )
                        }

                        // Wait for app launch
                        Log.d(TAG, "Waiting for app to launch...")
                        kotlinx.coroutines.delay(1000)

                        Skillresult.success(
                            "App opened: $packageName (waited 1s for launch)",
                            mapOf("package" to packageName, "wait_time_ms" to 1000)
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "App launch timeout after 5s")
                    Skillresult.error("App launch timeout after 5s. The app might be slow to start or blocked.")
                }
            } else {
                Skillresult.error("App not found: $packageName")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Package not found: $packageName", e)
            Skillresult.error("Package not found: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Open app failed", e)
            Skillresult.error("Open app failed: ${e.message}")
        }
    }
}
