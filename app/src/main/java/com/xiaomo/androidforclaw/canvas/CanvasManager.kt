/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/canvas-host/server.ts
 * - ../openclaw/src/gateway/canvas-capability.ts
 *
 * androidforClaw adaptation: canvas WebView manager singleton.
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
 * Canvas Manager — Manage canvas WebView status and action.
 *
 * Agent through canvas tool call this manager to control canvas:
 * - present: Start CanvasActivity and load specified URL/files
 * - hide: Close CanvasActivity
 * - navigate: navigate to new URL
 * - eval: execute JavaScript and return result
 * - snapshot: screenshot return base64
 */
object CanvasManager {
    private const val TAG = "CanvasManager"

    /** Canvas root directory */
    private val CANVAS_ROOT = StoragePaths.canvas.absolutePath

    /** Current CanvasActivity instance (weak reference, Activity destroyed auto clear null) */
    @Volatile
    var currentActivity: CanvasActivity? = null
        internal set

    /**
     * Screen tab embedded canvas controller reference (by MainActivityCompose settings).
     * Canvas tool prefers this path, renders in Screen tab WebView,
     * instead of starting a standalone CanvasActivity.
     */
    @Volatile
    var screenTabController: ai.openclaw.app.node.canvasController? = null

    /** Pending eval request */
    private val pendingEvals = mutableMapOf<String, CompletableDeferred<String?>>()

    /** Pending snapshot request */
    private val pendingSnapshots = mutableMapOf<String, CompletableDeferred<SnapshotResult>>()

    /**
     * Get canvas root directory, create if not exists
     */
    fun getCanvasRoot(): File {
        val dir = File(CANVAS_ROOT)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * present — Show canvas, load specified URL or local files
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
     * hide — Close canvas
     */
    fun hide() {
        currentActivity?.finish()
        currentActivity = null
        Log.i(TAG, "canvas.hide")
    }

    /**
     * navigate — navigate to new URL
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
     * eval — execute JavaScript, return result string
     */
    suspend fun eval(javaScript: String, timeoutMs: Long = 10_000): String? {
        val activity = currentActivity
            ?: throw IllegalStateException("No active canvas to evaluate JavaScript")

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
     * Called by CanvasActivity callback eval result
     */
    internal fun onEvalResult(id: String, result: String?) {
        synchronized(pendingEvals) { pendingEvals[id]?.complete(result) }
    }

    /**
     * snapshot — capture WebView screenshot
     */
    suspend fun snapshot(
        format: String = "png",
        maxWidth: Int? = null,
        quality: Int = 90,
        timeoutMs: Long = 15_000
    ): SnapshotResult {
        val activity = currentActivity
            ?: throw IllegalStateException("No active canvas to snapshot")

        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SnapshotResult>()
        synchronized(pendingSnapshots) { pendingSnapshots[id] = deferred }

        activity.runOnUiThread { activity.takeSnapshot(id, format, maxWidth, quality) }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(pendingSnapshots) { pendingSnapshots.remove(id) }
        }
    }

    /**
     * Called by CanvasActivity callback snapshot result
     */
    internal fun onSnapshotResult(id: String, result: SnapshotResult) {
        synchronized(pendingSnapshots) { pendingSnapshots[id]?.complete(result) }
    }

    /**
     * Public URL parse method, for canvas tool and other external use
     */
    fun resolveUrlPublic(url: String): String = resolveUrl(url)

    /**
     * Parse URL — support local file path, http(s) URL
     */
    private fun resolveUrl(url: String): String {
        // Already a complete URL
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return url
        }
        // Absolute path
        if (url.startsWith("/")) {
            return "file://$url"
        }
        // Relative path → canvas root directory
        val file = File(getCanvasRoot(), url)
        return "file://${file.absolutePath}"
    }

    data class SnapshotResult(
        val base64: String,
        val format: String,
        val width: Int,
        val height: Int
    )
}
