/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.manager

import android.webkit.ValueCallback
import info.plateaukao.einkbro.activity.BrowserActivity
import info.plateaukao.einkbro.view.EBWebView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Browser Manager
 *
 * Responsibilities:
 * - Manage BrowserActivity instance
 * - Provide JavaScript execution interface
 * - Provide navigation control interface
 * - Ensure UI thread safety
 */
object BrowserManager {

    private var browserActivity: BrowserActivity? = null

    /**
     * Set current BrowserActivity instance
     *
     * Should be called in BrowserActivity.onCreate()
     */
    fun setBrowserActivity(activity: BrowserActivity?) {
        browserActivity = activity
    }

    /**
     * Get current BrowserActivity instance
     */
    fun getBrowserActivity(): BrowserActivity? = browserActivity

    /**
     * Get current active WebView
     */
    private fun getCurrentWebView(): EBWebView? {
        return browserActivity?.getCurrentAlbumController() as? EBWebView
    }

    /**
     * Run action on UI thread
     *
     * @param action Action to execute
     */
    private fun runOnUiThread(action: (BrowserActivity) -> Unit) {
        val activity = browserActivity ?: return
        activity.runOnUiThread {
            action(activity)
        }
    }

    /**
     * Execute JavaScript code
     *
     * @param script JavaScript code
     * @return Execution result (JSON String), return null if failed
     */
    suspend fun evaluateJavascript(script: String): String? {
        return suspendCoroutine { continuation ->
            val webView = getCurrentWebView()
            if (webView == null) {
                continuation.resume(null)
                return@suspendCoroutine
            }

            runOnUiThread { _ ->
                webView.evaluateJavascript(script, ValueCallback { result ->
                    continuation.resume(result)
                })
            }
        }
    }

    /**
     * Navigate to specified URL
     *
     * @param url Target URL
     */
    fun navigate(url: String) {
        runOnUiThread { _ ->
            val webView = getCurrentWebView()
            webView?.loadUrl(url)
        }
    }

    /**
     * Get current page URL
     *
     * @return Current URL, return null if no active page
     */
    fun getCurrentUrl(): String? {
        return browserActivity?.getCurrentAlbumController()?.albumUrl
    }

    /**
     * Get current page title
     *
     * @return Current title, return null if no active page
     */
    fun getCurrentTitle(): String? {
        return browserActivity?.getCurrentAlbumController()?.albumTitle
    }

    /**
     * Check if there is an active browser instance
     *
     * @return true if has active instance
     */
    fun isActive(): Boolean {
        return browserActivity != null && getCurrentWebView() != null
    }
}
