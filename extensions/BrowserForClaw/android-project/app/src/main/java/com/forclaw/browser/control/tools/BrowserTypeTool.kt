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
 * 浏览器Input工具
 *
 * 在Input field中InputText
 *
 * Parameters:
 * - selector: String (Required) - CSS choose器
 * - text: String (Required) - 要Input的Text
 * - submit: Boolean (Optional) - YesNoCommitTable单, Default false
 *
 * Return:
 * - selector: String - use的choose器
 * - text: String - Input的Text
 * - submitted: Boolean - YesNoCommit了Table单
 */
class BrowserTypeTool : BrowserTool {
    override val name = "browser_type"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
        val selector = args["selector"] as? String
            ?: return Toolresult.error("Missing required parameter: selector")
        val text = args["text"] as? String
            ?: return Toolresult.error("Missing required parameter: text")
        val submit = (args["submit"] as? Boolean) ?: false

        if (selector.isBlank()) {
            return Toolresult.error("Parameter 'selector' cannot be empty")
        }

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
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

                    // 触发 input Event (MockUserInput)
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));

                    ${if (submit) {
                        """
                        // CommitTable单
                        const form = el.closest('form');
                        if (form) {
                            form.submit();
                        } else {
                            // ifNoneTable单, Mock Enter Key
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

        // 4. 执Row JavaScript
        try {
            val result = BrowserManager.evaluateJavascript(script)
            val typed = result?.trim() == "true"

            // 5. Returnresult
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
