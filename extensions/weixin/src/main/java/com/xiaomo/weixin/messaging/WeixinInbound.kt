/**
 * OpenClaw Source Reference:
 * - @tencent-weixin/openclaw-weixin/src/messaging/inbound.ts
 *
 * Inbound message conversion (Weixin → internal format).
 */
package com.xiaomo.weixin.messaging

import com.xiaomo.weixin.api.CDNMedia
import com.xiaomo.weixin.api.MessageItemType
import com.xiaomo.weixin.api.WeixinMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Context token cache: accountId+userId → contextToken.
 * Must be echoed in every outbound send.
 */
object ContextTokenStore {
    private val store = ConcurrentHashMap<String, String>()

    fun set(accountId: String, userId: String, token: String) {
        store["$accountId:$userId"] = token
    }

    fun get(accountId: String, userId: String): String? {
        return store["$accountId:$userId"]
    }

    fun clear() {
        store.clear()
    }

    /** Debug: return all cached keys (for logging) */
    fun debugKeys(): String = store.keys.joinToString(", ")
}

/**
 * Parsed inbound message — ready for Agent dispatch.
 */
data class WeixinInboundMessage(
    val body: String,
    val fromUserId: String,
    val messageId: Long?,
    val timestamp: Long?,
    val contextToken: String?,
    val hasMedia: Boolean = false,
    // Media fields (populated when hasMedia = true)
    val mediaType: Int? = null,          // MessageItemType.IMAGE / VOICE / FILE / VIDEO
    val mediaCdn: CDNMedia? = null,      // CDN reference for download
    val mediaFileName: String? = null,   // File name (for FILE type)
    val mediaMimeType: String? = null,   // Inferred MIME type
    val mediaFileExtension: String? = null, // File extension (jpg, png, silk, mp4, etc.)
    // Voice-specific
    val voiceText: String? = null,       // STT transcription text
    val voicePlaytime: Int? = null,      // Duration in seconds
    // Image-specific
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

/**
 * Extract text body from message item list.
 */
fun extractBody(msg: WeixinMessage): String {
    val items = msg.itemList ?: return ""
    for (item in items) {
        // Text message
        if (item.type == MessageItemType.TEXT && item.textItem?.text != null) {
            val text = item.textItem.text
            val ref = item.refMsg
            if (ref == null) return text

            // Build quoted context
            val parts = mutableListOf<String>()
            ref.title?.let { parts.add(it) }
            ref.messageItem?.let { refItem ->
                if (refItem.type == MessageItemType.TEXT && refItem.textItem?.text != null) {
                    parts.add(refItem.textItem.text)
                }
            }
            return if (parts.isNotEmpty()) {
                "[引用: ${parts.joinToString(" | ")}]\n$text"
            } else {
                text
            }
        }
        // Voice with text transcription
        if (item.type == MessageItemType.VOICE && item.voiceItem?.text != null) {
            return item.voiceItem.text
        }
    }
    return ""
}

/**
 * Convert WeixinMessage to WeixinInboundMessage.
 */
fun parseInbound(msg: WeixinMessage, accountId: String): WeixinInboundMessage {
    // Cache context token
    val fromUser = msg.fromUserId ?: ""
    val ctxToken = msg.contextToken
    if (!ctxToken.isNullOrBlank() && fromUser.isNotBlank()) {
        ContextTokenStore.set(accountId, fromUser, ctxToken)
    }

    // Scan items for text body and media
    val items = msg.itemList
    var body = ""
    var mediaType: Int? = null
    var mediaCdn: CDNMedia? = null
    var mediaFileName: String? = null
    var voiceText: String? = null
    var voicePlaytime: Int? = null
    var imageWidth: Int? = null
    var imageHeight: Int? = null

    if (items != null) {
        for (item in items) {
            when (item.type) {
                MessageItemType.TEXT -> {
                    if (body.isBlank()) {
                        body = item.textItem?.text ?: ""
                        // Handle quoted messages
                        val ref = item.refMsg
                        if (ref != null) {
                            val parts = mutableListOf<String>()
                            ref.title?.let { parts.add(it) }
                            ref.messageItem?.let { refItem ->
                                if (refItem.type == MessageItemType.TEXT && refItem.textItem?.text != null) {
                                    parts.add(refItem.textItem.text)
                                }
                            }
                            if (parts.isNotEmpty()) {
                                body = "[引用: ${parts.joinToString(" | ")}]\n$body"
                            }
                        }
                    }
                }
                MessageItemType.VOICE -> {
                    mediaType = MessageItemType.VOICE
                    voiceText = item.voiceItem?.text
                    voicePlaytime = item.voiceItem?.playtime
                    mediaCdn = item.voiceItem?.media
                    // Use voice text as body if no text message
                    if (body.isBlank() && !voiceText.isNullOrBlank()) {
                        body = voiceText!!
                    }
                }
                MessageItemType.IMAGE -> {
                    mediaType = MessageItemType.IMAGE
                    mediaCdn = item.imageItem?.media ?: item.imageItem?.thumbMedia
                    imageWidth = item.imageItem?.thumbWidth
                    imageHeight = item.imageItem?.thumbHeight
                }
                MessageItemType.FILE -> {
                    mediaType = MessageItemType.FILE
                    mediaCdn = item.fileItem?.media
                    mediaFileName = item.fileItem?.fileName
                }
                MessageItemType.VIDEO -> {
                    mediaType = MessageItemType.VIDEO
                    mediaCdn = item.videoItem?.media
                }
            }
        }
    }

    val hasMedia = mediaType != null
    val mediaMimeType = mediaType?.let { inferMimeType(it, mediaFileName) }
    val mediaFileExtension = mediaType?.let { inferExtension(it, mediaFileName) }

    return WeixinInboundMessage(
        body = body,
        fromUserId = fromUser,
        messageId = msg.messageId,
        timestamp = msg.createTimeMs,
        contextToken = ctxToken,
        hasMedia = hasMedia,
        mediaType = mediaType,
        mediaCdn = mediaCdn,
        mediaFileName = mediaFileName,
        mediaMimeType = mediaMimeType,
        mediaFileExtension = mediaFileExtension,
        voiceText = voiceText,
        voicePlaytime = voicePlaytime,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

/** Infer MIME type from message type and filename. */
private fun inferMimeType(mediaType: Int, fileName: String?): String {
    return when (mediaType) {
        MessageItemType.IMAGE -> "image/jpeg"
        MessageItemType.VOICE -> "audio/ogg"
        MessageItemType.VIDEO -> "video/mp4"
        MessageItemType.FILE -> {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase()
            when (ext) {
                "pdf" -> "application/pdf"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                "txt" -> "text/plain"
                "zip", "rar", "7z" -> "application/zip"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "csv" -> "text/csv"
                else -> "application/octet-stream"
            }
        }
        else -> "application/octet-stream"
    }
}

/** Infer file extension from message type and filename. */
private fun inferExtension(mediaType: Int, fileName: String?): String {
    return when (mediaType) {
        MessageItemType.IMAGE -> "jpg"
        MessageItemType.VOICE -> "silk"
        MessageItemType.VIDEO -> "mp4"
        MessageItemType.FILE -> {
            fileName?.substringAfterLast('.', "bin") ?: "bin"
        }
        else -> "bin"
    }
}
