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
 * 浏览器 Cookie Get工具
 *
 * Get当Front页面的 Cookies
 *
 * Parameters: None
 *
 * Return:
 * - cookies: String - Cookie String
 * - url: String - 当Front URL
 */
class BrowserGetCookiesTool : BrowserTool {
    override val name = "browser_get_cookies"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 2. Get当Front URL (通过 JavaScript 避免ThreadIssue)
        val url = BrowserManager.evaluateJavascript("window.location.href")
            ?.trim('"') ?: return Toolresult.error("No active page")

        // 3. Get Cookies
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url) ?: ""

            // 4. Returnresult
            return Toolresult.success(
                "cookies" to cookies
            )
        } catch (e: Exception) {
            return Toolresult.error("Get cookies failed: ${e.message}")
        }
    }
}

/**
 * 浏览器 Cookie Settings工具
 *
 * Settings Cookies
 *
 * Parameters:
 * - url: String (Optional) - 目标 URL, Defaultuse当Front URL
 * - cookies: List<String> (Required) - Cookie List, 格式: "name=value; path=/; domain=.example.com"
 *
 * Return:
 * - url: String - Settings的 URL
 * - count: Int - Settings的 Cookie 数量
 */
class BrowserSetCookiesTool : BrowserTool {
    override val name = "browser_set_cookies"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. ValidateParameters
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

        // 3. Settings Cookies
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            cookieList.forEach { cookie ->
                cookieManager.setCookie(url, cookie)
            }

            // EnsurePersistent化
            cookieManager.flush()

            // 4. Returnresult
            return Toolresult.success(
                "url" to url,
                "count" to cookieList.size
            )
        } catch (e: Exception) {
            return Toolresult.error("Set cookies failed: ${e.message}")
        }
    }
}
