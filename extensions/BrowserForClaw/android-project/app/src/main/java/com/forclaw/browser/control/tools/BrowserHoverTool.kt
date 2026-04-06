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
 * Browser Hover Tool
 *
 * Trigger mouse hover event
 *
 * Parameters:
 * - selector: String (Required) - CSS selector
 *
 * Return:
 * - selector: String - Used selector
 * - hovered: Boolean - Success or not
 */
class BrowserHoverTool : BrowserTool {
    override val name = "browser_hover"

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
                    if (!el) return false;

                    // Scroll to element visible
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });

                    // Trigger mouseenter event
                    const mouseenterEvent = new MouseEvent('mouseenter', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    el.dispatchEvent(mouseenterEvent);

                    // Trigger mouseover event
                    const mouseoverEvent = new MouseEvent('mouseover', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    });
                    el.dispatchEvent(mouseoverEvent);

                    // Trigger mousemove event
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

        // 4. Execute JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val hovered = result?.trim() == "true"

            // 5. Return result
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
