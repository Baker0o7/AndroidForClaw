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
 * 浏览器GetInside容工具
 *
 * Get当Front页面的TextInside容
 *
 * Parameters:
 * - format: String (Optional) - Inside容格式: "text" (纯Text) 或 "html", Default "text"
 * - waitForContent: Boolean (Optional) - YesNoWaitInside容Load complete, Default true
 * - timeout: Int (Optional) - WaitTimeoutTime(毫秒), Default 5000
 *
 * Return:
 * - content: String - 页面Inside容
 * - length: Int - Inside容长度
 * - url: String - 当Front页面 URL
 * - title: String - 当Front页面Title
 */
class BrowserGetContentTool : BrowserTool {
    override val name = "browser_get_content"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. GetParameters
        val format = (args["format"] as? String)?.lowercase() ?: "text"
        val waitForContent = (args["waitForContent"] as? Boolean) ?: true
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 5000L

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. ifNeedWaitInside容, 先Check页面YesNoLoad complete
        if (waitForContent) {
            val loadCheckScript = """
                (function() {
                    return document.readyState === 'complete' &&
                           (document.body.innerText || document.body.textContent || '').length > 0;
                })()
            """.trimIndent()

            // WaitInside容Load, most多Wait timeout 毫秒
            val startTime = System.currentTimeMillis()
            var contentReady = false

            while (!contentReady && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    // Must在主Thread执Row evaluateJavascript
                    val result = withContext(Dispatchers.Main) {
                        BrowserManager.evaluateJavascript(loadCheckScript)
                    }
                    contentReady = result?.trim() == "true"

                    if (!contentReady) {
                        kotlinx.coroutines.delay(200) // 每 200ms Check一次
                    }
                } catch (e: Exception) {
                    // ContinueWait
                    kotlinx.coroutines.delay(200)
                }
            }
        }

        // 4. 构造 JavaScript 代码
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

        // 5. 执Row JavaScript (Must在主Thread)
        try {
            val rawresult = withContext(Dispatchers.Main) {
                BrowserManager.evaluateJavascript(script)
            }
            // evaluateJavascript Return的StringYes JSON Encode的, Need去掉首尾引号
            val content = rawresult?.trim()?.removeSurrounding("\"")?.let {
                // Decode JSON 转义
                it.replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } ?: ""

            // 6. LimitInside容长度, 避免 HTTP Response过大
            val maxLength = 10000 // Max 10000 字符
            val truncated = content.length > maxLength
            val finalContent = if (truncated) {
                content.substring(0, maxLength) + "\n...(truncated)"
            } else {
                content
            }

            // 7. Returnresult
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
