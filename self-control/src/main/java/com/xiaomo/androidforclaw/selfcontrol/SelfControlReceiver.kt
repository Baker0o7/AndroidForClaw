package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Self-Control Broadcast Receiver
 *
 * Exposes Self-Control capabilities via Broadcast for external calls (e.g., ADB).
 *
 * Usage:
 *
 * 1. Via ADB broadcast
 * ```bash
 * # Page navigation
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es skill navigate_app \
 *   --es page config
 *
 * # Config Manage - Read
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es skill manage_config \
 *   --es operation get \
 *   --es key exploration_mode
 *
 * # Config Manage - Write
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es skill manage_config \
 *   --es operation set \
 *   --es key exploration_mode \
 *   --es value true
 *
 * # Service control
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es skill control_service \
 *   --es operation hide_float
 *
 * # Log query
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es skill query_logs \
 *   --es level E \
 *   --ei lines 50
 *
 * # List all skills
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es action list_skills
 *
 * # Health check
 * adb shell am broadcast \
 *   -a com.xiaomo.androidforclaw.SELF_CONTROL \
 *   --es action health
 * ```
 *
 * Parameter types:
 * - --es key value   (String)
 * - --ei key value   (Integer)
 * - --el key value   (Long)
 * - --ez key value   (Boolean)
 * - --ef key value   (Float)
 * - --ed key value   (Double)
 *
 * Return results:
 * - View via logcat (TAG: SelfControlReceiver)
 * - Via resultData return (for ordered broadcasts)
 */
class SelfControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SelfControlReceiver"
        const val ACTION_SELF_CONTROL = "com.xiaomo.androidforclaw.SELF_CONTROL"

        // Special actions
        private const val ACTION_LIST_SKILLS = "list_skills"
        private const val ACTION_HEALTH = "health"
    }

    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SELF_CONTROL) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }

        Log.d(TAG, "Received broadcast: ${intent.extras}")

        val action = intent.getStringExtra("action")
        val skillName = intent.getStringExtra("skill")

        // Async execution (to avoid blocking broadcast)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = when {
                    action == ACTION_LIST_SKILLS -> handleListSkills(context)
                    action == ACTION_HEALTH -> handleHealth(context)
                    skillName != null -> handleExecuteSkill(context, skillName, intent)
                    else -> "Error: Missing 'action' or 'skill' parameter"
                }

                // Output result to logcat
                Log.i(TAG, "Result: $result")

                // Set return data (for ordered broadcast)
                pendingResult.resultData = result
                pendingResult.resultCode = if (result.startsWith("Error")) -1 else 0

            } catch (e: Exception) {
                Log.e(TAG, "Execution failed", e)
                pendingResult.resultData = "Error: ${e.message}"
                pendingResult.resultCode = -1
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Execute skill
     */
    private suspend fun handleExecuteSkill(
        context: Context,
        skillName: String,
        intent: Intent
    ): String {
        val registry = SelfControlRegistry(context)

        // Check if skill exists
        if (!registry.contains(skillName)) {
            return "Error: Unknown skill: $skillName. Available: ${registry.getAllSkillNames()}"
        }

        // Extract parameters
        val args = extractArgs(intent)

        Log.d(TAG, "Executing skill: $skillName with args: $args")

        // Execute skill
        val result = registry.execute(skillName, args)

        if (result == null) {
            return "Error: Skill execution returned null"
        }

        // Formatted output
        return if (result.success) {
            buildString {
                appendLine("✅ Success")
                appendLine()
                appendLine(result.content)

                if (result.metadata.isNotEmpty()) {
                    appendLine()
                    appendLine("Metadata: ${gson.toJson(result.metadata)}")
                }
            }
        } else {
            "❌ ${result.content}"
        }
    }

    /**
     * List all skills
     */
    private fun handleListSkills(context: Context): String {
        val registry = SelfControlRegistry(context)
        val skills = registry.getAllSkillNames()
        val toolDefs = registry.getAllToolDefinitions()

        return buildString {
            appendLine("📋 Available Skills (${skills.size})")
            appendLine()

            toolDefs.forEach { tool ->
                appendLine("• ${tool.function.name}")
                appendLine("  ${tool.function.description.lines().first()}")

                if (tool.function.parameters.properties.isNotEmpty()) {
                    val params = tool.function.parameters.properties.keys.joinToString(", ")
                    appendLine("  Parameters: $params")
                }

                appendLine()
            }

            appendLine("Usage:")
            appendLine("  adb shell am broadcast \\")
            appendLine("    -a $ACTION_SELF_CONTROL \\")
            appendLine("    --es skill SKILL_NAME \\")
            appendLine("    --es arg1 value1 \\")
            appendLine("    --ei arg2 value2")
        }
    }

    /**
     * Health check
     */
    private fun handleHealth(context: Context): String {
        val registry = SelfControlRegistry(context)

        return buildString {
            appendLine("❤️ Self-Control Health Check")
            appendLine()
            appendLine("Status: ✅ Healthy")
            appendLine("Skills: ${registry.getAllSkillNames().size}")
            appendLine("Available: ${registry.getAllSkillNames().joinToString(", ")}")
        }
    }

    /**
     * Extract parameters from Intent
     */
    private fun extractArgs(intent: Intent): Map<String, Any?> {
        val args = mutableMapOf<String, Any?>()
        val extras = intent.extras ?: return args

        extras.keySet().forEach { key ->
            // Skip special keys
            if (key in setOf("action", "skill")) {
                return@forEach
            }

            val value = extras.get(key)
            args[key] = value
        }

        return args
    }
}
