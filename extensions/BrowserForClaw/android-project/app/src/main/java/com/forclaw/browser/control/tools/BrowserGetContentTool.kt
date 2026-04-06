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
 * Browser Get Content Tool
 *
 * Get current page text content
 *
 * Parameters:
 * - format: String (Optional) - Content format: "text" (plain text) or "html", default "text"
 * - waitForContent: Boolean (Optional) - Whether to wait for content to load completely, default true
 * - timeout: Int (Optional) - Wait timeout in milliseconds, default 5000
 *
 * Return:
 * - content: String - Page content
 * - length: Int - Content length
 * - url: String - Current page URL
 * - title: String - Current page title
 */
class BrowserGetContentTool : BrowserTool {
    override val name = "browser_get_content"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Get Parameters
        val format = (args["format"] as? String)?.lowercase() ?: "text"
        val waitForContent = (args["waitForContent"] as? Boolean) ?: true
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 5000L

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. If need wait content, first check if page loaded completely
        if (waitForContent) {
            val loadCheckScript = """
                (function() {
                    return document.readyState === 'complete' &&
                           (document.body.innerText || document.body.textContent || '').length > 0;
                })()
            """.trimIndent()

            // Wait for content to load, at most wait timeout milliseconds
            val startTime = System.currentTimeMillis()
            var contentReady = false

            while (!contentReady && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    // Must run evaluateJavascript on main thread
                    val result = withContext(Dispatchers.Main) {
                        BrowserManager.evaluateJavascript(loadCheckScript)
                    }
                    contentReady = result?.trim() == "true"

                    if (!contentReady) {
                        kotlinx.coroutines.delay(200) // Check every 200ms
                    }
                } catch (e: Exception) {
                    // Continue waiting
                    kotlinx.coroutines.delay(200)
                }
            }
        }

        // 4. Build JavaScript code
        val script = when (format) {
            "text" -> {
                """
                    (function() {
                        try {
                            return document.body.innerText || document.body.textContent || '';
                        } catch (e) {
                            return '';
                        }
                    })()
                """
            }
            "html" -> {
                """
                    (function() {
                        try {
                            return document.documentElement.outerHTML || '';
                        } catch (e) {
                            return '';
                        }
                    })()
                """
            }
            else -> return Toolresult.error("Invalid format: $format (must be 'text' or 'html')")
        }.trimIndent()

        // 5. Execute JavaScript (must on main thread)
        try {
            val rawresult = withContext(Dispatchers.Main) {
                BrowserManager.evaluateJavascript(script)
            }
            // evaluateJavascript returns JSON encoded string, need to remove surrounding quotes
            val content = rawresult?.trim()?.removeSurrounding("\"")?.let {
                // Decode JSON escaping
                it.replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } ?: ""

            // 6. Limit content length to avoid HTTP response too large
            val maxLength = 10000 // Max 10000 characters
            val truncated = content.length > maxLength
            val finalContent = if (truncated) {
                content.substring(0, maxLength) + "\n...(truncated)"
            } else {
                content
            }

            // 7. Return result
            return Toolresult.success(
                "content" to finalContent,
                "length" to content.length,
                "truncated" to truncated,
                "format" to format,
                "url" to (BrowserManager.getCurrentUrl() ?: ""),
                "title" to (BrowserManager.getCurrentTitle() ?: "")
            )
        } catch (e: Exception) {
            return Toolresult.error("Get content failed: ${e.message}")
        }
    }
}
