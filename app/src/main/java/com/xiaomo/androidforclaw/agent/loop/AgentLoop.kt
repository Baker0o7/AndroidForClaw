package com.xiaomo.androidforclaw.agent.loop

import com.xiaomo.androidforclaw.agent.subagent.SubagentPromptBuilder
import com.xiaomo.androidforclaw.util.ReasoningTagFilter

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-runner/run.ts (core: runEmbeedPiagent loop, overflow recovery, auth failover)
 * - ../openclaw/src/agents/agent-command.ts (session entry, model resolve, fallback orchestration)
 * - ../openclaw/src/agents/pi-embeed-subscribe.ts (streaming tool execution callbacks — not yet implemented here)
 *
 * androidforClaw adaptation: iterative agent loop, tool calling, progress updates.
 * note: OpenClaw splits the loop across run.ts (retry/overflow) and subscribe.ts (streaming/tool dispatch);
 * androidforClaw merges both into this single class with non-streaming batch calls.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.context.contextErrors
import com.xiaomo.androidforclaw.agent.context.contextmanager
import com.xiaomo.androidforclaw.agent.context.contextRecoveryresult
import com.xiaomo.androidforclaw.agent.context.contextWindowGuard
import com.xiaomo.androidforclaw.agent.context.toolresultcontextGuard
import com.xiaomo.androidforclaw.agent.session.HistorySanitizer
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.skillresult
import com.xiaomo.androidforclaw.agent.tools.tool
import com.xiaomo.androidforclaw.agent.tools.toolCallDispatcher
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.providers.ChunkType
import com.xiaomo.androidforclaw.providers.LLMResponse
import com.xiaomo.androidforclaw.providers.LLMtoolCall
import com.xiaomo.androidforclaw.providers.LLMUsage
import com.xiaomo.androidforclaw.providers.StreamChunk
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.toolCall
import com.xiaomo.androidforclaw.providers.llm.systemMessage
import com.xiaomo.androidforclaw.providers.llm.userMessage
import com.xiaomo.androidforclaw.providers.llm.assistantMessage
import com.xiaomo.androidforclaw.providers.llm.toolMessage
import com.xiaomo.androidforclaw.util.LayoutexceptionLogger
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutorNull
import java.io.File
import java.text.SimpleDateformat
import java.util.Date
import java.util.Locale

/**
 * agent loop - Core execution engine
 * Reference: OpenClaw's agent loop implementation
 *
 * Execution flow:
 * 1. Receive user message + system prompt
 * 2. Call LLM (with reasoning support)
 * 3. LLM selects tools via function calling
 * 4. Execute tools selected by LLM directly
 * 5. Repeat steps 2-4 until LLM returns final result or reaches max iterations
 *
 * Architecture (reference: OpenClaw pi-tools):
 * - toolRegistry: Universal tools (read, write, exec, web_fetch)
 * - androidtoolRegistry: android platform tools (tap, screenshot, open_app)
 * - skillsLoader: skills documents (mobile-operations.md)
 */
class agentloop(
    private val llmprovider: UnifiedLLMprovider,
    private val toolRegistry: toolRegistry,
    private val androidtoolRegistry: androidtoolRegistry,
    private val contextmanager: contextmanager? = null,  // Optional context manager
    @Deprecated("No longer used — aligned with OpenClaw (no iteration limit)")
    private val maxIterations: Int = Int.MAX_VALUE,  // Kept for call-site compat, ignored
    private val modelRef: String? = null,
    private val configLoader: configLoader? = null  // for context window resolution (Gap 2)
) {
    companion object {
        private const val TAG = "agentloop"
        private const val MAX_OVERFLOW_RECOVERY_ATTEMPTS = 3  // Aligned with OpenClaw MAX_OVERFLOW_COMPACTION_ATTEMPTS

        /**
         * LLM single call timeout.
         * OpenClaw: agents.defaults.timeoutSeconds (configurable, no hard default in loop).
         * android: 180s default for free/slow models, generous enough for long generations.
         */
        private const val LLM_TIMEOUT_MS = 180_000L

        /**
         * Transient HTTP retry delay (aligned with OpenClaw TRANSIENT_HTTP_RETRY_DELAY_MS).
         */
        private const val TRANSIENT_HTTP_RETRY_DELAY_MS = 2_500L

        /**
         * Timeout compaction: when LLM times out and context usage is high (>65%),
         * try compacting before retrying. Aligned with OpenClaw MAX_TIMEOUT_COMPACTION_ATTEMPTS.
         */
        private const val MAX_TIMEOUT_COMPACTION_ATTEMPTS = 2
        private const val TIMEOUT_COMPACTION_TOKEN_RATIO = 0.65f

        /**
         * Iteration warn threshold.
         * OpenClaw has no per-iteration timeout; this is android-only observability.
         */
        private const val ITERATION_WARN_THRESHOLD_MS = 5 * 60 * 1000L

        /**
         * Max loop iterations — safety cap aligned with OpenClaw MAX_RUN_LOOP_ITERATIONS.
         * OpenClaw: resolveMaxRunretryIterations(profilecandidates.length)
         *   = BASE_RUN_RETRY_ITERATIONS(24) + RUN_RETRY_ITERATIONS_PER_PROFILE(8) * numProfiles
         *   clamped to [MIN_RUN_RETRY_ITERATIONS(32), MAX_RUN_RETRY_ITERATIONS(160)]
         * android: single profile → use MAX (160) for safety headroom since no auth failover.
         */
        private const val MAX_RUN_LOOP_ITERATIONS = 160
        private const val MAX_THINKING_CHARS = 10_000 // Thinking insidemaxcharacters, Preventhallucination infinite loop

        /**
         * overload backoff: aligned with OpenClaw OVERLOAD_FAILOVER_BACKOFF_POLICY.
         * used for HTTP 529 (overloaded) and 503+overload message.
         * exponential: initialMs=250, maxMs=1500, factor=2, jitter=0.2
         */
        private const val OVERLOAD_BACKOFF_INITIAL_MS = 250L
        private const val OVERLOAD_BACKOFF_MAX_MS = 1_500L
        private const val OVERLOAD_BACKOFF_FACTOR = 2
        private const val OVERLOAD_BACKOFF_JITTER = 0.2

        // context pruning constants (aligned with OpenClaw DEFAULT_CONTEXT_PRUNING_SETTINGS)
        private const val SOFT_TRIM_RATIO = 0.3f
        private const val HARD_CLEAR_RATIO = 0.5f
        private const val MIN_PRUNABLE_TOOL_CHARS = 50_000
        private const val KEEP_LAST_ASSISTANTS = 3
        private const val SOFT_TRIM_MAX_CHARS = 4_000
        private const val SOFT_TRIM_HEAD_CHARS = 1_500
        private const val SOFT_TRIM_TAIL_CHARS = 1_500
        private const val HARD_CLEAR_PLACEHOLDER = "[old tool result content cleared]"

        // Anthropic refusal magic string scrub (aligned with OpenClaw scrubAnthropicRefusalMagic)
        private const val ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL = "ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL"
        private const val ANTHROPIC_MAGIC_STRING_REPLACEMENT = "ANTHROPIC MAGIC STRING TRIGGER REFUSAL (redacted)"
    }

    private val gson = Gson()

    /**
     * session key for this agent loop instance.
     * used for per-channel history limit resolution (aligned with OpenClaw getHistoryLimitfromsessionKey).
     * Set by caller (MainEntrynew, SubagentSpawner, Gateway) after construction.
     */
    var sessionKey: String? = null

    /**
     * Extra per-session tools (e.g. subagent tools: sessions_spawn, sessions_list, etc.)
     * Set after construction to resolve circular dependency (tools need agentloop ref).
     * Aligned with OpenClaw per-session tool injection.
     */
    var extratools: List<tool> = emptyList()
        set(value) {
            field = value
            _extratoolsMap = value.associateBy { it.name }
            // Invalidate cached tool definitions
            _alltoolDefinitionsCache = null
        }
    private var _extratoolsMap: Map<String, tool> = emptyMap()

    private val toolCallDispatcher: toolCallDispatcher
        get() = toolCallDispatcher(toolRegistry, androidtoolRegistry, _extratoolsMap)

    /**
     * Resolve context window tokens from config (Gap 2).
     * uses contextWindowGuard for proper resolution with warn/block thresholds.
     */
    private fun resolvecontextWindowTokens(): Int {
        if (configLoader == null) return contextWindowGuard.DEFAULT_CONTEXT_WINDOW_TOKENS

        // Parse provider/model from modelRef (format: "provider/model" or just "model")
        val parts = modelRef?.split("/", limit = 2)
        val providerName = if (parts != null && parts.size == 2) parts[0] else null
        val modelId = if (parts != null && parts.size == 2) parts[1] else modelRef

        val guard = contextWindowGuard.resolveandEvaluate(configLoader, providerName, modelId)
        if (guard.shouldWarn) {
            Log.w(TAG, "context window below recommended: ${guard.tokens} tokens")
        }
        return guard.tokens
    }

    // Log file configuration
    private val logDir = StoragePaths.workspaceLogs
    private val dateformat = SimpleDateformat("yyyy-MM-_HH-mm-ss", Locale.US)
    private var sessionLogFile: File? = null
    private val logBuffer = StringBuilder()

    // Cache tool Definitions — invalidated when extratools changes
    @Volatile private var _alltoolDefinitionsCache: List<com.xiaomo.androidforclaw.providers.toolDefinition>? = null
    private val alltoolDefinitions: List<com.xiaomo.androidforclaw.providers.toolDefinition>
        get() {
            _alltoolDefinitionsCache?.let { return it }
            val defs = toolRegistry.gettoolDefinitions() + androidtoolRegistry.gettoolDefinitions() +
                extratools.map { it.gettoolDefinition() }
            _alltoolDefinitionsCache = defs
            return defs
        }

    // Progress update flow
    private val _progressFlow = MutableSharedFlow<ProgressUpdate>(
        replay = 1,
        extraBufferCapacity = 10
    )
    val progressFlow: SharedFlow<ProgressUpdate> = _progressFlow.asSharedFlow()

    /**
     * steer message channel: external code (e.g. Messagequeuemanager) can send
     * mid-run user messages into this channel.  The main loop drains it after
     * each tool-execution round and injects the messages into the conversation
     * before the next LLM call.
     *
     * uses a channel (not SharedFlow) so that tryReceive() is available for
     * non-suspending drain inside the loop.
     */
    val steerchannel = channel<String>(capacity = 16)

    // Stop flag
    @Volatile
    private var shouldStop = false

    // loop detector state
    private val loopDetectionState = toolloopDetection.sessionState()

    // Timeout compaction counter (aligned with OpenClaw timeoutCompactionAttempts)
    private var timeoutCompactionAttempts = 0
    // Empty/suspicious response retry counter
    private var emptyResponseretryAttempts = 0
    private val MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS = 2

    // Transient HTTP retry guard (aligned with OpenClaw didretryTransientHttpError)
    private var didretryTransientHttpError = false

    // Hook runner (aligned with OpenClaw EmbeedagentHookRunner)
    val hookRunner = com.xiaomo.androidforclaw.agent.hook.HookRunner()

    // Memory flush manager (aligned with OpenClaw runMemoryFlushifneeded)
    private val memoryFlushmanager = com.xiaomo.androidforclaw.agent.memory.MemoryFlushmanager()

    /**
     * Live conversation messages, accessible for sessions_history.
     * Set to the mutable list used inside runInternal(). after run() completes,
     * contains the full conversation. Thread-safe for read-only access.
     */
    @Volatile
    var conversationMessages: List<Message> = emptyList()
        private set

    /**
     * Yield signal for sessions_yield tool.
     * when set, the loop pauses after the current tool execution round
     * and waits until the deferred is completed (by announce or timeout).
     * Aligned with OpenClaw sessions_yield behavior.
     */
    @Volatile
    var yieldSignal: CompletableDeferred<String?>? = null

    /**
     * Write log to file and buffer
     */
    private fun writeLog(message: String) {
        val timestamp = SimpleDateformat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] $message"

        // A to buffer
        logBuffer.appendLine(logLine)

        // Write to file (if file is created)
        sessionLogFile?.let { file ->
            try {
                file.appendText(logLine + "\n")
            } catch (e: exception) {
                Log.e(TAG, "Failed to write log to file", e)
            }
        }

        // Also output to logcat
        Log.d(TAG, message)
    }

    /**
     * Initialize session log file
     */
    private fun initsessionLog(userMessage: String) {
        try {
            // Ensure log directory exists
            logDir.mkdirs()

            // Create log file (using timestamp + user message prefix as filename)
            val timestamp = dateformat.format(Date())
            val messagePrefix = userMessage.take(20).replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
            val filename = "agentloop_${timestamp}_${messagePrefix}.log"
            sessionLogFile = File(logDir, filename)

            // Clear buffer
            logBuffer.clear()

            // Write session header
            sessionLogFile?.writeText("========== agent loop session ==========\n", Charsets.UTF_8)
            sessionLogFile?.appendText("Start time: ${SimpleDateformat("yyyy-MM- HH:mm:ss", Locale.US).format(Date())}\n")
            sessionLogFile?.appendText("user message: $userMessage\n")
            sessionLogFile?.appendText("========================================\n\n")

            Log.i(TAG, "[NOTE] session log initialized: ${sessionLogFile?.absolutePath}")
        } catch (e: exception) {
            Log.w(TAG, "Failed to initialize session log (will continue without file logging): ${e.message}")
            sessionLogFile = null  // Disabled file logging, but continue execution
        }
    }

    /**
     * Finalize session log
     */
    private fun finalizesessionLog(result: agentresult) {
        sessionLogFile?.let { file ->
            try {
                file.appendText("\n========================================\n")
                file.appendText("End time: ${SimpleDateformat("yyyy-MM- HH:mm:ss", Locale.US).format(Date())}\n")
                file.appendText("Total iterations: ${result.iterations}\n")
                file.appendText("tools used: ${result.toolsused.joinToString(", ")}\n")
                file.appendText("Final content length: ${result.finalContent.length} chars\n")
                file.appendText("========================================\n")

                Log.i(TAG, "[OK] session log saved: ${file.absolutePath} (${file.length()} bytes)")
            } catch (e: exception) {
                Log.e(TAG, "Failed to finalize session log", e)
            }
        }
    }

    /**
     * Run agent loop
     *
     * @param systemPrompt System prompt
     * @param userMessage user message
     * @param contextHistory Historical conversation records
     * @param reasoningEnabled Whether to enable reasoning
     * @return agentresult containing final content, tools used, and all messages
     */
    suspend fun run(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message> = emptyList(),
        reasoningEnabled: Boolean = true,
        images: List<ImageBlock>? = null
    ): agentresult {
        // [SHIELD] Global error fallback: ensure any uncaught error can return to user
        return try {
            runInternal(systemPrompt, userMessage, contextHistory, reasoningEnabled, images)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] agentloop uncaught error", e)
            LayoutexceptionLogger.log("agentloop#run", e)

            // return friendly error info to user
            val errorMessage = buildString {
                append("[ERROR] agent execution failed\n\n")
                append("**ErrorInfo**: ${e.message ?: "Unknown error"}\n\n")
                append("**ErrorType**: ${e.javaClass.simpleName}\n\n")
                append("**suggest**: \n")
                append("- please check network connection\n")
                append("- if issue persists,use /new to start new conversation\n")
                append("- view log for more details")
            }

            agentresult(
                finalContent = errorMessage,
                toolsused = emptyList(),
                messages = listOf(
                    systemMessage(systemPrompt),
                    userMessage(userMessage),
                    assistantMessage(errorMessage)
                ),
                iterations = 0
            )
        }
    }

    /**
     * agentloop main execution logic (Internal)
     */
    private suspend fun runInternal(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message>,
        reasoningEnabled: Boolean,
        images: List<ImageBlock>? = null
    ): agentresult {
        shouldStop = false
        val messages = mutableListOf<Message>()
        conversationMessages = messages  // Expose for sessions_history

        // Initialize session log
        initsessionLog(userMessage)

        // Reset context manager
        contextmanager?.reset()

        writeLog("========== agent loop Start ==========")
        writeLog("model: ${modelRef ?: "default"}")
        writeLog("Reasoning: ${if (reasoningEnabled) "enabled" else "disabled"}")
        writeLog("[WRENCH] Universal tools: ${toolRegistry.gettoolCount()}")
        writeLog("[APP] android tools: ${androidtoolRegistry.gettoolCount()}")
        writeLog("[SYNC] context manager: ${if (contextmanager != null) "enabled" else "disabled"}")

        // 1. A system prompt
        messages.a(systemMessage(systemPrompt))
        writeLog("[OK] System prompt aed (${systemPrompt.length} chars)")

        // 2. A conversation history (sanitized — aligned with OpenClaw)
        if (contextHistory.isnotEmpty()) {
            val sanitized = HistorySanitizer.sanitize(contextHistory, maxTurns = 20)
            messages.aAll(sanitized)
            if (sanitized.size != contextHistory.size) {
                writeLog("[OK] context history sanitized: ${contextHistory.size} → ${sanitized.size} messages")
            } else {
                writeLog("[OK] context history aed: ${sanitized.size} messages")
            }
        }

        // 3. A user message (with images if present — aligned with OpenClaw native image injection)
        if (!images.isNullorEmpty()) {
            messages.a(userMessage(userMessage, images))
            writeLog("[OK] user message: $userMessage [+${images.size} image(s)]")
        } else {
            messages.a(userMessage(userMessage))
            writeLog("[OK] user message: $userMessage")
        }

        // 3b. Detect image references in user message text (aligned with OpenClaw detectandLoadPromptImages)
        // Pass workspaceDir so relative paths like "inbox/photo.png" resolve correctly (OpenClaw image-tool fix)
        val workspaceDir = StoragePaths.workspace.absolutePath
        val imageRefs = com.xiaomo.androidforclaw.agent.tools.ImageLoader.detectImageReferences(userMessage, workspaceDir)
        if (imageRefs.isnotEmpty()) {
            writeLog("🖼️ Detected ${imageRefs.size} image reference(s) in user message")
            val loadedImages = imageRefs.mapnotNull { ref ->
                com.xiaomo.androidforclaw.agent.tools.ImageLoader.loadImagefromPath(ref)
            }
            if (loadedImages.isnotEmpty()) {
                // Replace the last user message with one that includes the loaded images
                val lastIdx = messages.size - 1
                if (messages[lastIdx].role == "user") {
                    messages[lastIdx] = userMessage(userMessage, loadedImages)
                    writeLog("🖼️ Loaded ${loadedImages.size} image(s) into user message")
                }
            }
        }

        writeLog("[SEND] preparing to send first LLM request...")

        var iteration = 0
        var finalContent: String? = null
        val toolsused = mutableListOf<String>()
        val loopStartTime = System.currentTimeMillis()
        // Usage accumulator (aligned with OpenClaw usageAccumulator — accumulate across retries)
        var cumulativePromptTokens = 0L
        var cumulativeCompletionTokens = 0L
        var cumulativeTotalTokens = 0L

        // 4. Main loop — no iteration limit, no overall timeout (aligned with OpenClaw)
        // OpenClaw's inner loop is while(true), terminates when LLM returns
        // final answer (no tool_calls) or abort/error.
        while (!shouldStop) {
            iteration++
            // Safety cap — aligned with OpenClaw MAX_RUN_LOOP_ITERATIONS
            if (iteration > MAX_RUN_LOOP_ITERATIONS) {
                writeLog("🛑 Max loop iterations reached ($MAX_RUN_LOOP_ITERATIONS), stopping")
                finalContent = "[ERROR] Request failed after repeated internal retries (max iterations: $MAX_RUN_LOOP_ITERATIONS)."
                break
            }
            val iterationStartTime = System.currentTimeMillis()
            writeLog("========== Iteration $iteration ==========")

            try {
                // 4.1 Call LLM
                writeLog("📢 send iteration progress update...")
                _progressFlow.emit(ProgressUpdate.Iteration(iteration))
                writeLog("[OK] iteration progress sent")

                // ===== context Management (aligned with OpenClaw) =====
                val contextWindowTokens = resolvecontextWindowTokens()

                // Step 1: Limit history turns — drop old user/assistant turn pairs
                // Aligned with OpenClaw limitHistoryTurns + getHistoryLimitfromsessionKey
                val maxTurns = com.xiaomo.androidforclaw.agent.session.HistoryTurnLimiter
                    .getHistoryLimitfromsessionKey(sessionKey, configLoader)

                val systemMsg = messages.firstorNull { it.role == "system" }
                val nonSystemMessages = messages.filter { it.role != "system" }.toMutableList()
                val limitedNonSystem = HistorySanitizer.limitHistoryTurns(nonSystemMessages, maxTurns)
                if (limitedNonSystem.size < nonSystemMessages.size) {
                    val dropped = nonSystemMessages.size - limitedNonSystem.size
                    messages.clear()
                    if (systemMsg != null) messages.a(systemMsg)
                    messages.aAll(limitedNonSystem)
                    writeLog("[SYNC] History limited: dropped $dropped old messages (kept $maxTurns turns)")
                }

                // Step 2: context pruning — soft trim old large tool results
                // Aligned with OpenClaw context-pruning cache-ttl mode
                pruneoldtoolresults(messages, contextWindowTokens)

                // Step 3: Enforce tool result context budget (truncate + compact)
                // Aligned with OpenClaw tool-result-context-guard.ts
                toolresultcontextGuard.enforcecontextBudget(messages, contextWindowTokens)

                // Step 4: Final budget check — if still over, aggressively trim
                val totalChars = toolresultcontextGuard.estimatecontextChars(messages)
                val budgetChars = (contextWindowTokens * 4 * 0.75).toInt()
                if (totalChars > budgetChars) {
                    writeLog("[WARN] context still over budget ($totalChars / $budgetChars chars), aggressive trim...")
                    aggressiveTrimMessages(messages, budgetChars)
                }

                writeLog("[SEND] call UnifiedLLMprovider.chatwithtoolsStreaming...")
                writeLog("   Messages: ${messages.size}, tools+skills: ${alltoolDefinitions.size}")

                // [NOTIF] Send intermediate feedback: thinking step X
                _progressFlow.emit(ProgressUpdate.Thinking(iteration))

                val llmStartTime = System.currentTimeMillis()

                // ⏱️ SSE streaming call + timeout protection
                // Aligned with OpenClaw streamSimple → thinking_delta/text_delta Real-time push
                val response: LLMResponse
                try {
                    val thinkingAccumulated = StringBuilder()
                    val contentAccumulated = StringBuilder()
                    data class toolCallAccum(
                        var id: String = "",
                        var name: String = "",
                        val args: StringBuilder = StringBuilder()
                    )
                    val toolCallsAccumulated = mutableMapOf<Int, toolCallAccum>()
                    var finalUsage: LLMUsage? = null
                    var finalFinishReason: String? = null

                    // Scrub Anthropic refusal magic string from system prompt (aligned with OpenClaw scrubAnthropicRefusalMagic)
                    val scrubbedMessages = scrubAnthropicRefusalMagic(messages)

                    // thinking duplicate detection(prevent hallucination infinite loop)
                    var thinkingRepeatCount = 0
                    var lastThinkingSig = ""

                    kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                        llmprovider.chatwithtoolsStreaming(
                            messages = scrubbedMessages,
                            tools = alltoolDefinitions,
                            modelRef = modelRef,
                            reasoningEnabled = reasoningEnabled
                        ).collect { chunk ->
                            when (chunk.type) {
                                ChunkType.THINKING_DELTA -> {
                                    // detect duplicate thinking(model stuck in loop)
                                    val sig = chunk.text.take(50).trim()
                                    if (sig.isnotEmpty() && sig == lastThinkingSig) {
                                        thinkingRepeatCount++
                                        if (thinkingRepeatCount > 20) {
                                            writeLog("[WARN] thinking duplicate exceeds 20 times, skip remaining thinking")
                                            Log.w(TAG, "Thinking repetition detected ($thinkingRepeatCount times), skipping")
                                            // no longer accumulate, also not push
                                        }
                                    } else {
                                        thinkingRepeatCount = 0
                                        lastThinkingSig = sig
                                    }
                                    if (thinkingRepeatCount <= 20 && thinkingAccumulated.length < MAX_THINKING_CHARS) {
                                        thinkingAccumulated.append(chunk.text)
                                        _progressFlow.emit(ProgressUpdate.ReasoningDelta(chunk.text))
                                    }
                                }
                                ChunkType.TEXT_DELTA -> {
                                    contentAccumulated.append(chunk.text)
                                    _progressFlow.emit(ProgressUpdate.ContentDelta(chunk.text))
                                }
                                ChunkType.TOOL_CALL_DELTA -> {
                                    val idx = chunk.toolCallIndex ?: 0
                                    val accum = toolCallsAccumulated.getorPut(idx) { toolCallAccum() }
                                    if (!chunk.toolCallId.isNullorEmpty()) accum.id = chunk.toolCallId
                                    if (!chunk.toolCallName.isNullorEmpty()) accum.name = chunk.toolCallName
                                    if (!chunk.toolCallArgs.isNullorEmpty()) accum.args.append(chunk.toolCallArgs)
                                }
                                ChunkType.DONE -> {
                                    finalFinishReason = chunk.finishReason ?: finalFinishReason
                                    chunk.usage?.let { u ->
                                        finalUsage = LLMUsage(u.promptTokens, u.completionTokens, u.totalTokens)
                                    }
                                }
                                ChunkType.USAGE -> {
                                    chunk.usage?.let { u ->
                                        finalUsage = LLMUsage(u.promptTokens, u.completionTokens, u.totalTokens)
                                    }
                                }
                                ChunkType.PING -> { /* ignore */ }
                            }
                        }
                    }

                    // assemble complete LLM response(connect with subsequent tool call logic)
                    val rawtoolCalls = if (toolCallsAccumulated.isEmpty()) null else {
                        toolCallsAccumulated.entries.sortedBy { it.key }.map { (_, tc) ->
                            LLMtoolCall(
                                id = tc.id.ifEmpty { "call_${System.currentTimeMillis()}" },
                                name = tc.name,
                                arguments = tc.args.toString()
                            )
                        }
                    }
                    response = LLMResponse(
                        content = contentAccumulated.toString().ifEmpty { null },
                        toolCalls = rawtoolCalls?.let { sanitizetoolCalls(it) },
                        thinkingContent = thinkingAccumulated.toString().ifEmpty { null },
                        usage = finalUsage,
                        finishReason = finalFinishReason
                    )

                    // Accumulate usage across retries (aligned with OpenClaw usageAccumulator)
                    finalUsage?.let { u ->
                        cumulativePromptTokens += u.promptTokens
                        cumulativeCompletionTokens += u.completionTokens
                        cumulativeTotalTokens += u.totalTokens
                    }
                } catch (e: kotlinx.coroutines.Timeoutcancellationexception) {
                    val errorMsg = "LLM call timeout (${LLM_TIMEOUT_MS / 1000}s)"
                    writeLog("[TIME] $errorMsg")
                    Log.w(TAG, errorMsg)

                    // ── Timeout compaction (aligned with OpenClaw) ──
                    val totalCharsNow = toolresultcontextGuard.estimatecontextChars(messages)
                    val budgetCharsNow = (contextWindowTokens * 4 * 0.75).toInt()
                    val tokenusedRatio = if (budgetCharsNow > 0) totalCharsNow.toFloat() / budgetCharsNow else 0f

                    if (timeoutCompactionAttempts < MAX_TIMEOUT_COMPACTION_ATTEMPTS &&
                        tokenusedRatio > TIMEOUT_COMPACTION_TOKEN_RATIO
                    ) {
                        timeoutCompactionAttempts++
                        writeLog("[SYNC] Timeout compaction attempt $timeoutCompactionAttempts/$MAX_TIMEOUT_COMPACTION_ATTEMPTS " +
                            "(context usage: ${(tokenusedRatio * 100).toInt()}%)")

                        // Run before_compaction hook (aligned with OpenClaw hookRunner.runbeforeCompaction)
                        val hookCtx = com.xiaomo.androidforclaw.agent.hook.Hookcontext(
                            sessionKey = null,
                            agentId = null,
                            provider = "",
                            model = modelRef ?: ""
                        )
                        val hookresult = hookRunner.runbeforeCompaction(
                            data = mapOf("reason" to "timeout", "tokenusedRatio" to tokenusedRatio),
                            context = hookCtx
                        )

                        if (hookresult.shouldcancel) {
                            writeLog("🪝 before_compaction hook cancelled compaction")
                            // Skip compaction, continue with current messages
                        } else {
                            // Memory flush: run before compaction if context is getting full
                            // Aligned with OpenClaw runMemoryFlushifneeded()
                            try {
                                if (memoryFlushmanager.shouldRunFlush(
                                    tokenCount = (totalCharsNow / 4),
                                    contextWindowTokens = contextWindowTokens
                                )) {
                                    writeLog("[BRAIN] Running memory flush before compaction...")
                                    val flushresult = memoryFlushmanager.runFlush(
                                        llmprovider = llmprovider,
                                        modelRef = modelRef ?: "",
                                        messages = messages
                                    )
                                    if (flushresult.success && flushresult.memoriesExtracted) {
                                        writeLog("[OK] Memory flush: extracted ${flushresult.memoriesContent?.length ?: 0} chars")
                                        _progressFlow.emit(ProgressUpdate.BlockReply(text = "[BRAIN] memory extraction complete", iteration = iteration))
                                    }
                                }
                            } catch (e: exception) {
                                writeLog("[WARN] Memory flush failed (non-fatal): ${e.message}")
                            }
                        }

                        if (contextmanager != null) {
                            val recoveryresult = contextmanager.handlecontextoverflow(
                                error = e,
                                messages = messages
                            )
                            if (recoveryresult is contextRecoveryresult.Recovered) {
                                writeLog("[OK] Timeout compaction succeeded: ${recoveryresult.strategy}")
                                messages.clear()
                                messages.aAll(recoveryresult.messages)
                                continue
                            }
                        }

                        pruneoldtoolresults(messages, contextWindowTokens)
                        aggressiveTrimMessages(messages, budgetCharsNow)
                        writeLog("[OK] Timeout compaction fallback: pruned context")
                        continue
                    }

                    writeLog("[ERROR] LLM timeout after $timeoutCompactionAttempts compaction attempts, surfacing error")
                    finalContent = "[TIME] LLM call timeout. please simplify issueor use /new to start new conversation. "
                    break
                }

                val llmDuration = System.currentTimeMillis() - llmStartTime

                writeLog("[OK] LLM streaming response complete [time spent: ${llmDuration}ms]")
                // Debug: log raw LLM response for diagnosing empty/unexpected responses
                writeLog("   Raw content length: ${response.content?.length ?: 0}")
                writeLog("   Raw content: [${response.content?.take(500) ?: "null"}]")
                writeLog("   tool calls: ${response.toolCalls?.size ?: 0}")
                writeLog("   Finish reason: ${response.finishReason ?: "null"}")
                if (response.content != null && response.content.length > 500) {
                    writeLog("   Raw content tail: ...${response.content.takeLast(200)}")
                }

                if (llmDuration > 30_000) {
                    writeLog("[WARN] LLM response time is long: ${llmDuration}ms")
                }

                // 4.2 display reasoning thinking process (complete content, streaming increment already sent in collect)
                response.thinkingContent?.let { reasoning ->
                    writeLog("[BRAIN] Reasoning (${reasoning.length} chars):")
                    writeLog("   ${reasoning.take(500)}${if (reasoning.length > 500) "..." else ""}")
                    _progressFlow.emit(ProgressUpdate.Reasoning(reasoning, llmDuration))
                }

                // 4.3 Check if there are function calls
                if (response.toolCalls != null && response.toolCalls.isnotEmpty()) {
                    writeLog("Function calls: ${response.toolCalls.size}")

                    // [OK] Block Reply: emit intermediate text immediately
                    // Aligned with OpenClaw blockReplyBreak="text_end" + normalizeStreamingText
                    val intermediateText = response.content?.trim()
                    if (!intermediateText.isNullorEmpty() && !SubagentPromptBuilder.isSilentReplyText(intermediateText)) {
                        writeLog("[SEND] Block reply (intermediate text): ${intermediateText.take(200)}...")
                        _progressFlow.emit(ProgressUpdate.BlockReply(intermediateText, iteration))
                    }

                    // A assistant message (containing function calls)
                    messages.a(
                        assistantMessage(
                            content = response.content,
                            toolCalls = response.toolCalls.map {
                                com.xiaomo.androidforclaw.providers.llm.toolCall(
                                    id = it.id,
                                    name = it.name,
                                    arguments = it.arguments
                                )
                            }
                        )
                    )

                    // Execute each tool/skill (directly execute the capabilities selected by LLM)
                    var totalExecDuration = 0L
                    for (toolCall in response.toolCalls) {
                        val functionName = toolCall.name
                        val argsJson = toolCall.arguments

                        writeLog("[WRENCH] Function: $functionName")
                        writeLog("   Args: $argsJson")

                        // Parse arguments
                        val args = try {
                            @Suppress("UNCHECKED_CAST")
                            gson.fromJson(argsJson, Map::class.java) as Map<String, Any?>
                        } catch (e: exception) {
                            writeLog("Failed to parse arguments: ${e.message}")
                            Log.e(TAG, "Failed to parse arguments", e)
                            mapOf<String, Any?>()
                        }

                        // [OK] Detect tool call loop (before execution)
                        val loopDetection = toolloopDetection.detecttoolCallloop(
                            state = loopDetectionState,
                            toolName = functionName,
                            params = args
                        )

                        when (loopDetection) {
                            is loopDetectionresult.loopDetected -> {
                                val logLevel = if (loopDetection.level == loopDetectionresult.Level.CRITICAL) "🚨" else "[WARN]"
                                writeLog("$logLevel loop detected: ${loopDetection.detector} (count: ${loopDetection.count})")
                                writeLog("   ${loopDetection.message}")

                                // Critical level: abort execution
                                if (loopDetection.level == loopDetectionresult.Level.CRITICAL) {
                                    writeLog("🛑 Critical loop detected, stopping execution")
                                    Log.e(TAG, "🛑 Critical loop detected: ${loopDetection.message}")

                                    // A error message to conversation
                                    messages.a(
                                        toolMessage(
                                            toolCallId = toolCall.id,
                                            content = loopDetection.message,
                                            name = functionName
                                        )
                                    )

                                    _progressFlow.emit(ProgressUpdate.loopDetected(
                                        detector = loopDetection.detector.name,
                                        count = loopDetection.count,
                                        message = loopDetection.message,
                                        critical = true
                                    ))

                                    // Abort entire loop
                                    shouldStop = true
                                    finalContent = "Task failed: ${loopDetection.message}"
                                    break
                                }

                                // Warning level: inject warning but continue execution
                                writeLog("[WARN] loop warning injected into conversation")
                                messages.a(
                                    toolMessage(
                                        toolCallId = toolCall.id,
                                        content = loopDetection.message,
                                        name = functionName
                                    )
                                )

                                _progressFlow.emit(ProgressUpdate.loopDetected(
                                    detector = loopDetection.detector.name,
                                    count = loopDetection.count,
                                    message = loopDetection.message,
                                    critical = false
                                ))

                                // Skip this tool call after warning
                                continue
                            }
                            loopDetectionresult.Noloop -> {
                                // No loop, continue execution
                            }
                        }

                        // Record tool call (before execution)
                        toolloopDetection.recordtoolCall(
                            state = loopDetectionState,
                            toolName = functionName,
                            params = args,
                            toolCallId = toolCall.id
                        )

                        toolsused.a(functionName)

                        // Send call progress update
                        _progressFlow.emit(ProgressUpdate.toolCall(functionName, args))

                        // Run before_tool_call hook (aligned with OpenClaw hookRunner.runbeforetoolCall)
                        val toolHookCtx = com.xiaomo.androidforclaw.agent.hook.Hookcontext(
                            sessionKey = null,
                            agentId = null,
                            provider = "",
                            model = modelRef ?: ""
                        )
                        val beforetoolHook = hookRunner.runbeforetoolCall(
                            toolName = functionName,
                            arguments = toolCall.arguments,
                            context = toolHookCtx
                        )

                        if (beforetoolHook.shouldcancel) {
                            writeLog("🪝 before_tool_call hook cancelled $functionName")
                            messages.a(
                                toolMessage(toolCallId = toolCall.id, content = "tool execution cancelled by hook", name = functionName)
                            )
                            continue
                        }

                        // [OK] Search universal tools first, then android tools
                        val execStartTime = System.currentTimeMillis()

                        // Execute tool (no per-tool timeout — aligned with OpenClaw)
                        // Individual tools manage their own timeouts internally.
                        val result = run {
                            val target = toolCallDispatcher.resolve(functionName)
                            when (target) {
                                is toolCallDispatcher.DispatchTarget.Universal -> writeLog("   → Universal tool")
                                is toolCallDispatcher.DispatchTarget.android -> writeLog("   → android tool")
                                is toolCallDispatcher.DispatchTarget.Extra -> writeLog("   → Extra tool (subagent)")
                                null -> writeLog("   [ERROR] Unknown function: $functionName")
                            }
                            toolCallDispatcher.execute(functionName, args)
                        }

                        val execDuration = System.currentTimeMillis() - execStartTime
                        totalExecDuration += execDuration

                        writeLog("   result: ${result.success}, ${result.content.take(200)}")
                        writeLog("   ⏱️ executiontime spent: ${execDuration}ms")

                        // Log tool execution errors (aligned with OpenClaw: no consecutive error abort)
                        // OpenClaw lets the LLM see tool errors and decide how to proceed.
                        // toolloopDetection handles runaway loops separately.
                        if (!result.success) {
                            writeLog("   [WARN] tool execution failed: ${result.content.take(200)}")
                        }

                        // Record tool call result (for loop detection)
                        toolloopDetection.recordtoolCallOutcome(
                            state = loopDetectionState,
                            toolName = functionName,
                            toolParams = args,
                            result = result.toString(),
                            error = if (result.success) null else exception(result.content),
                            toolCallId = toolCall.id
                        )

                        // Run after_tool_call hook (aligned with OpenClaw hookRunner.runaftertoolCall)
                        hookRunner.runaftertoolCall(
                            toolName = functionName,
                            arguments = toolCall.arguments,
                            result = result.toString(),
                            success = result.success,
                            context = toolHookCtx
                        )

                        // A result to message list (with images if tool returned any)
                        messages.a(
                            toolMessage(
                                toolCallId = toolCall.id,
                                content = result.toString(),
                                name = functionName,
                                images = result.images
                            )
                        )

                        // Send result update
                        _progressFlow.emit(ProgressUpdate.toolresult(functionName, result.toString(), execDuration))

                        // Check if it's stop skill
                        if (functionName == "stop") {
                            val metadata = result.metadata
                            val stopped = metadata["stopped"] as? Boolean ?: false
                            if (stopped) {
                                shouldStop = true
                                finalContent = result.content
                                writeLog("Stop function called, ending loop")
                                break
                            }
                        }
                    }

                    // Continue loop, let LLM decide next step after seeing function results
                    if (shouldStop) break

                    // Drain steer messages injected by Messagequeuemanager (STEER mode)
                    while (true) {
                        val steerMsg = steerchannel.tryReceive().getorNull() ?: break
                        Log.i(TAG, "Injecting steer message: ${steerMsg.take(50)}...")
                        writeLog("[TARGET] [STEER] Injecting mid-run user message: ${steerMsg.take(100)}")
                        messages.a(userMessage(steerMsg))
                        _progressFlow.emit(ProgressUpdate.steerMessageInjected(steerMsg))
                    }

                    // Check for yield signal (sessions_yield tool)
                    // if set, pause the loop until subagent announcements arrive or timeout.
                    // Aligned with OpenClaw sessions_yield behavior.
                    yieldSignal?.let { deferred ->
                        writeLog("[PAUSE] Yield signal detected, pausing loop...")
                        _progressFlow.emit(ProgressUpdate.Yielded)
                        // Wait up to 300s to prevent deadlock
                        val yieldMessage = withTimeoutorNull(300_000L) { deferred.await() }
                        yieldSignal = null
                        if (!yieldMessage.isNullorBlank()) {
                            messages.a(userMessage(yieldMessage))
                            writeLog("[PLAY] resumed from yield with message: ${yieldMessage.take(100)}")
                        } else {
                            writeLog("[PLAY] resumed from yield (timeout or no message)")
                        }
                    }

                    val iterationDuration = System.currentTimeMillis() - iterationStartTime
                    writeLog("⏱️ This iteration total time: ${iterationDuration}ms (LLM: ${llmDuration}ms, execution: ${totalExecDuration}ms)")

                    // Single iteration time warning (only warn, not interrupt)
                    if (iterationDuration > ITERATION_WARN_THRESHOLD_MS) {
                        writeLog("[WARN] Iteration $iteration time is long (${iterationDuration}ms > ${ITERATION_WARN_THRESHOLD_MS}ms)")
                        Log.w(TAG, "Iteration $iteration slow: ${iterationDuration}ms")
                    }

                    // Send iteration complete event (with time statistics)
                    _progressFlow.emit(ProgressUpdate.IterationComplete(iteration, iterationDuration, llmDuration, totalExecDuration))
                    continue
                }

                // 4.4 No tool calls, meaning LLM provided final answer
                // Filter SILENT_REPLY_TOKEN (aligned with OpenClaw normalizeStreamingText)
                var rawContent = response.content?.let { ReasoningTagFilter.stripReasoningTags(it) }
                    ?: response.content

                // Fallback: some models (e.g. o3 on Copilot API) return content in reasoning_content
                // instead of content when reasoning is disabled. use reasoning as fallback.
                if (rawContent.isNullorBlank() && !response.thinkingContent.isNullorBlank()) {
                    writeLog("[WARN] content is empty, falling back to reasoning_content (${response.thinkingContent!!.length} chars)")
                    Log.w(TAG, "[WARN] content empty, using reasoning_content as fallback")
                    rawContent = response.thinkingContent
                }

                // Detect token-loop: finish_reason=length + no tool calls → model hit max_tokens in repetitive loop
                // Also detect repetitive content (e.g. same sentence repeated 100+ times)
                val isTokenloop = response.finishReason == "length" && response.toolCalls.isNullorEmpty()
                val isRepetitiveContent = rawContent != null && rawContent.length > 500 && isHighlyRepetitive(rawContent)
                if ((isTokenloop || isRepetitiveContent) && emptyResponseretryAttempts < MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS) {
                    emptyResponseretryAttempts++
                    val reason = if (isTokenloop) "finish_reason=length, no tools" else "repetitive content (${rawContent!!.length} chars)"
                    writeLog("[WARN] LLM stuck in token loop: $reason (retry $emptyResponseretryAttempts/$MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS)")
                    Log.w(TAG, "[WARN] Token loop detected: $reason, retry $emptyResponseretryAttempts")

                    if (contextmanager != null) {
                        writeLog("[SYNC] compress context and retry...")
                        val recoveryresult = contextmanager.handlecontextoverflow(
                            error = exception("Token loop: $reason"),
                            messages = messages
                        )
                        when (recoveryresult) {
                            is contextRecoveryresult.Recovered -> {
                                messages.clear()
                                messages.aAll(recoveryresult.messages)
                                _progressFlow.emit(ProgressUpdate.contextRecovered(
                                    strategy = recoveryresult.strategy,
                                    attempt = recoveryresult.attempt
                                ))
                            }
                            is contextRecoveryresult.cannotRecover -> {
                                val ctxTokens = resolvecontextWindowTokens()
                                pruneoldtoolresults(messages, ctxTokens)
                                toolresultcontextGuard.enforcecontextBudget(messages, ctxTokens)
                            }
                        }
                    } else {
                        writeLog("[SYNC] no contextmanager, directly retry...")
                    }
                    continue
                }

                // Detect suspicious default text from LLM (e.g. "NoneResponse")
                // Instead of silently accepting, try to compact context and retry
                val isSuspiciousResponse = rawContent == "NoneResponse" || rawContent == "NoneResponse. " || rawContent == "NoneResponse"
                if (isSuspiciousResponse && emptyResponseretryAttempts < MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS) {
                    emptyResponseretryAttempts++
                    writeLog("[WARN] LLM returned suspicious default text: '$rawContent' (retry $emptyResponseretryAttempts/$MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS)")
                    writeLog("   Messages count: ${messages.size}, Total context chars: ${toolresultcontextGuard.estimatecontextChars(messages)}")
                    Log.w(TAG, "[WARN] Suspicious response '$rawContent', retry $emptyResponseretryAttempts/$MAX_EMPTY_RESPONSE_RETRY_ATTEMPTS")

                    if (contextmanager != null) {
                        writeLog("[SYNC] currentlycompress context and retry...")
                        val recoveryresult = contextmanager.handlecontextoverflow(
                            error = exception("Suspicious default response: $rawContent"),
                            messages = messages
                        )
                        when (recoveryresult) {
                            is contextRecoveryresult.Recovered -> {
                                writeLog("[OK] context compress success: ${recoveryresult.strategy}")
                                messages.clear()
                                messages.aAll(recoveryresult.messages)
                                _progressFlow.emit(ProgressUpdate.contextRecovered(
                                    strategy = recoveryresult.strategy,
                                    attempt = recoveryresult.attempt
                                ))
                            }
                            is contextRecoveryresult.cannotRecover -> {
                                writeLog("[WARN] context compress failed, try tool result truncation...")
                                val ctxTokens = resolvecontextWindowTokens()
                                pruneoldtoolresults(messages, ctxTokens)
                                toolresultcontextGuard.enforcecontextBudget(messages, ctxTokens)
                            }
                        }
                    } else {
                        writeLog("[SYNC] no contextmanager, directly retry...")
                    }
                    continue
                }

                if (isSuspiciousResponse) {
                    writeLog("[ERROR] LLM returns suspicious default text multiple times '$rawContent', give up retry")
                    Log.e(TAG, "[ERROR] LLM returned suspicious response after $emptyResponseretryAttempts retries")
                }

                finalContent = if (SubagentPromptBuilder.isSilentReplyText(rawContent)) null else rawContent
                messages.a(assistantMessage(content = finalContent))

                writeLog("Final content received (finish_reason: ${response.finishReason})")
                writeLog("Content: ${finalContent?.take(500)}${if ((finalContent?.length ?: 0) > 500) "..." else ""}")
                break

            } catch (e: exception) {
                writeLog("Iteration $iteration error: ${e.message}")
                Log.e(TAG, "Iteration $iteration error", e)
                LayoutexceptionLogger.log("agentloop#run#iteration$iteration", e)

                // Check if it's a context overflow error
                val errorMessage = contextErrors.extractErrorMessage(e)
                val iscontextoverflow = contextErrors.islikelycontextoverflowError(errorMessage)

                if (iscontextoverflow && contextmanager != null) {
                    writeLog("[SYNC] detected context exceeded limit, try resume...")
                    Log.w(TAG, "[SYNC] detected context exceeded limit, try resume...")
                    _progressFlow.emit(ProgressUpdate.contextoverflow("context overflow detected, attempting recovery..."))

                    // Attempt recovery
                    val recoveryresult = contextmanager.handlecontextoverflow(
                        error = e,
                        messages = messages
                    )

                    when (recoveryresult) {
                        is contextRecoveryresult.Recovered -> {
                            writeLog("[OK] context resume success: ${recoveryresult.strategy} (attempt ${recoveryresult.attempt})")
                            Log.d(TAG, "[OK] context resume success: ${recoveryresult.strategy} (attempt ${recoveryresult.attempt})")
                            _progressFlow.emit(ProgressUpdate.contextRecovered(
                                strategy = recoveryresult.strategy,
                                attempt = recoveryresult.attempt
                            ))

                            // Replace message list
                            messages.clear()
                            messages.aAll(recoveryresult.messages)

                            // retry current iteration
                            continue
                        }
                        is contextRecoveryresult.cannotRecover -> {
                            // Step 2: Aggressive tool result truncation (aligned with OpenClaw truncateoversizedtoolresultsInsession)
                            // OpenClaw: when contextEngine.compact fails, try truncating oversized tool results before giving up
                            writeLog("[WARN] Compaction failed, trying aggressive tool result truncation...")
                            val ctxTokens = resolvecontextWindowTokens()
                            val budgetCharsNow = (ctxTokens * 4 * 0.75).toInt()
                            pruneoldtoolresults(messages, ctxTokens)
                            toolresultcontextGuard.enforcecontextBudget(messages, ctxTokens)
                            aggressiveTrimMessages(messages, budgetCharsNow)

                            val totalafterTruncation = toolresultcontextGuard.estimatecontextChars(messages)
                            if (totalafterTruncation <= budgetCharsNow) {
                                writeLog("[OK] tool result truncation succeeded, retrying iteration")
                                _progressFlow.emit(ProgressUpdate.contextRecovered(
                                    strategy = "tool_result_truncation",
                                    attempt = 0
                                ))
                                continue
                            }

                            writeLog("[ERROR] context resume failed(including truncation fallback): ${recoveryresult.reason}")
                            Log.e(TAG, "[ERROR] context resume failed: ${recoveryresult.reason}")
                            _progressFlow.emit(ProgressUpdate.Error("context overflow: ${recoveryresult.reason}"))

                            finalContent = buildString {
                                append("[ERROR] context overflow\n\n")
                                append("**Error**: ${recoveryresult.reason}\n\n")
                                append("**suggest**: conversation history too long, pleaseuse /new or /reset to start new conversation")
                            }
                            break
                        }
                    }
                } else {
                    // Non-context overflow error — classify and decide recovery
                    // Aligned with OpenClaw runagentTurnwithFallback error classification
                    val isBilling = contextErrors.isBillingErrorMessage(errorMessage)
                    val isRoleordering = contextErrors.isRoleorderingError(errorMessage)
                    val issessionCorruption = contextErrors.issessionCorruptionError(errorMessage)
                    val isTransientHttp = contextErrors.isTransientHttpError(errorMessage)

                    // Role ordering conflict → reset conversation
                    // Aligned with OpenClaw resetsessionafterRoleorderingConflict
                    if (isRoleordering) {
                        writeLog("[WARN] Role ordering conflict detected, resetting conversation")
                        Log.w(TAG, "Role ordering conflict: $errorMessage")
                        finalContent = "[WARN] Message ordering conflict. Conversation has been reset - please try again."
                        break
                    }

                    // Gemini session corruption → reset conversation
                    // Aligned with OpenClaw issessionCorruption handling
                    if (issessionCorruption) {
                        writeLog("[WARN] session history corrupted (function call ordering), resetting")
                        Log.w(TAG, "session corruption: $errorMessage")
                        finalContent = "[WARN] session history was corrupted. Conversation has been reset - please try again!"
                        break
                    }

                    // Transient HTTP error → single retry with delay
                    // Aligned with OpenClaw: TRANSIENT_HTTP_RETRY_DELAY_MS = 2500, retry once
                    if (isTransientHttp && !didretryTransientHttpError) {
                        // overloaded (529/503+overload) → exponential backoff (aligned with OpenClaw OVERLOAD_FAILOVER_BACKOFF_POLICY)
                        val isoverloaded = contextErrors.isoverloadedError(errorMessage)
                        if (isoverloaded) {
                            writeLog("[WARN] overloaded error, exponential backoff...")
                            var backoffDelay = OVERLOAD_BACKOFF_INITIAL_MS
                            val maxRetries = 4  // 250 → 500 → 1000 → 1500ms
                            for (backoffAttempt in 1..maxRetries) {
                                // A jitter: delay * (1 ± jitter)
                                val jitter = backoffDelay * OVERLOAD_BACKOFF_JITTER * (Math.random() * 2 - 1)
                                val actualDelay = (backoffDelay + jitter).toLong().coerceAtLeast(0)
                                writeLog("   backoff $backoffAttempt/$maxRetries: ${actualDelay}ms")
                                kotlinx.coroutines.delay(actualDelay)
                                backoffDelay = (backoffDelay * OVERLOAD_BACKOFF_FACTOR).coerceAtMost(OVERLOAD_BACKOFF_MAX_MS)
                            }
                        } else {
                            writeLog("[WARN] Transient HTTP error, retrying in ${TRANSIENT_HTTP_RETRY_DELAY_MS}ms... ($errorMessage)")
                            kotlinx.coroutines.delay(TRANSIENT_HTTP_RETRY_DELAY_MS)
                        }
                        didretryTransientHttpError = true
                        Log.w(TAG, "Transient HTTP error, retrying: $errorMessage")
                        continue
                    }

                    _progressFlow.emit(ProgressUpdate.Error(e.message ?: "Unknown error"))

                    // Timeout error: no bare retry (aligned with OpenClaw — timeout is failover, not retry)
                    // OpenClaw handles timeout via runwithmodelFallback → classifyFailoverReason("timeout") → failover.
                    // android has no model fallback, so surface the error instead of infinite retry.
                    if (e.message?.contains("timeout", ignoreCase = true) == true) {
                        writeLog("[TIME] Timeout error, surfacing to user (no infinite retry)")
                        finalContent = "[TIME] LLM call timeout. please simplify issueor use /new to start new conversation. "
                        break
                    }

                    // Other errors, stop loop and format error message
                    writeLog("[ERROR] agent loop failed: ${e.message}")
                    Log.e(TAG, "agent loop failed", e)

                    // Build friendly error message (aligned with OpenClaw error formatting)
                    finalContent = buildString {
                        if (isBilling) {
                            append("[WARN] Billing error — please check your account balance or API key quota.")
                        } else {
                            append("[ERROR] Execution error\n\n")

                            when (e) {
                                is com.xiaomo.androidforclaw.providers.LLMexception -> {
                                    append("**ErrorType**: API call failed\n")
                                    append("**ErrorInfo**: ${e.message}\n\n")
                                    append("**Suggestion**: Please check model config and API key\n")
                                    append("**Config files**: ${StoragePaths.openclawconfig.absolutePath}\n")
                                }
                                else -> {
                                    append("**ErrorInfo**: ${e.message}\n")
                                }
                            }

                            append("\n**DebugInfo**:\n```\n")
                            append(e.stackTraceToString().take(800))
                            append("\n```")
                        }
                    }
                    break
                }
            }
        }

        // 5. Handle loop end
        // No maxIterations limit (aligned with OpenClaw). loop only exits when:
        // - LLM returns final answer (no tool_calls)
        // - shouldStop flag set (abort, critical loop, stop tool)
        // - Unrecoverable error

        writeLog("========== agent loop End ==========")
        writeLog("Iterations: $iteration")
        writeLog("tools used: ${toolsused.joinToString(", ")}")

        // A final content as assistant message if not empty
        val effectiveFinalContent = when {
            finalContent != null -> finalContent
            shouldStop -> "[OK] task stopped"
            else -> "NoneResponse"
        }
        if (effectiveFinalContent.isnotEmpty()) {
            messages.a(com.xiaomo.androidforclaw.providers.llm.Message(
                role = "assistant",
                content = effectiveFinalContent
            ))
        }

        // Log cumulative usage (aligned with OpenClaw usageAccumulator)
        if (cumulativeTotalTokens > 0) {
            writeLog("[STATS] Cumulative usage: $cumulativePromptTokens prompt + $cumulativeCompletionTokens completion = $cumulativeTotalTokens total tokens")
        }

        val result = agentresult(
            finalContent = effectiveFinalContent,
            toolsused = toolsused,
            messages = messages,
            iterations = iteration
        )

        // Finalize session log
        finalizesessionLog(result)

        return result
    }

    // ===== context Pruning (aligned with OpenClaw context-pruning cache-ttl) =====

    /**
     * Soft-trim and hard-clear old large tool results.
     * Aligned with OpenClaw DEFAULT_CONTEXT_PRUNING_SETTINGS:
     * - softTrimRatio: 0.3 (start trimming when 30% of context is used)
     * - hardClearRatio: 0.5 (hard clear when 50% is used)
     * - minPrunabletoolChars: 50000
     * - keepLastAssistants: 3
     * - softTrim.maxChars: 4000, headChars: 1500, tailChars: 1500
     * - hardClear.placeholder: "[old tool result content cleared]"
     */
    private fun pruneoldtoolresults(
        messages: MutableList<Message>,
        contextWindowTokens: Int
    ) {
        val budgetChars = (contextWindowTokens * 4 * 0.75).toInt()
        val currentChars = toolresultcontextGuard.estimatecontextChars(messages)
        val usageRatio = currentChars.toFloat() / budgetChars.toFloat()

        if (usageRatio < SOFT_TRIM_RATIO) return  // under 30%, no action needed

        // Find the last 3 assistant messages (keep their tool results untouched)
        val keepafterIndex = findKeepBoundaryIndex(messages, KEEP_LAST_ASSISTANTS)

        var trimmed = 0
        var cleared = 0

        for (i in messages.indices) {
            if (i >= keepafterIndex) break  // Don't touch recent messages
            val msg = messages[i]
            if (msg.role != "tool") continue

            val content = msg.content ?: continue
            if (content.length < MIN_PRUNABLE_TOOL_CHARS) continue

            if (usageRatio >= HARD_CLEAR_RATIO) {
                // Hard clear
                messages[i] = msg.copy(content = HARD_CLEAR_PLACEHOLDER)
                cleared++
            } else {
                // Soft trim: keep head + tail
                if (content.length > SOFT_TRIM_MAX_CHARS) {
                    val head = content.take(SOFT_TRIM_HEAD_CHARS)
                    val tail = content.takeLast(SOFT_TRIM_TAIL_CHARS)
                    val trimmedContent = "$head\n\n[...${content.length - SOFT_TRIM_HEAD_CHARS - SOFT_TRIM_TAIL_CHARS} chars trimmed...]\n\n$tail"
                    messages[i] = msg.copy(content = trimmedContent)
                    trimmed++
                }
            }
        }

        if (trimmed > 0 || cleared > 0) {
            writeLog("[SYNC] context pruning: soft-trimmed $trimmed, hard-cleared $cleared tool results")
        }
    }

    /**
     * Find the message index before which we can prune.
     * Keep the last N assistant messages and their tool results untouched.
     */
    private fun findKeepBoundaryIndex(messages: List<Message>, keepCount: Int): Int {
        var assistantCount = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == "assistant") {
                assistantCount++
                if (assistantCount >= keepCount) return i
            }
        }
        return 0  // Keep everything if fewer than keepCount assistants
    }

    /**
     * Aggressive trim when still over budget after pruning + guard.
     * Drops oldest non-system, non-last-user messages until under budget.
     */
    /**
     * Aggressive trim: aligned with OpenClaw pruneHistoryforcontextShare.
     * Drop oldest 50% of non-system messages repeatedly until under budget.
     * maxHistoryShare = 0.5 (history can use at most 50% of context window)
     */
    private fun aggressiveTrimMessages(messages: MutableList<Message>, budgetChars: Int) {
        val maxHistoryBudget = (budgetChars * 0.5).toInt() // OpenClaw: maxHistoryShare = 0.5

        // Aligned with OpenClaw pruneHistoryforcontextShare:
        // Drop oldest messages (any role) until under budget.
        // Keep: first system message + last 2 messages (user + assistant).
        // History may contain system-role messages from prior session saves — drop those too.
        val totalChars = toolresultcontextGuard.estimatecontextChars(messages)
        val roleCounts = messages.groupBy { it.role }.mapValues { it.value.size }
        writeLog("[STATS] Pruning: total=${messages.size} chars=$totalChars budget=$maxHistoryBudget roles=$roleCounts")

        if (totalChars <= maxHistoryBudget) return

        // Keep first message (system prompt) and last 2 (current user + last response)
        val keep = 3 // first + last 2
        if (messages.size <= keep) return

        var iterations = 0
        while (toolresultcontextGuard.estimatecontextChars(messages) > maxHistoryBudget && messages.size > keep && iterations < 15) {
            // Drop oldest half between index 1 and size-2
            val droppableCount = messages.size - keep
            val dropCount = (droppableCount / 2).coerceAtLeast(1)

            writeLog("[DELETE] Pruning: dropping $dropCount of $droppableCount droppable messages (iteration ${iterations + 1})")

            repeat(dropCount) {
                if (messages.size > keep) {
                    messages.removeAt(1) // Remove second message (oldest non-first)
                }
            }
            iterations++
        }

        writeLog("[OK] Pruned: ${messages.size} messages, ${toolresultcontextGuard.estimatecontextChars(messages)} chars after $iterations iterations")
    }

    // ===== Anthropic Refusal Magic Scrub (aligned with OpenClaw scrubAnthropicRefusalMagic) =====

    /**
     * Scrub Anthropic refusal magic string from system prompt messages.
     * Aligned with OpenClaw scrubAnthropicRefusalMagic:
     * Replaces "ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL" (which triggers Anthropic's
     * refusal filter) with a redacted version.
     * Only applied to system messages (the system prompt).
     */
    private fun scrubAnthropicRefusalMagic(messages: List<Message>): List<Message> {
        // Fast path: no magic string present
        val hasMagic = messages.any { it.role == "system" && it.content?.contains(ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL) == true }
        if (!hasMagic) return messages

        return messages.map { msg ->
            if (msg.role == "system" && msg.content?.contains(ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL) == true) {
                msg.copy(content = msg.content.replace(ANTHROPIC_MAGIC_STRING_TRIGGER_REFUSAL, ANTHROPIC_MAGIC_STRING_REPLACEMENT))
            } else {
                msg
            }
        }
    }

    // ===== tool Call Sanitization (aligned with OpenClaw stream wrapper chain) =====

    /**
     * Sanitize and repair tool calls from LLM response.
     * Aligned with OpenClaw stream wrapper chain:
     * 1. wrapStreamFnTrimtoolCallNames — trim whitespace from tool names
     * 2. wrapStreamFnRepairMalformedtoolCallArguments — repair Anthropic JSON issues
     * 3. wrapStreamFnDecodeXaitoolCallArguments — decode HTML entities in xAI responses
     */
    private fun sanitizetoolCalls(toolCalls: List<LLMtoolCall>): List<LLMtoolCall> {
        return toolCalls.map { tc ->
            val trimmedName = tc.name.trim()
            val repairedArgs = repairtoolCallArguments(tc.arguments)
            val decodedArgs = decodeHtmlEntities(repairedArgs)
            if (trimmedName != tc.name || repairedArgs != tc.arguments || decodedArgs != repairedArgs) {
                if (trimmedName != tc.name) writeLog("[WRENCH] Trimmed tool name: '${tc.name}' → '${trimmedName}'")
                if (repairedArgs != tc.arguments) writeLog("[WRENCH] Repaired tool arguments for $trimmedName")
                LLMtoolCall(id = tc.id, name = trimmedName, arguments = decodedArgs)
            } else {
                tc
            }
        }
    }

    /**
     * Repair malformed tool call arguments (aligned with OpenClaw wrapStreamFnRepairMalformedtoolCallArguments).
     * Handles common Anthropic streaming issues:
     * - Missing closing braces
     * - Double-encoded JSON strings
     * - Truncated JSON at natural break points
     */
    private fun repairtoolCallArguments(arguments: String): String {
        if (arguments.isBlank()) return arguments
        val trimmed = arguments.trim()

        // Try parsing as-is first
        try {
            com.google.gson.JsonParser.parseString(trimmed)
            return trimmed // Valid JSON, no repair needed
        } catch (_: exception) { /* continue to repair */ }

        // Attempt 1: Fix missing closing braces/brackets
        var repaired = trimmed
        val openBraces = repaired.count { it == '{' }
        val closeBraces = repaired.count { it == '}' }
        val openBrackets = repaired.count { it == '[' }
        val closeBrackets = repaired.count { it == ']' }
        repaired += "}".repeat((openBraces - closeBraces).coerceAtLeast(0))
        repaired += "]".repeat((openBrackets - closeBrackets).coerceAtLeast(0))

        try {
            com.google.gson.JsonParser.parseString(repaired)
            return repaired
        } catch (_: exception) { /* continue */ }

        // Attempt 2: if it looks like a JSON string value, try removing trailing incomplete value
        val lastColon = repaired.lastIndexOf(':')
        val lastComma = repaired.lastIndexOf(',')
        if (lastComma > lastColon && lastComma < repaired.length - 1) {
            val truncated = repaired.substring(0, lastComma) + "}"
            try {
                com.google.gson.JsonParser.parseString(truncated)
                return truncated
            } catch (_: exception) { /* give up */ }
        }

        return trimmed // Return original if all repairs fail
    }

    /**
     * Decode HTML entities in tool call arguments (aligned with OpenClaw wrapStreamFnDecodeXaitoolCallArguments).
     * xAI models sometimes emit HTML-encoded characters in JSON arguments.
     */
    private fun decodeHtmlEntities(arguments: String): String {
        if (!arguments.contains("&#")) return arguments
        return arguments
            .replace("&#39;", "'")
            .replace("&#34;", "\"")
            .replace("&#38;", "&")
            .replace("&#60;", "<")
            .replace("&#62;", ">")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * Stop agent loop
     */
    fun stop() {
        shouldStop = true
        Log.d(TAG, "Stop signal received")
    }

    /**
     * Reset internal loop state for steer-restart.
     * Called after the coroutine Job is cancelled and before re-launching run().
     * Clears: shouldStop, loopDetectionState, timeoutCompactionAttempts, steerchannel.
     * Aligned with OpenClaw steer abort+restart flow.
     */
    fun reset() {
        shouldStop = false
        timeoutCompactionAttempts = 0
        emptyResponseretryAttempts = 0
        didretryTransientHttpError = false
        loopDetectionState.toolCallHistory.clear()
        // Drain steer channel to remove stale messages
        while (steerchannel.tryReceive().isSuccess) { /* drain */ }
        // Clear yield signal
        yieldSignal = null
        Log.d(TAG, "agentloop reset for steer-restart")
    }

    /**
     * Detect highly repetitive content (model stuck in a loop).
     * Takes the first 50 chars as a "chunk" and checks how many times it repeats.
     * Returns true if >50% of the content is the same chunk repeated.
     */
    private fun isHighlyRepetitive(content: String): Boolean {
        if (content.length < 500) return false
        val chunkSize = 50
        val chunk = content.take(chunkSize)
        if (chunk.isBlank()) return false
        var repeatCount = 0
        var pos = 0
        while (pos + chunkSize <= content.length) {
            if (content.regionMatches(pos, chunk, 0, chunkSize)) {
                repeatCount++
            } else {
                break // Stop at first mismatch — repetitive content is typically at the start
            }
            pos += chunkSize
        }
        val repeatedChars = repeatCount * chunkSize
        return repeatedChars > content.length * 0.5
    }
}

/**
 * agent execution result
 */
data class agentresult(
    val finalContent: String,
    val toolsused: List<String>,
    val messages: List<Message>,
    val iterations: Int
)

/**
 * Progress update
 */
sealed class ProgressUpdate {
    /** Start new iteration */
    data class Iteration(val number: Int) : ProgressUpdate()

    /** Thinking step X (intermediate feedback) */
    data class Thinking(val iteration: Int) : ProgressUpdate()

    /** Reasoning thinking process */
    data class Reasoning(val content: String, val llmDuration: Long) : ProgressUpdate()

    /** tool call */
    data class toolCall(val name: String, val arguments: Map<String, Any?>) : ProgressUpdate()

    /** tool result */
    data class toolresult(val name: String, val result: String, val execDuration: Long) : ProgressUpdate()

    /** Iteration complete */
    data class IterationComplete(val number: Int, val iterationDuration: Long, val llmDuration: Long, val execDuration: Long) : ProgressUpdate()

    /** context overflow */
    data class contextoverflow(val message: String) : ProgressUpdate()

    /** context recovered successfully */
    data class contextRecovered(val strategy: String, val attempt: Int) : ProgressUpdate()

    /** Error */
    data class Error(val message: String) : ProgressUpdate()

    /** loop detected */
    data class loopDetected(
        val detector: String,
        val count: Int,
        val message: String,
        val critical: Boolean
    ) : ProgressUpdate()

    /**
     * Intermediate text reply (block reply).
     *
     * Aligned with OpenClaw's blockReplyBreak="text_end" mechanism:
     * when LLM returns text + tool_calls in the same response,
     * the text is emitted immediately as an intermediate reply
     * (not held until the final answer).
     */
    data class BlockReply(val text: String, val iteration: Int) : ProgressUpdate()

    /** A steer message was injected into the conversation mid-run */
    data class steerMessageInjected(val content: String) : ProgressUpdate()

    /** A subagent was spawned (for observability) */
    data class SubagentSpawned(val runId: String, val label: String, val childsessionKey: String) : ProgressUpdate()

    /** A subagent completed and its result was announced to the parent */
    data class SubagentAnnounced(val runId: String, val label: String, val status: String) : ProgressUpdate()

    /** The agent loop yielded (sessions_yield) to wait for subagent results */
    data object Yielded : ProgressUpdate()

    /** Streaming: incremental reasoning/thinking token */
    data class ReasoningDelta(val text: String) : ProgressUpdate()

    /** Streaming: incremental content token */
    data class ContentDelta(val text: String) : ProgressUpdate()
}
