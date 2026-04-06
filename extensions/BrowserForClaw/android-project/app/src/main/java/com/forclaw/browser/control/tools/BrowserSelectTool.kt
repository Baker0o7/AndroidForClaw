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
 * Browser Select Tool
 *
 * Select dropdown options
 *
 * Parameters:
 * - selector: String (Required) - CSS selector (points to <select> element)
 * - values: List<String> (Required) - List of values to select
 *
 * Return:
 * - selector: String - Used selector
 * - values: List<String> - Selected values
 * - selected: Boolean - Whether selection succeeded
 */
class BrowserSelectTool : BrowserTool {
    override val name = "browser_select"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val selector = args["selector"] as? String
            ?: return Toolresult.error("Missing required parameter: selector")

        if (selector.isBlank()) {
            return Toolresult.error("Parameter 'selector' cannot be empty")
        }

        @Suppress("UNCHECKED_CAST")
        val values = (args["values"] as? List<*>)?.mapNotNull { it as? String }
            ?: return Toolresult.error("Missing required parameter: values")

        if (values.isEmpty()) {
            return Toolresult.error("Parameter 'values' cannot be empty")
        }

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val escapedSelector = selector.replace("'", "\\'")
        val valuesJson = values.joinToString(",") { "'${it.replace("'", "\\'")}'" }

        val script = """
            (function() {
                try {
                    const select = document.querySelector('$escapedSelector');
                    if (!select || select.tagName !== 'SELECT') return false;

                    const valuesToSelect = [$valuesJson];

                    // clearAllOptions
                    Array.from(select.options).forEach(option => {
                        option.selected = false;
                    });

                    // Select specified value
                    let selectedCount = 0;
                    valuesToSelect.forEach(value => {
                        Array.from(select.options).forEach(option => {
                            if (option.value === value || option.text === value) {
                                option.selected = true;
                                selectedCount++;
                            }
                        });
                    });

                    // Trigger change event
                    if (selectedCount > 0) {
                        select.dispatchEvent(new Event('change', { bubbles: true }));
                        select.dispatchEvent(new Event('input', { bubbles: true }));
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
            val selected = result?.trim() == "true"

            // 5. Returnresult
            return if (selected) {
                Toolresult.success(
                    "selector" to selector,
                    "values" to values,
                    "selected" to true
                )
            } else {
                Toolresult.error("Select element not found or selection failed: $selector")
            }
        } catch (e: Exception) {
            return Toolresult.error("Select failed: ${e.message}")
        }
    }
}
