/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.service

import android.content.ClipData
import android.content.Clipboardmanager
import android.content.context
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.logging.Log

/**
 * cut板Input助手
 *
 * throughcut板implementationTextInput, 避免 ClawIME Key盘各种Issue. 
 * 流程: Writecut板 → 找toFocusInput field → executionpasteAction
 *
 * 优势: 
 * - notneedswitchInput method
 * - SupportAllcharacters(中文、emoji 等)
 * - 比 ClawIME moreStable
 *
 * Limit: 
 * - android 10+ backgroundappaccesscut板受限
 * - needAccessibilityserviceto executeRowpasteAction
 */
object ClipboardInputhelper {
    private const val TAG = "ClipboardInputhelper"

    /**
     * throughcut板 + AccessibilitypasteInputText
     *
     * @param context app context
     * @param text needInputText
     * @return whetherSuccess
     */
    fun inputTextViaClipboard(context: context, text: String): Boolean {
        try {
            // 1. Get Clipboardmanager
            val clipboardmanager = context.getSystemservice(context.CLIPBOARD_SERVICE) as? Clipboardmanager
            if (clipboardmanager == null) {
                Log.e(TAG, "Clipboardmanager not available")
                return false
            }

            // Saveoldcut板content, Action完backresume
            val oldClip = try {
                clipboardmanager.primaryClip
            } catch (e: exception) {
                Log.w(TAG, "cannot read old clipboard (expected on android 10+): ${e.message}")
                null
            }

            // 2. WritenewTextto clipboard
            val clip = ClipData.newPlainText("claw_input", text)
            clipboardmanager.setPrimaryClip(clip)
            Log.d(TAG, "✓ Clipboard set: ${text.take(50)}${if (text.length > 50) "..." else ""}")

            // 3. throughAccessibilityservice找FocusNodeexecutionpaste
            val pasted = performPasteViaAccessibility()
            if (!pasted) {
                Log.e(TAG, "Paste via accessibility failed")
                // try resumeoldcut板
                restoreClipboard(clipboardmanager, oldClip)
                return false
            }

            // 4. short暂Delaybackresumeoldcut板content
            android.os.Handler(android.os.looper.getMainlooper()).postDelayed({
                restoreClipboard(clipboardmanager, oldClip)
            }, 500)

            Log.d(TAG, "✓ Text input via clipboard successful")
            return true
        } catch (e: exception) {
            Log.e(TAG, "Clipboard input failed", e)
            return false
        }
    }

    /**
     * throughAccessibilityserviceexecutionpasteAction
     */
    private fun performPasteViaAccessibility(): Boolean {
        val service = com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderservice.serviceInstance
        if (service == null) {
            Log.e(TAG, "Accessibility service not available")
            return false
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "No root window available")
            return false
        }

        // 找towhenFrontFocuscanEditNode
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Log.e(TAG, "No focused input node found")
            return false
        }

        if (!focusedNode.isEditable) {
            Log.e(TAG, "Focused node is not editable")
            return false
        }

        // executionpasteAction
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result: $success")
        return success
    }

    /**
     * resumeoldcut板content
     */
    private fun restoreClipboard(clipboardmanager: Clipboardmanager, oldClip: ClipData?) {
        try {
            if (oldClip != null) {
                clipboardmanager.setPrimaryClip(oldClip)
                Log.d(TAG, "old clipboard restored")
            } else {
                // 清Nullcut板, 避免泄露Inputcontent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardmanager.clearPrimaryClip()
                } else {
                    clipboardmanager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                Log.d(TAG, "Clipboard cleared")
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to restore clipboard: ${e.message}")
        }
    }

    /**
     * Checkcut板whetherAvailable
     * android 10+ Limitbackgroundappaccesscut板, but我们 App usuallyinforegroundorHasAccessibilityservice
     */
    fun isClipboardAvailable(context: context): Boolean {
        return try {
            val clipboardmanager = context.getSystemservice(context.CLIPBOARD_SERVICE) as? Clipboardmanager
            if (clipboardmanager == null) {
                false
            } else {
                // TryWriteTestcontent
                val testClip = ClipData.newPlainText("claw_test", "test")
                clipboardmanager.setPrimaryClip(testClip)
                // 清理
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardmanager.clearPrimaryClip()
                } else {
                    clipboardmanager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                true
            }
        } catch (e: exception) {
            Log.w(TAG, "Clipboard not available: ${e.message}")
            false
        }
    }

    /**
     * CheckAccessibilitypastewhetherAvailable(needAccessibilityservice)
     */
    fun isPasteAvailable(): Boolean {
        val service = com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderservice.serviceInstance
        return service != null
    }
}
