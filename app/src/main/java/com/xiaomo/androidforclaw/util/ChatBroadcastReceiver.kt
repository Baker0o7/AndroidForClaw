/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.core.MyApplication

/**
 * Chat Broadcast Receiver - ADB TestInterface
 *
 * 用途: 方便通过 ADB 直接sendMessage到Chat界面IntoRowTest
 *
 * useMethod:
 * adb shell am broadcast -a CLAW_SEND_MESSAGE --es message "YourMessageInside容"
 *
 * 示例:
 * adb shell am broadcast -a CLAW_SEND_MESSAGE --es message "usebrowserSearchopenclaw"
 */
class ChatBroadcastReceiver() : BroadcastReceiver() {

    // Optional的Callback,用于DynamicRegister时
    private var onMessageReceived: ((String) -> Unit)? = null

    // 提供带Callback的构造Function用于DynamicRegister
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

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "📨 onReceive 被call - action: ${intent?.action}")
        if (intent?.action == ACTION_SEND_MESSAGE) {
            val message = intent.getStringExtra(EXTRA_MESSAGE)
            Log.d(TAG, "📨 MessageInside容: $message")
            if (message != null && message.isNotBlank()) {
                Log.d(TAG, "✅ 收到 ADB Message: $message")

                // 优先useCallback
                if (onMessageReceived != null) {
                    onMessageReceived?.invoke(message)
                } else {
                    // 通过Global方式sendMessage
                    Log.d(TAG, "⚙️ 通过 MyApplication sendMessage")
                    MyApplication.handleChatBroadcast(message)
                }
            } else {
                Log.w(TAG, "⚠️ 收到NullMessage")
            }
        } else {
            Log.w(TAG, "⚠️ Unknown action: ${intent?.action}")
        }
    }
}
