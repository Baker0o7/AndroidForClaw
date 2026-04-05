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
 * Self-Control Navigation Skill
 *
 * Exposes PhoneForClaw's own page navigation capabilities, enabling AI Agent to:
 * - Jump to various config pages
 * - Open feature settings
 * - Access logs and history
 * - Manage Channels and sessions
 *
 * Use Cases:
 * - AI self-development iteration (modify config, view logs)
 * - Remote configuration and management
 * - Automated testing and debugging
 */
class NavigationSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "NavigationSkill"

        // Available page targets
        object Pages {
            const val MAIN = "main"                    // Main screen
            const val CONFIG = "config"                // Config page
            const val PERMISSIONS = "permissions"      // Permission management
            const val CHAT_HISTORY = "chat_history"    // Chat history
            const val CHAT_LOG = "chat_log"            // Chat logs
            const val FEISHU_CHANNEL = "feishu"        // Feishu channel
            const val CHANNEL_LIST = "channels"        // Channel list
            const val RESULT = "result"                // Result page
        }
    }

    override val name = "navigate_app"

    override val description = """
        Navigate to various pages within the PhoneForClaw app.

        Available pages:
        - main: Main screen
        - config: Config page (API, model settings)
        - permissions: Permission management page
        - chat_history: Chat history records
        - chat_log: Detailed chat logs
        - feishu: Feishu channel config
        - channels: Channel list management
        - result: Result display page

        Use cases:
        - Modify app config
        - Check permission status
        - View runtime logs
        - Manage channel connections

        Note: App must be in foreground or have floating window permission.
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
                        "page" to PropertySchema(
                            type = "string",
                            description = "Target page name",
                            enum = listOf(
                                Pages.MAIN,
                                Pages.CONFIG,
                                Pages.PERMISSIONS,
                                Pages.CHAT_HISTORY,
                                Pages.CHAT_LOG,
                                Pages.FEISHU_CHANNEL,
                                Pages.CHANNEL_LIST,
                                Pages.RESULT
                            )
                        ),
                        "extras" to PropertySchema(
                            type = "object",
                            description = "Optional Intent extras (JSON object)"
                        )
                    ),
                    required = listOf("page")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val page = args["page"] as? String
            ?: return SkillResult.error("Missing required parameter: page")

        val extras = args["extras"] as? Map<String, Any?>

        return try {
            val intent = createIntentForPage(page)
                ?: return SkillResult.error("Unknown page: $page")

            // Add extra parameters
            extras?.forEach { (key, value) ->
                when (value) {
                    is String -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is Float -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                }
            }

            // Start Activity
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Log.d(TAG, "Successfully navigated to page: $page")

            SkillResult.success(
                "Navigated to page: $page",
                mapOf(
                    "page" to page,
                    "extras" to (extras ?: emptyMap<String, Any>())
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to page: $page", e)
            SkillResult.error("Page navigation failed: ${e.message}")
        }
    }

    private fun createIntentForPage(page: String): Intent? {
        val packageName = context.packageName
        val className = when (page) {
            Pages.MAIN -> "$packageName.ui.activity.MainActivity"
            Pages.CONFIG -> "$packageName.ui.activity.ConfigActivity"
            Pages.PERMISSIONS -> "$packageName.ui.activity.PermissionsActivity"
            Pages.CHAT_HISTORY -> "$packageName.ui.activity.ChatHistoryActivity"
            Pages.CHAT_LOG -> "$packageName.ui.activity.ChatLogActivity"
            Pages.FEISHU_CHANNEL -> "$packageName.ui.activity.FeishuChannelActivity"
            Pages.CHANNEL_LIST -> "$packageName.ui.activity.ChannelListActivity"
            Pages.RESULT -> "$packageName.ui.activity.ResultActivity"
            else -> return null
        }

        return Intent().apply {
            setClassName(packageName, className)
        }
    }
}
