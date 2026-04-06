package com.xiaomo.androidforclaw.agent.skills.browser

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client-actions-url.ts
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
 * browser_navigate - Navigate to URL
 *
 * Corresponds to OpenClaw's navigate tool
 */
class BrowserNavigateskill(private val context: context) : skill {
    override val name = "browser_navigate"
    override val description = "Navigate to a URL in the browser"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Navigate to a URL in the browserforclaw browser. Provide 'url' (must start with http:// or https://) and optional 'waitMs' (wait time after navigation in milliseconds). Example: {\"url\": \"https://google.com\", \"waitMs\": 2000}",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "url" to Propertyschema(
                            "string",
                            "The URL to navigate to (e.g., https://example.com)"
                        ),
                        "waitMs" to Propertyschema(
                            "integer",
                            "Optional wait time after navigation in milliseconds"
                        )
                    ),
                    required = listOf("url")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val url = args["url"] as? String
            ?: return skillResult.error("Missing required parameter: url")

        val waitMs = (args["waitMs"] as? Number)?.toLong()

        return try {
            val browserClient = BrowsertoolClient(context)
            val toolArgs = mutableMapOf<String, Any?>("url" to url)
            if (waitMs != null) {
                toolArgs["waitMs"] = waitMs
            }

            val result = browserClient.executetoolAsync("browser_navigate", toolArgs)

            if (result.success) {
                skillResult.success(
                    "Successfully navigated to $url",
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Navigation failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to navigate: ${e.message}")
        }
    }
}
