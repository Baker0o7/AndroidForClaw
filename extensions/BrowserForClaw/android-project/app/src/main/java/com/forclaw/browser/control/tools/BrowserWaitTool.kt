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
import kotlinx.coroutines.withTimeout

/**
 * Browser Wait Tool
 *
 * Wait for page to satisfy specific condition
 *
 * Parameters:
 * - timeMs: Int (Optional) - Simple wait specified milliseconds
 * - selector: String (Optional) - Wait for element to appear
 * - text: String (Optional) - Wait for text to appear
 * - textGone: String (Optional) - Wait for text to disappear
 * - url: String (Optional) - Wait for URL change
 * - jsCondition: String (Optional) - Custom JavaScript condition
 * - timeout: Int (Optional) - Timeout time in milliseconds, default 30000ms
 *
 * Return:
 * - waitType: String - Wait type
 * - success: Boolean - Yes/No success
 */
class BrowserWaitTool : BrowserTool {
    override val name = "browser_wait"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Get Parameters
        val timeMs = (args["timeMs"] as? Number)?.toLong()
        val selector = args["selector"] as? String
        val text = args["text"] as? String
        val textGone = args["textGone"] as? String
        val url = args["url"] as? String
        val jsCondition = args["jsCondition"] as? String
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 30000L

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Execute different wait based on parameter type
        try {
            return withTimeout(timeout) {
                when {
                    // Simple wait
                    timeMs != null -> {
                        delay(timeMs)
                        Toolresult.success(
                            "waitType" to "time",
                            "timeMs" to timeMs
                        )
                    }

                    // Wait for element to appear
                    selector != null -> {
                        waitForSelector(selector)
                        Toolresult.success(
                            "waitType" to "selector",
                            "selector" to selector
                        )
                    }

                    // Wait for text to appear
                    text != null -> {
                        waitForText(text)
                        Toolresult.success(
                            "waitType" to "text",
                            "text" to text
                        )
                    }

                    // Wait for text to disappear
                    textGone != null -> {
                        waitForTextGone(textGone)
                        Toolresult.success(
                            "waitType" to "textGone",
                            "text" to textGone
                        )
                    }

                    // Wait for URL change
                    url != null -> {
                        waitForUrl(url)
                        Toolresult.success(
                            "waitType" to "url",
                            "url" to url
                        )
                    }

                    // Custom JavaScript Condition
                    jsCondition != null -> {
                        waitForJsCondition(jsCondition)
                        Toolresult.success(
                            "waitType" to "jsCondition",
                            "condition" to jsCondition
                        )
                    }

                    else -> {
                        Toolresult.error("No wait condition specified")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return Toolresult.error("Wait timeout after ${timeout}ms")
        } catch (e: Exception) {
            return Toolresult.error("Wait failed: ${e.message}")
        }
    }

/**
     * Wait for element to appear
     */
    private suspend fun waitForSelector(selector: String) {
        val escapedSelector = selector.replace("'", "\\'")
        val script = """
            (function() {
                const el = document.querySelector('$escapedSelector');
                return el !== null;
            })()
        """.trimIndent()

        waitUntilTrue(script, checkInterval = 200L)
    }

/**
     * Wait for text to disappear
     */
    private suspend fun waitForText(text: String) {
        val escapedText = text.replace("'", "\\'")
        val script = """
            (function() {
                const bodyText = document.body.innerText || document.body.textContent || '';
                return bodyText.includes('$escapedText');
            })()
        """.trimIndent()

        waitUntilTrue(script, checkInterval = 200L)
    }

    /**
     * Wait for text to disappear
     */
    private suspend fun waitForTextGone(text: String) {
        val escapedText = text.replace("'", "\\'")
        val script = """
            (function() {
                const bodyText = document.body.innerText || document.body.textContent || '';
                return !bodyText.includes('$escapedText');
            })()
        """.trimIndent()

        waitUntilTrue(script, checkInterval = 200L)
    }

/**
     * Wait for URL change
     */
    private suspend fun waitForUrl(targetUrl: String) {
        while (true) {
            // Use JavaScript to get URL, avoid cross-thread issue
            val currentUrl = BrowserManager.evaluateJavascript("window.location.href")
                ?.trim('"') ?: ""
            if (currentUrl.contains(targetUrl)) {
                break
            }
            delay(200L)
        }
    }

    /**
     * Wait for custom JavaScript condition
     */
    private suspend fun waitForJsCondition(jsCondition: String) {
        val script = """
            (function() {
                return ($jsCondition);
            })()
        """.trimIndent()

        waitUntilTrue(script, checkInterval = 200L)
    }

    /**
     * Wait until JavaScript expression returns true
     */
    private suspend fun waitUntilTrue(script: String, checkInterval: Long) {
        while (true) {
            val result = BrowserManager.evaluateJavascript(script)
            if (result?.trim() == "true") {
                break
            }
            delay(checkInterval)
        }
    }
}
