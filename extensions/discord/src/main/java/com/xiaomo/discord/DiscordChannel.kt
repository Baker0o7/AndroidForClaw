/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Discord Channel Core Class
 * Reference:
 * - OpenClaw discord/src/channel.ts
 * - Feishu FeishuChannel.kt
 *
 * Feature: 
 * - Gateway (WebSocket) Connection Management
 * - Message Receive and Send
 * - Event Distribution
 * - Session Management
 * - Multi Account Support
 */
class DiscordChannel private constructor(
    private val context: Context,
    private val config: DiscordConfig
) {
    companion object {
        private const val TAG = "DiscordChannel"
        private var instance: DiscordChannel? = null

        /**
         * Start Discord Channel
         */
        fun start(context: Context, config: DiscordConfig): result<DiscordChannel> {
            return try {
                if (instance != null) {
                    Log.w(TAG, "Discord Channel already started")
                    return result.success(instance!!)
                }

                val channel = DiscordChannel(context, config)
                instance = channel

                // Start Channel
                channel.scope.launch {
                    channel.startInternal()
                }

                result.success(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Discord Channel", e)
                result.failure(e)
            }
        }

        /**
         * Stop Discord Channel
         */
        fun stop() {
            instance?.stopInternal()
            instance = null
        }

        /**
         * Get Current Instance
         */
        fun getInstance(): DiscordChannel? = instance
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // REST API Client
    private lateinit var client: DiscordClient

    // Gateway (WebSocket) Connection
    private var gateway: DiscordGateway? = null

    // Event flow
    private val _eventFlow = MutableSharedFlow<ChannelEvent>(replay = 0, extraBufferCapacity = 100)
    val eventFlow: SharedFlow<ChannelEvent> = _eventFlow.asSharedFlow()

    // Connection Status
    private var isConnected = false
    private var currentBotUserId: String? = null
    private var currentBotUsername: String? = null

    /**
     * Internal Start Logic
     */
    private suspend fun startInternal() {
        try {
            // Validate Config
            val token = config.token?.trim()
            if (token.isNullOrBlank()) {
                throw IllegalArgumentException("Discord token is required")
            }

            Log.i(TAG, "🚀 Starting Discord Channel...")
            Log.i(TAG, "   Name: ${config.name ?: "default"}")
            Log.i(TAG, "   DM Policy: ${config.dm?.policy ?: "pairing"}")
            Log.i(TAG, "   Group Policy: ${config.groupPolicy ?: "open"}")
            Log.i(TAG, "   Reply Mode: ${config.replyToMode ?: "off"}")

            // Initialize REST API Client
            client = DiscordClient(token)

            // Get Current Bot Info
            val botInfoResult = client.getCurrentUser()
            if (botInfoResult.isSuccess) {
                val botInfo = botInfoResult.getOrNull()
                currentBotUserId = botInfo?.get("id")?.asString
                currentBotUsername = botInfo?.get("username")?.asString
                Log.i(TAG, "   Bot: $currentBotUsername ($currentBotUserId)")
            } else {
                Log.w(TAG, "   Failed to get bot info: ${botInfoResult.exceptionOrNull()?.message}")
            }

            // Calculate Intents
            val intents = calculateIntents()
            Log.i(TAG, "   Intents: $intents")

            // Start Gateway Connection
            val eventFlow = MutableSharedFlow<DiscordEvent>(replay = 0, extraBufferCapacity = 100)
            gateway = DiscordGateway(token, intents, eventFlow)
            gateway?.start()

            // Listen to Gateway Events
            scope.launch {
                eventFlow.collect { event ->
                    handleGatewayEvent(event)
                }
            }

            Log.i(TAG, "✅ Discord Channel started successfully")
            isConnected = true
            _eventFlow.emit(ChannelEvent.Connected)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start Discord Channel", e)
            _eventFlow.emit(ChannelEvent.Error(e))
            throw e
        }
    }

    /**
     * Internal Stop Logic
     */
    private fun stopInternal() {
        Log.i(TAG, "Stopping Discord Channel...")
        try {
            gateway?.stop()
            gateway = null
            isConnected = false
            scope.cancel()
            Log.i(TAG, "Discord Channel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Discord Channel", e)
        }
    }

    /**
     * Calculate Gateway Intents
     */
    private fun calculateIntents(): Int {
        return DiscordGateway.DEFAULT_INTENTS
    }

    /**
     * Process Gateway Event
     */
    private suspend fun handleGatewayEvent(event: DiscordEvent) {
        try {
            when (event) {
                is DiscordEvent.Connected -> {
                    Log.i(TAG, "✅ Gateway connected")
                    isConnected = true
                    _eventFlow.emit(ChannelEvent.Connected)
                }

                is DiscordEvent.Message -> {
                    handleMessage(event)
                }

                is DiscordEvent.ReactionAdd -> {
                    handleReactionAdd(event)
                }

                is DiscordEvent.ReactionRemove -> {
                    handleReactionRemove(event)
                }

                is DiscordEvent.TypingStart -> {
                    handleTypingStart(event)
                }

                is DiscordEvent.Error -> {
                    Log.e(TAG, "Gateway error", event.exception)
                    _eventFlow.emit(ChannelEvent.Error(event.exception))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling gateway event", e)
        }
    }

    /**
     * Process Message Event
     */
    private suspend fun handleMessage(event: DiscordEvent.Message) {
        try {
            // Ignore Bot's own messages
            if (event.authorId == currentBotUserId) {
                Log.d(TAG, "Ignoring self message: ${event.messageId}")
                return
            }

            // Determine Message Type (DM or Guild)
            val chatType = if (event.guildId == null) "direct" else "channel"

            // DM Permission Check
            if (chatType == "direct") {
                val dmPolicy = config.dm?.policy ?: "pairing"
                val allowFrom = config.dm?.allowFrom ?: emptyList()

                when (dmPolicy) {
                    "open" -> {
                        // Allow all DM
                    }
                    "pairing" -> {
                        // Need pairing
                        if (event.authorId !in allowFrom) {
                            Log.d(TAG, "DM from ${event.authorId} not in allowlist (pairing mode)")
                            sendPairingMessage(event.channelId)
                            return
                        }
                    }
                    "allowlist" -> {
                        // Allowlist only
                        if (event.authorId !in allowFrom) {
                            Log.d(TAG, "DM from ${event.authorId} not in allowlist")
                            return
                        }
                    }
                    "denylist" -> {
                        // Denylist only
                        if (event.authorId in allowFrom) {
                            Log.d(TAG, "DM from ${event.authorId} in denylist")
                            return
                        }
                    }
                }
            }

            // Guild Permission Check
            if (chatType == "channel" && event.guildId != null) {
                val guildConfig = config.guilds?.get(event.guildId)
                val groupPolicy = config.groupPolicy ?: "open"

                // Check if in allowlist
                val allowedChannels = guildConfig?.channels
                if (allowedChannels != null && event.channelId !in allowedChannels) {
                    Log.d(TAG, "Channel ${event.channelId} not in allowlist")
                    return
                }

                // Check if need @ mention
                val requireMention = guildConfig?.requireMention ?: true
                if (requireMention) {
                    val botMentioned = currentBotUserId in event.mentions
                    if (!botMentioned) {
                        Log.d(TAG, "Bot not mentioned in channel message")
                        return
                    }
                }
            }

            Log.d(TAG, "📨 Received message: ${event.messageId} from ${event.authorName}")
            Log.d(TAG, "   Content: ${event.content}")
            Log.d(TAG, "   Type: $chatType")

            // Emit Message Event
            _eventFlow.emit(
                ChannelEvent.Message(
                    messageId = event.messageId,
                    channelId = event.channelId,
                    guildId = event.guildId,
                    authorId = event.authorId,
                    authorName = event.authorName,
                    content = event.content,
                    chatType = chatType,
                    mentions = event.mentions,
                    timestamp = event.timestamp
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    /**
     * Send Pairing Message
     */
    private suspend fun sendPairingMessage(channelId: String) {
        try {
            val message = """
                👋 Hello! I'm an AI assistant.

                To use me, please ask the admin to approve pairing by adding your user ID to the allowlist.
            """.trimIndent()

            client.sendMessage(channelId, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending pairing message", e)
        }
    }

    /**
     * Process Reaction Add Event
     */
    private suspend fun handleReactionAdd(event: DiscordEvent.ReactionAdd) {
        try {
            Log.d(TAG, "👍 Reaction added: ${event.emoji}")
            _eventFlow.emit(
                ChannelEvent.ReactionAdd(
                    userId = event.userId,
                    channelId = event.channelId,
                    messageId = event.messageId,
                    emoji = event.emoji
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reaction add", e)
        }
    }

    /**
     * Process Reaction Remove Event
     */
    private suspend fun handleReactionRemove(event: DiscordEvent.ReactionRemove) {
        try {
            Log.d(TAG, "👎 Reaction removed: ${event.emoji}")
            _eventFlow.emit(
                ChannelEvent.ReactionRemove(
                    userId = event.userId,
                    channelId = event.channelId,
                    messageId = event.messageId,
                    emoji = event.emoji
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reaction remove", e)
        }
    }

    /**
     * Process Input Status Event
     */
    private suspend fun handleTypingStart(event: DiscordEvent.TypingStart) {
        try {
            Log.d(TAG, "⌨️ User typing: ${event.userId}")
            _eventFlow.emit(
                ChannelEvent.TypingStart(
                    userId = event.userId,
                    channelId = event.channelId,
                    timestamp = event.timestamp
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing start", e)
        }
    }

    // ==================== Public API ====================

    /**
     * Send Message
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        embeds: List<Map<String, Any>>? = null,
        components: List<Map<String, Any>>? = null,
        replyToId: String? = null
    ): result<String> {
        return try {
            val messageReference = replyToId?.let {
                mapOf("message_id" to it)
            }

            val result = client.sendMessage(channelId, content, embeds, components, messageReference)
            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id in response"))
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            result.failure(e)
        }
    }

    /**
     * Send DM (Private Message)
     */
    suspend fun sendDirectMessage(userId: String, content: String): result<String> {
        return try {
            val result = client.sendDirectMessage(userId, content)
            if (result.isSuccess) {
                val response = result.getOrNull()
                val messageId = response?.get("id")?.asString
                    ?: return result.failure(Exception("Missing message_id in response"))
                result.success(messageId)
            } else {
                result.map { "" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending DM", e)
            result.failure(e)
        }
    }

    /**
     * Add Reaction (Emoji)
     */
    suspend fun addReaction(channelId: String, messageId: String, emoji: String): result<Unit> {
        return client.addReaction(channelId, messageId, emoji)
    }

    /**
     * Remove Reaction
     */
    suspend fun removeReaction(channelId: String, messageId: String, emoji: String): result<Unit> {
        return client.removeReaction(channelId, messageId, emoji)
    }

    /**
     * Trigger Input Status Indicator
     */
    suspend fun triggerTyping(channelId: String): result<Unit> {
        return client.triggerTyping(channelId)
    }

    /**
     * Get Guild Info
     */
    suspend fun getGuild(guildId: String): result<JsonObject> {
        return client.getGuild(guildId)
    }

    /**
     * Get Channel Info
     */
    suspend fun getChannel(channelId: String): result<JsonObject> {
        return client.getChannel(channelId)
    }

    /**
     * Is Connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Get Current Bot User ID
     */
    fun getBotUserId(): String? = currentBotUserId

    /**
     * Get Current Bot Username
     */
    fun getBotUsername(): String? = currentBotUsername
}

/**
 * Discord Channel Event
 */
sealed class ChannelEvent {
    object Connected : ChannelEvent()

    data class Error(val error: Throwable) : ChannelEvent()

    data class Message(
        val messageId: String,
        val channelId: String,
        val guildId: String?,
        val authorId: String,
        val authorName: String,
        val content: String,
        val chatType: String, // "direct" or "channel"
        val mentions: List<String>,
        val timestamp: String
    ) : ChannelEvent()

    data class ReactionAdd(
        val userId: String,
        val channelId: String,
        val messageId: String,
        val emoji: String
    ) : ChannelEvent()

    data class ReactionRemove(
        val userId: String,
        val channelId: String,
        val messageId: String,
        val emoji: String
    ) : ChannelEvent()

    data class TypingStart(
        val userId: String,
        val channelId: String,
        val timestamp: Long
    ) : ChannelEvent()
}
