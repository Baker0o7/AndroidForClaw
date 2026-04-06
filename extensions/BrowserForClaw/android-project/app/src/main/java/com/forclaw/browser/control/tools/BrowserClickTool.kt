/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import com.forclaw.browser.control.manager.BrowserManager
import com.forclaw.browser.control.model.Toolresult

/**
 * Browser Click Tool
 *
 * Click element on page
 *
 * Parameters:
 * - selector: String (Required) - CSS selector
 *
 * Return:
 * - selector: String - Used selector
 * - clicked: Boolean - Success or not
 */
class BrowserClickTool : BrowserTool {
    override val name = "browser_click"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val selector = args["selector"] as? String
            ?: return Toolresult.error("Missing required parameter: selector")

        if (selector.isBlank()) {
            return Toolresult.error("Parameter 'selector' cannot be empty")
        }

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val escapedSelector = selector.replace("'", "\\'")
        val script = """
            (function() {
                try {
                    const el = document.querySelector('$escapedSelector');
                    if (el) {
                        el.click();
                        return true;
                    }
                    return false;
                } catch (e) {
                    return false;
                }
            })()
        """.trimIndent()

        // 4. Execute JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val clicked = result?.trim()?.let {
                // evaluateJavascript returns "true" or "false"
                it == "true"
            } ?: false

            // 5. Return result
            return if (clicked) {
                Toolresult.success(
                    "selector" to selector,
                    "clicked" to true
                )
            } else {
                Toolresult.error("Element not found or not clickable: $selector")
            }
        } catch (e: Exception) {
            return Toolresult.error("Click failed: ${e.message}")
        }
    }
}
