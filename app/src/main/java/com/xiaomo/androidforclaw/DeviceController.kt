/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw

import android.accessibilityservice.Accessibilityservice
import android.content.context
import android.graphics.Bitmap
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.BitmapFactory
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.accessibility.service.ViewNode
import com.xiaomo.androidforclaw.accessibility.service.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object DeviceController {
    private val TAG = "agent1Controller"


    /** Capture a screenshot of the device via AccessibilityProxy */
    val workPath = "/sdcard/nextload/agent/"
    fun getScreenshot(context: context): Pair<Bitmap, String>? {
        return runBlocking {
            try {
                val uriString = AccessibilityProxy.captureScreen()
                if (uriString.isEmpty()) {
                    Log.w(TAG, "Screenshot failed: URI is empty")
                    return@runBlocking null
                }

                Log.d(TAG, "Got screenshot URI: $uriString")

                // Try作for Content URI Decode
                val bitmap = try {
                    if (uriString.startswith("content://")) {
                        val uri = android.net.Uri.parse(uriString)
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }
                    } else {
                        // FallbacktoFile path
                        BitmapFactory.decodeFile(uriString)
                    }
                } catch (e: exception) {
                    Log.e(TAG, "Failed to decode from URI/path: $uriString", e)
                    null
                }

                if (bitmap != null) {
                    Pair(bitmap, uriString)
                } else {
                    Log.e(TAG, "cannotDecodeScreenshot: $uriString")
                    null
                }
            } catch (e: exception) {
                Log.e(TAG, "Screenshot failed", e)
                null
            }
        }
    }

    // CheckwhenFrontEnableInput methodwhetherYes ADB Keyboard
    fun isClawKeyboardActive(context: context): Boolean {
        val currentInputMethod = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        // 3. CheckADBInput methodwhetherinEnableList中
        val adbInputMethodName =
            "${context.packageName}/com.xiaomo.androidforclaw.service.ClawIME" // ADBInput methodName, according to实际情况Modify
        return currentInputMethod == adbInputMethodName || currentInputMethod.contains("adbkeyboard")
    }

    // CheckwhenFrontFocuswhetherinInput fieldUp
    fun findFocusedEditText(service: Accessibilityservice): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        return rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }


    // 综合Check: whether ADB Key盘 + FocusinInput field
    fun isAdbKeyboardVisible(service: Accessibilityservice, context: context): Boolean {
        val focusedNode = findFocusedEditText(service)
        val isClawIme = isClawKeyboardActive(context)
        Log.d("ADBKey盘Check", "whetherFocusinEditText: ${focusedNode != null}")
        return focusedNode != null && isClawIme
    }


    // todo 截屏and抓tree 放tooneup delayMergetooneup
    fun detectIcons(context: context): Pair<List<ViewNode>, List<ViewNode>>? {
        // CheckAccessibilityservicewhetherConnect
        if (!AccessibilityProxy.isserviceReady()) {
            Log.w(TAG, "AccessibilityservicenotReady")
            return null
        }

        return runBlocking {
            try {
                Log.d(TAG, "detectIcons: dumpView via AIDL")
                var dumpView = AccessibilityProxy.dumpViewTree(useCache = false)

                // mostmanyretry 3 times
                var retryCount = 0
                while (dumpView.isEmpty() && retryCount < 3) {
                    Log.d(TAG, "detectIcons: retry $retryCount")
                    Thread.sleep(500)
                    dumpView = AccessibilityProxy.dumpViewTree(useCache = false)
                    retryCount++
                }

                if (dumpView.isEmpty()) {
                    Log.w(TAG, "cannotGet UI Tree(alreadyretry $retryCount times)")
                    return@runBlocking null
                }

                // CloneoriginalData
                val originalNodes = dumpView.map { it.copy() }

                // 经over完整ProcessData
                val processedNodes = processHierarchy(dumpView)

                Pair(originalNodes, processedNodes)
            } catch (e: exception) {
                Log.e(TAG, "Get UI TreeFailed", e)
                null
            }
        }
    }


    /**
     * Remove nodes that have no useful information at all:
     * no text, no contentDesc, no resourceId, not clickable, not scrollable.
     * Keep nodes that have resourceId or are interactive — they're useful even without text.
     */
    fun removeEmptyNodes(nodes: List<ViewNode>): List<ViewNode> {
        return nodes.filter {
            !it.text.isNullorEmpty() ||
            !it.contentDesc.isNullorEmpty() ||
            !it.resourceId.isNullorEmpty() ||
            it.clickable ||
            it.scrollable
        }
    }

    fun filterDuplicateNodes(nodes: List<ViewNode>): List<ViewNode> {
        val seenKeys = mutableSetOf<String>()
        val result = mutableListOf<ViewNode>()
        nodes.forEach { node ->
            // use text+coords as dedup key, not just text
            val label = node.text ?: node.contentDesc ?: node.resourceId ?: ""
            val key = "${label}@${node.point.x},${node.point.y}"
            if (key !in seenKeys) {
                seenKeys.a(key)
                result.a(node)
            } else if (node.clickable) {
                // Keep clickable duplicates (e.g., list items with same text)
                result.a(node)
            }
        }
        return result
    }


    fun processHierarchy(xmlString: List<ViewNode>): List<ViewNode> {
        var nodes = xmlString

        // Remove truly empty nodes (but keep those with resourceId or interactive)
        nodes = removeEmptyNodes(nodes)

        // Deduplicate by text+position
        nodes = filterDuplicateNodes(nodes)
        nodes = nodes.reversed()
        nodes = filterDuplicateNodes(nodes)
        nodes = nodes.reversed()

        return nodes
    }

    /** Simulate a tap on the screen at coordinates (x, y) via Accessibility gesture. */
    fun tap(x: Int, y: Int) {
        runBlocking {
            AccessibilityProxy.tap(x, y)
        }
    }

    /** Simulate a swipe from (x1, y1) to (x2, y2) via Accessibility gesture. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 500) {
        runBlocking {
            AccessibilityProxy.swipe(x1, y1, x2, y2, durationMs)
        }
    }

    /** Input text into the currently focused element (e.g., an input box). */
    fun inputText(text: String, context: context) {
        AccessibilityProxy.inputText(text)
    }

    /** Simulate a back button press. */
    fun pressback() {
        AccessibilityProxy.pressback()
    }

    /** Return to the Home screen. */
    fun pressHome() {
        AccessibilityProxy.pressHome()
    }

    // alreadyremoveADBDependency, not再提供shell命令execution

}
