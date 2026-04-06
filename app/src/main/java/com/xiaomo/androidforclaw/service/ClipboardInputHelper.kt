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
 * Clipboard Input Helper
 *
 * Implement text input via clipboard to avoid various issues with ClawIME keyboard.
 * Process: Write to clipboard → Find focused input field → Execute paste action
 *
 * Advantages:
 * - No need to switch input method
 * - Support all characters (Chinese, emoji, etc.)
 * - More stable than ClawIME
 *
 * Limitations:
 * - Android 10+ background app access to clipboard is restricted
 * - Need accessibility service to execute paste action
 */
object ClipboardInputhelper {
    private const val TAG = "ClipboardInputhelper"

    /**
     * Input text via clipboard + accessibility paste
     *
     * @param context app context
     * @param text text to input
     * @return whether successful
     */
    fun inputTextViaClipboard(context: context, text: String): Boolean {
        try {
            // 1. Get Clipboardmanager
            val clipboardmanager = context.getSystemservice(context.CLIPBOARD_SERVICE) as? Clipboardmanager
            if (clipboardmanager == null) {
                Log.e(TAG, "Clipboardmanager not available")
                return false
            }

            // Save old clipboard content, restore after action completes
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

            // 3. Through accessibility service find focus node and execute paste
            val pasted = performPasteViaAccessibility()
            if (!pasted) {
                Log.e(TAG, "Paste via accessibility failed")
                 // try resume old clipboard
                restoreClipboard(clipboardmanager, oldClip)
                return false
            }

            // 4. Short delay then restore old clipboard content
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

         // Find when front focus can edit node
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Log.e(TAG, "No focused input node found")
            return false
        }

        if (!focusedNode.isEditable) {
            Log.e(TAG, "Focused node is not editable")
            return false
        }

         // execute paste action
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result: $success")
        return success
    }

    /**
     * Restore old clipboard content
     */
    private fun restoreClipboard(clipboardmanager: Clipboardmanager, oldClip: ClipData?) {
        try {
            if (oldClip != null) {
                clipboardmanager.setPrimaryClip(oldClip)
                Log.d(TAG, "old clipboard restored")
            } else {
                // Clear clipboard to avoid leaking input content
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
      * Check clipboard whether available
      * android 10+ limit background app access to clipboard, but our App usually in foreground or has Accessibility service
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
                  // Clean up clipboard
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
      * Check accessibility paste whether available (need accessibility service)
      */
    fun isPasteAvailable(): Boolean {
        val service = com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderservice.serviceInstance
        return service != null
    }
}
