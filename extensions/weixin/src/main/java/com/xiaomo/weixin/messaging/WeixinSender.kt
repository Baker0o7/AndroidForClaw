/**
 * OpenClaw Source Reference:
 * - @tencent-weixin/openclaw-weixin/src/messaging/send.ts
 *
 * Outbound message sender for Weixin channel.
 */
package com.xiaomo.weixin.messaging

import android.util.Log
import com.xiaomo.weixin.api.WeixinApi

class WeixinSender(private val api: WeixinApi, private val accountId: String) {
    companion object {
        private const val TAG = "WeixinSender"
        private const val TEXT_CHUNK_LIMIT = 4000
    }

    /**
     * Send a text reply to a user.
     * Automatically chunks long messages.
     */
    suspend fun sendText(toUserId: String, text: String): Boolean {
        val contextToken = ContextTokenStore.get(accountId, toUserId)
        if (contextToken == null) {
            Log.e(TAG, "No context token for accountId=$accountId user=$toUserId, cannot send. Store keys: ${ContextTokenStore.debugKeys()}")
            return false
        }
        Log.d(TAG, "sendText to=$toUserId contextToken=${contextToken.take(20)}... text=${text.take(100)}")

        return try {
            if (text.length <= TEXT_CHUNK_LIMIT) {
                api.sendText(toUserId, text, contextToken)
            } else {
                // Chunk long messages
                val chunks = text.chunked(TEXT_CHUNK_LIMIT)
                for (chunk in chunks) {
                    api.sendText(toUserId, chunk, contextToken)
                }
            }
            Log.d(TAG, "✅ Text sent to $toUserId (${text.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text to $toUserId", e)
            false
        }
    }

    /**
     * Send typing indicator.
     */
    suspend fun sendTyping(toUserId: String) {
        try {
            val contextToken = ContextTokenStore.get(accountId, toUserId)
            val configResp = api.getConfig(toUserId, contextToken)
            val ticket = configResp.typingTicket
            if (!ticket.isNullOrBlank()) {
                api.sendTyping(toUserId, ticket)
                Log.d(TAG, "Typing indicator sent to $toUserId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send typing to $toUserId: ${e.message}")
        }
    }

    /**
     * Cancel typing indicator.
     */
    suspend fun cancelTyping(toUserId: String) {
        try {
            val contextToken = ContextTokenStore.get(accountId, toUserId)
            val configResp = api.getConfig(toUserId, contextToken)
            val ticket = configResp.typingTicket
            if (!ticket.isNullOrBlank()) {
                api.sendTyping(toUserId, ticket, com.xiaomo.weixin.api.TypingStatus.CANCEL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel typing: ${e.message}")
        }
    }
}
