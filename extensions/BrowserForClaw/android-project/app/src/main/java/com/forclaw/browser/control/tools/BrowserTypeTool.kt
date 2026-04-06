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
 * Browser Input Tool
 *
 * Input text into input field
 *
 * Parameters:
 * - selector: String (Required) - CSS selector
 * - text: String (Required) - Text to input
 * - submit: Boolean (Optional) - Whether to commit form, default false
 *
 * Return:
 * - selector: String - Used selector
 * - text: String - Input text
 * - submitted: Boolean - Whether form was submitted
 */
class BrowserTypeTool : BrowserTool {
    override val name = "browser_type"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val selector = args["selector"] as? String
            ?: return Toolresult.error("Missing required parameter: selector")
        val text = args["text"] as? String
            ?: return Toolresult.error("Missing required parameter: text")
        val submit = (args["submit"] as? Boolean) ?: false

        if (selector.isBlank()) {
            return Toolresult.error("Parameter 'selector' cannot be empty")
        }

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val escapedSelector = selector.replace("'", "\\'")
        val escapedText = text.replace("'", "\\'")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val script = """
            (function() {
                try {
                    const el = document.querySelector('$escapedSelector');
                    if (!el) return false;

                    // Settings value
                    el.value = '$escapedText';

                    // Trigger input event (mock user input)
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));

                    ${if (submit) {
                        """
                        // Commit form
                        const form = el.closest('form');
                        if (form) {
                            form.submit();
                        } else {
                            // if no form, mock Enter key
                            const event = new KeyboardEvent('keypress', {
                                key: 'Enter',
                                keyCode: 13,
                                which: 13,
                                bubbles: true
                            });
                            el.dispatchEvent(event);
                        }
                        """
                    } else ""
                    }

                    return true;
                } catch (e) {
                    return false;
                }
            })()
        """.trimIndent()

        // 4. Execute JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val typed = result?.trim() == "true"

            // 5. Return result
            return if (typed) {
                Toolresult.success(
                    "selector" to selector,
                    "text" to text,
                    "submitted" to submit
                )
            } else {
                Toolresult.error("Element not found or not typable: $selector")
            }
        } catch (e: Exception) {
            return Toolresult.error("Type failed: ${e.message}")
        }
    }
}
