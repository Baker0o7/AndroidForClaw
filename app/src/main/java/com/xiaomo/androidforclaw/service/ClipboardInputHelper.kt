/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.logging.Log

/**
 * cut板Input助手
 *
 * 通过cut板ImplementationTextInput, 避免 ClawIME Key盘的各种Issue. 
 * 流程: Writecut板 → 找到FocusInput field → 执RowpasteAction
 *
 * 优势: 
 * - 不NeedswitchInput method
 * - SupportAll字符(中文、emoji 等)
 * - 比 ClawIME moreStable
 *
 * Limit: 
 * - Android 10+ Back台apply访问cut板受限
 * - NeedAccessibilityServiceto executeRowpasteAction
 */
object ClipboardInputHelper {
    private const val TAG = "ClipboardInputHelper"

    /**
     * 通过cut板 + AccessibilitypasteInputText
     *
     * @param context apply Context
     * @param text 要Input的Text
     * @return YesNoSuccess
     */
    fun inputTextViaClipboard(context: Context, text: String): Boolean {
        try {
            // 1. Get ClipboardManager
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager == null) {
                Log.e(TAG, "ClipboardManager not available")
                return false
            }

            // SaveOldcut板Inside容, Action完BackResume
            val oldClip = try {
                clipboardManager.primaryClip
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read old clipboard (expected on Android 10+): ${e.message}")
                null
            }

            // 2. WriteNewTextto clipboard
            val clip = ClipData.newPlainText("claw_input", text)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "✓ Clipboard set: ${text.take(50)}${if (text.length > 50) "..." else ""}")

            // 3. 通过AccessibilityService找FocusNode执Rowpaste
            val pasted = performPasteViaAccessibility()
            if (!pasted) {
                Log.e(TAG, "Paste via accessibility failed")
                // TryResumeOldcut板
                restoreClipboard(clipboardManager, oldClip)
                return false
            }

            // 4. 短暂DelayBackResumeOldcut板Inside容
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                restoreClipboard(clipboardManager, oldClip)
            }, 500)

            Log.d(TAG, "✓ Text input via clipboard successful")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard input failed", e)
            return false
        }
    }

    /**
     * 通过AccessibilityService执RowpasteAction
     */
    private fun performPasteViaAccessibility(): Boolean {
        val service = com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderService.serviceInstance
        if (service == null) {
            Log.e(TAG, "Accessibility service not available")
            return false
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "No root window available")
            return false
        }

        // 找到当FrontFocus的可EditNode
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Log.e(TAG, "No focused input node found")
            return false
        }

        if (!focusedNode.isEditable) {
            Log.e(TAG, "Focused node is not editable")
            return false
        }

        // 执RowpasteAction
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result: $success")
        return success
    }

    /**
     * ResumeOld的cut板Inside容
     */
    private fun restoreClipboard(clipboardManager: ClipboardManager, oldClip: ClipData?) {
        try {
            if (oldClip != null) {
                clipboardManager.setPrimaryClip(oldClip)
                Log.d(TAG, "Old clipboard restored")
            } else {
                // 清Nullcut板, 避免泄露InputInside容
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardManager.clearPrimaryClip()
                } else {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                Log.d(TAG, "Clipboard cleared")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore clipboard: ${e.message}")
        }
    }

    /**
     * Checkcut板YesNoAvailable
     * Android 10+ LimitBack台apply访问cut板, 但我们的 App usually在Front台或HasAccessibilityService
     */
    fun isClipboardAvailable(context: Context): Boolean {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager == null) {
                false
            } else {
                // TryWriteTestInside容
                val testClip = ClipData.newPlainText("claw_test", "test")
                clipboardManager.setPrimaryClip(testClip)
                // 清理
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardManager.clearPrimaryClip()
                } else {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard not available: ${e.message}")
            false
        }
    }

    /**
     * CheckAccessibilitypasteYesNoAvailable(NeedAccessibilityService)
     */
    fun isPasteAvailable(): Boolean {
        val service = com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderService.serviceInstance
        return service != null
    }
}
