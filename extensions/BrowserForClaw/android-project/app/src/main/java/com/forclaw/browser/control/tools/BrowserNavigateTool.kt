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

/**
 * 浏览器导航工具
 *
 * Open指定 URL
 *
 * Parameters:
 * - url: String (Required) - 目标 URL
 * - waitMs: Int (Optional) - Wait页面StartLoad的毫秒数, Default 500ms
 *
 * Return:
 * - url: String - Request的 URL
 * - currentUrl: String - 当Front实际 URL
 */
class BrowserNavigateTool : BrowserTool {
    override val name = "browser_navigate"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
        val url = args["url"] as? String
            ?: return Toolresult.error("Missing required parameter: url")

        if (url.isBlank()) {
            return Toolresult.error("Parameter 'url' cannot be empty")
        }

        // 2. 规范化 URL (Add https:// if缺少Protocol)
        val fullUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("file://") || url.startsWith("about:") || url.startsWith("data:") -> url
            else -> "https://$url"
        }

        // 3. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 4. 执Row导航
        try {
            BrowserManager.navigate(fullUrl)

            // 5. Wait页面StartLoad
            val waitMs = (args["waitMs"] as? Number)?.toLong() ?: 500L
            if (waitMs > 0) {
                delay(waitMs)
            }

            // 6. Returnresult
            return Toolresult.success(
                "url" to fullUrl,
                "status" to "navigating"
            )
        } catch (e: Exception) {
            return Toolresult.error("Navigation failed: ${e.message}")
        }
    }
}
