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
 * 浏览器click工具
 *
 * click页面Up的Element
 *
 * Parameters:
 * - selector: String (Required) - CSS choose器
 *
 * Return:
 * - selector: String - use的choose器
 * - clicked: Boolean - YesNoSuccessclick
 */
class BrowserClickTool : BrowserTool {
    override val name = "browser_click"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
        val selector = args["selector"] as? String
            ?: return Toolresult.error("Missing required parameter: selector")

        if (selector.isBlank()) {
            return Toolresult.error("Parameter 'selector' cannot be empty")
        }

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
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

        // 4. 执Row JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val clicked = result?.trim()?.let {
                // evaluateJavascript Return的YesString "true" 或 "false"
                it == "true"
            } ?: false

            // 5. Returnresult
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
