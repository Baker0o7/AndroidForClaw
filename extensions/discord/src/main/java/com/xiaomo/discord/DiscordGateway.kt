/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Discord Gateway (WebSocket) Connection Handler
 * Based on Discord Gateway API v10
 * https://discord.com/developers/docs/topics/gateway
 */
class DiscordGateway(
    private val token: String,
    private val intents: Int = DEFAULT_INTENTS,
    private val eventFlow: MutableSharedFlow<DiscordEvent>
) {
    companion object {
        private const val TAG = "DiscordGateway"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        // Gateway Intents
        const val INTENT_GUILDS = 1 shl 0
        const val INTENT_GUILD_MESSAGES = 1 shl 9
        const val INTENT_GUILD_MESSAGE_REACTIONS = 1 shl 10
        const val INTENT_DIRECT_MESSAGES = 1 shl 12
        const val INTENT_DIRECT_MESSAGE_REACTIONS = 1 shl 13
        const val INTENT_MESSAGE_CONTENT = 1 shl 15  // Privileged

        const val DEFAULT_INTENTS = INTENT_GUILDS or
                                   INTENT_GUILD_MESSAGES or
                                   INTENT_GUILD_MESSAGE_REACTIONS or
                                   INTENT_DIRECT_MESSAGES or
                                   INTENT_DIRECT_MESSAGE_REACTIONS or
                                   INTENT_MESSAGE_CONTENT

        // Opcodes
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var sequenceNumber: Int? = null
    private var sessionId: String? = null
    private var heartbeatInterval: Long = 0
    private var isConnected = false

    fun start() {
        scope.launch {
            try {
                Log.i(TAG, "🚀 Start Discord Gateway Connect...")
                Log.i(TAG, "   Intents: $intents")

                connect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Start Gateway Failed", e)
                eventFlow.emit(DiscordEvent.Error(e))
            }
        }
    }

    fun stop() {
        try {
            isConnected = false
            heartbeatJob?.cancel()
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            Log.i(TAG, "Gateway stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping gateway", e)
        }
    }

    private fun connect() {
        val request = Request.Builder()
            .url(GATEWAY_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "✅ WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code - $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                isConnected = false
                heartbeatJob?.cancel()

                // Attempt reconnect (unless normal close)
                if (code != 1000 && isConnected) {
                    scope.launch {
                        delay(5000)
                        Log.i(TAG, "Attempting reconnect...")
                        connect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed: ${response?.code}", t)
                scope.launch {
                    eventFlow.emit(DiscordEvent.Error(t))
                }

                // Attempt reconnect
                if (isConnected) {
                    scope.launch {
                        delay(5000)
                        Log.i(TAG, "Attempting reconnect...")
                        connect()
                    }
                }
            }
        })
    }

    private suspend fun handleMessage(text: String) {
        try {
            val payload = gson.fromJson(text, JsonObject::class.java)
            val op = payload.get("op")?.asInt ?: return
            val data = payload.get("d")?.takeIf { !it.isJsonNull }?.asJsonObject
            val eventName = payload.get("t")?.takeIf { !it.isJsonNull }?.asString
            val seq = payload.get("s")?.takeIf { !it.isJsonNull }?.asInt

            // Update sequence number
            seq?.let { sequenceNumber = it }

            when (op) {
                OP_HELLO -> handleHello(data)
                OP_DISPATCH -> handleDispatch(eventName, data)
                OP_HEARTBEAT_ACK -> Log.d(TAG, "💓 Heartbeat ACK")
                else -> Log.d(TAG, "Unknown opcode: $op")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message", e)
        }
    }

    private suspend fun handleHello(data: JsonObject?) {
        try {
            heartbeatInterval = data?.get("heartbeat_interval")?.asLong ?: 41250
            Log.i(TAG, "👋 Received HELLO, HeartbeatInterval: ${heartbeatInterval}ms")

            // Start Heartbeat
            startHeartbeat()

            // Send IDENTIFY
            sendIdentify()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process HELLO", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        try {
            val payload = JsonObject().apply {
                addProperty("op", OP_HEARTBEAT)
                if (sequenceNumber != null) {
                    addProperty("d", sequenceNumber)
                } else {
                    add("d", null)
                }
            }

            webSocket?.send(gson.toJson(payload))
            Log.d(TAG, "💓 Sent heartbeat")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat", e)
        }
    }

    private fun sendIdentify() {
        try {
            val payload = JsonObject().apply {
                addProperty("op", OP_IDENTIFY)
                add("d", JsonObject().apply {
                    addProperty("token", token)
                    addProperty("intents", intents)
                    add("properties", JsonObject().apply {
                        addProperty("os", "android")
                        addProperty("browser", "AndroidForClaw")
                        addProperty("device", "AndroidForClaw")
                    })
                })
            }

            webSocket?.send(gson.toJson(payload))
            Log.i(TAG, "🔐 Sent IDENTIFY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send IDENTIFY", e)
        }
    }

    private suspend fun handleDispatch(eventName: String?, data: JsonObject?) {
        if (eventName == null || data == null) return

        try {
            when (eventName) {
                "READY" -> handleReady(data)
                "MESSAGE_CREATE" -> handleMessageCreate(data)
                "MESSAGE_REACTION_ADD" -> handleReactionAdd(data)
                "MESSAGE_REACTION_REMOVE" -> handleReactionRemove(data)
                "TYPING_START" -> handleTypingStart(data)
                else -> Log.d(TAG, "Unhandled event: $eventName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process event $eventName", e)
        }
    }

    private suspend fun handleReady(data: JsonObject) {
        try {
            sessionId = data.get("session_id")?.asString
            val user = data.get("user")?.asJsonObject
            val username = user?.get("username")?.asString

            Log.i(TAG, "✅ READY - Logged in as: $username")
            Log.i(TAG, "   Session ID: $sessionId")

            isConnected = true
            eventFlow.emit(DiscordEvent.Connected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process READY", e)
        }
    }

    private suspend fun handleMessageCreate(data: JsonObject) {
        try {
            val messageId = data.get("id")?.asString ?: return
            val channelId = data.get("channel_id")?.asString ?: return
            val guildId = data.get("guild_id")?.asString
            val author = data.get("author")?.asJsonObject ?: return
            val authorId = author.get("id")?.asString ?: return
            val authorName = author.get("username")?.asString ?: "Unknown"
            val content = data.get("content")?.asString ?: ""
            val timestamp = data.get("timestamp")?.asString ?: ""

            // Ignore Bot's own messages
            val isBot = author.get("bot")?.asBoolean ?: false
            if (isBot) return

            // Parse mentions
            val mentionsArray = data.getAsJsonArray("mentions")
            val mentions = mutableListOf<String>()
            mentionsArray?.forEach { mention ->
                val mentionId = mention.asJsonObject.get("id")?.asString
                mentionId?.let { mentions.add(it) }
            }

            Log.d(TAG, "📨 Received message: $messageId from $authorName")
            Log.d(TAG, "   Content: $content")

            eventFlow.emit(
                DiscordEvent.Message(
                    messageId = messageId,
                    channelId = channelId,
                    guildId = guildId,
                    authorId = authorId,
                    authorName = authorName,
                    content = content,
                    mentions = mentions,
                    timestamp = timestamp
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MESSAGE_CREATE", e)
        }
    }

    private suspend fun handleReactionAdd(data: JsonObject) {
        try {
            val userId = data.get("user_id")?.asString ?: return
            val channelId = data.get("channel_id")?.asString ?: return
            val messageId = data.get("message_id")?.asString ?: return
            val emoji = data.get("emoji")?.asJsonObject
            val emojiName = emoji?.get("name")?.asString ?: return

            Log.d(TAG, "👍 Reaction added: $emojiName by $userId")

            eventFlow.emit(
                DiscordEvent.ReactionAdd(
                    userId = userId,
                    channelId = channelId,
                    messageId = messageId,
                    emoji = emojiName
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MESSAGE_REACTION_ADD", e)
        }
    }

    private suspend fun handleReactionRemove(data: JsonObject) {
        try {
            val userId = data.get("user_id")?.asString ?: return
            val channelId = data.get("channel_id")?.asString ?: return
            val messageId = data.get("message_id")?.asString ?: return
            val emoji = data.get("emoji")?.asJsonObject
            val emojiName = emoji?.get("name")?.asString ?: return

            Log.d(TAG, "👎 Reaction removed: $emojiName by $userId")

            eventFlow.emit(
                DiscordEvent.ReactionRemove(
                    userId = userId,
                    channelId = channelId,
                    messageId = messageId,
                    emoji = emojiName
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MESSAGE_REACTION_REMOVE", e)
        }
    }

    private suspend fun handleTypingStart(data: JsonObject) {
        try {
            val userId = data.get("user_id")?.asString ?: return
            val channelId = data.get("channel_id")?.asString ?: return
            val timestamp = data.get("timestamp")?.asLong ?: return

            eventFlow.emit(
                DiscordEvent.TypingStart(
                    userId = userId,
                    channelId = channelId,
                    timestamp = timestamp
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process TYPING_START", e)
        }
    }
}

/**
 * Discord Event
 */
sealed class DiscordEvent {
    object Connected : DiscordEvent()
    data class Error(val exception: Throwable) : DiscordEvent()

    data class Message(
        val messageId: String,
        val channelId: String,
        val guildId: String?,
        val authorId: String,
        val authorName: String,
        val content: String,
        val mentions: List<String>,
        val timestamp: String
    ) : DiscordEvent()

    data class ReactionAdd(
        val userId: String,
        val channelId: String,
        val messageId: String,
        val emoji: String
    ) : DiscordEvent()

    data class ReactionRemove(
        val userId: String,
        val channelId: String,
        val messageId: String,
        val emoji: String
    ) : DiscordEvent()

    data class TypingStart(
        val userId: String,
        val channelId: String,
        val timestamp: Long
    ) : DiscordEvent()
}
