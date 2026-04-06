package com.xiaomo.androidforclaw.agent.tools.device

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser-tool.ts (Android adaptation)
 *
 * AndroidForClaw adaptation: unified device control tool aligned with
 * Playwright/OpenClaw browser tool pattern.
 *
 * Usage pattern (same as Playwright):
 *   1. device(action="snapshot") → get UI tree with refs
 *   2. device(action="act", kind="tap", ref="e5") → act on element
 *   3. device(action="snapshot") → verify result
 */

import android.content.Context
import android.content.Intent
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.Tool
import com.xiaomo.androidforclaw.agent.tools.Toolresult
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import kotlinx.coroutines.delay

class DeviceTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "DeviceTool"
        // Aligned with Playwright Computer Use: wait after actions for UI to settle
        private const val POST_ACTION_DELAY_MS = 800L  // after tap/type/press
        private const val POST_OPEN_DELAY_MS = 1500L   // after opening apps
        private const val POST_SCROLL_DELAY_MS = 500L   // after scroll
    }

    override val name = "device"
    override val description = "Control the Android device screen. Use snapshot to get UI elements with refs, then act on them."

    private val refManager = RefManager()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "Action: snapshot | screenshot | act | open | status",
                            enum = listOf("snapshot", "screenshot", "act", "open", "status")
                        ),
                        "kind" to PropertySchema(
                            type = "string",
                            description = "For action=act: tap | type | press | long_press | scroll | swipe | wait | home | back",
                            enum = listOf("tap", "type", "press", "long_press", "scroll", "swipe", "wait", "home", "back")
                        ),
                        "ref" to PropertySchema(
                            type = "string",
                            description = "Element ref from snapshot (e.g. 'e5')"
                        ),
                        "text" to PropertySchema(
                            type = "string",
                            description = "Text to type (for kind=type)"
                        ),
                        "key" to PropertySchema(
                            type = "string",
                            description = "Key to press: BACK, HOME, ENTER, TAB, VOLUME_UP, etc."
                        ),
                        "coordinate" to PropertySchema(
                            type = "array",
                            description = "Fallback [x, y] coordinate when ref not available. For swipe: end coordinate.",
                            items = PropertySchema(type = "integer", description = "coordinate value")
                        ),
                        "start_coordinate" to PropertySchema(
                            type = "array",
                            description = "Start [x, y] coordinate for swipe gesture",
                            items = PropertySchema(type = "integer", description = "coordinate value")
                        ),
                        "direction" to PropertySchema(
                            type = "string",
                            description = "Scroll direction",
                            enum = listOf("up", "down", "left", "right")
                        ),
                        "amount" to PropertySchema(
                            type = "number",
                            description = "Scroll amount (default: 3)"
                        ),
                        "timeMs" to PropertySchema(
                            type = "number",
                            description = "Wait time in milliseconds"
                        ),
                        "package_name" to PropertySchema(
                            type = "string",
                            description = "App package name for action=open"
                        ),
                        "format" to PropertySchema(
                            type = "string",
                            description = "Snapshot format: compact (default) | tree | interactive",
                            enum = listOf("compact", "tree", "interactive")
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val action = args["action"] as? String ?: return Toolresult.error("Missing action")

        return when (action) {
            "snapshot" -> executeSnapshot(args)
            "screenshot" -> executeScreenshot()
            "act" -> executeAct(args)
            "open" -> executeOpen(args)
            "status" -> executeStatus()
            else -> Toolresult.error("Unknown action: $action")
        }
    }

    // ==================== snapshot ====================

    private suspend fun executeSnapshot(args: Map<String, Any?>): Toolresult {
        val format = (args["format"] as? String) ?: "compact"

        val proxy = AccessibilityProxy

        val viewNodes = try {
            proxy.dumpViewTree(useCache = false)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Accessibility service not available", e)
            return Toolresult.error("AccessibilityService未开启. 请到 Settings → Accessibility → AndroidForClaw 开启AccessibilityPermission, 才能GetScreenElement. ")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump view tree", e)
            return Toolresult.error("Get UI TreeFailed: ${e.message}. 请CheckAccessibilityServiceYesNo正常Run. ")
        }

        if (viewNodes.isEmpty()) {
            val accessibilityOn = try { proxy.isConnected.value == true && proxy.isServiceReady() } catch (_: Exception) { false }
            val status = if (accessibilityOn) "AccessibilityService: ✅ 已开启(但当Front页面NoneIdentifiable elements, possibly页面正在Load, suggest等 1-2 秒Retry)" 
                         else "AccessibilityService: ❌ 未开启. 请到 Settings → Accessibility → AndroidForClaw 开启AccessibilityPermission. "
            return Toolresult.error(status)
        }

        val nodes = SnapshotBuilder.buildFromViewNodes(viewNodes)
        refManager.updateRefs(nodes)

        // Get screen info
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val appName = try {
            proxy.getCurrentPackageName().let { pkg ->
                if (pkg.isNotBlank()) {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                } else null
            }
        } catch (e: Exception) { null }

        val output = when (format) {
            "tree" -> SnapshotFormatter.tree(nodes, width, height, appName)
            "interactive" -> SnapshotFormatter.interactive(nodes, appName)
            else -> SnapshotFormatter.compact(nodes, width, height, appName)
        }

        return Toolresult.success(output)
    }

    // ==================== screenshot ====================

    private suspend fun executeScreenshot(): Toolresult {
        // Delegate to existing ScreenshotSkill logic
        val screenshotresult = try {
            val controller = com.xiaomo.androidforclaw.DeviceController
            controller.getScreenshot(context)
        } catch (e: Exception) {
            null
        }

        if (screenshotresult == null) {
            // Fallback: try shell screencap
            try {
                val path = "${StoragePaths.workspaceScreenshots.absolutePath}/device_${System.currentTimeMillis()}.png"
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $path"))
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val file = java.io.File(path)
                if (file.exists() && file.length() > 0) {
                    return Toolresult.success("Screenshot saved: $path (${file.length()} bytes)")
                }
            } catch (_: Exception) {}
            return Toolresult.error("Screenshot failed. Please grant screen capture permission.")
        }

        val (bitmap, path) = screenshotresult
        return Toolresult.success("Screenshot: ${bitmap.width}x${bitmap.height}, path: $path")
    }

    // ==================== act ====================

    private suspend fun executeAct(args: Map<String, Any?>): Toolresult {
        val kind = args["kind"] as? String ?: return Toolresult.error("Missing 'kind' for action=act")

        return when (kind) {
            "tap" -> executeTap(args)
            "type" -> executeType(args)
            "press" -> executePress(args)
            "long_press" -> executeLongPress(args)
            "scroll" -> executeScroll(args)
            "swipe" -> executeSwipe(args)
            "wait" -> executeWait(args)
            "home" -> executeKey("HOME")
            "back" -> executeKey("BACK")
            else -> Toolresult.error("Unknown kind: $kind")
        }
    }

    private suspend fun executeTap(args: Map<String, Any?>): Toolresult {
        val (x, y, label) = resolveCoordinate(args) ?: return Toolresult.error("Cannot resolve target. Provide ref or coordinate.")

        return try {
            val ok = AccessibilityProxy.tap(x, y)
            if (!ok) {
                Toolresult.error("Tap failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                Toolresult.success("Tapped${label?.let { " '$it'" } ?: ""} at ($x, $y)")
            }
        } catch (e: Exception) {
            Toolresult.error("Tap failed: ${e.message}")
        }
    }

    private suspend fun executeType(args: Map<String, Any?>): Toolresult {
        val text = args["text"] as? String ?: return Toolresult.error("Missing 'text' for kind=type")

        val clipboardHelper = com.xiaomo.androidforclaw.service.ClipboardInputHelper
        val clawIme = com.xiaomo.androidforclaw.service.ClawIMEManager
        val clawImeActive = clawIme.isClawImeEnableddd(context) && clawIme.isConnected()
        val accessibilityAvailable = AccessibilityProxy.isServiceReady()
        val clipboardAvailable = accessibilityAvailable && clipboardHelper.isClipboardAvailable(context)

        // If ref provided, try to focus input
        val resolved = resolveCoordinate(args)
        if (resolved != null) {
            val (x, y, _) = resolved
            if (accessibilityAvailable) {
                AccessibilityProxy.tap(x, y)
                delay(POST_ACTION_DELAY_MS)
            } else if (clawImeActive) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap $x $y")).waitFor()
                delay(POST_ACTION_DELAY_MS)
            } else {
                return Toolresult.error("InputFailed: AccessibilityService和 ClawIME 均未Enabledd, Cannot聚焦Input field")
            }
        }

        // Type text: 优先cut板 → 兜底 ClawIME → 兜底 shell input
        try {
            val typed: Boolean
            val method: String

            if (clipboardAvailable) {
                // 优先走cut板paste(Most reliable, SupportAll字符)
                typed = clipboardHelper.inputTextViaClipboard(context, text)
                method = "clipboard"
                Log.d(TAG, "Clipboard.inputText('${text.take(30)}'): $typed")
            } else if (clawImeActive) {
                // 兜底到 ClawIME Key盘Input
                typed = clawIme.inputText(text)
                method = "clawime"
                Log.d(TAG, "ClawIME.inputText('${text.take(30)}'): $typed")
            } else {
                // most终兜底: shell input text(仅Support ASCII)
                val escaped = text.replace("'", "'\\''")
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escaped'"))
                val exitCode = proc.waitFor()
                typed = exitCode == 0
                method = "shell"
                Log.d(TAG, "shell input text exitCode: $exitCode")
            }

            if (!typed) {
                val hint = if (!accessibilityAvailable && !clawImeActive) {
                    "请开启AccessibilityService(recommend, Supportcut板paste), 或switch到 ClawIME Input method"
                } else if (!accessibilityAvailable) {
                    "cut板pasteNeedAccessibilityService. 请在Settings中开启AccessibilityPermission以obtainmoreokInput体验"
                } else {
                    "InputFailed, 请Retry"
                }
                return Toolresult.error("Type failed: $hint")
            }
            val refLabel = (args["ref"] as? String)?.let { refManager.getRefNode(it)?.name }
            return Toolresult.success("Typed '${text.take(100)}'${refLabel?.let { " into '$it'" } ?: ""} (via $method)")
        } catch (e: Exception) {
            return Toolresult.error("Type failed: ${e.message}")
        }
    }

    private suspend fun executePress(args: Map<String, Any?>): Toolresult {
        val key = (args["key"] as? String) ?: (args["text"] as? String)
            ?: return Toolresult.error("Missing 'key' for kind=press")
        return executeKey(key)
    }

    private fun executeKey(key: String): Toolresult {
        return try {
            val ok = when (key.uppercase()) {
                "BACK" -> AccessibilityProxy.pressBack()
                "HOME" -> AccessibilityProxy.pressHome()
                else -> {
                    val keycode = mapKeyToKeycode(key)
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode")).waitFor()
                    true
                }
            }
            if (ok) Toolresult.success("Pressed $key")
            else Toolresult.error("Key press failed for $key")
        } catch (e: Exception) {
            Toolresult.error("Key press failed: ${e.message}")
        }
    }

    private suspend fun executeLongPress(args: Map<String, Any?>): Toolresult {
        val (x, y, label) = resolveCoordinate(args) ?: return Toolresult.error("Cannot resolve target.")

        return try {
            val ok = AccessibilityProxy.longPress(x, y)
            if (!ok) {
                Toolresult.error("Long press failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                Toolresult.success("Long pressed${label?.let { " '$it'" } ?: ""} at ($x, $y)")
            }
        } catch (e: Exception) {
            Toolresult.error("Long press failed: ${e.message}")
        }
    }

    private suspend fun executeScroll(args: Map<String, Any?>): Toolresult {
        val direction = (args["direction"] as? String) ?: "down"
        val amount = ((args["amount"] as? Number)?.toInt()) ?: 3
        val dm = context.resources.displayMetrics
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 2
        val distance = dm.heightPixels / 4 * amount

        val (sx, sy, ex, ey) = when (direction) {
            "down" -> listOf(cx, cy + distance / 2, cx, cy - distance / 2)
            "up" -> listOf(cx, cy - distance / 2, cx, cy + distance / 2)
            "left" -> listOf(cx - distance / 2, cy, cx + distance / 2, cy)
            "right" -> listOf(cx + distance / 2, cy, cx - distance / 2, cy)
            else -> return Toolresult.error("Invalid direction: $direction")
        }

        return try {
            val ok = AccessibilityProxy.swipe(sx, sy, ex, ey, 300)
            if (!ok) {
                Toolresult.error("Scroll failed via accessibility service")
            } else {
                delay(POST_SCROLL_DELAY_MS)
                Toolresult.success("Scrolled $direction (amount=$amount)")
            }
        } catch (e: Exception) {
            Toolresult.error("Scroll failed: ${e.message}")
        }
    }

    private suspend fun executeSwipe(args: Map<String, Any?>): Toolresult {
        @Suppress("UNCHECKED_CAST")
        val startCoord = args["start_coordinate"] as? List<Number>
        @Suppress("UNCHECKED_CAST")
        val endCoord = args["coordinate"] as? List<Number>

        if (startCoord == null || endCoord == null || startCoord.size < 2 || endCoord.size < 2) {
            return Toolresult.error("Swipe requires start_coordinate and coordinate (both [x, y])")
        }

        return try {
            val ok = AccessibilityProxy.swipe(
                startCoord[0].toInt(),
                startCoord[1].toInt(),
                endCoord[0].toInt(),
                endCoord[1].toInt(),
                300
            )
            if (ok) {
                Toolresult.success("Swiped from (${startCoord[0]}, ${startCoord[1]}) to (${endCoord[0]}, ${endCoord[1]})")
            } else {
                Toolresult.error("Swipe failed via accessibility service")
            }
        } catch (e: Exception) {
            Toolresult.error("Swipe failed: ${e.message}")
        }
    }

    private suspend fun executeWait(args: Map<String, Any?>): Toolresult {
        val ms = ((args["timeMs"] as? Number)?.toLong()) ?: 1000
        delay(ms.coerceIn(100, 10_000))
        return Toolresult.success("Waited ${ms}ms")
    }

    // ==================== open ====================

    private suspend fun executeOpen(args: Map<String, Any?>): Toolresult {
        val packageName = args["package_name"] as? String
            ?: return Toolresult.error("Missing package_name")

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (_: Exception) { packageName }
                kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
                Toolresult.success("Opened $appName ($packageName)")
            } else {
                Toolresult.error("App not found: $packageName")
            }
        } catch (e: Exception) {
            Toolresult.error("Failed to open app: ${e.message}")
        }
    }

    // ==================== status ====================

    private fun executeStatus(): Toolresult {
        val proxy = AccessibilityProxy
        val connected = proxy.isConnected.value == true
        val refCount = refManager.getRefCount()
        val stale = refManager.isStale()

        return Toolresult.success(buildString {
            appendLine("Device status:")
            appendLine("  Accessibility: ${if (connected) "✅ connected" else "❌ not connected"}")
            appendLine("  Cached refs: $refCount${if (stale) " (stale)" else ""}")
            appendLine("  Screen: ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
        })
    }

    // ==================== helpers ====================

    private data class ResolvedCoordinate(val x: Int, val y: Int, val label: String?)

    @Suppress("UNCHECKED_CAST")
    private fun resolveCoordinate(args: Map<String, Any?>): ResolvedCoordinate? {
        // Priority 1: ref
        val ref = args["ref"] as? String
        if (ref != null) {
            val coord = refManager.resolveRef(ref)
            if (coord != null) {
                val label = refManager.getRefNode(ref)?.name
                return ResolvedCoordinate(coord.first, coord.second, label)
            }
            Log.w(TAG, "Ref '$ref' not found in cache, trying coordinate fallback")
        }

        // Priority 2: coordinate
        val coordList = args["coordinate"]
        if (coordList is List<*> && coordList.size >= 2) {
            val x = (coordList[0] as? Number)?.toInt()
            val y = (coordList[1] as? Number)?.toInt()
            if (x != null && y != null) {
                return ResolvedCoordinate(x, y, null)
            }
        }

        // Priority 3: x, y params
        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        if (x != null && y != null) {
            return ResolvedCoordinate(x, y, null)
        }

        return null
    }

    private fun mapKeyToKeycode(key: String): String {
        return when (key.uppercase()) {
            "BACK" -> "KEYCODE_BACK"
            "HOME" -> "KEYCODE_HOME"
            "ENTER", "RETURN" -> "KEYCODE_ENTER"
            "TAB" -> "KEYCODE_TAB"
            "ESCAPE", "ESC" -> "KEYCODE_ESCAPE"
            "DELETE", "DEL" -> "KEYCODE_DEL"
            "VOLUME_UP" -> "KEYCODE_VOLUME_UP"
            "VOLUME_DOWN" -> "KEYCODE_VOLUME_DOWN"
            "POWER" -> "KEYCODE_POWER"
            "SPACE" -> "KEYCODE_SPACE"
            "MENU" -> "KEYCODE_MENU"
            "RECENT", "APP_SWITCH" -> "KEYCODE_APP_SWITCH"
            else -> "KEYCODE_$key"
        }
    }
}
