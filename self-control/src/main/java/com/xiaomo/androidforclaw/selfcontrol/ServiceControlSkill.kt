package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaomo.androidforclaw.selfcontrol.Skill
import com.xiaomo.androidforclaw.selfcontrol.SkillResult
import com.xiaomo.androidforclaw.selfcontrol.FunctionDefinition
import com.xiaomo.androidforclaw.selfcontrol.ParametersSchema
import com.xiaomo.androidforclaw.selfcontrol.PropertySchema
import com.xiaomo.androidforclaw.selfcontrol.ToolDefinition

/**
 * Self-Control Service Management Skill
 *
 * Controls PhoneForClaw services (floating window, Accessibility, etc.), enabling AI Agent to:
 * - Start/stop floating window service
 * - Show/hide floating window
 * - Check service running status
 * - Manage background services
 *
 * Usage scenarios:
 * - Hide floating window before screenshot
 * - Show results after task completion
 * - Control UI during automated testing
 * - Remote service management
 */
class ServiceControlSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ServiceControlSkill"

        object Operations {
            const val SHOW_FLOAT = "show_float"        // Show floating window
            const val HIDE_FLOAT = "hide_float"        // Hide floating window
            const val START_FLOAT = "start_float"      // Start floating window service
            const val STOP_FLOAT = "stop_float"        // Stop floating window service
            const val CHECK_STATUS = "check_status"    // Check service status
        }
    }

    override val name = "control_service"

    override val description = """
        Controls PhoneForClaw services and UI components.

        Supported operations:
        - show_float: Show floating window (don't start service)
        - hide_float: Hide floating window (don't stop service)
        - start_float: Start floating window service
        - stop_float: Stop floating window service
        - check_status: Check service running status

        Usage scenarios:
        - Hide floating window before screenshot: {"operation": "hide_float"}
        - Show results after task completion: {"operation": "show_float"}
        - Clean up background services: {"operation": "stop_float"}

        Note: Some operations require floating window permission.
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "operation" to PropertySchema(
                            type = "string",
                            description = "Operation type",
                            enum = listOf(
                                Operations.SHOW_FLOAT,
                                Operations.HIDE_FLOAT,
                                Operations.START_FLOAT,
                                Operations.STOP_FLOAT,
                                Operations.CHECK_STATUS
                            )
                        ),
                        "delay_ms" to PropertySchema(
                            type = "integer",
                            description = "Delay in milliseconds, used for show/hide operations"
                        )
                    ),
                    required = listOf("operation")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val operation = args["operation"] as? String
            ?: return SkillResult.error("Missing required parameter: operation")

        val delayMs = (args["delay_ms"] as? Number)?.toLong() ?: 0L

        return try {
            when (operation) {
                Operations.SHOW_FLOAT -> handleShowFloat(delayMs)
                Operations.HIDE_FLOAT -> handleHideFloat(delayMs)
                Operations.START_FLOAT -> handleStartFloat()
                Operations.STOP_FLOAT -> handleStopFloat()
                Operations.CHECK_STATUS -> handleCheckStatus()
                else -> SkillResult.error("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service control failed: $operation", e)
            SkillResult.error("Service control failed: ${e.message}")
        }
    }

    private fun handleShowFloat(delayMs: Long): SkillResult {
        return try {
            // Manage floating window through MyApplication
            val appClass = Class.forName("${context.packageName}.core.MyApplication")
            val method = appClass.getDeclaredMethod(
                "manageFloatingWindow",
                Boolean::class.java,
                Long::class.java,
                String::class.java,
                Function0::class.java
            )

            method.invoke(null, true, delayMs, "SelfControl: show_float", null)

            SkillResult.success(
                "Floating window shown${if (delayMs > 0) " (delay ${delayMs}ms)" else ""}",
                mapOf("operation" to "show_float", "delay_ms" to delayMs)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating window", e)
            SkillResult.error("Failed to show floating window: ${e.message}")
        }
    }

    private fun handleHideFloat(delayMs: Long): SkillResult {
        return try {
            val appClass = Class.forName("${context.packageName}.core.MyApplication")
            val method = appClass.getDeclaredMethod(
                "manageFloatingWindow",
                Boolean::class.java,
                Long::class.java,
                String::class.java,
                Function0::class.java
            )

            method.invoke(null, false, delayMs, "SelfControl: hide_float", null)

            SkillResult.success(
                "Floating window hidden${if (delayMs > 0) " (delay ${delayMs}ms)" else ""}",
                mapOf("operation" to "hide_float", "delay_ms" to delayMs)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide floating window", e)
            SkillResult.error("Failed to hide floating window: ${e.message}")
        }
    }

    private fun handleStartFloat(): SkillResult {
        return try {
            val serviceClass = Class.forName("${context.packageName}.service.FloatingWindowService")
            val intent = Intent(context, serviceClass)
            context.startService(intent)

            SkillResult.success(
                "Floating window service started",
                mapOf("operation" to "start_float")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start floating window service", e)
            SkillResult.error("Failed to start floating window service: ${e.message}")
        }
    }

    private fun handleStopFloat(): SkillResult {
        return try {
            val serviceClass = Class.forName("${context.packageName}.service.FloatingWindowService")
            val intent = Intent(context, serviceClass)
            context.stopService(intent)

            SkillResult.success(
                "Floating window service stopped",
                mapOf("operation" to "stop_float")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop floating window service", e)
            SkillResult.error("Failed to stop floating window service: ${e.message}")
        }
    }

    private fun handleCheckStatus(): SkillResult {
        return try {
            // Check service status through ActivityManager
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)

            val floatingWindowRunning = services.any {
                it.service.className.contains("FloatingWindowService")
            }

            val accessibilityRunning = services.any {
                it.service.className.contains("PhoneAccessibilityService")
            }

            val status = buildString {
                appendLine("【Service Status】")
                appendLine("Floating Window: ${if (floatingWindowRunning) "Running ✓" else "Stopped ✗"}")
                appendLine("Accessibility: ${if (accessibilityRunning) "Running ✓" else "Stopped ✗"}")
            }

            SkillResult.success(
                status,
                mapOf(
                    "floating_window" to floatingWindowRunning,
                    "accessibility" to accessibilityRunning
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check service status", e)
            SkillResult.error("Failed to check service status: ${e.message}")
        }
    }
}
