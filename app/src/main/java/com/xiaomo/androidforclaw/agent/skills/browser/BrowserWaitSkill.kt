package com.xiaomo.androidforclaw.agent.skills.browser

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client-actions-state.ts
 */


import android.content.context
import com.xiaomo.androidforclaw.agent.tools.skill
import com.xiaomo.androidforclaw.agent.tools.skillResult
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.browser.BrowsertoolClient

/**
 * browser_wait - Wait for condition
 *
 * Supports 6 wait modes:
 * 1. timeMs - Wait for specified time
 * 2. selector - Wait for element to appear
 * 3. text - Wait for text to appear
 * 4. url - Wait for URL match
 * 5. js - Wait for JavaScript condition to be true
 * 6. navigation - Wait for page navigation to complete
 */
class BrowserWaitskill(private val context: context) : skill {
    override val name = "browser_wait"
    override val description = "Wait for a condition in the browser"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Wait for various conditions in the browser. 6 wait modes: (1) timeMs - wait for milliseconds, (2) selector - wait for element to appear, (3) text - wait for text on page, (4) url - wait for URL match, (5) js - wait for JavaScript condition, (6) navigation - wait for page navigation. All modes support optional 'timeout' parameter (default: 10000ms). Examples: {\"timeMs\": 2000}, {\"selector\": \"#login-form\", \"timeout\": 5000}, {\"text\": \"Welcome\"}, {\"url\": \"/dashboard\"}",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "timeMs" to Propertyschema(
                            "integer",
                            "Wait for specified milliseconds"
                        ),
                        "selector" to Propertyschema(
                            "string",
                            "CSS selector to wait for"
                        ),
                        "text" to Propertyschema(
                            "string",
                            "Text to wait for on the page"
                        ),
                        "url" to Propertyschema(
                            "string",
                            "URL pattern to wait for"
                        ),
                        "js" to Propertyschema(
                            "string",
                            "JavaScript condition to wait for"
                        ),
                        "navigation" to Propertyschema(
                            "boolean",
                            "Wait for page navigation to complete"
                        ),
                        "timeout" to Propertyschema(
                            "integer",
                            "Timeout in milliseconds (default: 10000)"
                        )
                    ),
                    required = emptyList()  // At least one wait condition must be provided
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 10000L

        return try {
            val browserClient = BrowsertoolClient(context)

            val result = when {
                args.containsKey("timeMs") -> {
                    val timeMs = (args["timeMs"] as? Number)?.toLong()
                        ?: return skillResult.error("Invalid timeMs parameter")
                    browserClient.waitTime(timeMs)
                }
                args.containsKey("selector") -> {
                    val selector = args["selector"] as? String
                        ?: return skillResult.error("Invalid selector parameter")
                    browserClient.waitforSelector(selector, timeout)
                }
                args.containsKey("text") -> {
                    val text = args["text"] as? String
                        ?: return skillResult.error("Invalid text parameter")
                    browserClient.waitforText(text, timeout)
                }
                args.containsKey("url") -> {
                    val url = args["url"] as? String
                        ?: return skillResult.error("Invalid url parameter")
                    browserClient.waitforUrl(url, timeout)
                }
                args.containsKey("js") || args.containsKey("navigation") -> {
                    // These modes use the generic executetoolAsync
                    browserClient.executetoolAsync("browser_wait", args, timeout)
                }
                else -> {
                    return skillResult.error("No wait condition specified. use one of: timeMs, selector, text, url, js, navigation")
                }
            }

            if (result.success) {
                skillResult.success(
                    "Wait condition met",
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Wait failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to wait: ${e.message}")
        }
    }
}
