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
 * 浏览器滚动工具
 *
 * 滚动页面
 *
 * Parameters:
 * - direction: String (Optional) - 滚动方向: "down", "up", "top", "bottom", Default "down"
 * - amount: Int (Optional) - 滚动Like素数 (仅对 "down" 和 "up" Valid)
 *
 * Return:
 * - direction: String - 滚动方向
 * - scrolled: Boolean - YesNoSuccess滚动
 */
class BrowserScrollTool : BrowserTool {
    override val name = "browser_scroll"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. GetParameters
        val direction = (args["direction"] as? String)?.lowercase() ?: "down"
        val amount = (args["amount"] as? Number)?.toInt()

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
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

        // 4. 执Row JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val scrolled = result?.trim() == "true"

            // 5. Returnresult
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
