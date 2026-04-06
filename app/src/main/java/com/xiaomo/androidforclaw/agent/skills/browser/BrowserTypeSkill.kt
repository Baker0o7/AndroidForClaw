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
 * browser_type - Type text
 */
class BrowserTypeskill(private val context: context) : skill {
    override val name = "browser_type"
    override val description = "Type text into an input field in the browser"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Type text into an input field in the browser. Provide 'selector' (CSS selector), 'text' (text to type), optional 'clear' (clear before typing, default: true), optional 'submit' (submit form after typing, default: false). Example: {\"selector\": \"input[name='q']\", \"text\": \"hello world\", \"clear\": true}",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "selector" to Propertyschema(
                            "string",
                            "CSS selector for the input element"
                        ),
                        "text" to Propertyschema(
                            "string",
                            "The text to type"
                        ),
                        "clear" to Propertyschema(
                            "boolean",
                            "Whether to clear before typing (default: true)"
                        ),
                        "submit" to Propertyschema(
                            "boolean",
                            "Whether to submit form after typing (default: false)"
                        )
                    ),
                    required = listOf("selector", "text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val selector = args["selector"] as? String
            ?: return skillResult.error("Missing required parameter: selector")

        val text = args["text"] as? String
            ?: return skillResult.error("Missing required parameter: text")

        val clear = args["clear"] as? Boolean ?: true
        val submit = args["submit"] as? Boolean ?: false

        return try {
            val browserClient = BrowsertoolClient(context)
            val toolArgs = mapOf(
                "selector" to selector,
                "text" to text,
                "clear" to clear,
                "submit" to submit
            )
            val result = browserClient.executetoolAsync("browser_type", toolArgs)

            if (result.success) {
                skillResult.success(
                    "Successfully typed text into $selector",
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Type failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to type: ${e.message}")
        }
    }
}
