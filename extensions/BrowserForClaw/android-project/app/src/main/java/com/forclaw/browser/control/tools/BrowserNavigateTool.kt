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
 * Browser Navigation Tool
 *
 * Open specified URL
 *
 * Parameters:
 * - url: String (Required) - Target URL
 * - waitMs: Int (Optional) - Wait for page to start loading in milliseconds, default 500ms
 *
 * Return:
 * - url: String - Requested URL
 * - currentUrl: String - Current actual URL
 */
class BrowserNavigateTool : BrowserTool {
    override val name = "browser_navigate"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        val url = args["url"] as? String
            ?: return Toolresult.error("Missing required parameter: url")

        if (url.isBlank()) {
            return Toolresult.error("Parameter 'url' cannot be empty")
        }

        // 2. Normalize URL (add https:// if protocol missing)
        val fullUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("file://") || url.startsWith("about:") || url.startsWith("data:") -> url
            else -> "https://$url"
        }

        // 3. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 4. Execute navigation
        try {
            BrowserManager.navigate(fullUrl)

            // 5. Wait for page to start loading
            val waitMs = (args["waitMs"] as? Number)?.toLong() ?: 500L
            if (waitMs > 0) {
                delay(waitMs)
            }

            // 6. Return result
            return Toolresult.success(
                "url" to fullUrl,
                "status" to "navigating"
            )
        } catch (e: Exception) {
            return Toolresult.error("Navigation failed: ${e.message}")
        }
    }
}
