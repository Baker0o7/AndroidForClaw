/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/canvas-tool.ts
 *
 * AndroidForClaw adaptation: Canvas tool for Agent function calling.
 * 优先走 Screen tab Inside嵌的 CanvasController(WebView), 
 * 而不YesStart独立的 CanvasActivity. 
 */
package com.xiaomo.androidforclaw.agent.tools

import android.content.Context
import ai.openclaw.app.node.CanvasController
import com.xiaomo.androidforclaw.canvas.CanvasManager
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

/**
 * Canvas Tool — 让 Agent 通过 function calling 控制 Canvas WebView. 
 *
 * Actions:
 * - present: Show Canvas(Optional url/target)
 * - hide: Hide/Close Canvas
 * - navigate: 导航到New URL
 * - eval: 执Row JavaScript 并Returnresult
 * - snapshot: 截取 Canvas Screenshot(Return base64)
 * - a2ui_push: push A2UI JSONL Inside容
 * - a2ui_reset: Reset A2UI Inside容
 */
class CanvasTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "CanvasTool"
    }

    override val name = "canvas"
    override val description = "Control node canvases (present/hide/navigate/eval/snapshot/A2UI). Use snapshot to capture the rendered UI."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "Canvas action to perform",
                            enum = listOf("present", "hide", "navigate", "eval", "snapshot", "a2ui_push", "a2ui_reset")
                        ),
                        "url" to PropertySchema(
                            type = "string",
                            description = "URL or file path to load (for present/navigate)"
                        ),
                        "target" to PropertySchema(
                            type = "string",
                            description = "Alias for url (for present/navigate)"
                        ),
                        "javaScript" to PropertySchema(
                            type = "string",
                            description = "JavaScript code to execute (for eval)"
                        ),
                        "outputFormat" to PropertySchema(
                            type = "string",
                            description = "Snapshot output format (default: png)",
                            enum = listOf("png", "jpg", "jpeg")
                        ),
                        "maxWidth" to PropertySchema(
                            type = "number",
                            description = "Maximum width for snapshot"
                        ),
                        "quality" to PropertySchema(
                            type = "number",
                            description = "Snapshot quality (1-100, for jpeg)"
                        ),
                        "jsonl" to PropertySchema(
                            type = "string",
                            description = "A2UI JSONL content to push"
                        ),
                        "width" to PropertySchema(
                            type = "number",
                            description = "Canvas width"
                        ),
                        "height" to PropertySchema(
                            type = "number",
                            description = "Canvas height"
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    /** Get Screen tab 的 CanvasController(优先), 不Available时Return null */
    private fun getController(): CanvasController? = CanvasManager.screenTabController

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val action = args["action"] as? String
            ?: return Toolresult.error("action is required")

        return try {
            when (action) {
                "present" -> executePresent(args)
                "hide" -> executeHide()
                "navigate" -> executeNavigate(args)
                "eval" -> executeEval(args)
                "snapshot" -> executeSnapshot(args)
                "a2ui_push" -> executeA2uiPush(args)
                "a2ui_reset" -> executeA2uiReset()
                else -> Toolresult.error("Unknown canvas action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Canvas action '$action' failed", e)
            Toolresult.error("canvas.$action failed: ${e.message}")
        }
    }

    private fun executePresent(args: Map<String, Any?>): Toolresult {
        val url = args["url"] as? String ?: args["target"] as? String
        val ctrl = getController()
        if (ctrl != null) {
            // 在 Screen tab 的 WebView 中导航
            if (url != null) {
                val resolved = CanvasManager.resolveUrlPublic(url)
                ctrl.navigate(resolved)
            }
            Log.i(TAG, "canvas.present via CanvasController url=$url")
        } else {
            // Fallback: Start独立 CanvasActivity
            CanvasManager.present(context, url)
        }
        return Toolresult.success("{\"ok\":true}")
    }

    private fun executeHide(): Toolresult {
        val ctrl = getController()
        if (ctrl != null) {
            // 导航回Default首页(scaffold)
            ctrl.navigate("")
            Log.i(TAG, "canvas.hide via CanvasController (reset to scaffold)")
        } else {
            CanvasManager.hide()
        }
        return Toolresult.success("{\"ok\":true}")
    }

    private fun executeNavigate(args: Map<String, Any?>): Toolresult {
        val url = args["url"] as? String ?: args["target"] as? String
            ?: return Toolresult.error("url is required for navigate")
        val ctrl = getController()
        if (ctrl != null) {
            val resolved = CanvasManager.resolveUrlPublic(url)
            ctrl.navigate(resolved)
            Log.i(TAG, "canvas.navigate via CanvasController url=$resolved")
        } else {
            CanvasManager.navigate(url)
        }
        return Toolresult.success("{\"ok\":true}")
    }

    private suspend fun executeEval(args: Map<String, Any?>): Toolresult {
        val js = args["javaScript"] as? String
            ?: return Toolresult.error("javaScript is required for eval")
        val ctrl = getController()
        if (ctrl != null) {
            val result = ctrl.eval(js)
            return Toolresult.success(result)
        } else {
            val result = CanvasManager.eval(js)
            return Toolresult.success(result ?: "null")
        }
    }

    private suspend fun executeSnapshot(args: Map<String, Any?>): Toolresult {
        val formatRaw = (args["outputFormat"] as? String)?.lowercase() ?: "png"
        val maxWidth = (args["maxWidth"] as? Number)?.toInt()
        val quality = (args["quality"] as? Number)?.toDouble()

        val ctrl = getController()
        if (ctrl != null) {
            val format = if (formatRaw == "jpg" || formatRaw == "jpeg") {
                CanvasController.SnapshotFormat.Jpeg
            } else {
                CanvasController.SnapshotFormat.Png
            }
            val base64 = ctrl.snapshotBase64(format, quality, maxWidth)
            if (base64.isEmpty()) {
                return Toolresult.error("Snapshot failed: empty result")
            }
            val mimeType = if (format == CanvasController.SnapshotFormat.Jpeg) "image/jpeg" else "image/png"
            return Toolresult.success(
                "{\"ok\":true, \"format\":\"${format.rawValue}\", \"mimeType\":\"$mimeType\"}",
                metadata = mapOf(
                    "base64" to base64,
                    "mimeType" to mimeType,
                    "format" to format.rawValue
                )
            )
        } else {
            // Fallback to CanvasManager (独立 CanvasActivity)
            val format = if (formatRaw == "jpg" || formatRaw == "jpeg") "jpeg" else "png"
            val q = (quality?.toInt()) ?: 90
            val result = CanvasManager.snapshot(format, maxWidth, q)
            if (result.base64.isEmpty()) {
                return Toolresult.error("Snapshot failed: empty result")
            }
            val mimeType = if (format == "jpeg") "image/jpeg" else "image/png"
            return Toolresult.success(
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

    private suspend fun executeA2uiPush(args: Map<String, Any?>): Toolresult {
        val jsonl = args["jsonl"] as? String
            ?: return Toolresult.error("jsonl is required for a2ui_push")
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
            return Toolresult.success("{\"ok\":true,\"result\":$result}")
        } else {
            val activity = CanvasManager.currentActivity
                ?: return Toolresult.error("No active Canvas for a2ui_push")
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(js, null)
            }
            return Toolresult.success("{\"ok\":true}")
        }
    }

    private suspend fun executeA2uiReset(): Toolresult {
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
            return Toolresult.success("{\"ok\":true,\"result\":$result}")
        } else {
            val activity = CanvasManager.currentActivity
                ?: return Toolresult.error("No active Canvas for a2ui_reset")
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(js, null)
            }
            return Toolresult.success("{\"ok\":true}")
        }
    }
}
