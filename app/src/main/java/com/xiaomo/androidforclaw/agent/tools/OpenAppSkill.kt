package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.app.PendingIntent
import android.content.context
import android.content.Intent
import android.content.pm.Packagemanager
import android.os.Build
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Timeoutcancellationexception

/**
 * Open App skill
 * Open a specified app
 */
class OpenAppskill(private val context: context) : skill {
    companion object {
        private const val TAG = "OpenAppskill"
    }

    override val name = "open_app"
    override val description = "Open a specified app. Requires app package name."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "package_name" to Propertyschema("string", "appPackage name, e.g. 'com.android.settings'")
                    ),
                    required = listOf("package_name")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val packageName = args["package_name"] as? String

        if (packageName == null) {
            return skillresult.error("Missing required parameter: package_name")
        }

        Log.d(TAG, "Opening app: $packageName")
        return try {
            val packagemanager = context.packagemanager
            val intent = packagemanager.getLaunchIntentforPackage(packageName)

            if (intent != null) {
                // A flags to handle background launch restrictions
                intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.aFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.aFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                // use withTimeout to prevent blocking indefinitely
                try {
                    withTimeout(5000L) { // 5 second timeout
                        try {
                            context.startActivity(intent)
                            Log.d(TAG, "Activity started successfully")
                        } catch (e: Securityexception) {
                            Log.w(TAG, "background launch blocked: ${e.message}")
                            // if blocked by BAL restrictions, return error with guidance
                            return@withTimeout skillresult.error(
                                "Cannot launch app from background due to Android restrictions. " +
                                "Suggestion: Use 'home' tool first to go to launcher, then use 'tap' to click the app icon."
                            )
                        }

                        // Wait for app launch
                        Log.d(TAG, "Waiting for app to launch...")
                        kotlinx.coroutines.delay(1000)

                        skillresult.success(
                            "App opened: $packageName (waited 1s for launch)",
                            mapOf("package" to packageName, "wait_time_ms" to 1000)
                        )
                    }
                } catch (e: Timeoutcancellationexception) {
                    Log.e(TAG, "App launch timeout after 5s")
                    skillresult.error("App launch timeout after 5s. The app might be slow to start or blocked.")
                }
            } else {
                skillresult.error("App not found: $packageName")
            }
        } catch (e: Packagemanager.NamenotFoundexception) {
            Log.e(TAG, "Package not found: $packageName", e)
            skillresult.error("Package not found: $packageName")
        } catch (e: exception) {
            Log.e(TAG, "Open app failed", e)
            skillresult.error("Open app failed: ${e.message}")
        }
    }
}
