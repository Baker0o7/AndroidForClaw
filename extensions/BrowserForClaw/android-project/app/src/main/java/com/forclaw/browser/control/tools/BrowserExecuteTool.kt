/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import com.forclaw.browser.control.manager.BrowserManager
import com.forclaw.browser.control.model.Toolresult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览器执Row JavaScript 工具
 *
 * 执RowCustom JavaScript 代码
 *
 * Parameters:
 * - script: String (Required) - JavaScript 代码
 * - selector: String (Optional) - 在特定ElementUp执Row
 *
 * Return:
 * - result: String - 执Rowresult
 * - script: String - 执Row的脚本
 */
class BrowserExecuteTool : BrowserTool {
    override val name = "browser_execute"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
        val script = args["script"] as? String
            ?: return Toolresult.error("Missing required parameter: script")

        if (script.isBlank()) {
            return Toolresult.error("Parameter 'script' cannot be empty")
        }

        val selector = args["selector"] as? String

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. 构造 JavaScript 代码
        val fullScript = if (selector != null) {
            // 在特定ElementUp执Row
            val escapedSelector = selector.replace("'", "\\'")
            """
                (function() {
                    const el = document.querySelector('$escapedSelector');
                    if (!el) return null;
                    return (function(element) {
                        $script
                    })(el);
                })()
            """.trimIndent()
        } else {
            // 在页面UpDown文执Row
            """
                (function() {
                    $script
                })()
            """.trimIndent()
        }

        // 4. 执Row JavaScript (Must在主Thread)
        try {
            val rawresult = withContext(Dispatchers.Main) {
                BrowserManager.evaluateJavascript(fullScript)
            }

            // Parseresult
            val result = rawresult?.let {
                // evaluateJavascript Return JSON Encoded string
                if (it == "null" || it == "undefined") {
                    null
                } else {
                    // Attempt去掉 JSON String的引号
                    it.trim().removeSurrounding("\"")
                }
            }

            // 5. Returnresult
            return Toolresult.success(
                "result" to result,
                "script" to script,
                "selector" to selector
            )
        } catch (e: Exception) {
            return Toolresult.error("Execute failed: ${e.message}")
        }
    }
}
