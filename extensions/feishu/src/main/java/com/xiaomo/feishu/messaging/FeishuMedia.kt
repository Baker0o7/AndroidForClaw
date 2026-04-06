package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu messaging transport.
 */


import android.util.Log
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feishu media handling (simplified)
 *
 * Note:
 * - uploadImage removed, use FeishuImageUploadTool instead
 * - Only keep sendImage/sendFile (send already uploaded media)
 */
class FeishuMedia(
    private val config: FeishuConfig,
    private val client: FeishuClient
) {
    companion object {
        private const val TAG = "FeishuMedia"
    }

    /**
     * Send image message
     */
    suspend fun sendImage(
        receiveId: String,
        imageKey: String,
        receiveIdType: String = "open_id"
    ): result<String> = withContext(Dispatchers.IO) {
        try {
            val content = """{"image_key":"$imageKey"}"""
            val body = mapOf(
                "receive_id" to receiveId,
                "msg_type" to "image",
                "content" to content
            )

            val result = client.post(
                "/open-apis/im/v1/messages?receive_id_type=$receiveIdType",
                body
            )

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val messageId = data?.get("message_id")?.asString
                ?: return@withContext result.failure(Exception("Missing message_id"))

            Log.d(TAG, "Image message sent: $messageId")
            result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send image message", e)
            result.failure(e)
        }
    }

    /**
     * Send file message
     */
    suspend fun sendFile(
        receiveId: String,
        fileKey: String,
        receiveIdType: String = "open_id"
    ): result<String> = withContext(Dispatchers.IO) {
        try {
            val content = """{"file_key":"$fileKey"}"""
            val body = mapOf(
                "receive_id" to receiveId,
                "msg_type" to "file",
                "content" to content
            )

            val result = client.post(
                "/open-apis/im/v1/messages?receive_id_type=$receiveIdType",
                body
            )

            if (result.isFailure) {
                return@withContext result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val messageId = data?.get("message_id")?.asString
                ?: return@withContext result.failure(Exception("Missing message_id"))

            Log.d(TAG, "File message sent: $messageId")
            result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file message", e)
            result.failure(e)
        }
    }
}
