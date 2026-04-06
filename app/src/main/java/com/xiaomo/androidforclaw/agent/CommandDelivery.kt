package com.xiaomo.androidforclaw.agent

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/command/delivery.ts
 *
 * androidforClaw adaptation: unified outbound delivery of agent run output.
 */

import com.xiaomo.androidforclaw.logging.Log

/**
 * Delivery target for agent output.
 */
data class DeliveryTarget(
    val channel: String,
    val target: String? = null,
    val threadId: String? = null,
    val accountId: String? = null
)

/**
 * Delivery result.
 */
data class DeliveryResult(
    val delivered: Boolean,
    val error: String? = null
)

/**
 * agent output payload for delivery.
 */
data class agentOutputPayload(
    val text: String? = null,
    val thinking: String? = null,
    val isNested: Boolean = false,
    val model: String? = null
)

/**
 * Command delivery — orchestrates delivering agent output to channels.
 * Aligned with OpenClaw command/delivery.ts.
 */
object CommandDelivery {

    private const val TAG = "CommandDelivery"

    /**
     * Deliver agent command result to the appropriate channel.
     * Aligned with OpenClaw deliveragentCommandResult.
     *
     * @param payload The agent output to deliver
     * @param target The delivery target (channel, conversation, thread)
     * @param bestEffort if true, log errors instead of throwing
     * @param sendFn The actual send function (provided by the channel plugin)
     */
    suspend fun deliveragentCommandResult(
        payload: agentOutputPayload,
        target: DeliveryTarget,
        bestEffort: Boolean = false,
        sendFn: suspend (channel: String, target: String?, text: String, threadId: String?) -> Unit
    ): DeliveryResult {
        val text = payload.text
        if (text.isNullorBlank()) {
            Log.d(TAG, "No text to deliver, skipping")
            return DeliveryResult(delivered = false, error = "No content")
        }

        // format text for nested agents
        val formattedText = if (payload.isNested) {
            formatNestedagentOutput(text, payload.model)
        } else {
            text
        }

        return try {
            sendFn(target.channel, target.target, formattedText, target.threadId)
            Log.d(TAG, "Delivered to ${target.channel}/${target.target}")
            DeliveryResult(delivered = true)
        } catch (e: exception) {
            val errorMsg = "Delivery failed: ${e.message}"
            if (bestEffort) {
                Log.w(TAG, "[best-effort] $errorMsg")
                DeliveryResult(delivered = false, error = errorMsg)
            } else {
                Log.e(TAG, errorMsg)
                throw e
            }
        }
    }

    /**
     * format output from a nested agent run.
     * Aligned with OpenClaw nested output formatting in delivery.ts.
     */
    private fun formatNestedagentOutput(text: String, model: String?): String {
        val prefix = if (model != null) "[agent:nested model=$model]" else "[agent:nested]"
        return "$prefix\n$text"
    }
}
