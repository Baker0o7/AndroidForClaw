/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.BroadcastReceiver
import android.content.context
import android.content.Intent
import android.content.IntentFilter
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.core.MyApplication

/**
 * Chat Broadcast Receiver - ADB Test Interface
 *
 * Purpose: Convenient for sending messages directly to Chat interface via ADB for testing
 *
 * Usage:
 * adb shell am broadcast -a CLAW_SEND_MESSAGE --es message "YourMessageContent"
 *
 * Example:
 * adb shell am broadcast -a CLAW_SEND_MESSAGE --es message "use browser Search openclaw"
 */
class ChatBroadcastReceiver() : BroadcastReceiver() {

    // Optional callback, used for dynamic registration
    private var onMessageReceived: ((String) -> Unit)? = null

    // Constructor with callback for dynamic registration
    constructor(onMessageReceived: (String) -> Unit) : this() {
        this.onMessageReceived = onMessageReceived
    }

    companion object {
        private const val TAG = "ChatBroadcastReceiver"
        const val ACTION_SEND_MESSAGE = "CLAW_SEND_MESSAGE"
        const val EXTRA_MESSAGE = "message"

        /**
         * Create IntentFilter
         */
        fun createIntentFilter(): IntentFilter {
            return IntentFilter(ACTION_SEND_MESSAGE)
        }
    }

    override fun onReceive(context: context?, intent: Intent?) {
        Log.d(TAG, "onReceive called - action: ${intent?.action}")
        if (intent?.action == ACTION_SEND_MESSAGE) {
            val message = intent.getStringExtra(EXTRA_MESSAGE)
            Log.d(TAG, "Message content: $message")
            if (message != null && message.isnotBlank()) {
                Log.d(TAG, "Received ADB message: $message")

                // Priority: use callback
                if (onMessageReceived != null) {
                    onMessageReceived?.invoke(message)
                } else {
                    // use global method to send message
                    Log.d(TAG, "Send via MyApplication")
                    MyApplication.handleChatBroadcast(message)
                }
            } else {
                Log.w(TAG, "Received null message")
            }
        } else {
            Log.w(TAG, "Unknown action: ${intent?.action}")
        }
    }
}