package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import android.content.pm.ApplicationInfo
import android.content.pm.Packagemanager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * List Installed Apps skill
 * Get list of installed applications
 */
class ListInstalledAppsskill(private val context: context) : skill {
    companion object {
        private const val TAG = "ListInstalledAppsskill"
    }

    override val name = "list_installed_apps"
    override val description = "List installed apps with package names"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "include_system" to Propertyschema(
                            "boolean",
                            "Include system apps (default: false)"
                        ),
                        "filter" to Propertyschema(
                            "string",
                            "Filter apps by name or package (optional)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val includeSystem = args["include_system"] as? Boolean ?: false
        val filter = args["filter"] as? String

        return try {
            val pm = context.packagemanager
            val packages = pm.getInstalledApplications(Packagemanager.GET_META_DATA)

            val apps = packages
                .filter { appInfo ->
                    // Filter system apps
                    if (!includeSystem && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                        return@filter false
                    }
                    true
                }
                .mapnotNull { appInfo ->
                    try {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        val packageName = appInfo.packageName
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        // Apply filter
                        if (filter != null && !filter.isBlank()) {
                            if (!label.contains(filter, ignoreCase = true) &&
                                !packageName.contains(filter, ignoreCase = true)
                            ) {
                                return@mapnotNull null
                            }
                        }

                        mapOf(
                            "package" to packageName,
                            "label" to label,
                            "system" to isSystem
                        )
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to get app info for ${appInfo.packageName}", e)
                        null
                    }
                }
                .sortedBy { (it["label"] as String).lowercase() }

            Log.d(TAG, "Found ${apps.size} apps (includeSystem=$includeSystem, filter=$filter)")

            val content = buildString {
                appendLine("[Installed Apps] (${apps.size} total)")
                appendLine()

                if (apps.isEmpty()) {
                    appendLine("No matching apps found")
                } else {
                    apps.forEachIndexed { index, app ->
                        val label = app["label"] as String
                        val packageName = app["package"] as String
                        val isSystem = app["system"] as Boolean

                        val systemTag = if (isSystem) " [System]" else ""
                        appendLine("${index + 1}. $label$systemTag")
                        appendLine("   Package name: $packageName")
                    }
                }
            }

            skillresult.success(
                content,
                mapOf(
                    "count" to apps.size,
                    "apps" to apps
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "Failed to list installed apps", e)
            skillresult.error("Failed to list apps: ${e.message}")
        }
    }
}
