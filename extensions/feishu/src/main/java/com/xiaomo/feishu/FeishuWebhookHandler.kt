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
 * 飞书 Webhook ConnectProcess器
 * Aligned with OpenClaw webhook Schema
 *
 * TODO: Need集成到 Gateway HTTP Service器
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

        // TODO: Register webhook endpoint 到 Gateway HTTP Service器
        // Gateway 会将飞书的 webhook Callback转发到这里
    }

    override fun stop() {
        Log.i(TAG, "Webhook mode stopped")
        // TODO: Logout webhook endpoint
    }

    /**
     * Process Webhook Callback
     * by Gateway HTTP Service器call
     */
    suspend fun handleWebhookCallback(payload: String): String {
        try {
            // TODO: Parse并Process webhook payload
            // TODO: Validate签名
            // TODO: sendEvent到 eventFlow
            return """{"code":0}"""

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle webhook callback", e)
            return """{"code":1,"msg":"${e.message}"}"""
        }
    }
}
