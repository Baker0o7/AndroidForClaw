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
 * MicCaptureManager Gateway Connection Status Test
 *
 * Validate fix: localChatChannel in mode gatewayConnected needs to be true,
 * otherwise sendQueuedIfIdle's !gatewayConnected check causes messages to never be sent.
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
     * Test 1: When gatewayConnected=false, message is not sent, status shows queue wait
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

        // Don't call onGatewayConnectionChanged(true) - mock old bug scenario
        // Inject message to messageQueue via reflection
        injectMessageToQueue(mic, "Hello from voice test")

        // Trigger sendQueuedIfIdle
        callSendQueuedIfIdle(mic)

        // Wait a bit for coroutine execution
        Thread.sleep(500)

        // Message should not be sent
        assertEquals("Message should not be sent when gatewayConnected=false", 0, sentMessages.size)

        // statusText should show queue status
        val status = runBlocking { mic.statusText.first() }
        Log.i(TAG, "disconnected status: $status")
        assertTrue(
            "statusText should contain queue info, actual: $status",
            status.contains("queued", ignoreCase = true) || status.contains("waiting", ignoreCase = true)
        )

        scope.cancel()
        Log.i(TAG, "✅ test01 PASSED: Message not sent when gateway disconnected")
    }

    /**
     * Test 2: When gatewayConnected=true, messages are sent normally
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

        // Mark as connected first
        mic.onGatewayConnectionChanged(true)

        // Inject message
        injectMessageToQueue(mic, "Hello connected test")

        // Trigger send
        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Message should be sent within 5s", sent)
        assertEquals("Should send 1 message", 1, sentMessages.size)
        assertEquals("Hello connected test", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test02 PASSED: Message sent normally when gateway connected")
    }

    /**
     * Test 3: Disconnect then reconnect, queued messages are sent
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

        // Inject message (gateway not connected at this time)
        injectMessageToQueue(mic, "Queued before connect")

        // Verify not sent
        callSendQueuedIfIdle(mic)
        Thread.sleep(300)
        assertEquals("Should not send before connect", 0, sentMessages.size)

        // Now connect - onGatewayConnectionChanged internally calls sendQueuedIfIdle
        mic.onGatewayConnectionChanged(true)

        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Should auto send queued messages after reconnect", sent)
        assertEquals(1, sentMessages.size)
        assertEquals("Queued before connect", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test03 PASSED: Queued messages auto sent after reconnect")
    }

    /**
     * Test 4: Chat final event triggers TTS and resets isSending
     *
     * Flow: Connect -> inject message -> send (sendToGateway sets pendingRunId) -> send chat final event -> validate
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
                // Set pendingRunId so handleGatewayEvent can match
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

        // Wait for send to complete (pendingRunId already set)
        assertTrue("Message should be sent", sendLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        // At this point isSending=true, pendingRunId="run_test_4"
        val sendingBefore = runBlocking { mic.isSending.first() }
        Log.i(TAG, "isSending before final event: $sendingBefore")

        // Mock chat final event
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

        // Validate TTS was called
        assertEquals("speakAssistantReply should be called", "The answer is 2.", spokenText.get())

        // Validate isSending reset
        val sendingAfter = runBlocking { mic.isSending.first() }
        assertFalse("isSending should be false after final event", sendingAfter)

        scope.cancel()
        Log.i(TAG, "✅ test04 PASSED: Chat final event triggers TTS and resets sending status")
    }

    /**
     * Test 5: Mock complete local schema flow (localChatChannel scenario)
     * Validate onGatewayConnectionChanged(true) effect when called immediately after init
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

        // Mock fix in NodeRuntime init: immediately mark as connected
        scope.launch { mic.onGatewayConnectionChanged(true) }

        // Wait a bit for launch to execute
        Thread.sleep(200)

        // Inject message and send
        injectMessageToQueue(mic, "Local mode voice test")
        callSendQueuedIfIdle(mic)

        val sent = sendLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Local schema message should be sent normally", sent)
        assertEquals(1, sentMessages.size)
        assertEquals("Local mode voice test", sentMessages[0])

        scope.cancel()
        Log.i(TAG, "✅ test05 PASSED: Local schema full flow validation passed")
    }

    // ==================== Reflection helper methods ====================

    /**
     * Inject message into messageQueue via reflection
     */
    private fun injectMessageToQueue(mic: MicCaptureManager, message: String) {
        try {
            val queueField = MicCaptureManager::class.java.getDeclaredField("messageQueue")
            queueField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val queue = queueField.get(mic) as ArrayDeque<String>
            queue.addLast(message)

            // Also update _queuedMessages flow
            val publishMethod = MicCaptureManager::class.java.getDeclaredMethod("publishQueue")
            publishMethod.isAccessible = true
            publishMethod.invoke(mic)

            Log.i(TAG, "Injected message to queue: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject message: ${e.message}", e)
            fail("Reflection injection failed: ${e.message}")
        }
    }

    /**
     * Call sendQueuedIfIdle via reflection
     */
    private fun callSendQueuedIfIdle(mic: MicCaptureManager) {
        try {
            val method = MicCaptureManager::class.java.getDeclaredMethod("sendQueuedIfIdle")
            method.isAccessible = true
            method.invoke(mic)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call sendQueuedIfIdle: ${e.message}", e)
            fail("Reflection call failed: ${e.message}")
        }
    }
}