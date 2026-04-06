/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import com.forclaw.browser.control.manager.BrowserManager
import com.forclaw.browser.control.model.Toolresult
import kotlinx.coroutines.delay

/**
 * 浏览器按Key工具
 *
 * MockKey盘按Key
 *
 * Parameters:
 * - key: String (Required) - 按KeyName (such as "Enter", "Tab", "Escape", "ArrowDown")
 * - delayMs: Int (Optional) - 按KeyBackDelay毫秒数, Default 100ms
 *
 * Return:
 * - key: String - 按Down的Key
 * - pressed: Boolean - YesNoSuccess
 */
class BrowserPressTool : BrowserTool {
    override val name = "browser_press"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
        val key = args["key"] as? String
            ?: return Toolresult.error("Missing required parameter: key")

        if (key.isBlank()) {
            return Toolresult.error("Parameter 'key' cannot be empty")
        }

        val delayMs = (args["delayMs"] as? Number)?.toLong() ?: 100L

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
        val escapedKey = key.replace("'", "\\'")
        val script = """
            (function() {
                try {
                    // Get当FrontFocusElement, ifNone则use body
                    const target = document.activeElement || document.body;

                    // 触发 keydown Event
                    const keydownEvent = new KeyboardEvent('keydown', {
                        key: '$escapedKey',
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(keydownEvent);

                    // 触发 keypress Event (某些场景Need)
                    const keypressEvent = new KeyboardEvent('keypress', {
                        key: '$escapedKey',
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(keypressEvent);

                    // 触发 keyup Event
                    const keyupEvent = new KeyboardEvent('keyup', {
                        key: '$escapedKey',
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(keyupEvent);

                    return true;
                } catch (e) {
                    return false;
                }
            })()
        """.trimIndent()

        // 4. 执Row JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val pressed = result?.trim() == "true"

            // 5. WaitDelay
            if (pressed && delayMs > 0) {
                delay(delayMs)
            }

            // 6. Returnresult
            return if (pressed) {
                Toolresult.success(
                    "key" to key,
                    "pressed" to true
                )
            } else {
                Toolresult.error("Press key failed: $key")
            }
        } catch (e: Exception) {
            return Toolresult.error("Press failed: ${e.message}")
        }
    }
}
