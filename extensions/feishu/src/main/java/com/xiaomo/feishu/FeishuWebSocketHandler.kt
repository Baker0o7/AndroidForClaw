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
 * 飞书 WebSocket ConnectProcess器
 * use官方 oapi-sdk-java 的 com.lark.oapi.ws.Client Implementation
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
                Log.i(TAG, "🚀 Start Feishu WebSocket Connect...")
                Log.i(TAG, "   App ID: ${config.appId}")
                Log.i(TAG, "   Domain: ${config.domain}")

                // CreateEvent分发器
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
                                    Log.e(TAG, "ProcessMessageEventFailed", e)
                                }
                            }
                        }
                    })
                    .onP2MessageReadV1(object : ImService.P2MessageReadV1Handler() {
                        override fun handle(data: com.lark.oapi.service.im.v1.model.P2MessageReadV1?) {
                            // IgnoreMessage已读Event
                        }
                    })
                    .build()

                // Create WebSocket Client
                wsClient = com.lark.oapi.ws.Client.Builder(config.appId, config.appSecret)
                    .eventHandler(eventDispatcher)
                    .build()

                // Start WebSocket
                Log.i(TAG, "正在Connect WebSocket...")
                wsClient?.start()

                // 注意: start() Method会Block主Thread, 直到ConnectClose
                // so我们在协程中call它
                Log.i(TAG, "✅ WebSocket 已Start")
                eventFlow.emit(FeishuEvent.Connected)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Start WebSocket Failed", e)
                eventFlow.emit(FeishuEvent.Error(e))
            }
        }
    }

    override fun stop() {
        try {
            // 注意: ws.Client 的 disconnect() 和 reconnect() MethodYes protected 的
            // 我们只能通过Its他方式Stop(for instanceInterruptThread)
            wsClient = null
            Log.i(TAG, "WebSocket 已Stop")
        } catch (e: Exception) {
            Log.e(TAG, "Stop WebSocket 时出错", e)
        }
    }

    /**
     * ProcessreceiveMessageEvent
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

            // use ContentParser ParseAllMessageType(Aligned with OpenClaw bot-content.ts)
            val parseresult = com.xiaomo.feishu.messaging.FeishuContentParser.parseMessageContent(msgType, content)

            // 提取 thread/reply Field(Aligned with OpenClaw reply-dispatcher)
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

            // sendEvent
            eventFlow.emit(
                FeishuEvent.Message(
                    messageId = messageId,
                    senderId = senderId,
                    chatId = chatId,
                    chatType = chatType,
                    content = parseresult.text,
                    msgType = msgType,
                    mentions = mentions,
                    mentionNames = mentionNames,
                    rootId = rootId,
                    parentId = parentId,
                    threadId = threadId,
                    mediaKeys = parseresult.mediaKeys,
                    createTime = createTime
                )
            )

            Log.d(TAG, "📨 收到Message: $messageId from $senderId (type=$msgType)")
            Log.d(TAG, "   Inside容: ${parseresult.text.take(200)}")
            if (rootId != null) Log.d(TAG, "   rootId: $rootId, threadId: $threadId")

        } catch (e: Exception) {
            Log.e(TAG, "ProcessMessageFailed", e)
        }
    }
}
