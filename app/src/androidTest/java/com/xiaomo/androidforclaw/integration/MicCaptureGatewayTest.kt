package com.xiaomo.androidforclaw.integration

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import ai.openclaw.app.voice.MicCaptureManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * MicCaptureManager GatewayConnectStatusTest
 *
 * ValidateFix: localChatChannel In mode gatewayConnected 需为 true, 
 * No则 sendQueuedIfIdle 中 !gatewayConnected Check导致Message永远发不出. 
 *
 * Run:
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.xiaomo.androidforclaw.integration.MicCaptureGatewayTest
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class MicCaptureGatewayTest {

    companion object {
        private const val TAG = "MicGatewayTest"
    }

    /**
     * Test 1: gatewayConnected=false 时Message不发送, StatusShow排队Wait
     */
    @Test
    fun test01_messageNotSentWhenGatewayDisconnected() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sentMessages = mutableListOf<String>()

        val mic = MicCaptureManager(
            context = context,
            scope = scope,
            sendToGateway = { message, onRunIdKnown ->
                sentMessages.add(message)
                onRunIdKnown("run_test_1")
                "run_test_1"
            },
        )

        // 不调用 onGatewayConnectionChanged(true) — MockOld的 bug 场景
        // 通过反射注入Message到 messageQueue
        injectMessageToQueue(mic, "Hello from voice test")

        // 触发 sendQueuedIfIdle
        callSendQueuedIfIdle(mic)

        // 等一Down让协程执Row
        Thread.sleep(500)

        // Message不应被发送
        assertEquals("gatewayConnected=false 时不应发送Message", 0, sentMessages.size)

        // statusText ShouldShow排队Status
        val status = runBlocking { mic.statusText.first() }
        Log.i(TAG, "disconnected status: $status")
        assertTrue(
            "statusText 应Contains排队Info, 实际: $status",
            status.contains("queued", ignoreCase = true) || status.contains("waiting", ignoreCase = true)
        )

        scope.cancel()
        Log.i(TAG, "✅ test01 PASSED: Message在 gateway 断开时不发送")
    }

    /**
     * Test 2: gatewayConnected=true BackMessage正常发送
     */
    @Test
    fun test02_messageSentWhenGatewayConnected() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sentMessages = mutableListOf<String>()
        val sendLatch = CountDownLatch(1)

        val mic = MicCaptureManager(
            context = context,
            scope = scope,
            sendToGateway = { message, onRunIdKnown ->
                Log.i(TAG, "sendToGateway called with: $message")
                sentMessages.add(message)
                onRunIdKnown("run_test_2")
                sendLatch.countDown()
                "run_test_2"
            },
        )

        // 先标记已Connect
        mic.onGatewayConnectionChanged(true)

        // 注入Message
        injectMessageToQueue(mic, "Hello connected test")

        // 触发发送
        callSendQueuedIfIdle(mic)

        // Wait发送Complete
        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Message应在 5s Inside发送", sent)
        assertEquals("应发送 1 条Message", 1, sentMessages.size)
        assertEquals("Hello connected test", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test02 PASSED: Message在 gateway ConnectBack正常发送")
    }

    /**
     * Test 3: 先断开再Connect, 积压Message被发送
     */
    @Test
    fun test03_queuedMessagesSentAfterReconnect() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sentMessages = mutableListOf<String>()
        val sendLatch = CountDownLatch(1)

        val mic = MicCaptureManager(
            context = context,
            scope = scope,
            sendToGateway = { message, onRunIdKnown ->
                Log.i(TAG, "sendToGateway (reconnect): $message")
                sentMessages.add(message)
                onRunIdKnown("run_test_3")
                sendLatch.countDown()
                "run_test_3"
            },
        )

        // 注入Message(此时 gateway Not connected)
        injectMessageToQueue(mic, "Queued before connect")

        // Validate未发送
        callSendQueuedIfIdle(mic)
        Thread.sleep(300)
        assertEquals("ConnectFront不应发送", 0, sentMessages.size)

        // 现在Connect — onGatewayConnectionChanged Internal会调用 sendQueuedIfIdle
        mic.onGatewayConnectionChanged(true)

        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("ConnectBack应Auto发送积压Message", sent)
        assertEquals(1, sentMessages.size)
        assertEquals("Queued before connect", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test03 PASSED: 重连Back积压MessageAuto发送")
    }

    /**
     * Test 4: chat final Event触发 TTS 并Reset isSending
     *
     * 流程: Connect → 注入Message → 发送(sendToGateway 设 pendingRunId)→ 发 chat final Event → Validate
     */
    @Test
    fun test04_chatFinalEventTriggersTtsAndResetsSending() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val spokenText = AtomicReference<String?>(null)
        val sendLatch = CountDownLatch(1)

        val mic = MicCaptureManager(
            context = context,
            scope = scope,
            sendToGateway = { message, onRunIdKnown ->
                // Settings pendingRunId 以便 handleGatewayEvent 匹配
                onRunIdKnown("run_test_4")
                sendLatch.countDown()
                "run_test_4"
            },
            speakAssistantReply = { text ->
                Log.i(TAG, "speakAssistantReply: $text")
                spokenText.set(text)
            },
        )

        mic.onGatewayConnectionChanged(true)
        injectMessageToQueue(mic, "What is 1+1?")
        callSendQueuedIfIdle(mic)

        // 等发送Complete(pendingRunId 已Settings)
        assertTrue("Message应发送", sendLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        // 此时 isSending=true, pendingRunId="run_test_4"
        val sendingBefore = runBlocking { mic.isSending.first() }
        Log.i(TAG, "isSending before final event: $sendingBefore")

        // Mock chat final Event
        val chatPayload = """
            {
                "state": "final",
                "runId": "run_test_4",
                "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "The answer is 2."}]
                }
            }
        """.trimIndent()
        mic.handleGatewayEvent("chat", chatPayload)
        Thread.sleep(500)

        // Validate TTS 被调用
        assertEquals("speakAssistantReply 应被调用", "The answer is 2.", spokenText.get())

        // Validate isSending Reset
        val sendingAfter = runBlocking { mic.isSending.first() }
        assertFalse("final EventBack isSending 应为 false", sendingAfter)

        scope.cancel()
        Log.i(TAG, "✅ test04 PASSED: chat final Event触发 TTS 并Reset发送Status")
    }

    /**
     * Test 5: Mock完整本地Schema流程(localChatChannel 场景)
     * Validate onGatewayConnectionChanged(true) 在 init Back立即调用的效果
     */
    @Test
    fun test05_localChannelModeFullFlow() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val sentMessages = mutableListOf<String>()
        val sendLatch = CountDownLatch(1)

        val mic = MicCaptureManager(
            context = context,
            scope = scope,
            sendToGateway = { message, onRunIdKnown ->
                Log.i(TAG, "localChannel sendToGateway: $message")
                sentMessages.add(message)
                onRunIdKnown("run_local_1")
                sendLatch.countDown()
                "run_local_1"
            },
        )

        // Mock NodeRuntime init 中的Fix: 立即标记为已Connect
        scope.launch { mic.onGatewayConnectionChanged(true) }

        // 稍等让 launch 执Row
        Thread.sleep(200)

        // 注入MessageConcurrency送
        injectMessageToQueue(mic, "Local mode voice test")
        callSendQueuedIfIdle(mic)

        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("本地SchemaMessage应正常发送", sent)
        assertEquals(1, sentMessages.size)
        assertEquals("Local mode voice test", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test05 PASSED: 本地Schema完整流程Validate通过")
    }

    // ==================== Reflect tool method ====================

    /**
     * 通过反射向 messageQueue 注入Message
     */
    private fun injectMessageToQueue(mic: MicCaptureManager, message: String) {
        try {
            val queueField = MicCaptureManager::class.java.getDeclaredField("messageQueue")
            queueField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val queue = queueField.get(mic) as ArrayDeque<String>
            queue.addLast(message)

            // 同时Update _queuedMessages flow
            val publishMethod = MicCaptureManager::class.java.getDeclaredMethod("publishQueue")
            publishMethod.isAccessible = true
            publishMethod.invoke(mic)

            Log.i(TAG, "Injected message to queue: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject message: ${e.message}", e)
            fail("反射注入Failed: ${e.message}")
        }
    }

    /**
     * 通过反射调用 sendQueuedIfIdle
     */
    private fun callSendQueuedIfIdle(mic: MicCaptureManager) {
        try {
            val method = MicCaptureManager::class.java.getDeclaredMethod("sendQueuedIfIdle")
            method.isAccessible = true
            method.invoke(mic)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call sendQueuedIfIdle: ${e.message}", e)
            fail("反射调用Failed: ${e.message}")
        }
    }
}
