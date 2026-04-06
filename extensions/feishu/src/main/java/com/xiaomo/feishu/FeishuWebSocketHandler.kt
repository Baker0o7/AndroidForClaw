package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Feishu WebSocket connection handler
 * Uses official oapi-sdk-java com.lark.oapi.ws.Client implementation
 */
class FeishuWebSocketHandler(
    private val config: FeishuConfig,
    private val client: FeishuClient,
    private val eventFlow: MutableSharedFlow<FeishuEvent>
) : FeishuConnectionHandler {

    companion object {
        private const val TAG = "FeishuWebSocket"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var wsClient: com.lark.oapi.ws.Client? = null

    override fun start() {
        scope.launch {
            try {
                Log.i(TAG, "Start Feishu WebSocket connection...")
                Log.i(TAG, "   App ID: ${config.appId}")
                Log.i(TAG, "   Domain: ${config.domain}")

                // Create event dispatcher
                val eventDispatcher = EventDispatcher.newBuilder(
                    config.verificationToken ?: "",
                    config.encryptKey ?: ""
                )
                    .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                        override fun handle(data: P2MessageReceiveV1?) {
                            scope.launch {
                                try {
                                    if (data != null && data.event != null) {
                                        handleMessageReceive(data.event)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Process message event failed", e)
                                }
                            }
                        }
                    })
                    .onP2MessageReadV1(object : ImService.P2MessageReadV1Handler() {
                        override fun handle(data: com.lark.oapi.service.im.v1.model.P2MessageReadV1?) {
                            // Ignore message read events
                        }
                    })
                    .build()

                // Create WebSocket client
                wsClient = com.lark.oapi.ws.Client.Builder(config.appId, config.appSecret)
                    .eventHandler(eventDispatcher)
                    .build()

                // Start WebSocket
                Log.i(TAG, "Connecting to WebSocket...")
                wsClient?.start()

                // Note: start() method blocks main thread until connection closes
                // So we call it in a coroutine
                Log.i(TAG, "WebSocket started")
                eventFlow.emit(FeishuEvent.Connected)

            } catch (e: Exception) {
                Log.e(TAG, "Start WebSocket failed", e)
                eventFlow.emit(FeishuEvent.Error(e))
            }
        }
    }

    override fun stop() {
        try {
            // Note: ws.Client's disconnect() and reconnect() methods are protected
            // We can only stop it in other ways (e.g. interrupt thread)
            wsClient = null
            Log.i(TAG, "WebSocket stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket", e)
        }
    }

    /**
     * Process received message event
     */
    private suspend fun handleMessageReceive(event: com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data) {
        try {
            val sender = event.sender
            val message = event.message

            val messageId = message.messageId ?: return
            val senderId = sender.senderId?.openId ?: return
            val chatId = message.chatId ?: return
            val chatType = message.chatType ?: return
            val msgType = message.messageType ?: return
            val content = message.content ?: return

            // Use ContentParser to parse all message types (Aligned with OpenClaw bot-content.ts)
            val parseResult = com.xiaomo.feishu.messaging.FeishuContentParser.parseMessageContent(msgType, content)

            // Extract thread/reply fields (Aligned with OpenClaw reply-dispatcher)
            val rootId = try { message.rootId } catch (e: Exception) { null }
            val parentId = try { message.parentId } catch (e: Exception) { null }
            val threadId = try { message.threadId } catch (e: Exception) { null }

            // Parse mentions
            val mentions = mutableListOf<String>()
            val mentionNames = mutableListOf<String>()
            message.mentions?.forEach { mention ->
                Log.d(TAG, "   mention: key=${mention.key}, name=${mention.name}, openId=${mention.id?.openId}, userId=${mention.id?.userId}, unionId=${mention.id?.unionId}")
                mention.id?.openId?.let { mentions.add(it) }
                mention.name?.let { mentionNames.add(it) }
            }

            // Parse message create_time (aligned with OpenClaw: use original
            // message timestamp instead of processing time).
            // Feishu uses millisecond epoch string.
            val createTime = try {
                message.createTime?.toLongOrNull() ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            // Emit event
            eventFlow.emit(
                FeishuEvent.Message(
                    messageId = messageId,
                    senderId = senderId,
                    chatId = chatId,
                    chatType = chatType,
                    content = parseResult.text,
                    msgType = msgType,
                    mentions = mentions,
                    mentionNames = mentionNames,
                    rootId = rootId,
                    parentId = parentId,
                    threadId = threadId,
                    mediaKeys = parseResult.mediaKeys,
                    createTime = createTime
                )
            )

            Log.d(TAG, "Received message: $messageId from $senderId (type=$msgType)")
            Log.d(TAG, "   Content: ${parseResult.text.take(200)}")
            if (rootId != null) Log.d(TAG, "   rootId: $rootId, threadId: $threadId")

        } catch (e: Exception) {
            Log.e(TAG, "Process message failed", e)
        }
    }
}
