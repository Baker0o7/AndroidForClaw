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
 * 浏览器choose工具
 *
 * chooseDown拉框Options
 *
 * Parameters:
 * - selector: String (Required) - CSS choose器 (指向 <select> Element)
 * - values: List<String> (Required) - 要choose的ValueList
 *
 * Return:
 * - selector: String - use的choose器
 * - values: List<String> - choose的Value
 * - selected: Boolean - YesNoSuccesschoose
 */
class BrowserSelectTool : BrowserTool {
    override val name = "browser_select"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
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

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
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

                    // choose指定的Value
                    let selectedCount = 0;
                    valuesToSelect.forEach(value => {
                        Array.from(select.options).forEach(option => {
                            if (option.value === value || option.text === value) {
                                option.selected = true;
                                selectedCount++;
                            }
                        });
                    });

                    // 触发 change Event
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

        // 4. 执Row JavaScript
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
