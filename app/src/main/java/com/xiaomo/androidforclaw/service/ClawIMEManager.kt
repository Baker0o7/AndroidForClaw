/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.service

import android.content.context
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest

/**
 * ClawIME Manager
 * Provides direct ClawIME call interface, avoiding broadcast
 *
 * How it works:
 * - ClawIME is InputMethodService in the same process
 * - Uses singleton pattern to let ClawIME register its instance
 * - Other components call ClawIME methods through this manager
 */
object ClawIMEManager {
    private const val TAG = "ClawIMEManager"

    // ClawIME instance reference
    private var clawImeInstance: ClawIME? = null

    /**
     * Register ClawIME instance (called by ClawIME.onCreateInputView)
     */
    fun registerInstance(instance: ClawIME) {
        clawImeInstance = instance
        Log.d(TAG, "✓ ClawIME instance registered")
    }

    /**
     * Unregister ClawIME instance (called by ClawIME.onDestroy)
     */
    fun unregisterInstance() {
        clawImeInstance = null
        Log.d(TAG, "✓ ClawIME instance unregistered")
    }

    /**
     * Check if ClawIME is currently enabled as input method
     */
    fun isClawImeEnabled(context: android.content.Context): Boolean {
        return try {
            val currentIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            val clawImeName = "${context.packageName}/com.xiaomo.androidforclaw.service.ClawIME"
            val isEnabled = currentIme == clawImeName
            Log.d(TAG, "Current IME: $currentIme, ClawIME enabled: $isEnabled")
            isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IME status", e)
            false
        }
    }

    /**
     * Check if ClawIME is already connected
     * As long as instance exists, it's considered connected (currentInputConnection is only non-null
     * in edit sessions, but IME service itself is alive, tap input field and connection will auto ready)
     */
    fun isConnected(): Boolean {
        val hasInstance = clawImeInstance != null
        val hasIc = clawImeInstance?.currentInputConnection != null
        Log.d(TAG, "isConnected: instance=$hasInstance, inputConnection=$hasIc")
        return hasInstance
    }

    /**
     * Check if currently has active input connection (input field has focus and keyboard is already shown)
     */
    fun hasActiveInputConnection(): Boolean {
        return clawImeInstance?.currentInputConnection != null
    }

    /**
     * Input text (with retry, wait for InputConnection ready)
     */
    fun inputText(text: String): Boolean {
        val ime = clawImeInstance
        if (ime == null) {
            Log.e(TAG, "ClawIME instance not available")
            return false
        }

        // Wait for InputConnection ready (tap back may need some time)
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
     * Clear input field
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
     * Send message (perform editor action or return)
     */
    fun sendMessage(): Boolean {
        val ic = waitForInputConnection() ?: return false

        return try {
            // First try IME_ACTION_SEND
            var sent = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            Log.d(TAG, "performEditorAction IME_ACTION_SEND: $sent")

            // If failed, try IME_ACTION_GO
            if (!sent) {
                sent = ic.performEditorAction(EditorInfo.IME_ACTION_GO)
                Log.d(TAG, "performEditorAction IME_ACTION_GO: $sent")
            }

            // If still failed, try send return key
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
     * Wait for InputConnection ready (max 1 second)
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
     * Send key event
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
     * Get current input field text (for debug)
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
