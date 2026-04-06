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
 * 浏览器Wait工具
 *
 * Wait页面satisfy特定Condition
 *
 * Parameters:
 * - timeMs: Int (Optional) - SimpleWait指定毫秒数
 * - selector: String (Optional) - WaitElement出现
 * - text: String (Optional) - WaitText出现
 * - textGone: String (Optional) - WaitText消失
 * - url: String (Optional) - Wait URL 变化
 * - jsCondition: String (Optional) - Custom JavaScript Condition
 * - timeout: Int (Optional) - TimeoutTime(毫秒), Default 30000ms
 *
 * Return:
 * - waitType: String - WaitType
 * - success: Boolean - YesNoSuccess
 */
class BrowserWaitTool : BrowserTool {
    override val name = "browser_wait"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. GetParameters
        val timeMs = (args["timeMs"] as? Number)?.toLong()
        val selector = args["selector"] as? String
        val text = args["text"] as? String
        val textGone = args["textGone"] as? String
        val url = args["url"] as? String
        val jsCondition = args["jsCondition"] as? String
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 30000L

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. according toParametersType执Row不同的Wait
        try {
            return withTimeout(timeout) {
                when {
                    // SimpleWait
                    timeMs != null -> {
                        delay(timeMs)
                        Toolresult.success(
                            "waitType" to "time",
                            "timeMs" to timeMs
                        )
                    }

                    // WaitElement出现
                    selector != null -> {
                        waitForSelector(selector)
                        Toolresult.success(
                            "waitType" to "selector",
                            "selector" to selector
                        )
                    }

                    // WaitText出现
                    text != null -> {
                        waitForText(text)
                        Toolresult.success(
                            "waitType" to "text",
                            "text" to text
                        )
                    }

                    // WaitText消失
                    textGone != null -> {
                        waitForTextGone(textGone)
                        Toolresult.success(
                            "waitType" to "textGone",
                            "text" to textGone
                        )
                    }

                    // Wait URL 变化
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
     * WaitElement出现
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
     * WaitText出现
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
     * WaitText消失
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
     * Wait URL 变化
     */
    private suspend fun waitForUrl(targetUrl: String) {
        while (true) {
            // use JavaScript Get URL, 避免跨ThreadIssue
            val currentUrl = BrowserManager.evaluateJavascript("window.location.href")
                ?.trim('"') ?: ""
            if (currentUrl.contains(targetUrl)) {
                break
            }
            delay(200L)
        }
    }

    /**
     * WaitCustom JavaScript Condition
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
     * Wait JavaScript Table达式Return true
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
