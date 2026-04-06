package com.xiaomo.androidforclaw.core

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.BroadcastReceiver
import android.content.context
import android.content.Intent
import com.xiaomo.androidforclaw.logging.Log
import com.tencent.mmkv.MMKV

/**
 * agent Message Broadcast Receiver
 * Receives agent execution requests from Gateway or ADB
 */
class agentMessageReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "agentMessageReceiver"
    }

    override fun onReceive(context: context, intent: Intent) {
        // use System.out to ensure logs are visible
        System.out.println("========== agentMessageReceiver.onReceive called ==========")
        Log.e(TAG, "========== onReceive called ==========")
        Log.e(TAG, "Action: ${intent.action}")
        Log.e(TAG, "Extras: ${intent.extras}")

        if (intent.action != "com.xiaomo.androidforclaw.ACTION_EXECUTE_AGENT") {
            Log.e(TAG, "[WARN] [Receiver] Unknown action: ${intent.action}")
            return
        }

        val message = intent.getStringExtra("message")
        val explicitsessionId = intent.getStringExtra("sessionId")
        val resolvedsessionId = explicitsessionId ?: MMKV.defaultMMKV()?.decodeString("last_session_id")

        Log.e(TAG, "[MSG] [Receiver] Received agent execution request:")
        Log.e(TAG, "  [CHAT] Message: $message")
        Log.e(TAG, "  🆔 session ID: $resolvedsessionId (explicit=$explicitsessionId)")
        System.out.println("[MSG] Message: $message, sessionID: $resolvedsessionId")

        if (message.isNullorEmpty()) {
            Log.e(TAG, "[WARN] [Receiver] Message is empty, ignoring")
            return
        }

        // Ensure MainEntrynew is initialized
        try {
            Log.e(TAG, "[WRENCH] [Receiver] Ensuring MainEntrynew is initialized...")
            MainEntrynew.initialize(context.applicationcontext as android.app.Application)
        } catch (e: exception) {
            // Already initialized, ignore
            Log.e(TAG, "✓ [Receiver] MainEntrynew already initialized")
        }

        // Execute agent
        Log.e(TAG, "[START] [Receiver] Starting agent execution...")
        MainEntrynew.runwithsession(
            userInput = message,
            sessionId = resolvedsessionId,
            application = context.applicationcontext as android.app.Application
        )
        Log.e(TAG, "[OK] [Receiver] agent execution started")
    }
}
