/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/canvas-host/server.ts
 * - ../openclaw/src/gateway/canvas-capability.ts
 *
 * AndroidForClaw adaptation: Canvas WebView manager singleton.
 */
package com.xiaomo.androidforclaw.canvas

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * Canvas Manage器 — Manage Canvas WebView 的Status和Action. 
 *
 * Agent 通过 CanvasTool call此 manager 来控制 Canvas: 
 * - present: Start CanvasActivity And load specified URL/文件
 * - hide: Close CanvasActivity
 * - navigate: 导航到New URL
 * - eval: 执Row JavaScript 并Returnresult
 * - snapshot: ScreenshotReturn base64
 */
object CanvasManager {
    private const val TAG = "CanvasManager"

    /** Canvas 根目录 */
    private val CANVAS_ROOT = StoragePaths.canvas.absolutePath

    /** 当Front CanvasActivity Instance(弱引用, Activity Destroy时Auto清Null) */
    @Volatile
    var currentActivity: CanvasActivity? = null
        internal set

    /**
     * Screen tab Inside嵌的 CanvasController 引用(by MainActivityCompose Settings). 
     * CanvasTool 优先走此Path, 在 Screen tab 的 WebView 中渲染, 
     * 而不YesStart独立的 CanvasActivity. 
     */
    @Volatile
    var screenTabController: ai.openclaw.app.node.CanvasController? = null

    /** pending eval Request */
    private val pendingEvals = mutableMapOf<String, CompletableDeferred<String?>>()

    /** pending snapshot Request */
    private val pendingSnapshots = mutableMapOf<String, CompletableDeferred<Snapshotresult>>()

    /**
     * Get canvas 根目录, 不Exists则Create
     */
    fun getCanvasRoot(): File {
        val dir = File(CANVAS_ROOT)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * present — Show Canvas, Load指定 URL 或本地文件
     */
    fun present(context: Context, url: String? = null, placement: Map<String, Int>? = null) {
        val intent = Intent(context, CanvasActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (url != null) {
                putExtra(CanvasActivity.EXTRA_URL, resolveUrl(url))
            }
        }
        context.startActivity(intent)
        Log.i(TAG, "canvas.present url=$url")
    }

    /**
     * hide — Close Canvas
     */
    fun hide() {
        currentActivity?.finish()
        currentActivity = null
        Log.i(TAG, "canvas.hide")
    }

    /**
     * navigate — 导航到New URL
     */
    fun navigate(url: String) {
        val resolved = resolveUrl(url)
        val activity = currentActivity
        if (activity != null) {
            activity.runOnUiThread { activity.loadUrl(resolved) }
            Log.i(TAG, "canvas.navigate url=$resolved")
        } else {
            Log.w(TAG, "canvas.navigate: no active CanvasActivity")
        }
    }

    /**
     * eval — 执Row JavaScript, ReturnresultString
     */
    suspend fun eval(javaScript: String, timeoutMs: Long = 10_000): String? {
        val activity = currentActivity
            ?: throw IllegalStateException("No active Canvas to evaluate JavaScript")

        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String?>()
        synchronized(pendingEvals) { pendingEvals[id] = deferred }

        activity.runOnUiThread { activity.evaluateJavaScript(id, javaScript) }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(pendingEvals) { pendingEvals.remove(id) }
        }
    }

    /**
     * by CanvasActivity Callback eval result
     */
    internal fun onEvalresult(id: String, result: String?) {
        synchronized(pendingEvals) { pendingEvals[id]?.complete(result) }
    }

    /**
     * snapshot — 截取 WebView Screenshot
     */
    suspend fun snapshot(
        format: String = "png",
        maxWidth: Int? = null,
        quality: Int = 90,
        timeoutMs: Long = 15_000
    ): Snapshotresult {
        val activity = currentActivity
            ?: throw IllegalStateException("No active Canvas to snapshot")

        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Snapshotresult>()
        synchronized(pendingSnapshots) { pendingSnapshots[id] = deferred }

        activity.runOnUiThread { activity.takeSnapshot(id, format, maxWidth, quality) }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(pendingSnapshots) { pendingSnapshots.remove(id) }
        }
    }

    /**
     * by CanvasActivity Callback snapshot result
     */
    internal fun onSnapshotresult(id: String, result: Snapshotresult) {
        synchronized(pendingSnapshots) { pendingSnapshots[id]?.complete(result) }
    }

    /**
     * 公开的 URL ParseMethod, 供 CanvasTool 等Externaluse
     */
    fun resolveUrlPublic(url: String): String = resolveUrl(url)

    /**
     * Parse URL — Support本地File path、http(s) URL
     */
    private fun resolveUrl(url: String): String {
        // 已Yes完整 URL
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return url
        }
        // absolutelyPath
        if (url.startsWith("/")) {
            return "file://$url"
        }
        // relativelyPath → canvas 根目录
        val file = File(getCanvasRoot(), url)
        return "file://${file.absolutePath}"
    }

    data class Snapshotresult(
        val base64: String,
        val format: String,
        val width: Int,
        val height: Int
    )
}
