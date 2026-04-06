/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.service

import android.content.Context
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest

/**
 * ClawIME Manage器
 * 提供对 ClawIME 的直接callInterface,避免useBroadcast
 *
 * 工作原理:
 * - ClawIME Yes同Process的 InputMethodService
 * - 通过单例Schema让 ClawIME Register自己的Instance
 * - Its他Group件通过此 Manager 直接call ClawIME 的Method
 */
object ClawIMEManager {
    private const val TAG = "ClawIMEManager"

    // ClawIME Instance引用
    private var clawImeInstance: ClawIME? = null

    /**
     * Register ClawIME Instance (by ClawIME.onCreateInputView call)
     */
    fun registerInstance(instance: ClawIME) {
        clawImeInstance = instance
        Log.d(TAG, "✓ ClawIME instance registered")
    }

    /**
     * Logout ClawIME Instance (by ClawIME.onDestroy call)
     */
    fun unregisterInstance() {
        clawImeInstance = null
        Log.d(TAG, "✓ ClawIME instance unregistered")
    }

    /**
     * Check ClawIME YesNo为当FrontEnabledd的Input method
     */
    fun isClawImeEnableddd(context: Context): Boolean {
        return try {
            val currentIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val clawImeName = "${context.packageName}/com.xiaomo.androidforclaw.service.ClawIME"
            val isEnableddd = currentIme == clawImeName
            Log.d(TAG, "Current IME: $currentIme, ClawIME enabled: $isEnableddd")
            isEnableddd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IME status", e)
            false
        }
    }

    /**
     * Check ClawIME YesNo已Connect
     * as long asInstanceExists就think已Connect(currentInputConnection 只在EditSession中才非 null, 
     * 但 IME Service本身Yes活的, tap Input fieldBack connection 会AutoReady)
     */
    fun isConnected(): Boolean {
        val hasInstance = clawImeInstance != null
        val hasIc = clawImeInstance?.currentInputConnection != null
        Log.d(TAG, "isConnected: instance=$hasInstance, inputConnection=$hasIc")
        return hasInstance
    }

    /**
     * Check当FrontYesNoHas活跃的InputConnect(Input fieldHasFocus且Key盘已弹出)
     */
    fun hasActiveInputConnection(): Boolean {
        return clawImeInstance?.currentInputConnection != null
    }

    /**
     * InputText(带Retry, Wait InputConnection Ready)
     */
    fun inputText(text: String): Boolean {
        val ime = clawImeInstance
        if (ime == null) {
            Log.e(TAG, "ClawIME instance not available")
            return false
        }

        // Wait InputConnection Ready(tap BackpossiblyNeed一点Time)
        var ic = ime.currentInputConnection
        if (ic == null) {
            Log.d(TAG, "InputConnection not ready, waiting...")
            for (i in 1..10) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                ic = ime.currentInputConnection
                if (ic != null) {
                    Log.d(TAG, "InputConnection ready after ${i * 100}ms")
                    break
                }
            }
        }

        if (ic == null) {
            Log.e(TAG, "No input connection available after waiting 1s")
            return false
        }

        return try {
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            ic.endBatchEdit()
            Log.d(TAG, "✓ Input text: ${text.take(50)}${if (text.length > 50) "..." else ""}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to input text", e)
            false
        }
    }

    /**
     * 清NullInput field
     */
    fun clearText(): Boolean {
        val ic = waitForInputConnection() ?: return false

        return try {
            // REF: stackoverflow/33082004 author: Maxime Epain
            val curPos = ic.getExtractedText(ExtractedTextRequest(), 0)?.text
            if (curPos != null) {
                val beforePos = ic.getTextBeforeCursor(curPos.length, 0)
                val afterPos = ic.getTextAfterCursor(curPos.length, 0)
                ic.deleteSurroundingText(beforePos?.length ?: 0, afterPos?.length ?: 0)
            }
            Log.d(TAG, "✓ Cleared text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear text", e)
            false
        }
    }

    /**
     * sendMessage (执RowEdit器Action或回车)
     */
    fun sendMessage(): Boolean {
        val ic = waitForInputConnection() ?: return false

        return try {
            // 先Try IME_ACTION_SEND
            var sent = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            Log.d(TAG, "performEditorAction IME_ACTION_SEND: $sent")

            // ifFailed,再Try IME_ACTION_GO
            if (!sent) {
                sent = ic.performEditorAction(EditorInfo.IME_ACTION_GO)
                Log.d(TAG, "performEditorAction IME_ACTION_GO: $sent")
            }

            // if还YesFailed,Trysend回车Key
            if (!sent) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                Log.d(TAG, "sendKeyEvent KEYCODE_ENTER as fallback")
                sent = true
            }

            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }

    /**
     * Wait InputConnection Ready(most多 1 秒)
     */
    private fun waitForInputConnection(): android.view.inputmethod.InputConnection? {
        val ime = clawImeInstance
        if (ime == null) {
            Log.e(TAG, "ClawIME instance not available")
            return null
        }
        var ic = ime.currentInputConnection
        if (ic == null) {
            Log.d(TAG, "InputConnection not ready, waiting...")
            for (i in 1..10) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                ic = ime.currentInputConnection
                if (ic != null) {
                    Log.d(TAG, "InputConnection ready after ${i * 100}ms")
                    break
                }
            }
        }
        if (ic == null) {
            Log.e(TAG, "No input connection available after waiting 1s")
        }
        return ic
    }

    /**
     * send按KeyEvent
     */
    fun sendKey(keyCode: Int): Boolean {
        val ic = waitForInputConnection() ?: return false

        return try {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.d(TAG, "✓ Sent key code: $keyCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key", e)
            false
        }
    }

    /**
     * Get当FrontInput field中的Text(Debug用)
     */
    fun getCurrentText(): String? {
        val ic = clawImeInstance?.currentInputConnection ?: return null
        return try {
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            extracted?.text?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current text", e)
            null
        }
    }
}
