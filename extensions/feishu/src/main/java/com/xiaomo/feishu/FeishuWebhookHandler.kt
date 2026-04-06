package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Feishu Webhook connection handler
 * Aligned with OpenClaw webhook Schema
 *
 * TODO: Need to integrate with Gateway HTTP server
 */
class FeishuWebhookHandler(
    private val config: FeishuConfig,
    private val client: FeishuClient,
    private val eventFlow: MutableSharedFlow<FeishuEvent>
) : FeishuConnectionHandler {

    companion object {
        private const val TAG = "FeishuWebhook"
    }

    override fun start() {
        Log.i(TAG, "Webhook mode started")
        Log.i(TAG, "Webhook path: ${config.webhookPath}")
        Log.i(TAG, "Webhook port: ${config.webhookPort}")

        // TODO: Register webhook endpoint with Gateway HTTP server
        // Gateway will forward Feishu webhook callbacks here
    }

    override fun stop() {
        Log.i(TAG, "Webhook mode stopped")
        // TODO: Unregister webhook endpoint
    }

    /**
     * Process webhook callback
     * Called by Gateway HTTP server
     */
    suspend fun handleWebhookCallback(payload: String): String {
        try {
            // TODO: Parse and process webhook payload
            // TODO: Validate signature
            // TODO: Emit event to eventFlow
            return """{"code":0}"""

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle webhook callback", e)
            return """{"code":1,"msg":"${e.message}"}"""
        }
    }
}
