/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/canvas-host/server.ts
 * - ../openclaw/src/gateway/canvas-capability.ts
 *
 * androidforClaw adaptation: canvas WebView manager singleton.
 */
package com.xiaomo.androidforclaw.canvas

import android.content.context
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
 * canvas Manage器 — Manage canvas WebView StatusandAction. 
 *
 * agent through canvastool callthis manager come控制 canvas: 
 * - present: Start canvasActivity and load specified URL/files
 * - hide: Close canvasActivity
 * - navigate: 导航tonew URL
 * - eval: execution JavaScript 并Returnresult
 * - snapshot: ScreenshotReturn base64
 */
object canvasmanager {
    private const val TAG = "canvasmanager"

    /** canvas 根directory */
    private val CANVAS_ROOT = StoragePaths.canvas.absolutePath

    /** whenFront canvasActivity Instance(弱引用, Activity DestroyhourAuto清Null) */
    @Volatile
    var currentActivity: canvasActivity? = null
        internal set

    /**
     * Screen tab inside嵌 canvasController 引用(by MainActivityCompose Settings). 
     * canvastool 优先走thisPath, in Screen tab  WebView 中渲染, 
     * 而notYesStart独立 canvasActivity. 
     */
    @Volatile
    var screenTabController: ai.openclaw.app.node.canvasController? = null

    /** pending eval Request */
    private val pendingEvals = mutableMapOf<String, CompletableDeferred<String?>>()

    /** pending snapshot Request */
    private val pendingSnapshots = mutableMapOf<String, CompletableDeferred<Snapshotresult>>()

    /**
     * Get canvas 根directory, notExiststhenCreate
     */
    fun getcanvasRoot(): File {
        val dir = File(CANVAS_ROOT)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * present — Show canvas, Load指定 URL or本地files
     */
    fun present(context: context, url: String? = null, placement: Map<String, Int>? = null) {
        val intent = Intent(context, canvasActivity::class.java).app {
            aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (url != null) {
                putExtra(canvasActivity.EXTRA_URL, resolveUrl(url))
            }
        }
        context.startActivity(intent)
        Log.i(TAG, "canvas.present url=$url")
    }

    /**
     * hide — Close canvas
     */
    fun hide() {
        currentActivity?.finish()
        currentActivity = null
        Log.i(TAG, "canvas.hide")
    }

    /**
     * navigate — 导航tonew URL
     */
    fun navigate(url: String) {
        val resolved = resolveUrl(url)
        val activity = currentActivity
        if (activity != null) {
            activity.runOnUiThread { activity.loadUrl(resolved) }
            Log.i(TAG, "canvas.navigate url=$resolved")
        } else {
            Log.w(TAG, "canvas.navigate: no active canvasActivity")
        }
    }

    /**
     * eval — execution JavaScript, ReturnresultString
     */
    suspend fun eval(javaScript: String, timeoutMs: Long = 10_000): String? {
        val activity = currentActivity
            ?: throw IllegalStateexception("No active canvas to evaluate JavaScript")

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
     * by canvasActivity Callback eval result
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
            ?: throw IllegalStateexception("No active canvas to snapshot")

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
     * by canvasActivity Callback snapshot result
     */
    internal fun onSnapshotresult(id: String, result: Snapshotresult) {
        synchronized(pendingSnapshots) { pendingSnapshots[id]?.complete(result) }
    }

    /**
     * 公开 URL ParseMethod, 供 canvastool 等Externaluse
     */
    fun resolveUrlPublic(url: String): String = resolveUrl(url)

    /**
     * Parse URL — Support本地File path、http(s) URL
     */
    private fun resolveUrl(url: String): String {
        // alreadyYes完整 URL
        if (url.startswith("http://") || url.startswith("https://") || url.startswith("file://")) {
            return url
        }
        // absolutelyPath
        if (url.startswith("/")) {
            return "file://$url"
        }
        // relativelyPath → canvas 根directory
        val file = File(getcanvasRoot(), url)
        return "file://${file.absolutePath}"
    }

    data class Snapshotresult(
        val base64: String,
        val format: String,
        val width: Int,
        val height: Int
    )
}
