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
 * 飞书打字指示器
 * Aligned with OpenClaw typing.ts
 *
 * 通过Add/移除 Typing Table情来Show"typing"Status
 */
object FeishuTyping {
    private const val TAG = "FeishuTyping"

    // Feishu emoji for typing indicator
    private const val TYPING_EMOJI = "Typing"

    // Feishu API backoff error codes (rate limit, quota exceeded)
    private val BACKOFF_CODES = setOf(99991400, 99991403, 429)

    /**
     * 打字指示器Status
     */
    data class TypingIndicatorState(
        val messageId: String,
        val reactionId: String?
    )

    /**
     * Feishu backoff Exception(限流、配额超限)
     */
    class FeishuBackoffError(val code: Int) : Exception("Feishu API backoff: code $code")

    /**
     * CheckYesNo为 backoff Error
     */
    private fun isBackoffError(exception: Exception): Boolean {
        return exception is FeishuBackoffError
    }

    /**
     * 从Response中Check backoff Error码
     */
    private fun getBackoffCodeFromResponse(response: Map<String, Any?>): Int? {
        val code = response["code"] as? Int
        return if (code != null && BACKOFF_CODES.contains(code)) code else null
    }

    /**
     * Add打字指示器(Typing Table情)
     *
     * @param client Feishu Client
     * @param messageId Message ID
     * @return 打字指示器Status
     * @throws FeishuBackoffError 当遇到限流/配额Error时抛出
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
                // Its他Error静默Failed(Messagepossibly已Delete、PermissionIssue等)
                Log.d(TAG, "Failed to add typing indicator (non-critical): ${exception?.message}")
                return@withContext result.success(TypingIndicatorState(messageId, null))
            }

            val data = response.getOrNull() as? Map<*, *>
            val dataMap = data?.get("data") as? Map<*, *>
            val reactionId = dataMap?.get("reaction_id") as? String

            result.success(TypingIndicatorState(messageId, reactionId))

        } catch (e: Exception) {
            if (e is FeishuBackoffError) {
                throw e // 重New抛出 backoff Error
            }
            Log.d(TAG, "Failed to add typing indicator: ${e.message}")
            result.success(TypingIndicatorState(messageId, null))
        }
    }

    /**
     * 移除打字指示器
     *
     * @param client Feishu Client
     * @param state 打字指示器Status
     * @throws FeishuBackoffError 当遇到限流/配额Error时抛出
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
                // Its他Error静默Failed
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
