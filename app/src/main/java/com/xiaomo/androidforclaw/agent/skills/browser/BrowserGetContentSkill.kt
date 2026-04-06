package com.xiaomo.androidforclaw.agent.skills.browser

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client-actions-observe.ts
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
 * browser_get_content - Get page content
 */
class BrowserGetContentskill(private val context: context) : skill {
    override val name = "browser_get_content"
    override val description = "Get page content from the browser"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = "Get page content from the browser in various formats. Provide 'format' (text/html/markdown, default: text) and optional 'selector' (CSS selector for specific element). Returns page content, URL, and title. Examples: {\"format\": \"text\"}, {\"format\": \"html\", \"selector\": \"#main-content\"}",
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "format" to Propertyschema(
                            "string",
                            "Content format: text, html, or markdown (default: text)"
                        ),
                        "selector" to Propertyschema(
                            "string",
                            "Optional CSS selector for specific element"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val format = args["format"] as? String ?: "text"
        val selector = args["selector"] as? String

        return try {
            val browserClient = BrowsertoolClient(context)
            val toolArgs = mutableMapOf<String, Any?>("format" to format)
            if (selector != null) {
                toolArgs["selector"] = selector
            }

            val result = browserClient.executetoolAsync("browser_get_content", toolArgs)

            if (result.success) {
                val content = result.data?.get("content") as? String ?: ""
                val url = result.data?.get("url") as? String ?: ""
                val title = result.data?.get("title") as? String ?: ""

                skillResult.success(
                    "Page content retrieved:\nURL: $url\nTitle: $title\n\nContent:\n${content.take(1000)}${if (content.length > 1000) "\n...(truncated)" else ""}",
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Failed to get content")
            }
        } catch (e: exception) {
            skillResult.error("Failed to get content: ${e.message}")
        }
    }
}
