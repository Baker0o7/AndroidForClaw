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
 * Browser Keyboard Press Tool
 *
 * Mock keyboard key press
 *
 * Parameters:
 * - key: String (Required) - Key name (such as "Enter", "Tab", "Escape", "ArrowDown")
 * - delayMs: Int (Optional) - Press back delay in milliseconds, default 100ms
 *
 * Return:
 * - key: String - Pressed key
 * - pressed: Boolean - Success or not
 */
class BrowserPressTool : BrowserTool {
    override val name = "browser_press"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val key = args["key"] as? String
            ?: return Toolresult.error("Missing required parameter: key")

        if (key.isBlank()) {
            return Toolresult.error("Parameter 'key' cannot be empty")
        }

        val delayMs = (args["delayMs"] as? Number)?.toLong() ?: 100L

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val escapedKey = key.replace("'", "\\'")
        val script = """
            (function() {
                try {
                    // Get currently focused element, if none then use body
                    const target = document.activeElement || document.body;

                    // Trigger keydown event
                    const keydownEvent = new KeyboardEvent('keydown', {
                        key: '$escapedKey',
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(keydownEvent);

                    // Trigger keypress event (needed in some scenarios)
                    const keypressEvent = new KeyboardEvent('keypress', {
                        key: '$escapedKey',
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(keypressEvent);

                    // Trigger keyup event
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

        // 4. Execute JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val pressed = result?.trim() == "true"

            // 5. Wait delay
            if (pressed && delayMs > 0) {
                delay(delayMs)
            }

            // 6. Return result
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
