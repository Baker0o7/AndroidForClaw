package com.xiaomo.feishu.messaging

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/media.ts (downloadImageFeishu, downloadMessageResourceFeishu)
 *
 * Downloads media attachments from Feishu messages.
 */

import android.util.Log
import com.xiaomo.feishu.FeishuClient
import java.io.File

/**
 * Media download result
 */
data class Downloadresult(
    val file: File,
    val contentType: String? = null
)

/**
 * Feishu media downloader
 * Aligned with OpenClaw media.ts download APIs
 */
class FeishuMediaDownload(
    private val client: FeishuClient,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "FeishuMediaDownload"
        private const val MEDIA_CACHE_DIR = "feishu_media"
    }

    private val mediaCacheDir: File by lazy {
        File(cacheDir, MEDIA_CACHE_DIR).also { it.mkdirs() }
    }

    /**
     * Download individual image
     * Aligned with OpenClaw downloadImageFeishu
     *
     * API: GET /open-apis/im/v1/images/{image_key}
     */
    suspend fun downloadImage(imageKey: String): result<Downloadresult> {
        // Check cache
        val cached = File(mediaCacheDir, "img_$imageKey")
        if (cached.exists() && cached.length() > 0) {
            Log.d(TAG, "Image cache hit: $imageKey")
            return result.success(Downloadresult(cached))
        }

        val result = client.downloadRaw("/open-apis/im/v1/images/$imageKey")
        if (result.isFailure) {
            return result.failure(result.exceptionOrNull()!!)
        }

        val bytes = result.getOrNull()!!
        cached.writeBytes(bytes)
        Log.d(TAG, "Downloaded image: $imageKey (${bytes.size} bytes)")
        return result.success(Downloadresult(cached))
    }

    /**
     * Download message attachment resource
     * Aligned with OpenClaw downloadMessageResourceFeishu
     *
     * API: GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type={image|file}
     */
    suspend fun downloadMessageResource(
        messageId: String,
        fileKey: String,
        type: String = "file"
    ): result<Downloadresult> {
        // Check cache
        val cached = File(mediaCacheDir, "res_${fileKey}")
        if (cached.exists() && cached.length() > 0) {
            Log.d(TAG, "Resource cache hit: $fileKey")
            return result.success(Downloadresult(cached))
        }

        val path = "/open-apis/im/v1/messages/$messageId/resources/$fileKey?type=$type"
        val result = client.downloadRaw(path)
        if (result.isFailure) {
            return result.failure(result.exceptionOrNull()!!)
        }

        val bytes = result.getOrNull()!!
        cached.writeBytes(bytes)
        Log.d(TAG, "Downloaded resource: $fileKey (${bytes.size} bytes)")
        return result.success(Downloadresult(cached))
    }

    /**
     * Auto download based on media keys
     * Returns local file path (to be appended to message content)
     */
    suspend fun downloadMedia(
        messageId: String,
        mediaKeys: MediaKeys
    ): result<Downloadresult> {
        return when (mediaKeys.mediaType) {
            "image" -> {
                val key = mediaKeys.imageKey
                    ?: return result.failure(Exception("Missing image_key"))
                downloadImage(key)
            }
            "file", "audio", "video", "sticker" -> {
                val key = mediaKeys.fileKey
                    ?: return result.failure(Exception("Missing file_key"))
                downloadMessageResource(messageId, key, "file")
            }
            else -> result.failure(Exception("Unsupported media type: ${mediaKeys.mediaType}"))
        }
    }

    /**
     * Cleanup expired cache (older than 24 hours)
     */
    fun cleanupCache(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        mediaCacheDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }
}
