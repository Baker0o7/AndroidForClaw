package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu messaging transport.
 */


import android.util.Log
import com.xiaomo.feishu.FeishuClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feishu typing indicator
 * Aligned with OpenClaw typing.ts
 *
 * Shows "typing" status by adding/removing typing reaction
 */
object FeishuTyping {
    private const val TAG = "FeishuTyping"

    // Feishu emoji for typing indicator
    private const val TYPING_EMOJI = "Typing"

    // Feishu API backoff error codes (rate limit, quota exceeded)
    private val BACKOFF_CODES = setOf(99991400, 99991403, 429)

    /**
     * Typing indicator state
     */
    data class TypingIndicatorState(
        val messageId: String,
        val reactionId: String?
    )

    /**
     * Feishu backoff exception (rate limit, quota exceeded)
     */
    class FeishuBackoffError(val code: Int) : Exception("Feishu API backoff: code $code")

    /**
     * Check if backoff error
     */
    private fun isBackoffError(exception: Exception): Boolean {
        return exception is FeishuBackoffError
    }

    /**
     * Check backoff error code from response
     */
    private fun getBackoffCodeFromResponse(response: Map<String, Any?>): Int? {
        val code = response["code"] as? Int
        return if (code != null && BACKOFF_CODES.contains(code)) code else null
    }

    /**
     * Add typing indicator (typing reaction)
     *
     * @param client Feishu Client
     * @param messageId Message ID
     * @return typing indicator state
     * @throws FeishuBackoffError when rate limit/quota exceeded
     */
    suspend fun addTypingIndicator(
        client: FeishuClient,
        messageId: String
    ): result<TypingIndicatorState> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "reaction_type" to mapOf(
                    "emoji_type" to TYPING_EMOJI
                )
            )

            val response = client.post("/open-apis/im/v1/messages/$messageId/reactions", body)

            if (response.isFailure) {
                val exception = response.exceptionOrNull()
                if (exception is FeishuBackoffError) {
                    Log.d(TAG, "Typing indicator hit backoff, stopping keepalive")
                    throw exception
                }
                // Other errors fail silently (message may be deleted, permission issue, etc.)
                Log.d(TAG, "Failed to add typing indicator (non-critical): ${exception?.message}")
                return@withContext result.success(TypingIndicatorState(messageId, null))
            }

            val data = response.getOrNull() as? Map<*, *>
            val dataMap = data?.get("data") as? Map<*, *>
            val reactionId = dataMap?.get("reaction_id") as? String

            result.success(TypingIndicatorState(messageId, reactionId))

        } catch (e: Exception) {
            if (e is FeishuBackoffError) {
                throw e // Re-throw backoff error
            }
            Log.d(TAG, "Failed to add typing indicator: ${e.message}")
            result.success(TypingIndicatorState(messageId, null))
        }
    }

    /**
     * Remove typing indicator
     *
     * @param client Feishu Client
     * @param state typing indicator state
     * @throws FeishuBackoffError when rate limit/quota exceeded
     */
    suspend fun removeTypingIndicator(
        client: FeishuClient,
        state: TypingIndicatorState
    ): result<Unit> = withContext(Dispatchers.IO) {
        if (state.reactionId == null) {
            return@withContext result.success(Unit)
        }

        try {
            val response = client.delete(
                "/open-apis/im/v1/messages/${state.messageId}/reactions/${state.reactionId}"
            )

            if (response.isFailure) {
                val exception = response.exceptionOrNull()
                if (exception is FeishuBackoffError) {
                    Log.d(TAG, "Typing indicator removal hit backoff")
                    throw exception
                }
                // Other errors fail silently
                Log.d(TAG, "Failed to remove typing indicator (non-critical): ${exception?.message}")
            }

            result.success(Unit)

        } catch (e: Exception) {
            if (e is FeishuBackoffError) {
                throw e
            }
            Log.d(TAG, "Failed to remove typing indicator: ${e.message}")
            result.success(Unit)
        }
    }
}
