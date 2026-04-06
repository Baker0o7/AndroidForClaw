package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Feishu Channel Core Class
 * Aligned with OpenClaw channel.ts
 *
 * Feature: 
 * - WebSocket/Webhook Connection Management
 * - Message receive and send
 * - Event distribution
 * - Session Management
 */
class FeishuChannel(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuChannel"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = FeishuClient(config)

    /**
     * Feishu Tool Registry — all feishu extension tools (doc, wiki, drive, bitable, etc.)
     * Aligned with OpenClaw: extension tools auto-register when channel starts
     */
    private val feishuToolRegistry by lazy {
        com.xiaomo.feishu.tools.FeishuToolRegistry(config, client)
    }

    /**
     * Get FeishuToolRegistry for bridging into main ToolRegistry
     */
    fun getToolRegistry(): com.xiaomo.feishu.tools.FeishuToolRegistry = feishuToolRegistry

    /**
     * Get FeishuClient for direct API access (media download, streaming card, etc.)
     */
    fun getClient(): FeishuClient = client

    /**
     * Create a new streaming card session (Card Kit API)
     * Aligned with OpenClaw FeishuStreamingSession
     */
    fun createStreamingCard(): com.xiaomo.feishu.messaging.FeishuStreamingCard =
        com.xiaomo.feishu.messaging.FeishuStreamingCard(client)

    /**
     * FeishuSender - Support Markdown card rendering
     */
    val sender by lazy { com.xiaomo.feishu.messaging.FeishuSender(config, client) }

    // Event flow
    private val _eventFlow = MutableSharedFlow<FeishuEvent>(replay = 0, extraBufferCapacity = 100)
    val eventFlow: SharedFlow<FeishuEvent> = _eventFlow.asSharedFlow()

    // Connection status
    private var isConnected = false
    private var connectionHandler: FeishuConnectionHandler? = null

    // Bot's own open_id (for @mention detection)
    private var botOpenId: String? = null
    private var botName: String? = null

    // Current conversation context (for Agent tool calls)
    private var currentChatContext: ChatContext? = null

    /**
     * Current conversation context
     */
    data class ChatContext(
        val receiveId: String,
        val receiveIdType: String = "chat_id",
        val messageId: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Get bot's open_id
     */
    fun getBotOpenId(): String? = botOpenId

    fun getBotName(): String? = botName

    /**
     * Set bot's open_id
     */
    fun setBotOpenId(openId: String) {
        botOpenId = openId
        Log.d(TAG, "Bot open_id set: $openId")
    }

    /**
     * Background retry for bot identity (aligned with OpenClaw monitor.account.ts).
     * When bot info probe fails at startup, retry with escalating delays
     * so the degraded state (not knowing bot open_id) is bounded rather than permanent.
     */
    private fun startBotIdentityRetry() {
        val retryDelaysMs = listOf(60_000L, 120_000L, 300_000L, 600_000L, 900_000L)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
        )
        scope.launch {
            for ((i, delayMs) in retryDelaysMs.withIndex()) {
                if (botOpenId != null || !isConnected) return@launch
                delay(delayMs)
                if (botOpenId != null || !isConnected) return@launch
                try {
                    val botInforesult = client.getBotInfo()
                    if (botInforesult.isSuccess) {
                        val botInfo = botInforesult.getOrNull()
                        val openId = botInfo?.openId
                        if (openId != null) {
                            botOpenId = openId
                            Log.i(TAG, "✅ Bot open_id recovered via background retry: $openId")
                            return@launch
                        }
                    }
                    val nextDelay = retryDelaysMs.getOrNull(i + 1)
                    Log.w(TAG, "Bot identity retry ${i + 1}/${retryDelaysMs.size} failed" +
                        if (nextDelay != null) "; next attempt in ${nextDelay / 1000}s" else "")
                } catch (e: Exception) {
                    Log.w(TAG, "Bot identity retry ${i + 1} error: ${e.message}")
                }
            }
            Log.e(TAG, "Bot identity retry exhausted; requireMention group messages may be skipped until restart")
        }
    }

    /**
     * Update current conversation context (from MessageEvent)
     * Should be called when receiving a message, record current conversation info for Agent tool use
     */
    fun updateCurrentChatContext(receiveId: String, receiveIdType: String = "chat_id", messageId: String? = null) {
        currentChatContext = ChatContext(
            receiveId = receiveId,
            receiveIdType = receiveIdType,
            messageId = messageId,
            timestamp = System.currentTimeMillis()
        )
        Log.d(TAG, "Current chat context updated: receiveId=$receiveId, type=$receiveIdType")
    }

    /**
     * Get current conversation context
     */
    fun getCurrentChatContext(): ChatContext? = currentChatContext

    /**
     * Send image to current conversation
     * For Agent tool calls (FeishuSendImageSkill)
     */
    suspend fun sendImageToCurrentChat(imageFile: java.io.File): result<String> {
        val context = currentChatContext
        if (context == null) {
            Log.e(TAG, "No active chat context")
            return result.failure(Exception("No active chat context. Cannot determine recipient."))
        }

        // Check context expiration (over 5 minutes)
        val ageMs = System.currentTimeMillis() - context.timestamp
        if (ageMs > 5 * 60 * 1000) {
            Log.w(TAG, "Chat context is stale (${ageMs}ms old)")
            return result.failure(Exception("Chat context is stale. Please send a message first."))
        }

        return try {
            // Use new FeishuImageUploadTool to upload image
            val uploadTool = com.xiaomo.feishu.tools.media.FeishuImageUploadTool(config, client)

            // 1. Upload image
            Log.d(TAG, "Uploading image: ${imageFile.name} (${imageFile.length()} bytes)")
            val toolResult = uploadTool.execute(mapOf("image_path" to imageFile.absolutePath))

            if (!toolResult.success) {
                Log.e(TAG, "Failed to upload image: ${toolResult.error}")
                return result.failure(Exception(toolResult.error ?: "Upload failed"))
            }

            val imageKey = toolResult.data as? String
                ?: return result.failure(Exception("Upload succeeded but no image_key"))

            Log.d(TAG, "Image uploaded successfully. image_key: $imageKey")

            // 2. Send image message (still use FeishuMedia)
            val media = com.xiaomo.feishu.messaging.FeishuMedia(config, client)
            Log.d(TAG, "Sending image to ${context.receiveId} (type: ${context.receiveIdType})")
            val sendResult = media.sendImage(
                receiveId = context.receiveId,
                imageKey = imageKey,
                receiveIdType = context.receiveIdType
            )

            if (sendResult.isFailure) {
                val error = sendResult.exceptionOrNull()
                Log.e(TAG, "Failed to send image", error)
                return result.failure(error ?: Exception("Send failed"))
            }

            val messageId = sendResult.getOrNull()
                ?: return result.failure(Exception("Send succeeded but no message_id"))

            Log.i(TAG, "Image sent successfully. message_id: $messageId")
            result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send image to current chat", e)
            result.failure(e)
        }
    }

    /**
     * Start Channel
     */
    suspend fun start(): result<Unit> {
        return try {
            // Validate config
            config.validate().getOrThrow()

            Log.i(TAG, "Starting Feishu Channel...")
            Log.i(TAG, "  Mode: ${config.connectionMode}")
            Log.i(TAG, "  Domain: ${config.domain}")
            Log.i(TAG, "  DM Policy: ${config.dmPolicy}")
            Log.i(TAG, "  Group Policy: ${config.groupPolicy}")

            // Get bot info (Aligned with OpenClaw)
            val botInfoResult = client.getBotInfo()
            if (botInfoResult.isSuccess) {
                val botInfo = botInfoResult.getOrNull()
                botOpenId = botInfo?.openId
                botName = botInfo?.name
                Log.i(TAG, "  Bot open_id: ${botOpenId ?: "unknown"}")
                Log.i(TAG, "  Bot name: ${botInfo?.name ?: "unknown"}")
            } else {
                Log.w(TAG, "Failed to get bot info: ${botInfoResult.exceptionOrNull()?.message}")
                Log.w(TAG, "Will continue without bot open_id (mention check may not work correctly)")
                // Background retry with escalating delays (aligned with OpenClaw monitor.account.ts)
                startBotIdentityRetry()
            }

            // Create handler according to Connection Schema
            connectionHandler = when (config.connectionMode) {
                FeishuConfig.ConnectionMode.WEBSOCKET -> {
                    FeishuWebSocketHandler(config, client, _eventFlow)
                }
                FeishuConfig.ConnectionMode.WEBHOOK -> {
                    FeishuWebhookHandler(config, client, _eventFlow)
                }
            }

            // Start connection
            connectionHandler?.start()
            isConnected = true

            Log.i(TAG, "Feishu Channel started successfully")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Feishu Channel", e)
            result.failure(e)
        }
    }

    /**
     * Stop Channel
     */
    fun stop() {
        Log.i(TAG, "Stopping Feishu Channel...")
        connectionHandler?.stop()
        connectionHandler = null
        isConnected = false
        Log.i(TAG, "Feishu Channel stopped")
    }

    /**
     * Add message reaction (emoji)
     */
    suspend fun addReaction(messageId: String, emojiType: String): result<String> {
        return try {
            val body = mapOf(
                "reaction_type" to mapOf(
                    "emoji_type" to emojiType
                )
            )

            val result = client.post("/open-apis/im/v1/messages/$messageId/reactions", body)
            if (result.isFailure) {
                return result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val reactionId = data?.get("reaction_id")?.asString
                ?: return result.failure(Exception("Missing reaction_id in response"))

            Log.d(TAG, "Reaction added: $reactionId")
            result.success(reactionId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add reaction", e)
            result.failure(e)
        }
    }

    /**
     * Remove message reaction
     */
    suspend fun removeReaction(messageId: String, reactionId: String): result<Unit> {
        return try {
            val result = client.delete("/open-apis/im/v1/messages/$messageId/reactions/$reactionId")
            if (result.isFailure) {
                return result.failure(result.exceptionOrNull()!!)
            }

            Log.d(TAG, "Reaction removed: $reactionId")
            result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove reaction", e)
            result.failure(e)
        }
    }

    /**
     * Send text message
     */
    suspend fun sendMessage(
        receiveId: String,
        receiveIdType: String = "open_id",
        content: String,
        msgType: String = "text"
    ): result<String> {
        return try {
            // Build message content JSON string
            val contentJson = when (msgType) {
                "text" -> {
                    val textContent = mapOf("text" to content)
                    gson.toJson(textContent)
                }
                else -> content
            }

            val body = mapOf(
                "receive_id" to receiveId,
                "msg_type" to msgType,
                "content" to contentJson
            )

            val result = client.post("/open-apis/im/v1/messages?receive_id_type=$receiveIdType", body)
            if (result.isFailure) {
                return result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val messageId = data?.get("message_id")?.asString
                ?: return result.failure(Exception("Missing message_id in response"))

            Log.d(TAG, "Message sent: $messageId")
            result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            result.failure(e)
        }
    }

    /**
     * Send card message
     */
    suspend fun sendCard(
        receiveId: String,
        receiveIdType: String = "open_id",
        card: String
    ): result<String> {
        return sendMessage(receiveId, receiveIdType, card, "interactive")
    }

    /**
     * Upload and send image file
     * Aligned with OpenClaw sendMediaFeishu
     *
     * @param imageFile Image file
     * @param receiveId Receiver ID
     * @param receiveIdType Receiver ID type
     * @return Message ID
     */
    suspend fun uploadAndSendImage(
        imageFile: java.io.File,
        receiveId: String,
        receiveIdType: String = "open_id"
    ): result<String> {
        return try {
            // Use new FeishuImageUploadTool to upload image
            val uploadTool = com.xiaomo.feishu.tools.media.FeishuImageUploadTool(config, client)

            // 1. Upload image
            val toolResult = uploadTool.execute(mapOf("image_path" to imageFile.absolutePath))
            if (!toolResult.success) {
                return result.failure(Exception(toolResult.error ?: "Upload failed"))
            }
            val imageKey = toolResult.data as? String
                ?: return result.failure(Exception("Upload succeeded but no image_key"))

            // 2. Send image message
            val media = com.xiaomo.feishu.messaging.FeishuMedia(config, client)
            media.sendImage(receiveId, imageKey, receiveIdType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload and send image", e)
            result.failure(e)
        }
    }

    /**
     * Get user info
     */
    suspend fun getUserInfo(userId: String): result<FeishuUser> {
        return try {
            val result = client.get("/open-apis/contact/v3/users/$userId")
            if (result.isFailure) {
                return result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("user")
                ?: return result.failure(Exception("Missing user data"))

            val user = FeishuUser(
                userId = data.get("user_id")?.asString ?: userId,
                name = data.get("name")?.asString ?: "",
                enName = data.get("en_name")?.asString,
                email = data.get("email")?.asString,
                mobile = data.get("mobile")?.asString
            )

            result.success(user)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user info", e)
            result.failure(e)
        }
    }

    /**
     * Get group info
     */
    suspend fun getChatInfo(chatId: String): result<FeishuChat> {
        return try {
            val result = client.get("/open-apis/im/v1/chats/$chatId")
            if (result.isFailure) {
                return result.failure(result.exceptionOrNull()!!)
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
                ?: return result.failure(Exception("Missing chat data"))

            val chat = FeishuChat(
                chatId = data.get("chat_id")?.asString ?: chatId,
                name = data.get("name")?.asString ?: "",
                description = data.get("description")?.asString,
                ownerId = data.get("owner_id")?.asString
            )

            result.success(chat)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get chat info", e)
            result.failure(e)
        }
    }

    /**
     * Is connected
     */
    fun isConnected(): Boolean = isConnected
}

/**
 * Feishu Event base class
 */
sealed class FeishuEvent {
    data class Message(
        val messageId: String,
        val senderId: String,
        val chatId: String,
        val chatType: String, // "p2p" or "group"
        val content: String,
        val msgType: String,
        val mentions: List<String> = emptyList(),
        val mentionNames: List<String> = emptyList(),
        // Thread/reply awareness (from Lark SDK message object)
        val rootId: String? = null,
        val parentId: String? = null,
        val threadId: String? = null,
        // Media keys for image/file/audio/video/sticker messages
        val mediaKeys: com.xiaomo.feishu.messaging.MediaKeys? = null,
        // Original message timestamp from Feishu (millisecond epoch string).
        // Aligned with OpenClaw: use create_time instead of processing time.
        val createTime: Long = System.currentTimeMillis()
    ) : FeishuEvent()

    data class Error(val error: Throwable) : FeishuEvent()

    object Connected : FeishuEvent()
    object Disconnected : FeishuEvent()
}

/**
 * Feishu User Info
 */
data class FeishuUser(
    val userId: String,
    val name: String,
    val enName: String? = null,
    val email: String? = null,
    val mobile: String? = null
)

/**
 * Feishu Group Info
 */
data class FeishuChat(
    val chatId: String,
    val name: String,
    val description: String? = null,
    val ownerId: String? = null
)

/**
 * Connection Handler Interface
 */
interface FeishuConnectionHandler {
    fun start()
    fun stop()
}
