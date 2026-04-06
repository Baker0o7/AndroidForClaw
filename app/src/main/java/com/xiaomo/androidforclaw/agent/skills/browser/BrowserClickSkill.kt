package com.xiaomo.androidforclaw.agent.skills.browser

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client-actions-core.ts
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
 * browser_click - Click element
 */
class BrowserClickskill(private val context: context) : skill {
    override val name = "browser_click"
    override val description = "Click an element in the browser"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Click an element in the browser using a CSS selector. Provide 'selector' (CSS selector like '#login-button' or '.submit-btn') and optional 'index' (index when multiple elements match, default: 0). Example: {\"selector\": \"#search-button\", \"index\": 0}",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "selector" to Propertyschema(
                            "string",
                            "CSS selector for the element"
                        ),
                        "index" to Propertyschema(
                            "integer",
                            "Index when multiple elements match (default: 0)"
                        )
                    ),
                    required = listOf("selector")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val selector = args["selector"] as? String
            ?: return skillResult.error("Missing required parameter: selector")

        val index = (args["index"] as? Number)?.toInt() ?: 0

        return try {
            val browserClient = BrowsertoolClient(context)
            val toolArgs = mapOf(
                "selector" to selector,
                "index" to index
            )
            val result = browserClient.executetoolAsync("browser_click", toolArgs)

            if (result.success) {
                skillResult.success(
                    "Successfully clicked element: $selector",
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Click failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to click: ${e.message}")
        }
    }
}
