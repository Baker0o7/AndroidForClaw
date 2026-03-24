/**
 * OpenClaw Source Reference:
 * - @tencent-weixin/openclaw-weixin/src/messaging/inbound.ts
 *
 * Inbound message conversion (Weixin → internal format).
 */
package com.xiaomo.weixin.messaging

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
    // TODO: media download support
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

    val hasMedia = msg.itemList?.any { item ->
        item.type == MessageItemType.IMAGE ||
                item.type == MessageItemType.VIDEO ||
                item.type == MessageItemType.FILE ||
                item.type == MessageItemType.VOICE
    } ?: false

    return WeixinInboundMessage(
        body = extractBody(msg),
        fromUserId = fromUser,
        messageId = msg.messageId,
        timestamp = msg.createTimeMs,
        contextToken = ctxToken,
        hasMedia = hasMedia,
    )
}
