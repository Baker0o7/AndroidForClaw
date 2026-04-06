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
 * 浏览器Manage器
 *
 * 职责:
 * - Manage BrowserActivity Instance
 * - 提供 JavaScript 执RowInterface
 * - 提供导航控制Interface
 * - Ensure UI ThreadSecure
 */
object BrowserManager {

    private var browserActivity: BrowserActivity? = null

    /**
     * Settings当Front BrowserActivity Instance
     *
     * Should在 BrowserActivity.onCreate() 中call
     */
    fun setBrowserActivity(activity: BrowserActivity?) {
        browserActivity = activity
    }

    /**
     * Get当Front BrowserActivity Instance
     */
    fun getBrowserActivity(): BrowserActivity? = browserActivity

    /**
     * Get当Front活动的 WebView
     */
    private fun getCurrentWebView(): EBWebView? {
        return browserActivity?.getCurrentAlbumController() as? EBWebView
    }

    /**
     * 在 UI Thread执RowAction
     *
     * @param action 要执Row的Action
     */
    private fun runOnUiThread(action: (BrowserActivity) -> Unit) {
        val activity = browserActivity ?: return
        activity.runOnUiThread {
            action(activity)
        }
    }

    /**
     * 执Row JavaScript 代码
     *
     * @param script JavaScript 代码
     * @return 执Rowresult (JSON String), ifFailedReturn null
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
     * 导航到指定 URL
     *
     * @param url 目标 URL
     */
    fun navigate(url: String) {
        runOnUiThread { _ ->
            val webView = getCurrentWebView()
            webView?.loadUrl(url)
        }
    }

    /**
     * Get当Front页面 URL
     *
     * @return 当Front URL, ifNone活动页面Return null
     */
    fun getCurrentUrl(): String? {
        return browserActivity?.getCurrentAlbumController()?.albumUrl
    }

    /**
     * Get当Front页面Title
     *
     * @return 当FrontTitle, ifNone活动页面Return null
     */
    fun getCurrentTitle(): String? {
        return browserActivity?.getCurrentAlbumController()?.albumTitle
    }

    /**
     * CheckYesNoHas活动的浏览器Instance
     *
     * @return true ifHas活动Instance
     */
    fun isActive(): Boolean {
        return browserActivity != null && getCurrentWebView() != null
    }
}
