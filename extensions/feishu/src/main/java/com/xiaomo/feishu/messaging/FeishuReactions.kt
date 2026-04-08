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
 * Feishu reaction handling
 * Aligned with OpenClaw src/reactions.ts
 */
class FeishuReactions(private val client: FeishuClient) {
    companion object {
        private const val TAG = "FeishuReactions"
    }

/**
 * Add reaction
 */
    suspend fun addReaction(messageId: String, emoji: FeishuEmoji): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "reaction_type" to mapOf(
                    "emoji_type" to emoji.code
                )
            )

            val result = client.post("/open-apis/im/v1/messages/$messageId/reactions", body)

            if (result.isFailure) {
                return@withContext result
            }

            Log.d(TAG, "Reaction added: $messageId - ${emoji.code}")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add reaction", e)
            Result.failure(e)
        }
    }

/**
 * Remove reaction
 */
    suspend fun removeReaction(messageId: String, reactionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = client.delete("/open-apis/im/v1/messages/$messageId/reactions/$reactionId")

            if (result.isFailure) {
                return@withContext result
            }

            Log.d(TAG, "Reaction removed: $messageId - $reactionId")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove reaction", e)
            Result.failure(e)
        }
    }

/**
 * List all reactions on a message
 */
    suspend fun listReactions(messageId: String): Result<List<ReactionInfo>> = withContext(Dispatchers.IO) {
        try {
            val result = client.get("/open-apis/im/v1/messages/$messageId/reactions")

            if (result.isFailure) {
                return@withContext result
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items") ?: return@withContext Result.success(emptyList())

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

            Result.success(reactions)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to list reactions", e)
            Result.failure(e)
        }
    }
}

/**
 * Feishu emoji enum
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
    THINKING("Typing"),  // Align with clawdbfeishu: Typing emoji represents "typing"
    CRY("CRY"),
    CELEBRATE("CELEBRATE"),
    ROCKET("ROCKET"),
    CHECK("CHECK"),
    CROSS("CROSS")
}

/**
 * Reaction info
 */
data class ReactionInfo(
    val reactionId: String,
    val operatorId: String?,
    val emojiType: String?
)