package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client.ts
 */


import android.content.context
import com.xiaomo.androidforclaw.agent.tools.skill
import com.xiaomo.androidforclaw.agent.tools.skillResult
import com.xiaomo.androidforclaw.browser.BrowsertoolClient
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * BrowserforClaw - Browser Control skill
 *
 * This is a unified browser control entry point that encapsulates all browser operation capabilities.
 * Corresponds to the independent browserforclaw project, communicating via HTTP API.
 *
 * Supported operations:
 * - navigate: Navigate to URL
 * - click: Click element
 * - type: Type text
 * - get_content: Get page content
 * - wait: Wait for condition
 * - scroll: Scroll page
 * - execute: Execute JavaScript
 * - press: Press key
 * - screenshot: Take screenshot
 * - get_cookies/set_cookies: Cookie operations
 * - hover: Hover over element
 * - select: Select dropdown option
 */
class BrowserforClawskill(private val context: context) : skill {
    override val name = "browser"
    override val description = "Control browserforclaw to perform web automation tasks"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Control browserforclaw browser for web automation. Unified interface supporting: navigate (to URL), click (element), type (text input), get_content (page content), wait (conditions), scroll (page), execute (JavaScript), press (keys), screenshot, get_cookies/set_cookies, hover, select (dropdown). Pass operation + relevant params (url, selector, text, etc) to browserforclaw.",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "operation" to Propertyschema(
                            "string",
                            "Browser operation: navigate, click, type, get_content, wait, scroll, execute, press, screenshot, get_cookies, set_cookies, hover, select"
                        ),
                        "url" to Propertyschema("string", "URL for navigate operation"),
                        "selector" to Propertyschema("string", "CSS selector for element operations"),
                        "text" to Propertyschema("string", "Text for type operation"),
                        "format" to Propertyschema("string", "Content format for get_content: text, html, markdown"),
                        "direction" to Propertyschema("string", "Scroll direction: up, down, top, bottom"),
                        "script" to Propertyschema("string", "JavaScript code for execute operation"),
                        "key" to Propertyschema("string", "Key name for press operation"),
                        "timeMs" to Propertyschema("integer", "Wait time in milliseconds"),
                        "waitMs" to Propertyschema("integer", "Wait time after navigation"),
                        "timeout" to Propertyschema("integer", "Timeout for wait operations"),
                        "index" to Propertyschema("integer", "Element index when multiple match"),
                        "clear" to Propertyschema("boolean", "Clear field before typing"),
                        "submit" to Propertyschema("boolean", "Submit form after typing"),
                        "fullPage" to Propertyschema("boolean", "Capture full page screenshot"),
                        "cookies" to Propertyschema("array", "Cookie list for set_cookies", items = Propertyschema("string", "Cookie string")),
                        "values" to Propertyschema("array", "Values for select operation", items = Propertyschema("string", "Select value")),
                        "x" to Propertyschema("integer", "X coordinate for scroll"),
                        "y" to Propertyschema("integer", "Y coordinate for scroll")
                    ),
                    required = listOf("operation")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val operation = args["operation"] as? String
            ?: return skillResult.error("Missing required parameter: operation")

        return try {
            val browserClient = BrowsertoolClient(context)

            // Map operation to browserforclaw tool name
            val toolName = "browser_$operation"

            // Remove operation parameter, pass remaining parameters directly to browserforclaw
            val toolArgs = args.filterKeys { it != "operation" }

            val result = browserClient.executetoolAsync(toolName, toolArgs)

            if (result.success) {
                // format return message based on operation type
                val message = when (operation) {
                    "navigate" -> "Successfully navigated to ${args["url"]}"
                    "click" -> "Successfully clicked element: ${args["selector"]}"
                    "type" -> "Successfully typed text into ${args["selector"]}"
                    "get_content" -> {
                        val content = result.data?.get("content") as? String ?: ""
                        val url = result.data?.get("url") as? String ?: ""
                        val title = result.data?.get("title") as? String ?: ""
                        "Page content retrieved:\nURL: $url\nTitle: $title\n\nContent:\n${content.take(1000)}${if (content.length > 1000) "\n...(truncated)" else ""}"
                    }
                    "wait" -> "Wait condition met"
                    "scroll" -> "Successfully scrolled"
                    "execute" -> "JavaScript executed: ${result.data?.get("result")}"
                    "press" -> "Pressed key: ${args["key"]}"
                    "screenshot" -> "Screenshot captured"
                    "get_cookies" -> "Cookies retrieved: ${result.data?.get("cookies")}"
                    "set_cookies" -> "Cookies set successfully"
                    "hover" -> "Hovered over element: ${args["selector"]}"
                    "select" -> "Selected options in ${args["selector"]}"
                    else -> "Operation completed"
                }

                skillResult.success(message, result.data ?: emptyMap())
            } else {
                skillResult.error(result.error ?: "Browser operation failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to execute browser operation: ${e.message}")
        }
    }
}
