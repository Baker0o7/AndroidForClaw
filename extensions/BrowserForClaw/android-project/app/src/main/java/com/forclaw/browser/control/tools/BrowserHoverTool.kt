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
 * 浏览器悬停工具
 *
 * 触发鼠标悬停Event
 *
 * Parameters:
 * - selector: String (Required) - CSS choose器
 *
 * Return:
 * - selector: String - use的choose器
 * - hovered: Boolean - YesNoSuccess悬停
 */
class BrowserHoverTool : BrowserTool {
    override val name = "browser_hover"

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
                    if (!el) return false;

                    // 滚动到Element可见
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });

                    // 触发 mouseenter Event
                    const mouseenterEvent = new MouseEvent('mouseenter', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    el.dispatchEvent(mouseenterEvent);

                    // 触发 mouseover Event
                    const mouseoverEvent = new MouseEvent('mouseover', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    el.dispatchEvent(mouseoverEvent);

                    // 触发 mousemove Event
                    const mousemoveEvent = new MouseEvent('mousemove', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    el.dispatchEvent(mousemoveEvent);

                    return true;
                } catch (e) {
                    return false;
                }
            })()
        """.trimIndent()

        // 4. 执Row JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val hovered = result?.trim() == "true"

            // 5. Returnresult
            return if (hovered) {
                Toolresult.success(
                    "selector" to selector,
                    "hovered" to true
                )
            } else {
                Toolresult.error("Element not found or hover failed: $selector")
            }
        } catch (e: Exception) {
            return Toolresult.error("Hover failed: ${e.message}")
        }
    }
}
