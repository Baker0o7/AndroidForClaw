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
 * Browser Screenshot Tool
 *
 * Capture current page screenshot
 *
 * Parameters:
 * - fullPage: Boolean (Optional) - Whether to capture full page, default false
 * - format: String (Optional) - Image format "png" or "jpeg", default "png"
 * - quality: Int (Optional) - JPEG quality 0-100, default 80
 * - waitForStable: Boolean (Optional) - Whether to wait for page to stabilize, default true
 * - stabilityTimeout: Int (Optional) - Wait for stable timeout in milliseconds, default 3000
 *
 * Return:
 * - screenshot: String - Base64 encoded image data
 * - width: Int - Image width
 * - height: Int - Image height
 * - format: String - Image format
 */
class BrowserScreenshotTool : BrowserTool {
    override val name = "browser_screenshot"

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        // 1. Get Parameters
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

        // 2. Check browser instance
        if (!BrowserManager.isActive()) {
            return Toolresult.error("Browser is not active")
        }

        // 3. Wait for page to stabilize
        if (waitForStable) {
            try {
                waitForPageStable(stabilityTimeout)
            } catch (e: Exception) {
                // Wait timeout, continue with screenshot anyway
            }
        }

        // 4. Take screenshot
        try {
            val bitmap = captureScreenshot(fullPage)

            // 4. Convert to Base64
            val outputStream = ByteArrayOutputStream()
            val compressFormat = if (format == "png") {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }

            bitmap.compress(compressFormat, quality, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            // 5. Return result
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
     * Capture screen screenshot
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
                        // Full page screenshot
                        captureFullPage(webView)
                    } else {
                        // Capture visible area
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
     * Capture visible area
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
     * Capture full page
     */
    private fun captureFullPage(webView: EBWebView): Bitmap {
        // Get full content height
        val contentHeight = webView.contentHeight
        val scale = webView.scale

        val height = (contentHeight * scale).toInt()
        val width = webView.width

        // Create large bitmap
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = android.graphics.Canvas(bitmap)

        // Save current scroll position
        val originalScrollY = webView.scrollY

        // Draw entire page
        webView.scrollTo(0, 0)
        webView.draw(canvas)

        // Restore scroll position
        webView.scrollTo(0, originalScrollY)

        return bitmap
    }

    /**
     * Wait for page to stabilize
     * Checks page load status and content changes to determine
     */
    private suspend fun waitForPageStable(timeout: Long) {
        val startTime = System.currentTimeMillis()

        // Check page load status
        val loadCheckScript = """
            (function() {
                return document.readyState === 'complete';
            })()
        """.trimIndent()

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                // Must run evaluateJavascript on main thread
                val result = withContext(Dispatchers.Main) {
                    BrowserManager.evaluateJavascript(loadCheckScript)
                }
                if (result?.trim() == "true") {
                    // Page loaded, additionally wait 500ms for dynamic content rendering
                    kotlinx.coroutines.delay(500)
                    return
                }
            } catch (e: Exception) {
                // Continue waiting
            }
            kotlinx.coroutines.delay(200)
        }
    }
}
