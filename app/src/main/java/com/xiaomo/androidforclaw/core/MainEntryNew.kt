package com.xiaomo.androidforclaw.core

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/agent-command.ts, ../openclaw/src/gateway/boot.ts
 */


import android.app.Application
import java.util.concurrent.ConcurrentHashMap
import android.os.Build
import android.text.TextUtils
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.context.contextBuilder
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.loop.ProgressUpdate
import com.xiaomo.androidforclaw.agent.session.sessionmanager
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import kotlinx.coroutines.flow.asSharedFlow
import com.xiaomo.androidforclaw.ext.mmkv
import com.xiaomo.androidforclaw.ext.simpleSafeLaunch
import com.xiaomo.androidforclaw.providers.llm.tonewMessage
import com.xiaomo.androidforclaw.providers.llm.toLegacyMessage
import com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderservice
import com.xiaomo.androidforclaw.util.LayoutexceptionLogger
import com.xiaomo.androidforclaw.util.MMKVKeys
import com.xiaomo.androidforclaw.util.WakeLockmanager
import com.xiaomo.androidforclaw.util.ReasoningTagFilter
import com.xiaomo.androidforclaw.ui.float.sessionFloatWindow
import com.xiaomo.androidforclaw.Buildconfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import android.os.Environment
import java.io.File
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * new MainEntry - Refactored version based on Nanobot architecture
 *
 * Core changes:
 * 1. use agentloop instead of fixed process
 * 2. use LLM provider (Claude Opus 4.6 + Reasoning)
 * 3. toolize all operations
 * 4. Dynamic decision-making instead of hardcoded flow
 */
object MainEntrynew {
    private const val TAG = "MainEntrynew"
    const val ACTION_AGENT_PROGRESS = "com.xiaomo.androidforclaw.ACTION_AGENT_PROGRESS"
    const val EXTRA_PROGRESS_TYPE = "type"
    const val EXTRA_PROGRESS_TITLE = "title"
    const val EXTRA_PROGRESS_CONTENT = "content"

    // ================ Core Components ================
    private lateinit var application: Application
    private lateinit var toolRegistry: toolRegistry
    private lateinit var androidtoolRegistry: androidtoolRegistry
    private lateinit var agentloop: agentloop
    private lateinit var contextBuilder: contextBuilder
    private lateinit var sessionmanager: sessionmanager
    private lateinit var configLoader: configLoader
    private var subagentSpawner: com.xiaomo.androidforclaw.agent.subagent.SubagentSpawner? = null

    // ================ State Management ================
    var user: String = ""
    private var currentTaskId: String? = null
    private var currentDocId: String? = null
    private val sessionJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var activesessionId: String? = null  // for block reply broadcasting
    @Volatile
    private var lastBlockReplyText: String? = null  // Track last sent block reply to avoid duplicate final
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskDatamanager: TaskDatamanager = TaskDatamanager.getInstance()

    // Document sync completion state
    private val _docSyncFinished = MutableStateFlow(false)
    val docSyncFinished = _docSyncFinished.asStateFlow()

    // Test summary completion state
    private val _summaryFinished = MutableStateFlow(false)
    val summaryFinished = _summaryFinished.asStateFlow()

    data class UiProgressEvent(
        val type: String,
        val title: String,
        val content: String
    )

    private val _uiProgressFlow = MutableSharedFlow<UiProgressEvent>(extraBufferCapacity = 64)
    val uiProgressFlow: SharedFlow<UiProgressEvent> = _uiProgressFlow

    /**
     * Get sessionmanager (for Gateway use)
     */
    fun getsessionmanager(): sessionmanager? {
        return if (::sessionmanager.isInitialized) sessionmanager else null
    }

    /**
     * Get toolRegistry (for registering extension tools like feishu)
     */
    fun gettoolRegistry(): toolRegistry? {
        return if (::toolRegistry.isInitialized) toolRegistry else null
    }

    /**
     * Initialize - must be called before use
     */
    fun initialize(app: Application) {
        // use sessionmanager (the last critical component) as the initialization gate,
        // not application. Prevents partial-init → early-return → permanent null state.
        if (::sessionmanager.isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        Log.d(TAG, "Initializing MainEntrynew...")

        try {
            application = app
            // 0. Initialize configLoader
            configLoader = configLoader(application)
            Log.d(TAG, "✓ configLoader initialized")

            // 1. Initialize LLM provider (unified provider - supports all OpenClaw-compatible APIs)
            val llmprovider = com.xiaomo.androidforclaw.providers.UnifiedLLMprovider(application)
            Log.d(TAG, "✓ UnifiedLLMprovider initialized (supports multi-model APIs)")

            // 2. Initialize toolRegistry (universal tools - from Pi Coding agent)
            toolRegistry = toolRegistry(
                context = application,
                taskDatamanager = taskDatamanager
            )
            Log.d(TAG, "✓ toolRegistry initialized (${toolRegistry.gettoolCount()} universal tools)")

            // 3. Initialize Memorymanager (memory management + hybrid search index)
            val workspacePath = StoragePaths.workspace.absolutePath
            val openClawCfg = configLoader.loadOpenClawconfig()
            val embeingproviders = openClawCfg.resolveproviders()
            // Try to find an OpenAI-compatible provider for embeings
            val embeingBaseUrl = embeingproviders.values.firstorNull()?.baseUrl ?: ""
            val embeingApiKey = embeingproviders.values.firstorNull()?.apiKey ?: ""
            val memorymanager = com.xiaomo.androidforclaw.agent.memory.Memorymanager(
                workspacePath = workspacePath,
                context = application,
                embeingBaseUrl = embeingBaseUrl,
                embeingApiKey = embeingApiKey
            )

            // 4. Initialize androidtoolRegistry (android platform tools)
            androidtoolRegistry = androidtoolRegistry(
                context = application,
                taskDatamanager = taskDatamanager,
                memorymanager = memorymanager,
                workspacePath = workspacePath,
                cameraCapturemanager = MyApplication.getCameraCapturemanager(),
            )
            Log.d(TAG, "✓ androidtoolRegistry initialized (${androidtoolRegistry.gettoolCount()} android tools)")

            // 5. Initialize context builder (OpenClaw style)
            contextBuilder = contextBuilder(
                context = application,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                configLoader = configLoader
            )
            Log.d(TAG, "✓ contextBuilder initialized")

            // 5. Initialize session manager (use workspace directory, aligned with OpenClaw)
            val workspaceDir = com.xiaomo.androidforclaw.workspace.StoragePaths.workspace.also {
                it.mkdirs()
                Log.d(TAG, "Workspace: ${it.absolutePath}")
            }
            sessionmanager = sessionmanager(
                workspace = workspaceDir
            )
            Log.d(TAG, "✓ sessionmanager initialized (workspace: ${workspaceDir.absolutePath})")

            // 6. Initialize context manager (OpenClaw-aligned context overflow handling)
            val contextmanager = com.xiaomo.androidforclaw.agent.context.contextmanager(llmprovider)
            Log.d(TAG, "✓ contextmanager initialized")

            // Load maxIterations from config
            val config = configLoader.loadOpenClawconfig()
            val maxIterations = config.agent.maxIterations

            // 7. Initialize agentloop
            agentloop = agentloop(
                llmprovider = llmprovider,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                contextmanager = contextmanager,
                maxIterations = maxIterations,
                modelRef = null,  // use default model
                configLoader = configLoader  // Gap 2: context window resolution
            )
            Log.d(TAG, "✓ agentloop initialized (maxIterations: $maxIterations)")

            // 8. Initialize Subagent system (aligned with OpenClaw subagent-spawn.ts)
            val subagentsconfig = config.agents?.defaults?.subagents
                ?: com.xiaomo.androidforclaw.config.Subagentsconfig()
            if (subagentsconfig.enabled) {
                val registryStore = com.xiaomo.androidforclaw.agent.subagent.SubagentRegistryStore()
                val registry = com.xiaomo.androidforclaw.agent.subagent.SubagentRegistry(registryStore)
                registry.restorefromDisk()
                val spawner = com.xiaomo.androidforclaw.agent.subagent.SubagentSpawner(
                    registry = registry,
                    configLoader = configLoader,
                    llmprovider = llmprovider,
                    toolRegistry = toolRegistry,
                    androidtoolRegistry = androidtoolRegistry,
                )
                subagentSpawner = spawner

                // Wire subagent tools into main agentloop (depth=0)
                val subagenttools = com.xiaomo.androidforclaw.agent.subagent.SubagentSpawner.buildSubagenttools(
                    spawner = spawner,
                    parentsessionKey = "agent:main:main",
                    parentagentloop = agentloop,
                    parentDepth = 0,
                    configLoader = configLoader,
                )
                agentloop.extratools = subagenttools
                Log.d(TAG, "✓ Subagent system initialized (${subagenttools.size} tools)")
            } else {
                Log.d(TAG, "⏭ Subagent system disabled in config")
            }

            Log.d(TAG, "========== Initialization Complete ==========")

        } catch (e: exception) {
            Log.e(TAG, "Initialization failed", e)
            throw Runtimeexception("Failed to initialize MainEntrynew", e)
        }
    }

    // registerAlltools() removed
    // tools are now divided into:
    // - toolRegistry: Universal tools (read, write, exec, web_fetch)
    // - androidtoolRegistry: android platform tools (tap, screenshot, open_app)

    /**
     * Run agent with session management - Supports multi-turn conversations
     */
    fun runwithsession(
        userInput: String,
        sessionId: String?,
        application: Application
    ) {
        // Ensure initialized
        if (!::agentloop.isInitialized) {
            initialize(application)
        }

        val effectivesessionId = sessionId ?: "default"
        activesessionId = effectivesessionId  // Set for block reply broadcasting
        lastBlockReplyText = null  // Reset block reply tracking
        Log.d(TAG, "🆔 [session] session ID: $effectivesessionId")

        // Get or create session
        val session = sessionmanager.getorCreate(effectivesessionId)
        Log.d(TAG, "[CLIP] [session] History message count: ${session.messageCount()}")

        // Get history messages (recent 20) and convert to new format
        // Aligned with OpenClaw: limitHistoryTurns (by user turn count)
        // 1. Fetch all session messages
        // 2. Apply limitHistoryTurns with configurable dmHistoryLimit
        // 3. context pruning in agentloop handles the rest
        // Aligned with OpenClaw: getHistoryLimitfromsessionKey → limitHistoryTurns
        // 1. Read dmHistoryLimit from config (per-channel, per-user)
        // 2. if not configured → no truncation (undefined → limitHistoryTurns returns all)
        // 3. agentloop's context pruning (soft trim / hard clear) handles oversized context
        val dmHistoryLimit: Int? = try {
            val openClawconfig = configLoader?.loadOpenClawconfig()
            // Check channels.feishu.dmHistoryLimit (or channels.android.dmHistoryLimit)
            openClawconfig?.channels?.feishu?.dmHistoryLimit
        } catch (_: exception) { null }

        // Aligned with OpenClaw: if dmHistoryLimit not configured, send all history
        // agentloop's context pruning (pruneHistoryforcontextShare-aligned) handles oversized context
        val allMessages = if (dmHistoryLimit != null && dmHistoryLimit > 0) {
            val raw = session.getRecentMessages(dmHistoryLimit * 4).map { it.tonewMessage() }
            com.xiaomo.androidforclaw.agent.session.HistorySanitizer
                .limitHistoryTurns(raw.toMutableList(), dmHistoryLimit)
        } else {
            session.getRecentMessages(session.messages.size).map { it.tonewMessage() }
        }
        val contextHistory = allMessages
        Log.d(TAG, "[RECV] [History] total=${session.messages.size} raw=${allMessages.size} → context=${contextHistory.size} (dmHistoryLimit=${dmHistoryLimit ?: "unlimited"})")
        Log.d(TAG, "[RECV] [session] Loaded context: ${contextHistory.size} messages")

        if (TextUtils.isEmpty(user)) {
            user = Build.MODEL
        }

        // cancel previous run for THE SAME session only (prevents stuck state).
        // Different sessions are independent and must not cancel each other.
        sessionJobs[effectivesessionId]?.let { oldJob ->
            if (oldJob.isActive) {
                Log.w(TAG, "🛑 [session] cancelling previous run for session $effectivesessionId")
                // Only cancel the coroutine job — do NOT call agentloop.stop() here,
                // because agentloop is shared and stop() would kill ALL sessions' loops.
                oldJob.cancel()
            }
        }

        // Start coroutine execution (without showing floating window)
        val job = scope.simpleSafeLaunch(
            {
                Log.d(TAG, "========== agent session Execution Start ==========")
                Log.d(TAG, "🆔 session ID: $effectivesessionId")
                Log.d(TAG, "[CHAT] user input: $userInput")
                Log.d(TAG, "[CLIP] context messages: ${contextHistory.size}")

                // 1. Build system prompt
                Log.d(TAG, "[CHAT] Building system prompt...")
                val systemPrompt = contextBuilder.buildSystemPrompt(
                    userGoal = userInput,
                    packageName = "",
                    testMode = "chat"
                    // use default FULL mode to align with OpenClaw
                )
                Log.d(TAG, "[OK] System prompt built (${systemPrompt.length} chars)")

                // 2. Broadcast user message
                Log.d(TAG, "[SEND] [Broadcast] Broadcasting user message...")
                com.xiaomo.androidforclaw.gateway.GatewayServer.broadcastChatMessage(
                    effectivesessionId, "user", userInput
                )

                // 3. Start progress listening
                val progressJob = launch {
                    agentloop.progressFlow.collect { update ->
                        handleProgressUpdate(update)
                    }
                }

                // 4. Run agentloop (with context history)
                val result = agentloop.run(
                    systemPrompt = systemPrompt,
                    userMessage = userInput,
                    contextHistory = contextHistory,
                    reasoningEnabled = true  // Reasoning enabled by default
                )

                val cleanFinalContent = com.xiaomo.androidforclaw.util.ReplyTagFilter.strip(
                    ReasoningTagFilter.stripReasoningTags(result.finalContent)
                )
                Log.d(TAG, "========== agentloop Complete ==========")
                Log.d(TAG, "Iterations: ${result.iterations}")
                Log.d(TAG, "Final result: ${cleanFinalContent}")

                // 5. Broadcast AI response (skip if already sent via block reply)
                if (cleanFinalContent.isnotEmpty()) {
                    // Update floating window with latest AI response
                    com.xiaomo.androidforclaw.ui.float.sessionFloatWindow.updateLatestMessage(cleanFinalContent)

                    if (lastBlockReplyText?.trim() == cleanFinalContent.trim()) {
                        Log.d(TAG, "[OK] Final content matches last block reply, skipping broadcast")
                    } else {
                        Log.d(TAG, "[SEND] [Broadcast] Broadcasting AI response...")
                        com.xiaomo.androidforclaw.gateway.GatewayServer.broadcastChatMessage(
                            effectivesessionId, "assistant", cleanFinalContent
                        )
                    }
                }
                lastBlockReplyText = null  // Reset for next run

                // 6. Save messages to session (convert back to legacy format)
                Log.d(TAG, "[SAVE] [session] Saving messages to session...")
                result.messages.forEach { message ->
                    val sanitizedMessage = if (message.role == "assistant") {
                        message.copy(content = ReasoningTagFilter.stripReasoningTags(message.content))
                    } else message
                    session.aMessage(sanitizedMessage.toLegacyMessage())
                }
                sessionmanager.save(session)
                Log.d(TAG, "[OK] [session] session saved, total messages: ${session.messageCount()}")

                // cancel progress listening
                progressJob.cancel()

            },
            { exception ->
                Log.e(TAG, "[ERROR] agent session execution failed", exception)

                // Build友okErrorMessage
                val errorMessage = buildString {
                    append("[ERROR] execution出wrong:\n\n")
                    append("**Error**: ${exception.message}\n\n")

                    // ifYes LLM exception, Amore详细Info
                    if (exception is com.xiaomo.androidforclaw.providers.LLMexception) {
                        append("**Type**: API callFailed\n")
                        append("**suggest**: pleaseCheck模型configand API key\n\n")
                    }

                    // AHeapStacktrack (Front500characters)
                    append("**HeapStacktrack**:\n```\n")
                    append(exception.stackTraceToString().take(500))
                    append("\n```")
                }

                // BroadcastErrorMessagetoChat界面
                try {
                    com.xiaomo.androidforclaw.gateway.GatewayServer.broadcastChatMessage(
                        effectivesessionId, "assistant", errorMessage
                    )
                    Log.d(TAG, "[SEND] [Broadcast] Error message sent to user")
                } catch (e: exception) {
                    Log.e(TAG, "Failed to broadcast error message", e)
                }

                // SaveErrorto session
                try {
                    session.aMessage(com.xiaomo.androidforclaw.providers.LegacyMessage(
                        role = "assistant",
                        content = errorMessage
                    ))
                    sessionmanager.save(session)
                    Log.d(TAG, "[SAVE] [session] Error saved to session")
                } catch (e: exception) {
                    Log.e(TAG, "Failed to save error to session", e)
                }
            }
        )
        sessionJobs[effectivesessionId] = job
        job.invokeOnCompletion { sessionJobs.remove(effectivesessionId) }
    }

    /**
     * Run test task - new architecture version
     */
    fun run(
        userInput: String,
        application: Application,
        existingRecordId: String? = null,
        existingPackageName: String? = null,
        onSummaryFinished: (() -> Job)? = null
    ) {
        // EnsurealreadyInitialize
        if (!::agentloop.isInitialized) {
            initialize(application)
        }

        // ResetStatus
        _summaryFinished.value = false

        if (TextUtils.isEmpty(user)) {
            user = Build.MODEL
        }

        // 先returnto桌面
        safePressHome()

        // CreatenewTask
        val newTaskId = generateTaskId()
        taskDatamanager.startnewTask(newTaskId, existingPackageName ?: "")
        currentTaskId = newTaskId
        Log.d(TAG, "========== newTestTask: $newTaskId ==========")

        // Read mode from openclaw.json instead of MMKV
        val openClawconfig = configLoader.loadOpenClawconfig()
        val testMode = openClawconfig.agent.mode
        Log.d(TAG, "Testschema: $testMode (from openclaw.json)")

        // Set new task as running
        val newTaskData = taskDatamanager.getCurrentTaskData()
        newTaskData?.setIsRunning(true)

        // Acquire screen wake lock
        WakeLockmanager.acquireScreenWakeLock()
        Log.d(TAG, "alreadyGetScreenWakeLock")

        // cancel previous local task only
        val localsessionKey = "__local__"
        sessionJobs[localsessionKey]?.let { oldJob ->
            if (oldJob.isActive) {
                Log.w(TAG, "🛑 cancelling previous local task")
                oldJob.cancel()
            }
        }

        // Start coroutine to execute test
        Log.d(TAG, "[START] About to start coroutine for test task...")
        val job = scope.simpleSafeLaunch(
            {
                Log.d(TAG, "[OK] Coroutine started, executing test task...")

                // 1. Build system prompt
                Log.d(TAG, "[CHAT] Step 1: Building system prompt...")
                val packageName = existingPackageName ?: ""
                val systemPrompt = contextBuilder.buildSystemPrompt(
                    userGoal = userInput,
                    packageName = packageName,
                    testMode = testMode
                    // use default FULL mode to align with OpenClaw
                )

                Log.d(TAG, "[OK] System prompt built (${systemPrompt.length} chars)")
                Log.d(TAG, "[OK] Estimated Tokens: ~${systemPrompt.length / 4}")

                // Print skills statistics
                val skillsStats = contextBuilder.getskillsStatistics()
                if (skillsStats.isnotEmpty()) {
                    Log.d(TAG, "[STATS] skills statistics:")
                    skillsStats.lines().forEach { line ->
                        Log.d(TAG, "   $line")
                    }
                }

                // 2. Listen to agentloop progress (listen before start)
                Log.d(TAG, "👂 Step 2: Starting progress listening...")
                val progressJob = launch {
                    Log.d(TAG, "[OK] Progress listening coroutine started")
                    agentloop.progressFlow.collect { update ->
                        Log.d(TAG, "[RECV] Received progress update: ${update.javaClass.simpleName}")
                        handleProgressUpdate(update)
                    }
                }
                Log.d(TAG, "[OK] Progress listening set up")

                // 3. Run agentloop
                Log.d(TAG, "========== Starting agentloop ==========")
                Log.d(TAG, "System prompt length: ${systemPrompt.length}")
                Log.d(TAG, "user input: $userInput")
                Log.d(TAG, "Universal tools: ${toolRegistry.gettoolCount()}")
                Log.d(TAG, "android tools: ${androidtoolRegistry.gettoolCount()}")

                val result = agentloop.run(
                    systemPrompt = systemPrompt,
                    userMessage = userInput,
                    reasoningEnabled = true
                )

                Log.d(TAG, "========== agentloop Complete ==========")
                Log.d(TAG, "Iterations: ${result.iterations}")
                Log.d(TAG, "tools used: ${result.toolsused.joinToString(", ")}")
                Log.d(TAG, "Final result: ${result.finalContent}")

                // 4. Release resources
                WakeLockmanager.releaseScreenWakeLock()
                _summaryFinished.value = true
                onSummaryFinished?.invoke()

                Log.d(TAG, "TestTaskexecutionComplete")

            },
            { error ->
                Log.e(TAG, "TestTaskexecutionFailed", error)
                LayoutexceptionLogger.log("MainEntrynew#run", error)

                // Release resources
                WakeLockmanager.releaseScreenWakeLock()

                _summaryFinished.value = true
            }
        )
        sessionJobs[localsessionKey] = job
        job.invokeOnCompletion { sessionJobs.remove(localsessionKey) }
    }

    private fun emitProgressToUi(type: String, title: String, content: String) {
        _uiProgressFlow.tryEmit(UiProgressEvent(type, title, content))
    }

    /**
     * Handle progress update - Only update floating window display
     */
    private suspend fun handleProgressUpdate(update: ProgressUpdate) {
        Log.d(TAG, "handleProgressUpdate called: ${update.javaClass.simpleName}")
        when (update) {
            is ProgressUpdate.Iteration -> {
                Log.d(TAG, ">>> Iteration ${update.number}")
                sessionFloatWindow.updatesessionInfo(
                    title = "iteration ${update.number}",
                    content = "currentlythink..."
                )
            }

            is ProgressUpdate.Thinking -> {
                Log.d(TAG, "💭 Thinking: currentlyProcess第 ${update.iteration} step...")
                sessionFloatWindow.updatesessionInfo(
                    title = "currentlythink",
                    content = "currentlyProcess第 ${update.iteration} step..."
                )
                emitProgressToUi("thinking", "currentlythink", "currentlyProcess第 ${update.iteration} step...")
            }

            is ProgressUpdate.Reasoning -> {
                Log.d(TAG, "[BRAIN] Reasoning (${update.content.length} chars, ${update.llmDuration}ms)")
                sessionFloatWindow.updatesessionInfo(
                    title = "thinkComplete",
                    content = update.content.take(100) + if (update.content.length > 100) "..." else ""
                )
            }

            is ProgressUpdate.toolCall -> {
                Log.d(TAG, "[WRENCH] tool: ${update.name}")

                val argsText = if (update.arguments.isEmpty()) {
                    "NoneParameters"
                } else {
                    update.arguments.entries.joinToString("\n") { (key, value) ->
                        "  • $key: $value"
                    }
                }

                sessionFloatWindow.updatesessionInfo(
                    title = "execution: ${update.name}",
                    content = argsText.take(100)
                )
                emitProgressToUi("tool_call", "execution: ${update.name}", argsText)
            }

            is ProgressUpdate.toolresult -> {
                Log.d(TAG, "[OK] result: ${update.result.take(100)}, ${update.execDuration}ms")
                sessionFloatWindow.updatesessionInfo(
                    title = "executionComplete",
                    content = update.result.take(100) + if (update.result.length > 100) "..." else ""
                )
                emitProgressToUi("tool_result", "executionComplete", update.result)
            }

            is ProgressUpdate.IterationComplete -> {
                Log.d(TAG, "[FLAG] Iteration ${update.number} complete: total=${update.iterationDuration}ms, llm=${update.llmDuration}ms, exec=${update.execDuration}ms")
                sessionFloatWindow.updatesessionInfo(
                    title = "iteration ${update.number} Complete",
                    content = "time spent: ${update.iterationDuration}ms"
                )
            }

            is ProgressUpdate.contextoverflow -> {
                Log.w(TAG, "[SYNC] context overflow: ${update.message}")
                sessionFloatWindow.updatesessionInfo(
                    title = "context超限",
                    content = update.message
                )
            }

            is ProgressUpdate.contextRecovered -> {
                Log.d(TAG, "[OK] context recovered: ${update.strategy} (attempt ${update.attempt})")
                sessionFloatWindow.updatesessionInfo(
                    title = "contextalreadyresume",
                    content = "Policy: ${update.strategy}"
                )
            }

            is ProgressUpdate.loopDetected -> {
                val logLevel = if (update.critical) "🚨" else "[WARN]"
                Log.w(TAG, "$logLevel loop detected: ${update.detector} (count: ${update.count})")
                sessionFloatWindow.updatesessionInfo(
                    title = "${if (update.critical) "严重" else "Warning"}: loop检测",
                    content = "${update.detector}: ${update.count} times"
                )
            }

            is ProgressUpdate.Error -> {
                Log.e(TAG, "[ERROR] Error: ${update.message}")
                sessionFloatWindow.updatesessionInfo(
                    title = "Error",
                    content = update.message.take(100)
                )
                emitProgressToUi("error", "Error", update.message)
            }

            is ProgressUpdate.BlockReply -> {
                Log.d(TAG, "[SEND] Block reply: ${update.text.take(200)}")
                sessionFloatWindow.updatesessionInfo(
                    title = "中间return复",
                    content = update.text.take(100) + if (update.text.length > 100) "..." else ""
                )
                emitProgressToUi("block_reply", "中间return复", update.text)
                // for Gateway WebUI sessions, broadcast intermediate text immediately
                lastBlockReplyText = update.text
                activesessionId?.let { sessionId ->
                    com.xiaomo.androidforclaw.gateway.GatewayServer.broadcastChatMessage(
                        sessionId, "assistant", update.text
                    )
                }
            }

            is ProgressUpdate.steerMessageInjected -> {
                Log.d(TAG, "[TARGET] steer message injected: ${update.content.take(100)}")
                sessionFloatWindow.updatesessionInfo(
                    title = "Message注入",
                    content = update.content.take(100) + if (update.content.length > 100) "..." else ""
                )
            }

            is ProgressUpdate.SubagentSpawned -> {
                Log.i(TAG, "[START] Subagent spawned: ${update.label} (${update.runId})")
                sessionFloatWindow.updatesessionInfo(
                    title = "子ProxyalreadyStart",
                    content = update.label
                )
                emitProgressToUi("subagent_spawned", "子ProxyalreadyStart", update.label)
            }

            is ProgressUpdate.SubagentAnnounced -> {
                Log.i(TAG, "📣 Subagent announced: ${update.label} status=${update.status} (${update.runId})")
                sessionFloatWindow.updatesessionInfo(
                    title = "子ProxyComplete",
                    content = "${update.label}: ${update.status}"
                )
                emitProgressToUi("subagent_announced", "子ProxyComplete", "${update.label}: ${update.status}")
            }

            is ProgressUpdate.Yielded -> {
                Log.i(TAG, "[PAUSE] agent loop yielded, waiting for subagent results")
                sessionFloatWindow.updatesessionInfo(
                    title = "Wait子Proxy",
                    content = "alreadyPause, Wait子Proxyresult..."
                )
                emitProgressToUi("yielded", "Wait子Proxy", "alreadyPause, Wait子Proxyresult...")
            }

            is ProgressUpdate.ReasoningDelta -> {
                // 流式增量 reasoning — notUpdate float window(太频繁)
            }

            is ProgressUpdate.ContentDelta -> {
                // 流式增量 content — notUpdate float window(太频繁)
            }
        }
    }


    /**
     * cancel current task
     */
    fun cancelCurrentJob(isRunning: Boolean) {
        Log.d(TAG, "cancelCurrentJob")

        WakeLockmanager.releaseScreenWakeLock()

        currentTaskId = null
        taskDatamanager.clearCurrentTask()
        // cancel all session jobs
        sessionJobs.forEach { (id, j) ->
            Log.d(TAG, "cancelling job for session: $id")
            j.cancel()
        }
        sessionJobs.clear()

        val currentTaskData = taskDatamanager.getCurrentTaskData()
        currentTaskData?.setIsRunning(isRunning)

        _summaryFinished.value = true

        // Stop agentloop
        if (::agentloop.isInitialized) {
            agentloop.stop()
        }
    }

    /**
     * cancel current task without clearing TaskData
     */
    private fun cancelCurrentJobwithoutClearingTaskData() {
        Log.d(TAG, "cancelCurrentJobwithoutClearingTaskData")

        WakeLockmanager.releaseScreenWakeLock()
        // cancel local task only
        sessionJobs["__local__"]?.cancel()
        sessionJobs.remove("__local__")

        val currentTaskData = taskDatamanager.getCurrentTaskData()
        currentTaskData?.setIsRunning(false)
    }

    // ================ helper Methods ================

    private fun generateTaskId(): String {
        return "task_${System.currentTimeMillis()}"
    }

    private fun safePressHome() {
        try {
            AccessibilityBinderservice.serviceInstance?.pressHomebutton()
        } catch (e: exception) {
            LayoutexceptionLogger.log("MainEntrynew#safePressHome", e)
        }
    }
}
