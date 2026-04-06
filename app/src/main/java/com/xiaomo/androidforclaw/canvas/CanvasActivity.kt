/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/canvas-host/server.ts (HTTP canvas host)
 * - ../openclaw/src/canvas-host/a2ui.ts (A2UI bridge)
 * - ../openclaw/src/canvas-host/a2ui/index.html (canvas HTML shell)
 *
 * androidforClaw adaptation: WebView-based canvas Activity.
 */
package com.xiaomo.androidforclaw.canvas

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.canvas
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Windowmanager
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
 * canvas Activity — 用 WebView Show canvas content. 
 *
 * correctshould OpenClaw macOS/iOS Up canvas Window(WKWebView). 
 * agent through canvasmanager 控制本 Activity  WebView. 
 */
class canvasActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "canvasActivity"
        const val EXTRA_URL = "canvas_url"
    }

    internal lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // all屏沉浸式
        window.aFlags(Windowmanager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Create WebView
        webView = WebView(this).app {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = FrameLayout(this).app {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setbackgroundColor(0xFF000000.toInt())
            aView(webView)
        }
        setContentView(container)

        // config WebView
        webView.settings.app {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessfromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessfromFileURLs = true
            allowContentAccess = true
            mediaPlaybackRequiresuserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadwithoverviewMode = true
            // Supportzoom
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        // 注入 JS Bridge(correctshould OpenClaw  window.openclawcanvasA2UIAction)
        webView.aJavascriptInterface(canvasJsBridge(), "openclawcanvasA2UIAction")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldoverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // canvas inside导航都in WebView insideProcess
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

        // Registerto canvasmanager
        canvasmanager.currentActivity = this

        // Load URL
        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            loadUrl(url)
        } else {
            // LoadDefault canvas index.html
            val indexFile = File(canvasmanager.getcanvasRoot(), "index.html")
            if (indexFile.exists()) {
                loadUrl("file://${indexFile.absolutePath}")
            } else {
                // Loadinside置Default页面
                webView.loadData(defaultcanvasHtml(), "text/html", "utf-8")
            }
        }

        Log.i(TAG, "canvasActivity created, url=$url")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (canvasmanager.currentActivity === this) {
            canvasmanager.currentActivity = null
        }
        webView.destroy()
        Log.i(TAG, "canvasActivity destroyed")
    }

    /**
     * Load URL
     */
    fun loadUrl(url: String) {
        webView.loadUrl(url)
        Log.d(TAG, "Loading URL: $url")
    }

    /**
     * execution JavaScript 并Returnresult
     */
    fun evaluateJavaScript(id: String, script: String) {
        webView.evaluateJavascript(script) { result ->
            canvasmanager.onEvalresult(id, result)
        }
    }

    /**
     * 截取 WebView Screenshot
     */
    fun takeSnapshot(id: String, format: String, maxWidth: Int?, quality: Int) {
        try {
            // Get WebView 实际content尺寸
            var w = webView.width
            var h = webView.height
            if (w <= 0 || h <= 0) {
                canvasmanager.onSnapshotresult(id, canvasmanager.Snapshotresult("", format, 0, 0))
                return
            }

            // Create Bitmap 并绘制 WebView
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.config.ARGB_8888)
            val canvas = canvas(bitmap)
            webView.draw(canvas)

            // ifneedzoom
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
            val compressformat = if (format == "jpeg" || format == "jpg") {
                Bitmap.compressformat.JPEG
            } else {
                Bitmap.compressformat.PNG
            }
            finalBitmap.compress(compressformat, quality, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val result = canvasmanager.Snapshotresult(
                base64 = base64,
                format = if (compressformat == Bitmap.compressformat.JPEG) "jpeg" else "png",
                width = finalBitmap.width,
                height = finalBitmap.height
            )
            finalBitmap.recycle()

            canvasmanager.onSnapshotresult(id, result)
        } catch (e: exception) {
            Log.e(TAG, "Snapshot failed", e)
            canvasmanager.onSnapshotresult(id, canvasmanager.Snapshotresult("", format, 0, 0))
        }
    }

    /**
     * JS Bridge — correctshould OpenClaw  window.openclawcanvasA2UIAction
     *
     * in a2ui.ts 中定义: 
     *   window.openclawcanvasA2UIAction.postMessage(raw)
     */
    inner class canvasJsBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            Log.d(TAG, "JS Bridge received: ${raw.take(200)}")
            // A2UI action — 目FrontRecordLog, back续canextendProcess
        }
    }

    /**
     * Default canvas HTML 页面
     */
    private fun defaultcanvasHtml(): String = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Claw canvas</title>
<style>
html, body { height: 100%; margin: 0; background: #0b1328; color: #e5e7eb; 
  font: 16px system-ui, Roboto, sans-serif; }
.wrap { min-height: 100%; display: grid; place-items: center; paing: 24px; }
.card { width: min(560px, 88vw); text-align: center; paing: 24px;
  border-radius: 16px; background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.10); }
h1 { margin: 0 0 8px; font-size: 22px; }
.sub { opacity: 0.7; font-size: 14px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <h1>🦞 Claw canvas</h1>
    <div class="sub">Wait agent Loadcontent...</div>
  </div>
</div>
</body>
</html>
    """.trimIndent()
}
