package com.xiaomo.androidforclaw.agent.tools.device

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser-tool.ts (android adaptation)
 *
 * androidforClaw adaptation: unified device control tool aligned with
 * Playwright/OpenClaw browser tool pattern.
 *
 * Usage pattern (same as Playwright):
 *   1. device(action="snapshot") → get UI tree with refs
 *   2. device(action="act", kind="tap", ref="e5") → act on element
 *   3. device(action="snapshot") → verify result
 */

import android.content.context
import android.content.Intent
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.tool
import com.xiaomo.androidforclaw.agent.tools.toolresult
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.delay

class Devicetool(private val context: context) : tool {
    companion object {
        private const val TAG = "Devicetool"
        // Aligned with Playwright Computer use: wait after actions for UI to settle
        private const val POST_ACTION_DELAY_MS = 800L  // after tap/type/press
        private const val POST_OPEN_DELAY_MS = 1500L   // after opening apps
        private const val POST_SCROLL_DELAY_MS = 500L   // after scroll
    }

    override val name = "device"
    override val description = "Control the android device screen. use snapshot to get UI elements with refs, then act on them."

    private val refmanager = Refmanager()

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "action" to Propertyschema(
                            type = "string",
                            description = "Action: snapshot | screenshot | act | open | status",
                            enum = listOf("snapshot", "screenshot", "act", "open", "status")
                        ),
                        "kind" to Propertyschema(
                            type = "string",
                            description = "for action=act: tap | type | press | long_press | scroll | swipe | wait | home | back",
                            enum = listOf("tap", "type", "press", "long_press", "scroll", "swipe", "wait", "home", "back")
                        ),
                        "ref" to Propertyschema(
                            type = "string",
                            description = "Element ref from snapshot (e.g. 'e5')"
                        ),
                        "text" to Propertyschema(
                            type = "string",
                            description = "Text to type (for kind=type)"
                        ),
                        "key" to Propertyschema(
                            type = "string",
                            description = "Key to press: BACK, HOME, ENTER, TAB, VOLUME_UP, etc."
                        ),
                        "coordinate" to Propertyschema(
                            type = "array",
                            description = "Fallback [x, y] coordinate when ref not available. for swipe: end coordinate.",
                            items = Propertyschema(type = "integer", description = "coordinate value")
                        ),
                        "start_coordinate" to Propertyschema(
                            type = "array",
                            description = "Start [x, y] coordinate for swipe gesture",
                            items = Propertyschema(type = "integer", description = "coordinate value")
                        ),
                        "direction" to Propertyschema(
                            type = "string",
                            description = "Scroll direction",
                            enum = listOf("up", "down", "left", "right")
                        ),
                        "amount" to Propertyschema(
                            type = "number",
                            description = "Scroll amount (default: 3)"
                        ),
                        "timeMs" to Propertyschema(
                            type = "number",
                            description = "Wait time in milliseconds"
                        ),
                        "package_name" to Propertyschema(
                            type = "string",
                            description = "App package name for action=open"
                        ),
                        "format" to Propertyschema(
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

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val action = args["action"] as? String ?: return toolresult.error("Missing action")

        return when (action) {
            "snapshot" -> executeSnapshot(args)
            "screenshot" -> executeScreenshot()
            "act" -> executeAct(args)
            "open" -> executeOpen(args)
            "status" -> executeStatus()
            else -> toolresult.error("Unknown action: $action")
        }
    }

    // ==================== snapshot ====================

    private suspend fun executeSnapshot(args: Map<String, Any?>): toolresult {
        val format = (args["format"] as? String) ?: "compact"

        val proxy = AccessibilityProxy

        val viewNodes = try {
            proxy.dumpViewTree(useCache = false)
        } catch (e: IllegalStateexception) {
            Log.e(TAG, "Accessibility service not available", e)
            return toolresult.error("Accessibilityservicenotopen. pleaseto Settings → Accessibility → androidforClaw openAccessibilityPermission, 才canGetScreenElement. ")
        } catch (e: exception) {
            Log.e(TAG, "Failed to dump view tree", e)
            return toolresult.error("Get UI TreeFailed: ${e.message}. pleaseCheckAccessibilityservicewhether正常Run. ")
        }

        if (viewNodes.isEmpty()) {
            val accessibilityOn = try { proxy.isConnected.value == true && proxy.isserviceReady() } catch (_: exception) { false }
            val status = if (accessibilityOn) "Accessibilityservice: [OK] alreadyopen(butwhenFront页面NoneIdentifiable elements, possibly页面currentlyLoad, suggest等 1-2 secondsretry)" 
                         else "Accessibilityservice: [ERROR] notopen. pleaseto Settings → Accessibility → androidforClaw openAccessibilityPermission. "
            return toolresult.error(status)
        }

        val nodes = SnapshotBuilder.buildfromViewNodes(viewNodes)
        refmanager.updateRefs(nodes)

        // Get screen info
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val appName = try {
            proxy.getCurrentPackageName().let { pkg ->
                if (pkg.isnotBlank()) {
                    context.packagemanager.getApplicationLabel(
                        context.packagemanager.getApplicationInfo(pkg, 0)
                    ).toString()
                } else null
            }
        } catch (e: exception) { null }

        val output = when (format) {
            "tree" -> Snapshotformatter.tree(nodes, width, height, appName)
            "interactive" -> Snapshotformatter.interactive(nodes, appName)
            else -> Snapshotformatter.compact(nodes, width, height, appName)
        }

        return toolresult.success(output)
    }

    // ==================== screenshot ====================

    private suspend fun executeScreenshot(): toolresult {
        // Delegate to existing Screenshotskill logic
        val screenshotresult = try {
            val controller = com.xiaomo.androidforclaw.DeviceController
            controller.getScreenshot(context)
        } catch (e: exception) {
            null
        }

        if (screenshotresult == null) {
            // Fallback: try shell screencap
            try {
                val path = "${StoragePaths.workspaceScreenshots.absolutePath}/device_${System.currentTimeMillis()}.png"
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $path"))
                process.waitfor(5, java.util.concurrent.TimeUnit.SECONDS)
                val file = java.io.File(path)
                if (file.exists() && file.length() > 0) {
                    return toolresult.success("Screenshot saved: $path (${file.length()} bytes)")
                }
            } catch (_: exception) {}
            return toolresult.error("Screenshot failed. please grant screen capture permission.")
        }

        val (bitmap, path) = screenshotresult
        return toolresult.success("Screenshot: ${bitmap.width}x${bitmap.height}, path: $path")
    }

    // ==================== act ====================

    private suspend fun executeAct(args: Map<String, Any?>): toolresult {
        val kind = args["kind"] as? String ?: return toolresult.error("Missing 'kind' for action=act")

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
            else -> toolresult.error("Unknown kind: $kind")
        }
    }

    private suspend fun executeTap(args: Map<String, Any?>): toolresult {
        val (x, y, label) = resolveCoordinate(args) ?: return toolresult.error("cannot resolve target. Provide ref or coordinate.")

        return try {
            val ok = AccessibilityProxy.tap(x, y)
            if (!ok) {
                toolresult.error("Tap failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                toolresult.success("Tapped${label?.let { " '$it'" } ?: ""} at ($x, $y)")
            }
        } catch (e: exception) {
            toolresult.error("Tap failed: ${e.message}")
        }
    }

    private suspend fun executeType(args: Map<String, Any?>): toolresult {
        val text = args["text"] as? String ?: return toolresult.error("Missing 'text' for kind=type")

        val clipboardhelper = com.xiaomo.androidforclaw.service.ClipboardInputhelper
        val clawIme = com.xiaomo.androidforclaw.service.ClawIMEmanager
        val clawImeActive = clawIme.isClawImeEnabled(context) && clawIme.isConnected()
        val accessibilityAvailable = AccessibilityProxy.isserviceReady()
        val clipboardAvailable = accessibilityAvailable && clipboardhelper.isClipboardAvailable(context)

        // if ref provided, try to focus input
        val resolved = resolveCoordinate(args)
        if (resolved != null) {
            val (x, y, _) = resolved
            if (accessibilityAvailable) {
                AccessibilityProxy.tap(x, y)
                delay(POST_ACTION_DELAY_MS)
            } else if (clawImeActive) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap $x $y")).waitfor()
                delay(POST_ACTION_DELAY_MS)
            } else {
                return toolresult.error("InputFailed: Accessibilityserviceand ClawIME 均notEnable, cannot聚焦Input field")
            }
        }

        // Type text: 优先cut板 → 兜底 ClawIME → 兜底 shell input
        try {
            val typed: Boolean
            val method: String

            if (clipboardAvailable) {
                // 优先走cut板paste(Most reliable, SupportAllcharacters)
                typed = clipboardhelper.inputTextViaClipboard(context, text)
                method = "clipboard"
                Log.d(TAG, "Clipboard.inputText('${text.take(30)}'): $typed")
            } else if (clawImeActive) {
                // 兜底to ClawIME Key盘Input
                typed = clawIme.inputText(text)
                method = "clawime"
                Log.d(TAG, "ClawIME.inputText('${text.take(30)}'): $typed")
            } else {
                // most终兜底: shell input text(仅Support ASCII)
                val escaped = text.replace("'", "'\\''")
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escaped'"))
                val exitCode = proc.waitfor()
                typed = exitCode == 0
                method = "shell"
                Log.d(TAG, "shell input text exitCode: $exitCode")
            }

            if (!typed) {
                val hint = if (!accessibilityAvailable && !clawImeActive) {
                    "pleaseopenAccessibilityservice(recommend, Supportcut板paste), orswitchto ClawIME Input method"
                } else if (!accessibilityAvailable) {
                    "cut板pasteneedAccessibilityservice. pleaseinSettings中openAccessibilityPermissionbyobtainmoreokInput体验"
                } else {
                    "InputFailed, pleaseretry"
                }
                return toolresult.error("Type failed: $hint")
            }
            val refLabel = (args["ref"] as? String)?.let { refmanager.getRefNode(it)?.name }
            return toolresult.success("Typed '${text.take(100)}'${refLabel?.let { " into '$it'" } ?: ""} (via $method)")
        } catch (e: exception) {
            return toolresult.error("Type failed: ${e.message}")
        }
    }

    private suspend fun executePress(args: Map<String, Any?>): toolresult {
        val key = (args["key"] as? String) ?: (args["text"] as? String)
            ?: return toolresult.error("Missing 'key' for kind=press")
        return executeKey(key)
    }

    private fun executeKey(key: String): toolresult {
        return try {
            val ok = when (key.uppercase()) {
                "BACK" -> AccessibilityProxy.pressback()
                "HOME" -> AccessibilityProxy.pressHome()
                else -> {
                    val keycode = mapKeyToKeycode(key)
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode")).waitfor()
                    true
                }
            }
            if (ok) toolresult.success("Pressed $key")
            else toolresult.error("Key press failed for $key")
        } catch (e: exception) {
            toolresult.error("Key press failed: ${e.message}")
        }
    }

    private suspend fun executeLongPress(args: Map<String, Any?>): toolresult {
        val (x, y, label) = resolveCoordinate(args) ?: return toolresult.error("cannot resolve target.")

        return try {
            val ok = AccessibilityProxy.longPress(x, y)
            if (!ok) {
                toolresult.error("Long press failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                toolresult.success("Long pressed${label?.let { " '$it'" } ?: ""} at ($x, $y)")
            }
        } catch (e: exception) {
            toolresult.error("Long press failed: ${e.message}")
        }
    }

    private suspend fun executeScroll(args: Map<String, Any?>): toolresult {
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
            else -> return toolresult.error("Invalid direction: $direction")
        }

        return try {
            val ok = AccessibilityProxy.swipe(sx, sy, ex, ey, 300)
            if (!ok) {
                toolresult.error("Scroll failed via accessibility service")
            } else {
                delay(POST_SCROLL_DELAY_MS)
                toolresult.success("Scrolled $direction (amount=$amount)")
            }
        } catch (e: exception) {
            toolresult.error("Scroll failed: ${e.message}")
        }
    }

    private suspend fun executeSwipe(args: Map<String, Any?>): toolresult {
        @Suppress("UNCHECKED_CAST")
        val startCoord = args["start_coordinate"] as? List<Number>
        @Suppress("UNCHECKED_CAST")
        val endCoord = args["coordinate"] as? List<Number>

        if (startCoord == null || endCoord == null || startCoord.size < 2 || endCoord.size < 2) {
            return toolresult.error("Swipe requires start_coordinate and coordinate (both [x, y])")
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
                toolresult.success("Swiped from (${startCoord[0]}, ${startCoord[1]}) to (${endCoord[0]}, ${endCoord[1]})")
            } else {
                toolresult.error("Swipe failed via accessibility service")
            }
        } catch (e: exception) {
            toolresult.error("Swipe failed: ${e.message}")
        }
    }

    private suspend fun executeWait(args: Map<String, Any?>): toolresult {
        val ms = ((args["timeMs"] as? Number)?.toLong()) ?: 1000
        delay(ms.coerceIn(100, 10_000))
        return toolresult.success("Waited ${ms}ms")
    }

    // ==================== open ====================

    private suspend fun executeOpen(args: Map<String, Any?>): toolresult {
        val packageName = args["package_name"] as? String
            ?: return toolresult.error("Missing package_name")

        return try {
            val intent = context.packagemanager.getLaunchIntentforPackage(packageName)
            if (intent != null) {
                intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val appName = try {
                    context.packagemanager.getApplicationLabel(
                        context.packagemanager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (_: exception) { packageName }
                kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
                toolresult.success("Opened $appName ($packageName)")
            } else {
                toolresult.error("App not found: $packageName")
            }
        } catch (e: exception) {
            toolresult.error("Failed to open app: ${e.message}")
        }
    }

    // ==================== status ====================

    private fun executeStatus(): toolresult {
        val proxy = AccessibilityProxy
        val connected = proxy.isConnected.value == true
        val refCount = refmanager.getRefCount()
        val stale = refmanager.isStale()

        return toolresult.success(buildString {
            appendLine("Device status:")
            appendLine("  Accessibility: ${if (connected) "[OK] connected" else "[ERROR] not connected"}")
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
            val coord = refmanager.resolveRef(ref)
            if (coord != null) {
                val label = refmanager.getRefNode(ref)?.name
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
