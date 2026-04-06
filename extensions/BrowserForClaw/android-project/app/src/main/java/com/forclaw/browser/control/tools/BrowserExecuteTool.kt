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
 * Browser Execute JavaScript Tool
 *
 * Execute custom JavaScript code
 *
 * Parameters:
 * - script: String (Required) - JavaScript code
 * - selector: String (Optional) - Execute on specific element
 *
 * Return:
 * - result: String - Execution result
 * - script: String - Executed script
 */
class BrowserExecuteTool : BrowserTool {
    override val name = "browser_execute"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val script = args["script"] as? String
            ?: return Toolresult.error("Missing required parameter: script")

        if (script.isBlank()) {
            return Toolresult.error("Parameter 'script' cannot be empty")
        }

        val selector = args["selector"] as? String

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Build JavaScript code
        val fullScript = if (selector != null) {
            // Execute on specific element
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
            // Execute in page context
            """
                (function() {
                    $script
                })()
            """.trimIndent()
        }

        // 4. Execute JavaScript (must on main thread)
        try {
            val rawresult = withContext(Dispatchers.Main) {
                BrowserManager.evaluateJavascript(fullScript)
            }

            // Parse result
            val result = rawresult?.let {
                // evaluateJavascript returns JSON encoded string
                if (it == "null" || it == "undefined") {
                    null
                } else {
                    // Try to remove quotes from JSON string
                    it.trim().removeSurrounding("\"")
                }
            }

            // 5. Return result
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
