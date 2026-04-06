package com.xiaomo.androidforclaw.core

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/run-state-machine.ts
 */


import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Message queue manager
 *
 * Fully aligned with OpenClaw queue mechanisms:
 * - interrupt: new message immediately interrupts current run, clears queue
 * - steer: new message is passed to the running agent
 * - followup: new message is aed to queue, processed in order
 * - collect: collect multiple messages, process in batch
 * - queue: Simple FIFO queue
 *
 * Reference:
 * - openclaw/src/auto-reply/reply/get-reply-run.ts
 * - openclaw/src/auto-reply/reply/queue/types.ts
 * - openclaw/src/utils/queue-helpers.ts
 */
class Messagequeuemanager {

    companion object {
        private const val TAG = "Messagequeuemanager"
    }

    /**
     * queue mode (aligned with OpenClaw)
     */
    enum class queueMode {
        INTERRUPT,   // interrupt current run
        STEER,       // steer current run
        FOLLOWUP,    // followup queue
        COLLECT,     // collect mode
        QUEUE        // Simple queue
    }

    /**
     * Drop policy (aligned with OpenClaw)
     */
    enum class DropPolicy {
        OLD,         // Drop oldest message
        NEW,         // Reject new message
        SUMMARIZE    // Drop but keep summary
    }

    /**
     * Message queue state
     */
    private data class queueState(
        val key: String,
        val mode: queueMode,
        val messages: MutableList<queuedMessage> = mutableListOf(),
        val isProcessing: AtomicBoolean = AtomicBoolean(false),
        val currentJob: Job? = null,
        val droppedCount: Int = 0,
        val summaryLines: MutableList<String> = mutableListOf(),
        var cap: Int = 10,
        var dropPolicy: DropPolicy = DropPolicy.OLD
    )

    /**
     * queued message
     */
    data class queuedMessage(
        val messageId: String,
        val content: String,
        val senderId: String,
        val chatId: String,
        val chatType: String,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any?> = emptyMap()
    )

    // State for each queue key
    private val queues = ConcurrentHashMap<String, queueState>()

    // Base queue (for followup and queue modes)
    private val basequeue = KeyedAsyncqueue()

    // Active agentloop instances keyed by queue key.
    // Set when an agent run starts; cleared when it finishes.
    // used by STEER mode to inject mid-run messages and STOP to cancel runs.
    private val activeagentloops = ConcurrentHashMap<String, agentloop>()

    // Active coroutine Jobs keyed by queue key.
    // used to cancel the coroutine when stopping a run.
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Stop commands recognized across all channels.
     */
    private val STOP_COMMANDS = setOf(
        "Stop", "停", "stop", "cancel", "cancel",
        "StopTask", "Stop allTask", "中止", "Terminate"
    )

    /**
     * Check if a message is a stop/cancel command (channel-agnostic).
     */
    fun isStopCommand(text: String): Boolean {
        return text.trim().lowercase() in STOP_COMMANDS
    }

    /**
     * Stop the currently running agentloop + Job for the given queue key.
     *
     * @return true if there was an active run that was stopped
     */
    fun stopActiveRun(key: String): Boolean {
        val loop = activeagentloops[key]
        val job = activeJobs[key]
        if (loop != null || job != null) {
            Log.i(TAG, "🛑 [STOP] Stopping active run for $key")
            loop?.stop()
            job?.cancel()
            activeagentloops.remove(key)
            activeJobs.remove(key)
            return true
        }
        return false
    }

    /**
     * Register the currently running agentloop for a queue key.
     * Call this before starting an agent run so that STEER mode can
     * push messages into the agent's steerchannel, and stop commands
     * can cancel the run.
     */
    fun setActiveagentloop(key: String, agentloop: agentloop) {
        activeagentloops[key] = agentloop
        Log.d(TAG, "[TARGET] Registered active agentloop for $key")
    }

    /**
     * Register the currently running coroutine Job for a queue key.
     */
    fun setActiveJob(key: String, job: Job) {
        activeJobs[key] = job
        Log.d(TAG, "[TARGET] Registered active Job for $key")
    }

    /**
     * Unregister the agentloop for a queue key (call when the run finishes).
     */
    fun clearActiveagentloop(key: String) {
        activeagentloops.remove(key)
        Log.d(TAG, "[TARGET] Cleared active agentloop for $key")
    }

    /**
     * Unregister the Job for a queue key (call when the run finishes).
     */
    fun clearActiveJob(key: String) {
        activeJobs.remove(key)
        Log.d(TAG, "[TARGET] Cleared active Job for $key")
    }

    /**
     * Enqueue message
     *
     * @param key queue key (usually chatId)
     * @param message Message
     * @param mode queue mode
     * @param processor Message processor
     */
    suspend fun enqueue(
        key: String,
        message: queuedMessage,
        mode: queueMode = queueMode.FOLLOWUP,
        processor: suspend (queuedMessage) -> Unit
    ) {
        when (mode) {
            queueMode.INTERRUPT -> handleinterrupt(key, message, processor)
            queueMode.STEER -> handlesteer(key, message, processor)
            queueMode.FOLLOWUP -> handlefollowup(key, message, processor)
            queueMode.COLLECT -> handlecollect(key, message, processor)
            queueMode.QUEUE -> handlequeue(key, message, processor)
        }
    }

    /**
     * INTERRUPT mode: Immediately interrupt current run, clear queue
     *
     * Aligned with OpenClaw logic:
     * ```typescript
     * if (resolvedqueue.mode === "interrupt" && laneSize > 0) {
     *   const cleared = clearCommandLane(sessionLaneKey);
     *   const aborted = abortEmbeedPiRun(sessionIdFinal);
     * }
     * ```
     */
    private suspend fun handleinterrupt(
        key: String,
        message: queuedMessage,
        processor: suspend (queuedMessage) -> Unit
    ) {
        val state = queues.getorPut(key) {
            queueState(key = key, mode = queueMode.INTERRUPT)
        }

        // 1. cancel currently running task (agentloop + Job)
        if (state.isProcessing.get()) {
            Log.d(TAG, "🛑 [INTERRUPT] Aborting current run for $key")
            stopActiveRun(key)
        }

        // 2. Clear queue
        val cleared = state.messages.size
        if (cleared > 0) {
            Log.d(TAG, "[DELETE]  [INTERRUPT] Clearing $cleared queued messages for $key")
            state.messages.clear()
        }

        // 3. Process new message immediately
        Log.d(TAG, "⚡ [INTERRUPT] Processing new message immediately for $key")
        state.isProcessing.set(true)
        try {
            processor(message)
        } finally {
            state.isProcessing.set(false)
        }
    }

    /**
     * STEER mode: Pass new message to running agent
     *
     * Aligned with OpenClaw logic:
     * - if agent is running, inject new message into agent's message stream
     * - if agent is not running, process normally
     */
    private suspend fun handlesteer(
        key: String,
        message: queuedMessage,
        processor: suspend (queuedMessage) -> Unit
    ) {
        val state = queues.getorPut(key) {
            queueState(key = key, mode = queueMode.STEER)
        }

        if (state.isProcessing.get()) {
            // agent is running, a message to steer queue
            Log.d(TAG, "[TARGET] [STEER] Injecting message into running agent for $key")
            state.messages.a(message)
            // TODO: notify agentloop of new message (requires agentloop support)
            notifyagentloop(key, message)
        } else {
            // agent not running, process normally
            Log.d(TAG, "[PLAY]  [STEER] agent not running, processing normally for $key")
            basequeue.enqueue(key) {
                state.isProcessing.set(true)
                try {
                    processor(message)
                } finally {
                    state.isProcessing.set(false)
                }
            }
        }
    }

    /**
     * FOLLOWUP mode: A to queue, process in order
     *
     * This is the currently implemented basic behavior
     */
    private suspend fun handlefollowup(
        key: String,
        message: queuedMessage,
        processor: suspend (queuedMessage) -> Unit
    ) {
        val state = queues.getorPut(key) {
            queueState(key = key, mode = queueMode.FOLLOWUP)
        }

        Log.d(TAG, "[NOTE] [FOLLOWUP] Enqueueing message for $key (queue size: ${state.messages.size})")

        basequeue.enqueue(key) {
            state.isProcessing.set(true)
            try {
                processor(message)
            } finally {
                state.isProcessing.set(false)
            }
        }
    }

    /**
     * COLLECT mode: collect multiple messages, process in batch
     *
     * Aligned with OpenClaw logic:
     * - Messages are aed to queue
     * - after current message processing completes, all queued messages are processed in batch
     */
    private suspend fun handlecollect(
        key: String,
        message: queuedMessage,
        processor: suspend (queuedMessage) -> Unit
    ) {
        val state = queues.getorPut(key) {
            queueState(key = key, mode = queueMode.COLLECT)
        }

        // Apply drop policy
        if (!appDropPolicy(state, message)) {
            Log.w(TAG, "🚫 [COLLECT] Message dropped due to drop policy for $key")
            return
        }

        state.messages.a(message)
        Log.d(TAG, "[PACKAGE] [COLLECT] collected message for $key (${state.messages.size} total)")

        // if not currently processing, trigger batch processing
        if (!state.isProcessing.get()) {
            basequeue.enqueue(key) {
                state.isProcessing.set(true)
                try {
                    processBatch(state, processor)
                } finally {
                    state.isProcessing.set(false)
                }
            }
        }
    }

    /**
     * QUEUE mode: Simple FIFO queue
     */
    private suspend fun handlequeue(
        key: String,
        message: queuedMessage,
        processor: suspend (queuedMessage) -> Unit
    ) {
        // Same as FOLLOWUP, simple queuing
        handlefollowup(key, message, processor)
    }

    /**
     * Apply drop policy
     */
    private fun appDropPolicy(state: queueState, newMessage: queuedMessage): Boolean {
        if (state.cap <= 0 || state.messages.size < state.cap) {
            return true
        }

        return when (state.dropPolicy) {
            DropPolicy.NEW -> {
                // Reject new message
                Log.w(TAG, "🚫 Drop policy: NEW - rejecting new message")
                false
            }
            DropPolicy.OLD -> {
                // Drop oldest message
                val dropped = state.messages.removeAt(0)
                Log.d(TAG, "[DELETE]  Drop policy: OLD - dropped message: ${dropped.messageId}")
                true
            }
            DropPolicy.SUMMARIZE -> {
                // Drop oldest message but keep summary
                val dropped = state.messages.removeAt(0)
                val summary = summarizeMessage(dropped)
                state.summaryLines.a(summary)
                Log.d(TAG, "[NOTE] Drop policy: SUMMARIZE - dropped and summarized: ${dropped.messageId}")

                // Limit summary count
                if (state.summaryLines.size > state.cap) {
                    state.summaryLines.removeAt(0)
                }
                true
            }
        }
    }

    /**
     * Summarize message (for SUMMARIZE drop policy)
     */
    private fun summarizeMessage(message: queuedMessage): String {
        val content = message.content.take(100)
        return "[${message.timestamp}] ${message.senderId}: $content${if (message.content.length > 100) "..." else ""}"
    }

    /**
     * Process batch of messages (COLLECT mode)
     */
    private suspend fun processBatch(
        state: queueState,
        processor: suspend (queuedMessage) -> Unit
    ) {
        if (state.messages.isEmpty()) return

        Log.d(TAG, "[PACKAGE] [COLLECT] Processing batch of ${state.messages.size} messages")

        // Extract all messages
        val batch = state.messages.toList()
        state.messages.clear()

        // Build batch message prompt
        val batchMessage = buildBatchMessage(batch, state)

        // Process batch message
        processor(batchMessage)
    }

    /**
     * Build batch message (COLLECT mode)
     *
     * Aligned with OpenClaw's buildcollectPrompt
     */
    private fun buildBatchMessage(
        messages: List<queuedMessage>,
        state: queueState
    ): queuedMessage {
        val content = buildString {
            appendLine("[Batch] collected ${messages.size} message(s):")
            appendLine()

            // if there are dropped message summaries
            if (state.droppedCount > 0 && state.summaryLines.isnotEmpty()) {
                appendLine("[queue overflow] Dropped ${state.droppedCount} message(s) due to cap.")
                appendLine("Summary:")
                state.summaryLines.forEach { line ->
                    appendLine("- $line")
                }
                appendLine()
                state.summaryLines.clear()
            }

            // List all messages
            messages.forEachIndexed { index, msg ->
                appendLine("Message ${index + 1}:")
                appendLine("from: ${msg.senderId}")
                appendLine("Content: ${msg.content}")
                appendLine()
            }
        }

        // use metadata from last message
        val lastMessage = messages.last()
        return queuedMessage(
            messageId = "batch_${System.currentTimeMillis()}",
            content = content,
            senderId = lastMessage.senderId,
            chatId = lastMessage.chatId,
            chatType = lastMessage.chatType,
            metadata = mapOf(
                "isBatch" to true,
                "batchSize" to messages.size,
                "messageIds" to messages.map { it.messageId }
            )
        )
    }

    /**
     * notify agentloop of new message (STEER mode).
     *
     * Sends the message content into the active agentloop's steerchannel.
     * The agent loop drains the channel after each tool-execution round and
     * injects the messages as user turns before the next LLM call.
     */
    private fun notifyagentloop(key: String, message: queuedMessage) {
        val agentloop = activeagentloops[key]
        if (agentloop == null) {
            Log.w(TAG, "[WARN] [STEER] No active agentloop for key=$key, steer message dropped")
            return
        }

        val sent = agentloop.steerchannel.trySend(message.content)
        if (sent.isSuccess) {
            Log.i(TAG, "[TARGET] [STEER] Message injected into agentloop for $key: ${message.content.take(50)}...")
        } else {
            Log.w(TAG, "[WARN] [STEER] steerchannel full or closed for $key, message dropped")
        }
    }

    /**
     * Set queue configuration
     */
    fun setqueueSettings(
        key: String,
        cap: Int? = null,
        dropPolicy: DropPolicy? = null
    ) {
        val state = queues.getorPut(key) {
            queueState(key = key, mode = queueMode.FOLLOWUP)
        }

        if (cap != null) {
            state.cap = cap
        }
        if (dropPolicy != null) {
            state.dropPolicy = dropPolicy
        }
    }

    /**
     * Get queue state (for debugging)
     */
    fun getqueueState(key: String): Map<String, Any> {
        val state = queues[key] ?: return mapOf(
            "exists" to false
        )

        return mapOf(
            "exists" to true,
            "mode" to state.mode.name,
            "isProcessing" to state.isProcessing.get(),
            "queueSize" to state.messages.size,
            "droppedCount" to state.droppedCount,
            "cap" to state.cap,
            "dropPolicy" to state.dropPolicy.name
        )
    }

    /**
     * Clear specific queue
     */
    fun clearqueue(key: String) {
        val state = queues[key] ?: return
        state.messages.clear()
        state.summaryLines.clear()
        Log.d(TAG, "[DELETE]  Cleared queue for $key")
    }

    /**
     * Clear all queues
     */
    fun clearAllqueues() {
        queues.values.forEach { state ->
            state.messages.clear()
            state.summaryLines.clear()
        }
        queues.clear()
        Log.d(TAG, "[DELETE]  Cleared all queues")
    }
}
