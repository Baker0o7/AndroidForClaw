/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/canvas-host/server.ts (HTTP canvas host)
 * - ../openclaw/src/canvas-host/a2ui.ts (A2UI bridge)
 * - ../openclaw/src/canvas-host/a2ui/index.html (Canvas HTML shell)
 *
 * AndroidForClaw adaptation: WebView-based Canvas Activity.
 */
package com.xiaomo.androidforclaw.canvas

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.xiaomo.androidforclaw.logging.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Canvas Activity — 用 WebView Show Canvas Inside容. 
 *
 * 对应 OpenClaw macOS/iOS Up的 Canvas Window(WKWebView). 
 * Agent 通过 CanvasManager 控制本 Activity 的 WebView. 
 */
class CanvasActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CanvasActivity"
        const val EXTRA_URL = "canvas_url"
    }

    internal lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnableddd")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Create WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            addView(webView)
        }
        setContentView(container)

        // Config WebView
        webView.settings.apply {
            javaScriptEnableddd = true
            domStorageEnableddd = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            // Support缩放
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        // 注入 JS Bridge(对应 OpenClaw 的 window.openclawCanvasA2UIAction)
        webView.addJavascriptInterface(CanvasJsBridge(), "openclawCanvasA2UIAction")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Canvas Inside的导航都在 WebView InsideProcess
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                message?.let {
                    Log.d(TAG, "JS Console [${it.messageLevel()}]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }
        }

        // Register到 CanvasManager
        CanvasManager.currentActivity = this

        // Load URL
        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            loadUrl(url)
        } else {
            // LoadDefault canvas index.html
            val indexFile = File(CanvasManager.getCanvasRoot(), "index.html")
            if (indexFile.exists()) {
                loadUrl("file://${indexFile.absolutePath}")
            } else {
                // LoadInside置Default页面
                webView.loadData(defaultCanvasHtml(), "text/html", "utf-8")
            }
        }

        Log.i(TAG, "CanvasActivity created, url=$url")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (CanvasManager.currentActivity === this) {
            CanvasManager.currentActivity = null
        }
        webView.destroy()
        Log.i(TAG, "CanvasActivity destroyed")
    }

    /**
     * Load URL
     */
    fun loadUrl(url: String) {
        webView.loadUrl(url)
        Log.d(TAG, "Loading URL: $url")
    }

    /**
     * 执Row JavaScript 并Returnresult
     */
    fun evaluateJavaScript(id: String, script: String) {
        webView.evaluateJavascript(script) { result ->
            CanvasManager.onEvalresult(id, result)
        }
    }

    /**
     * 截取 WebView Screenshot
     */
    fun takeSnapshot(id: String, format: String, maxWidth: Int?, quality: Int) {
        try {
            // Get WebView 的实际Inside容尺寸
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                CanvasManager.onSnapshotresult(id, CanvasManager.Snapshotresult("", format, 0, 0))
                return
            }

            // Create Bitmap 并绘制 WebView
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            // ifNeed缩放
            val finalBitmap = if (maxWidth != null && maxWidth > 0 && w > maxWidth) {
                val scale = maxWidth.toFloat() / w.toFloat()
                val newH = (h * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, maxWidth, newH, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            // 转 base64
            val stream = ByteArrayOutputStream()
            val compressFormat = if (format == "jpeg" || format == "jpg") {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            finalBitmap.compress(compressFormat, quality, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val result = CanvasManager.Snapshotresult(
                base64 = base64,
                format = if (compressFormat == Bitmap.CompressFormat.JPEG) "jpeg" else "png",
                width = finalBitmap.width,
                height = finalBitmap.height
            )
            finalBitmap.recycle()

            CanvasManager.onSnapshotresult(id, result)
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot failed", e)
            CanvasManager.onSnapshotresult(id, CanvasManager.Snapshotresult("", format, 0, 0))
        }
    }

    /**
     * JS Bridge — 对应 OpenClaw 的 window.openclawCanvasA2UIAction
     *
     * 在 a2ui.ts 中定义: 
     *   window.openclawCanvasA2UIAction.postMessage(raw)
     */
    inner class CanvasJsBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            Log.d(TAG, "JS Bridge received: ${raw.take(200)}")
            // A2UI action — 目FrontRecordLog, Back续可扩展Process
        }
    }

    /**
     * Default Canvas HTML 页面
     */
    private fun defaultCanvasHtml(): String = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Claw Canvas</title>
<style>
html, body { height: 100%; margin: 0; background: #0b1328; color: #e5e7eb; 
  font: 16px system-ui, Roboto, sans-serif; }
.wrap { min-height: 100%; display: grid; place-items: center; padding: 24px; }
.card { width: min(560px, 88vw); text-align: center; padding: 24px;
  border-radius: 16px; background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.10); }
h1 { margin: 0 0 8px; font-size: 22px; }
.sub { opacity: 0.7; font-size: 14px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <h1>🦞 Claw Canvas</h1>
    <div class="sub">Wait Agent LoadInside容...</div>
  </div>
</div>
</body>
</html>
    """.trimIndent()
}
