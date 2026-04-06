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
 * Browser Scroll Tool
 *
 * Scroll the page
 *
 * Parameters:
 * - direction: String (Optional) - Scroll direction: "down", "up", "top", "bottom", default "down"
 * - amount: Int (Optional) - Scroll amount in pixels (only valid for "down" and "up")
 *
 * Return:
 * - direction: String - Scroll direction
 * - scrolled: Boolean - Whether scroll succeeded
 */
class BrowserScrollTool : BrowserTool {
    override val name = "browser_scroll"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Get Parameters
        val direction = (args["direction"] as? String)?.lowercase() ?: "down"
        val amount = (args["amount"] as? Number)?.toInt()

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val script = when (direction) {
            "down" -> {
                val scrollAmount = amount ?: "window.innerHeight"
                """
                    (function() {
                        try {
                            window.scrollBy(0, $scrollAmount);
                            return true;
                        } catch (e) {
                            return false;
                        }
                    })()
                """
            }
            "up" -> {
                val scrollAmount = amount ?: "window.innerHeight"
                """
                    (function() {
                        try {
                            window.scrollBy(0, -$scrollAmount);
                            return true;
                        } catch (e) {
                            return false;
                        }
                    })()
                """
            }
            "top" -> {
                """
                    (function() {
                        try {
                            window.scrollTo(0, 0);
                            return true;
                        } catch (e) {
                            return false;
                        }
                    })()
                """
            }
            "bottom" -> {
                """
                    (function() {
                        try {
                            window.scrollTo(0, document.body.scrollHeight);
                            return true;
                        } catch (e) {
                            return false;
                        }
                    })()
                """
            }
            else -> return Toolresult.error("Invalid direction: $direction (must be 'down', 'up', 'top', or 'bottom')")
        }.trimIndent()

        // 4. Execute JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val scrolled = result?.trim() == "true"

            // 5. Return result
            return if (scrolled) {
                Toolresult.success(
                    "direction" to direction,
                    "scrolled" to true
                )
            } else {
                Toolresult.error("Scroll failed")
            }
        } catch (e: Exception) {
            return Toolresult.error("Scroll failed: ${e.message}")
        }
    }
}
