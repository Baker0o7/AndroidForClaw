/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import android.graphics.Bitmap
import android.util.Base64
import com.forclaw.browser.control.manager.BrowserManager
import com.forclaw.browser.control.model.Toolresult
import info.plateaukao.einkbro.view.EBWebView
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览器Screenshot工具
 *
 * 截取当Front页面的Graph片
 *
 * Parameters:
 * - fullPage: Boolean (Optional) - YesNo全页Screenshot, Default false
 * - format: String (Optional) - Graph片格式 "png" 或 "jpeg", Default "png"
 * - quality: Int (Optional) - JPEG 质量 0-100, Default 80
 * - waitForStable: Boolean (Optional) - YesNoWait页面Stable, Default true
 * - stabilityTimeout: Int (Optional) - WaitStableTimeoutTime(毫秒), Default 3000
 *
 * Return:
 * - screenshot: String - Base64 Encode的Graph片Data
 * - width: Int - Graph片Breadth
 * - height: Int - Graph片高度
 * - format: String - Graph片格式
 */
class BrowserScreenshotTool : BrowserTool {
    override val name = "browser_screenshot"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. GetParameters
        val fullPage = (args["fullPage"] as? Boolean) ?: false
        val format = (args["format"] as? String)?.lowercase() ?: "png"
        val quality = (args["quality"] as? Number)?.toInt() ?: 80
        val waitForStable = (args["waitForStable"] as? Boolean) ?: true
        val stabilityTimeout = (args["stabilityTimeout"] as? Number)?.toLong() ?: 3000L

        // Validate format
        if (format !in listOf("png", "jpeg", "jpg")) {
            return Toolresult.error("Invalid format: $format (must be 'png' or 'jpeg')")
        }

        // Validate quality
        if (quality !in 0..100) {
            return Toolresult.error("Invalid quality: $quality (must be 0-100)")
        }

        // 2. Check浏览器Instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Wait页面Stable
        if (waitForStable) {
            try {
                waitForPageStable(stabilityTimeout)
            } catch (e: Exception) {
                // WaitTimeoutAlsoContinueScreenshot
            }
        }

        // 4. Screenshot
        try {
            val bitmap = captureScreenshot(fullPage)

            // 4. Convert为 Base64
            val outputStream = ByteArrayOutputStream()
            val compressFormat = if (format == "png") {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }

            bitmap.compress(compressFormat, quality, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            // 5. Returnresult
            return Toolresult.success(
                "screenshot" to base64Image,
                "width" to bitmap.width,
                "height" to bitmap.height,
                "format" to format,
                "size" to imageBytes.size
            )
        } catch (e: Exception) {
            return Toolresult.error("Screenshot failed: ${e.message}")
        }
    }

    /**
     * 截取ScreenGraph片
     */
    private suspend fun captureScreenshot(fullPage: Boolean): Bitmap {
        return suspendCoroutine { continuation ->
            val activity = BrowserManager.getBrowserActivity()
            val webView = activity?.getCurrentAlbumController() as? EBWebView

            if (webView == null) {
                throw Exception("WebView not available")
            }

            activity.runOnUiThread {
                try {
                    val bitmap = if (fullPage) {
                        // 全页Screenshot
                        captureFullPage(webView)
                    } else {
                        // 当Front可见区域Screenshot
                        captureVisibleArea(webView)
                    }
                    continuation.resume(bitmap)
                } catch (e: Exception) {
                    throw e
                }
            }
        }
    }

    /**
     * 截取可见区域
     */
    private fun captureVisibleArea(webView: EBWebView): Bitmap {
        val bitmap = Bitmap.createBitmap(
            webView.width,
            webView.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }

    /**
     * 截取全页
     */
    private fun captureFullPage(webView: EBWebView): Bitmap {
        // Get完整Inside容的高度
        val contentHeight = webView.contentHeight
        val scale = webView.scale

        val height = (contentHeight * scale).toInt()
        val width = webView.width

        // Create大位Graph
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = android.graphics.Canvas(bitmap)

        // Save当Front滚动位置
        val originalScrollY = webView.scrollY

        // 绘制整个页面
        webView.scrollTo(0, 0)
        webView.draw(canvas)

        // Resume滚动位置
        webView.scrollTo(0, originalScrollY)

        return bitmap
    }

    /**
     * Wait页面Stable
     * 通过Check页面LoadStatus和Inside容YesNo变化来Determine
     */
    private suspend fun waitForPageStable(timeout: Long) {
        val startTime = System.currentTimeMillis()

        // Check页面LoadStatus
        val loadCheckScript = """
            (function() {
                return document.readyState === 'complete';
            })()
        """.trimIndent()

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                // Must在主Thread执Row evaluateJavascript
                val result = withContext(Dispatchers.Main) {
                    BrowserManager.evaluateJavascript(loadCheckScript)
                }
                if (result?.trim() == "true") {
                    // 页面Load complete, 额OutsideWait 500ms 让DynamicInside容渲染
                    kotlinx.coroutines.delay(500)
                    return
                }
            } catch (e: Exception) {
                // ContinueWait
            }
            kotlinx.coroutines.delay(200)
        }
    }
}
