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
 * 飞书Table情回复
 * Aligned with OpenClaw src/reactions.ts
 */
class FeishuReactions(private val client: FeishuClient) {
    companion object {
        private const val TAG = "FeishuReactions"
    }

    /**
     * AddTable情回复
     */
    suspend fun addReaction(messageId: String, emoji: FeishuEmoji): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "reaction_type" to mapOf(
                    "emoji_type" to emoji.code
                )
            )

            val result = client.post("/open-apis/im/v1/messages/$messageId/reactions", body)

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Reaction added: $messageId - ${emoji.code}")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add reaction", e)
            result.failure(e)
        }
    }

    /**
     * 移除Table情回复
     */
    suspend fun removeReaction(messageId: String, reactionId: String): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = client.delete("/open-apis/im/v1/messages/$messageId/reactions/$reactionId")

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Reaction removed: $messageId - $reactionId")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove reaction", e)
            result.failure(e)
        }
    }

    /**
     * ListMessage的All回复
     */
    suspend fun listReactions(messageId: String): result<List<ReactionInfo>> = withContext(Dispatchers.IO) {
        try {
            val result = client.get("/open-apis/im/v1/messages/$messageId/reactions")

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items") ?: return@withContext result.success(emptyList())

            val reactions = items.map { item ->
                val obj = item.asJsonObject
                val reactionId = obj.get("reaction_id")?.asString ?: ""
                val operatorId = obj.getAsJsonObject("operator")?.get("operator_id")?.asString
                val reactionType = obj.getAsJsonObject("reaction_type")?.get("emoji_type")?.asString

                ReactionInfo(
                    reactionId = reactionId,
                    operatorId = operatorId,
                    emojiType = reactionType
                )
            }

            result.success(reactions)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to list reactions", e)
            result.failure(e)
        }
    }
}

/**
 * 飞书Table情枚举
 * Aligned with OpenClaw FeishuEmoji
 */
enum class FeishuEmoji(val code: String) {
    THUMBSUP("THUMBSUP"),
    THUMBSDOWN("THUMBSDOWN"),
    LAUGHING("LAUGHING"),
    HEART("HEART"),
    FIRE("FIRE"),
    OK("OK"),
    STAR("STAR"),
    EYES("EYES"),
    THINKING("Typing"),  // 对齐 clawdbot-feishu: Typing Table情Table示"typing"
    CRY("CRY"),
    CELEBRATE("CELEBRATE"),
    ROCKET("ROCKET"),
    CHECK("CHECK"),
    CROSS("CROSS")
}

/**
 * Table情回复Info
 */
data class ReactionInfo(
    val reactionId: String,
    val operatorId: String?,
    val emojiType: String?
)
