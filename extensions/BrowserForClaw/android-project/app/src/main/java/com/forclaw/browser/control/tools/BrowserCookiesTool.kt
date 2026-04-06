/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import android.webkit.CookieManager
import com.forclaw.browser.control.manager.BrowserManager
import com.forclaw.browser.control.model.Toolresult

/**
 * Browser Cookie Get Tool
 *
 * Get current page cookies
 *
 * Parameters: None
 *
 * Return:
 * - cookies: String - Cookie string
 * - url: String - Current URL
 */
class BrowserGetCookiesTool : BrowserTool {
    override val name = "browser_get_cookies"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 2. Get current URL (via JavaScript to avoid thread issues)
        val url = BrowserManager.evaluateJavascript("window.location.href")
            ?.trim('"') ?: return Toolresult.error("No active page")

        // 3. Get cookies
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url) ?: ""

            // 4. Return result
            return Toolresult.success(
                "cookies" to cookies
            )
        } catch (e: Exception) {
            return Toolresult.error("Get cookies failed: ${e.message}")
        }
    }
}

/**
 * Browser Cookie Settings Tool
 *
 * Set cookies
 *
 * Parameters:
 * - url: String (Optional) - Target URL, default use current URL
 * - cookies: List<String> (Required) - Cookie list, format: "name=value; path=/; domain=.example.com"
 *
 * Return:
 * - url: String - Set URL
 * - count: Int - Number of cookies set
 */
class BrowserSetCookiesTool : BrowserTool {
    override val name = "browser_set_cookies"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Validate Parameters
        @Suppress("UNCHECKED_CAST")
        val cookieList = (args["cookies"] as? List<*>)?.mapNotNull { it as? String }
            ?: return Toolresult.error("Missing required parameter: cookies")

        if (cookieList.isEmpty()) {
            return Toolresult.error("Parameter 'cookies' cannot be empty")
        }

        // 2. Get URL
        val url = (args["url"] as? String)
            ?: BrowserManager.evaluateJavascript("window.location.href")?.trim('"')
            ?: return Toolresult.error("No URL specified and no active page")

        // 3. Set cookies
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            cookieList.forEach { cookie ->
                cookieManager.setCookie(url, cookie)
            }

            // Ensure persistence
            cookieManager.flush()

            // 4. Return result
            return Toolresult.success(
                "url" to url,
                "count" to cookieList.size
            )
        } catch (e: Exception) {
            return Toolresult.error("Set cookies failed: ${e.message}")
        }
    }
}
