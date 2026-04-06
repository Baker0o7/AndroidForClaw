/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/canvas-tool.ts
 *
 * androidforClaw adaptation: canvas tool for agent function calling.
 * Prefer Screen tab embedded canvasController (WebView),
 * rather than starting a separate canvasActivity.
 */
package com.xiaomo.androidforclaw.agent.tools

import android.content.context
import ai.openclaw.app.node.canvasController
import com.xiaomo.androidforclaw.canvas.canvasManager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * Canvas tool — let agent control canvas WebView via function calling.
 *
 * Actions:
 * - present: Show canvas (optional url/target)
 * - hide: Hide/Close canvas
 * - navigate: Navigate to new URL
 * - eval: Execute JavaScript and return result
 * - snapshot: Capture canvas screenshot (return base64)
 * - a2ui_push: Push A2UI JSONL content
 * - a2ui_reset: Reset A2UI content
 */
class canvastool(private val context: context) : tool {
    companion object {
        private const val TAG = "canvastool"
    }

    override val name = "canvas"
    override val description = "Control node canvases (present/hide/navigate/eval/snapshot/A2UI). use snapshot to capture the rendered UI."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    properties = mapOf(
                        "action" to Propertyschema(
                            type = "string",
                            description = "canvas action to perform",
                            enum = listOf("present", "hide", "navigate", "eval", "snapshot", "a2ui_push", "a2ui_reset")
                        ),
                        "url" to Propertyschema(
                            type = "string",
                            description = "URL or file path to load (for present/navigate)"
                        ),
                        "target" to Propertyschema(
                            type = "string",
                            description = "Alias for url (for present/navigate)"
                        ),
                        "javaScript" to Propertyschema(
                            type = "string",
                            description = "JavaScript code to execute (for eval)"
                        ),
                        "outputformat" to Propertyschema(
                            type = "string",
                            description = "Snapshot output format (default: png)",
                            enum = listOf("png", "jpg", "jpeg")
                        ),
                        "maxWidth" to Propertyschema(
                            type = "number",
                            description = "Maximum width for snapshot"
                        ),
                        "quality" to Propertyschema(
                            type = "number",
                            description = "Snapshot quality (1-100, for jpeg)"
                        ),
                        "jsonl" to Propertyschema(
                            type = "string",
                            description = "A2UI JSONL content to push"
                        ),
                        "width" to Propertyschema(
                            type = "number",
                            description = "canvas width"
                        ),
                        "height" to Propertyschema(
                            type = "number",
                            description = "canvas height"
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    /** Get Screen tab canvasController (prefer), return null if not available */
    private fun getController(): canvasController? = canvasManager.screenTabController

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val action = args["action"] as? String
            ?: return toolresult.error("action is required")

        return try {
            when (action) {
                "present" -> executePresent(args)
                "hide" -> executeHide()
                "navigate" -> executeNavigate(args)
                "eval" -> executeEval(args)
                "snapshot" -> executeSnapshot(args)
                "a2ui_push" -> executeA2uiPush(args)
                "a2ui_reset" -> executeA2uiReset()
                else -> toolresult.error("Unknown canvas action: $action")
            }
        } catch (e: exception) {
            Log.e(TAG, "canvas action '$action' failed", e)
            toolresult.error("canvas.$action failed: ${e.message}")
        }
    }

    private fun executePresent(args: Map<String, Any?>): toolresult {
        val url = args["url"] as? String ?: args["target"] as? String
        val ctrl = getController()
        if (ctrl != null) {
            // Navigate in Screen tab WebView
            if (url != null) {
                val resolved = canvasManager.resolveUrlPublic(url)
                ctrl.navigate(resolved)
            }
            Log.i(TAG, "canvas.present via canvasController url=$url")
        } else {
            // Fallback: Start separate canvasActivity
            canvasManager.present(context, url)
        }
        return toolresult.success("{\"ok\":true}")
    }

    private fun executeHide(): toolresult {
        val ctrl = getController()
        if (ctrl != null) {
            // Navigate to default home (scaffold)
            ctrl.navigate("")
            Log.i(TAG, "canvas.hide via canvasController (reset to scaffold)")
        } else {
            canvasManager.hide()
        }
        return toolresult.success("{\"ok\":true}")
    }

    private fun executeNavigate(args: Map<String, Any?>): toolresult {
        val url = args["url"] as? String ?: args["target"] as? String
            ?: return toolresult.error("url is required for navigate")
        val ctrl = getController()
        if (ctrl != null) {
            val resolved = canvasmanager.resolveUrlPublic(url)
            ctrl.navigate(resolved)
            Log.i(TAG, "canvas.navigate via canvasController url=$resolved")
        } else {
            canvasmanager.navigate(url)
        }
        return toolresult.success("{\"ok\":true}")
    }

    private suspend fun executeEval(args: Map<String, Any?>): toolresult {
        val js = args["javaScript"] as? String
            ?: return toolresult.error("javaScript is required for eval")
        val ctrl = getController()
        if (ctrl != null) {
            val result = ctrl.eval(js)
            return toolresult.success(result)
        } else {
            val result = canvasmanager.eval(js)
            return toolresult.success(result ?: "null")
        }
    }

    private suspend fun executeSnapshot(args: Map<String, Any?>): toolresult {
        val formatRaw = (args["outputformat"] as? String)?.lowercase() ?: "png"
        val maxWidth = (args["maxWidth"] as? Number)?.toInt()
        val quality = (args["quality"] as? Number)?.toDouble()

        val ctrl = getController()
        if (ctrl != null) {
            val format = if (formatRaw == "jpg" || formatRaw == "jpeg") {
                canvasController.Snapshotformat.Jpeg
            } else {
                canvasController.Snapshotformat.Png
            }
            val base64 = ctrl.snapshotBase64(format, quality, maxWidth)
            if (base64.isEmpty()) {
                return toolresult.error("Snapshot failed: empty result")
            }
            val mimeType = if (format == canvasController.Snapshotformat.Jpeg) "image/jpeg" else "image/png"
            return toolresult.success(
                "{\"ok\":true, \"format\":\"${format.rawValue}\", \"mimeType\":\"$mimeType\"}",
                metadata = mapOf(
                    "base64" to base64,
                    "mimeType" to mimeType,
                    "format" to format.rawValue
                )
            )
        } else {
            // Fallback to canvasManager (separate canvasActivity)
            val format = if (formatRaw == "jpg" || formatRaw == "jpeg") "jpeg" else "png"
            val q = (quality?.toInt()) ?: 90
            val result = canvasmanager.snapshot(format, maxWidth, q)
            if (result.base64.isEmpty()) {
                return toolresult.error("Snapshot failed: empty result")
            }
            val mimeType = if (format == "jpeg") "image/jpeg" else "image/png"
            return toolresult.success(
                "{\"ok\":true, \"format\":\"${result.format}\", \"width\":${result.width}, \"height\":${result.height}, \"mimeType\":\"$mimeType\"}",
                metadata = mapOf(
                    "base64" to result.base64,
                    "mimeType" to mimeType,
                    "format" to result.format,
                    "width" to result.width,
                    "height" to result.height
                )
            )
        }
    }

    private suspend fun executeA2uiPush(args: Map<String, Any?>): toolresult {
        val jsonl = args["jsonl"] as? String
            ?: return toolresult.error("jsonl is required for a2ui_push")
        val escaped = jsonl.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val js = """
            (function() {
                if (window.__openclaw && window.__openclaw.a2ui && window.__openclaw.a2ui.pushJSONL) {
                    window.__openclaw.a2ui.pushJSONL("$escaped");
                    return "ok";
                }
                return "a2ui not available";
            })()
        """.trimIndent()

        val ctrl = getController()
        if (ctrl != null) {
            val result = ctrl.eval(js)
            return toolresult.success("{\"ok\":true,\"result\":$result}")
        } else {
            val activity = canvasmanager.currentActivity
                ?: return toolresult.error("No active canvas for a2ui_push")
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(js, null)
            }
            return toolresult.success("{\"ok\":true}")
        }
    }

    private suspend fun executeA2uiReset(): toolresult {
        val js = """
            (function() {
                if (window.__openclaw && window.__openclaw.a2ui && window.__openclaw.a2ui.reset) {
                    window.__openclaw.a2ui.reset();
                    return "ok";
                }
                return "a2ui not available";
            })()
        """.trimIndent()

        val ctrl = getController()
        if (ctrl != null) {
            val result = ctrl.eval(js)
            return toolresult.success("{\"ok\":true,\"result\":$result}")
        } else {
            val activity = canvasmanager.currentActivity
                ?: return toolresult.error("No active canvas for a2ui_reset")
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(js, null)
            }
            return toolresult.success("{\"ok\":true}")
        }
    }
}
