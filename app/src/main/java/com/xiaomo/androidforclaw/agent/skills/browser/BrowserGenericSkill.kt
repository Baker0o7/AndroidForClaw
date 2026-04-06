package com.xiaomo.androidforclaw.agent.skills.browser

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client-actions.ts
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
 * Generic Browser skill
 * used to wrap other browser tools
 */
class BrowserGenericskill(
    private val context: context,
    override val name: String,
    override val description: String,
    private val parametersDef: Map<String, Propertyschema>,
    private val requiredParams: List<String> = emptyList()
) : skill {

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = parametersDef,
                    required = requiredParams
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        return try {
            val browserClient = BrowsertoolClient(context)
            val result = browserClient.executetoolAsync(name, args)

            if (result.success) {
                skillResult.success(
                    result.data?.get("content")?.toString() ?: result.data.toString(),
                    result.data ?: emptyMap()
                )
            } else {
                skillResult.error(result.error ?: "Execution failed")
            }
        } catch (e: exception) {
            skillResult.error("Failed to execute $name: ${e.message}")
        }
    }
}

/**
 * Factory methods for creating browser skills
 */
object BrowserskillFactory {

    fun createScrollskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_scroll",
        description = "Scroll the browser page. Provide 'direction' (up/down/top/bottom), 'selector' (CSS selector to scroll to), or 'x'/'y' (scroll by pixels). Examples: {\"direction\": \"down\"}, {\"selector\": \"#content\"}, {\"x\": 0, \"y\": 500}",
        parametersDef = mapOf(
            "direction" to Propertyschema("string", "Scroll direction: up, down, top, bottom"),
            "selector" to Propertyschema("string", "CSS selector to scroll to"),
            "x" to Propertyschema("integer", "Horizontal scroll distance"),
            "y" to Propertyschema("integer", "Vertical scroll distance")
        )
    )

    fun createExecuteskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_execute",
        description = "Execute JavaScript in the browser. Provide 'script' (JavaScript code) and optional 'selector' (CSS selector for element context). Returns the execution result. Examples: {\"script\": \"document.title\"}, {\"script\": \"this.value\", \"selector\": \"input[name='q']\"}",
        parametersDef = mapOf(
            "script" to Propertyschema("string", "JavaScript code to execute"),
            "selector" to Propertyschema("string", "Optional CSS selector for context")
        ),
        requiredParams = listOf("script")
    )

    fun createPressskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_press",
        description = "Press a key in the browser. Supported keys: Enter, backspace, Tab, Escape, ArrowUp, Arrownext, ArrowLeft, ArrowRight, etc. Provide 'key' parameter. Example: {\"key\": \"Enter\"}",
        parametersDef = mapOf(
            "key" to Propertyschema("string", "Key name to press")
        ),
        requiredParams = listOf("key")
    )

    fun createScreenshotskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_screenshot",
        description = "Take a screenshot of the browser page. Provide optional 'fullPage' (capture entire page, default: false), 'format' (png/jpeg, default: png), 'quality' (JPEG quality 1-100, default: 80). Returns base64-encoded image data. Example: {\"fullPage\": true, \"format\": \"png\"}",
        parametersDef = mapOf(
            "fullPage" to Propertyschema("boolean", "Capture full page (default: false)"),
            "format" to Propertyschema("string", "Image format: png or jpeg"),
            "quality" to Propertyschema("integer", "JPEG quality 1-100")
        )
    )

    fun createGetCookiesskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_get_cookies",
        description = "Get cookies from the current browser page. Returns all cookies for the current domain. No parameters required. Example: {}",
        parametersDef = emptyMap()
    )

    fun createSetCookiesskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_set_cookies",
        description = "Set cookies in the browser. Provide 'cookies' (list of cookie strings in format \"name=value; path=/; domain=.example.com\"). Example: {\"cookies\": [\"session_id=abc123; path=/\"]}",
        parametersDef = mapOf(
            "cookies" to Propertyschema("array", "List of cookie strings", items = Propertyschema("string", "Cookie string"))
        ),
        requiredParams = listOf("cookies")
    )

    fun createHoverskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_hover",
        description = "Hover over an element in the browser. Provide 'selector' (CSS selector) and optional 'index' (index when multiple elements match, default: 0). Example: {\"selector\": \".dropdown-menu\"}",
        parametersDef = mapOf(
            "selector" to Propertyschema("string", "CSS selector for the element"),
            "index" to Propertyschema("integer", "Index when multiple match")
        ),
        requiredParams = listOf("selector")
    )

    fun createSelectskill(context: context) = BrowserGenericskill(
        context = context,
        name = "browser_select",
        description = "Select options from a dropdown in the browser. Provide 'selector' (CSS selector for select element) and 'values' (list of values to select, supports multi-select). Example: {\"selector\": \"select[name='country']\", \"values\": [\"CN\"]}",
        parametersDef = mapOf(
            "selector" to Propertyschema("string", "CSS selector for select element"),
            "values" to Propertyschema("array", "List of values to select", items = Propertyschema("string", "Select value"))
        ),
        requiredParams = listOf("selector", "values")
    )
}
