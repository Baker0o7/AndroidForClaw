/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/boot.ts, server-methods.ts
 *
 * androidforClaw adaptation: gateway server and RPC methods.
 */
package com.xiaomo.androidforclaw.gateway

import android.content.context
import com.xiaomo.androidforclaw.agent.context.contextBuilder
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.session.sessionmanager
import com.xiaomo.androidforclaw.gateway.methods.agentMethods
import com.xiaomo.androidforclaw.gateway.methods.HealthMethods
import com.xiaomo.androidforclaw.gateway.methods.sessionMethods
import com.xiaomo.androidforclaw.gateway.methods.modelsMethods
import com.xiaomo.androidforclaw.gateway.methods.toolsMethods
import com.xiaomo.androidforclaw.gateway.methods.skillsMethods
import com.xiaomo.androidforclaw.gateway.methods.configMethods
import com.xiaomo.androidforclaw.gateway.methods.TalkMethods
import com.xiaomo.androidforclaw.gateway.methods.CronMethods
import com.xiaomo.androidforclaw.agent.skills.skillsLoader
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.gateway.protocol.agentParams
import com.xiaomo.androidforclaw.gateway.protocol.agentWaitParams
import com.xiaomo.androidforclaw.gateway.protocol.EventFrame
import com.xiaomo.androidforclaw.gateway.security.TokenAuth
import com.xiaomo.androidforclaw.gateway.websocket.GatewayWebSocketServer
import fi.iki.elonen.NanoHTTPD
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.llm.tonewMessage
import com.xiaomo.androidforclaw.agent.loop.ProgressUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.util.SPhelper
import java.io.IOexception
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Main Gateway controller that integrates all components:
 * - WebSocket RPC server (Protocol v3)
 * - agent methods
 * - session methods
 * - Health methods
 * - Token authentication
 *
 * Aligned with OpenClaw Gateway architecture
 */
class GatewayController(
    private val context: context,
    private val agentloop: agentloop,
    private val sessionmanager: sessionmanager,
    private val toolRegistry: toolRegistry,
    private val androidtoolRegistry: androidtoolRegistry,
    private val skillsLoader: skillsLoader,
    private val port: Int = 8765,
    private val authToken: String? = null
) {
    private val TAG = "GatewayController"

    // contextBuilder for full system prompt (SOUL.md, AGENTS.md, skills, etc.)
    private val contextBuilder: contextBuilder by lazy {
        contextBuilder(
            context = context,
            toolRegistry = toolRegistry,
            androidtoolRegistry = androidtoolRegistry
        )
    }
    private companion object {
        private const val PREF_THINKING_LEVEL = "chat_thinking_level"
    }
    private var server: GatewayWebSocketServer? = null
    private var tokenAuth: TokenAuth? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Active agent runs: runId -> coroutine Job (for abort support)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    // Per-session agentloop instances (so sessions don't share shouldStop flag)
    private val sessionagentloops = ConcurrentHashMap<String, agentloop>()
    // Map runId -> sessionKey for abort routing
    private val runTosession = ConcurrentHashMap<String, String>()

    private lateinit var agentMethods: agentMethods
    private lateinit var sessionMethods: sessionMethods
    private lateinit var healthMethods: HealthMethods
    private lateinit var modelsMethods: modelsMethods
    private lateinit var toolsMethods: toolsMethods
    private lateinit var skillsMethods: skillsMethods
    private lateinit var configMethods: configMethods
    private lateinit var talkMethods: TalkMethods

    var isRunning = false
        private set

    /** 本地ProcessinsideEventreceive器, by LocalGatewaychannel Register, bypass WebSocket 直receive取Event.  */
    @Volatile var localEventSink: ((event: String, payloadJson: String) -> Unit)? = null

    /** BroadcastEvent: at the same time发给 WebSocket Clientand本地 channel.  */
    private fun broadcastEvent(frame: EventFrame) {
        server?.broadcast(frame)
        localEventSink?.let { sink ->
            try {
                val payloadJson = com.google.gson.Gson().toJson(frame.payload)
                sink(frame.event, payloadJson)
            } catch (_: Throwable) { /* SerializeFailedIgnore */ }
        }
    }

    /**
     * Start the Gateway WebSocket server
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG,"Gateway already running")
            return
        }

        try {
            // Initialize token auth if configured
            if (authToken != null) {
                tokenAuth = TokenAuth(authToken)
                Log.i(TAG,"Token authentication enabled")
            } else {
                Log.w(TAG,"Token authentication disabled - running in insecure mode")
            }

            // Create WebSocket server
            server = GatewayWebSocketServer(
                context = context,
                port = port,
                tokenAuth = tokenAuth
            ).app {
                // Initialize method handlers
                agentMethods = agentMethods(context, agentloop, sessionmanager, this, activeJobs)
                sessionMethods = sessionMethods(sessionmanager)
                healthMethods = HealthMethods()
                modelsMethods = modelsMethods(context)
                toolsMethods = toolsMethods(toolRegistry, androidtoolRegistry)
                skillsMethods = skillsMethods(context)
                configMethods = configMethods(context)
                talkMethods = TalkMethods.getInstance(context)
                talkMethods.init()

                // ── OpenClaw loopback handshake ───────────────────────────
                // Client (OpenClaw android) sends "connect" after receiving
                // the "connect.challenge" event.  We respond with server info.
                registerMethod("connect") { _ ->
                    mapOf(
                        "server" to mapOf("host" to "androidforClaw"),
                        "auth" to mapOf("deviceToken" to null),
                        "canvasHostUrl" to null,
                        "snapshot" to mapOf(
                            "sessionDefaults" to mapOf("mainsessionKey" to "main")
                        )
                    )
                }

                // ── OpenClaw chat protocol ─────────────────────────────────
                // chat.send: run agent asynchronously, stream "agent" events,
                // finish with a "chat" final event.
                registerMethod("chat.send") { params ->
                    @Suppress("UNCHECKED_CAST")
                    val p = params as? Map<String, Any?> ?: emptyMap()
                    val sessionKey = p["sessionKey"] as? String ?: "default"
                    val userMsg = p["message"] as? String ?: ""
                    val thinking = p["thinking"] as? String ?: "off"
                    SPhelper.getInstance(context).saveData(PREF_THINKING_LEVEL, thinking)
                    val reasoningEnabled = thinking != "off"
                    @Suppress("UNCHECKED_CAST")
                    val attachments = p["attachments"] as? List<Map<String, Any?>> ?: emptyList()
                    val runId = "run_${UUID.randomUUID()}"

                    // Extract and sanitize images from attachments
                    // Aligned with OpenClaw image-sanitization.ts: max 1200px, 5MB
                    val imageBlocks = mutableListOf<com.xiaomo.androidforclaw.providers.llm.ImageBlock>()
                    for (att in attachments) {
                        val type = att["type"] as? String ?: continue
                        if (type == "image" || type == "image_url") {
                            // Anthropic format: { type: "image", source: { data, media_type } }
                            val source = att["source"] as? Map<*, *>
                            val rawBase64 = source?.get("data") as? String
                            val mimeType = source?.get("media_type") as? String ?: "image/jpeg"
                            if (!rawBase64.isNullorBlank()) {
                                val sanitized = com.xiaomo.androidforclaw.media.ImageSanitizer.sanitize(
                                    base64Data = rawBase64,
                                    sourceMimeType = mimeType
                                )
                                if (sanitized != null) {
                                    imageBlocks.a(com.xiaomo.androidforclaw.providers.llm.ImageBlock(
                                        base64 = sanitized.base64,
                                        mimeType = sanitized.mimeType
                                    ))
                                    Log.i(TAG, "[CAMERA] Image sanitized: ${sanitized.originalBytes}→${sanitized.sanitizedBytes} bytes, resized=${sanitized.resized}")
                                }
                            }
                            // OpenAI format: { type: "image_url", image_url: { url: "data:...;base64,..." } }
                            val imageUrl = att["image_url"] as? Map<*, *>
                            val url = imageUrl?.get("url") as? String
                            if (url != null && url.startswith("data:")) {
                                val parts = url.removePrefix("data:").split(";base64,", limit = 2)
                                if (parts.size == 2) {
                                    val sanitized = com.xiaomo.androidforclaw.media.ImageSanitizer.sanitize(
                                        base64Data = parts[1],
                                        sourceMimeType = parts[0]
                                    )
                                    if (sanitized != null) {
                                        imageBlocks.a(com.xiaomo.androidforclaw.providers.llm.ImageBlock(
                                            base64 = sanitized.base64,
                                            mimeType = sanitized.mimeType
                                        ))
                                        Log.i(TAG, "[CAMERA] Image sanitized (URL): ${sanitized.originalBytes}→${sanitized.sanitizedBytes} bytes")
                                    }
                                }
                            }
                        }
                    }

                    // Build content as raw maps — lossless roundtrip through sessionmanager
                    val textPart: Map<String, Any?> = mapOf("type" to "text", "text" to userMsg)
                    val userContent: Any = if (attachments.isEmpty()) {
                        userMsg
                    } else {
                        mutableListOf(textPart).app { aAll(attachments) }
                    }

                    // Store user message via sessionmanager
                    val session = sessionmanager.getorCreate(sessionKey)
                    session.aMessage(LegacyMessage(role = "user", content = userContent))
                    sessionmanager.save(session)

                    // Build context history from session messages
                    val contextHistory = session.messages.dropLast(1).map { it.tonewMessage() }

                    // cancel previous run for the SAME session only
                    // Find runIds belonging to this session and cancel them
                    runTosession.entries.filter { it.value == sessionKey }.forEach { (oldRunId, _) ->
                        Log.w(TAG, "🛑 [chat.send] cancelling previous run $oldRunId for session $sessionKey")
                        sessionagentloops[sessionKey]?.stop()
                        activeJobs[oldRunId]?.cancel()
                        activeJobs.remove(oldRunId)
                        runTosession.remove(oldRunId)
                    }

                    // Create a per-session agentloop so sessions don't share shouldStop flag
                    val llmprovider = com.xiaomo.androidforclaw.providers.UnifiedLLMprovider(context)
                    val persessionloop = agentloop(
                        llmprovider = llmprovider,
                        toolRegistry = toolRegistry,
                        androidtoolRegistry = androidtoolRegistry
                    )
                    // Copy extra tools from the shared agentloop if any
                    persessionloop.extratools = agentloop.extratools
                    sessionagentloops[sessionKey] = persessionloop
                    runTosession[runId] = sessionKey

                    val job = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        // Track tool call IDs for correlating start/result pairs
                        val pendingtoolCallIds = ConcurrentHashMap<String, String>()

                        // collect streaming progress events in parallel
                        val streamJob = launch {
                            persessionloop.progressFlow.collect { update ->
                                when (update) {
                                    is ProgressUpdate.BlockReply -> {
                                        broadcastEvent(EventFrame(event = "agent", payload = mapOf(
                                            "sessionKey" to sessionKey,
                                            "stream" to "assistant",
                                            "data" to mapOf("text" to update.text)
                                        )))
                                    }
                                    is ProgressUpdate.toolCall -> {
                                        val toolCallId = "tc_${UUID.randomUUID()}"
                                        pendingtoolCallIds[update.name] = toolCallId
                                        broadcastEvent(EventFrame(event = "agent", payload = mapOf(
                                            "sessionKey" to sessionKey,
                                            "stream" to "tool",
                                            "data" to mapOf(
                                                "phase" to "start",
                                                "name" to update.name,
                                                "toolCallId" to toolCallId,
                                                "arguments" to update.arguments
                                            )
                                        )))
                                    }
                                    is ProgressUpdate.toolresult -> {
                                        val toolCallId = pendingtoolCallIds.remove(update.name) ?: "tc_${UUID.randomUUID()}"
                                        broadcastEvent(EventFrame(event = "agent", payload = mapOf(
                                            "sessionKey" to sessionKey,
                                            "stream" to "tool",
                                            "data" to mapOf(
                                                "phase" to "result",
                                                "name" to update.name,
                                                "toolCallId" to toolCallId,
                                                "result" to update.result
                                            )
                                        )))
                                    }
                                    else -> { /* ignore other progress types */ }
                                }
                            }
                        }

                        try {
                            val systemPrompt = contextBuilder.buildSystemPrompt(
                                userGoal = userMsg,
                                packageName = "",
                                testMode = "exploration"
                            )
                            Log.d(TAG, "[OK] Gateway system prompt built (${systemPrompt.length} chars)")

                            val result = persessionloop.run(
                                systemPrompt = systemPrompt,
                                userMessage = userMsg,
                                contextHistory = contextHistory,
                                reasoningEnabled = reasoningEnabled,
                                images = imageBlocks.ifEmpty { null }
                            )
                            streamJob.cancel()

                            val text = result.finalContent
                            val msgId = "msg_${UUID.randomUUID()}"
                            val nowMs = System.currentTimeMillis()

                            // Store assistant message via sessionmanager
                            session.aMessage(LegacyMessage(role = "assistant", content = text))
                            sessionmanager.save(session)

                            // Send final assistant text (full accumulated)
                            broadcastEvent(EventFrame(event = "agent", payload = mapOf(
                                "sessionKey" to sessionKey,
                                "stream" to "assistant",
                                "data" to mapOf("text" to text)
                            )))
                            // OpenClaw client expects "state" in chat events
                            broadcastEvent(EventFrame(event = "chat", payload = mapOf(
                                "state" to "final",
                                "sessionKey" to sessionKey,
                                "runId" to runId,
                                "message" to mapOf(
                                    "id" to msgId,
                                    "role" to "assistant",
                                    "content" to listOf(mapOf("type" to "text", "text" to text)),
                                    "timestamp" to nowMs
                                )
                            )))
                        } catch (e: kotlinx.coroutines.cancellationexception) {
                            streamJob.cancel()
                            Log.i(TAG, "chat.send cancelled (abort): $runId")
                            broadcastEvent(EventFrame(event = "chat", payload = mapOf(
                                "state" to "aborted",
                                "sessionKey" to sessionKey,
                                "runId" to runId
                            )))
                        } catch (e: exception) {
                            streamJob.cancel()
                            Log.e(TAG, "chat.send agent failed: ${e.message}", e)
                            val errorMsg = e.message ?: "error"
                            broadcastEvent(EventFrame(event = "agent", payload = mapOf(
                                "sessionKey" to sessionKey,
                                "stream" to "error",
                                "data" to mapOf("error" to errorMsg)
                            )))
                            broadcastEvent(EventFrame(event = "chat", payload = mapOf(
                                "state" to "error",
                                "sessionKey" to sessionKey,
                                "runId" to runId,
                                "errorMessage" to errorMsg
                            )))
                        } finally {
                            activeJobs.remove(runId)
                            runTosession.remove(runId)
                            sessionagentloops.remove(sessionKey)
                        }
                    }
                    activeJobs[runId] = job

                    mapOf("runId" to runId)
                }

                // chat.history: return session message history in OpenClaw format.
                registerMethod("chat.history") { params ->
                    @Suppress("UNCHECKED_CAST")
                    val p = params as? Map<String, Any?> ?: emptyMap()
                    val sessionKey = p["sessionKey"] as? String ?: "default"
                    val session = sessionmanager.get(sessionKey)
                    val messageList = session?.messages?.mapIndexed { idx, msg ->
                        val ts = session.messageTimestamps.getorElse(idx) { System.currentTimeMillis() }
                        mapOf(
                            "role" to msg.role,
                            "content" to legacyContentToOpenClaw(msg.content),
                            "timestamp" to ts
                        )
                    } ?: emptyList()
                    val savedThinking = SPhelper.getInstance(context)
                        .getData(PREF_THINKING_LEVEL, "off")
                        ?.takeif { it.isnotBlank() } ?: "off"
                    mapOf(
                        "sessionKey" to sessionKey,
                        "sessionId" to session?.sessionId,
                        "thinkingLevel" to savedThinking,
                        "messages" to messageList
                    )
                }

                // chat.setThinkingLevel: persist the thinking level immediately on change.
                registerMethod("chat.setThinkingLevel") { params ->
                    @Suppress("UNCHECKED_CAST")
                    val p = params as? Map<String, Any?> ?: emptyMap()
                    val level = p["level"] as? String ?: "off"
                    SPhelper.getInstance(context).saveData(PREF_THINKING_LEVEL, level)
                    mapOf("ok" to true)
                }

                // chat.health: returns current session health for the chat tab.
                registerMethod("chat.health") { _ ->
                    mapOf("ok" to true, "agentBusy" to false)
                }

                // chat.abort: cancel the running agent for the given runId.
                registerMethod("chat.abort") { params ->
                    @Suppress("UNCHECKED_CAST")
                    val p = params as? Map<String, Any?> ?: emptyMap()
                    val runId = p["runId"] as? String
                    if (runId != null) {
                        val sk = runTosession[runId]
                        if (sk != null) sessionagentloops[sk]?.stop()
                        activeJobs[runId]?.cancel()
                        activeJobs.remove(runId)
                        runTosession.remove(runId)
                        Log.i(TAG, "Aborted run: $runId")
                    } else {
                        // Abort all active runs
                        sessionagentloops.values.forEach { it.stop() }
                        sessionagentloops.clear()
                        activeJobs.values.forEach { it.cancel() }
                        activeJobs.clear()
                        runTosession.clear()
                        Log.i(TAG, "Aborted all active runs")
                    }
                    mapOf("aborted" to true)
                }

                // agents.list: list available agents (androidforClaw only has one).
                registerMethod("agents.list") { _ ->
                    mapOf("agents" to listOf(
                        mapOf(
                            "id" to "androidforclaw",
                            "name" to "androidforClaw",
                            "description" to "AI agent for android"
                        )
                    ))
                }

                // Register agent methods
                registerMethod("agent") { params ->
                    val agentParams = parseagentParams(params)
                    agentMethods.agent(agentParams)
                }

                registerMethod("agent.wait") { params ->
                    val waitParams = parseagentWaitParams(params)
                    agentMethods.agentWait(waitParams)
                }

                // OpenClaw uses "agent.identity.get" not "agent.identity"
                registerMethod("agent.identity.get") { _ ->
                    agentMethods.agentIdentity()
                }

                // Register session methods
                registerMethod("sessions.list") { params ->
                    sessionMethods.sessionsList(params)
                }

                registerMethod("sessions.preview") { params ->
                    sessionMethods.sessionsPreview(params)
                }

                registerMethod("sessions.reset") { params ->
                    sessionMethods.sessionsReset(params)
                }

                registerMethod("sessions.delete") { params ->
                    sessionMethods.sessionsDelete(params)
                }

                registerMethod("sessions.patch") { params ->
                    sessionMethods.sessionsPatch(params)
                }

                // Register Health methods
                registerMethod("health") { _ ->
                    healthMethods.health()
                }

                registerMethod("status") { _ ->
                    healthMethods.status()
                }

                // Register models methods
                registerMethod("models.list") { _ ->
                    modelsMethods.modelsList()
                }

                // Register tools methods
                registerMethod("tools.catalog") { _ ->
                    toolsMethods.toolsCatalog()
                }

                registerMethod("tools.list") { _ ->
                    toolsMethods.toolsList()
                }

                // Register skills methods
                registerMethod("skills.status") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.status(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.bins") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.bins(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.reload") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.reload(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.install") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.install(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.update") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.update(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.search") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.search(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                registerMethod("skills.uninstall") { params ->
                    val paramsObj = when (params) {
                        is com.google.gson.JsonObject -> params
                        is Map<*, *> -> com.google.gson.Gson().toJsonTree(params).asJsonObject
                        else -> com.google.gson.JsonObject()
                    }
                    val result = skillsMethods.uninstall(paramsObj)
                    if (result.isSuccess) result.getorNull() else throw result.exceptionorNull()!!
                }

                // Register config methods
                registerMethod("config.get") { params ->
                    configMethods.configGet(params)
                }

                registerMethod("config.set") { params ->
                    configMethods.configSet(params)
                }

                registerMethod("config.reload") { _ ->
                    configMethods.configReload()
                }

                // Register Cron methods (OpenClaw alignment)
                registerMethod("cron.list") { params ->
                    CronMethods.list(params as JSONObject)
                }

                registerMethod("cron.status") { params ->
                    CronMethods.status(params as JSONObject)
                }

                registerMethod("cron.a") { params ->
                    CronMethods.a(params as JSONObject)
                }

                registerMethod("cron.update") { params ->
                    CronMethods.update(params as JSONObject)
                }

                registerMethod("cron.remove") { params ->
                    CronMethods.remove(params as JSONObject)
                }

                registerMethod("cron.run") { params ->
                    CronMethods.run(params as JSONObject)
                }

                registerMethod("cron.runs") { params ->
                    CronMethods.runs(params as JSONObject)
                }

                // ── Talk (TTS) methods ───────────────────────────────────
                registerMethod("talk.config") { params ->
                    talkMethods.talkconfig(params)
                }
                registerMethod("talk.speak") { params ->
                    talkMethods.talkSpeak(params)
                }

                Log.i(TAG,"Registered ${getMethodCount()} RPC methods")
            }

            // Start server in background
            serviceScope.launch(Dispatchers.IO) {
                try {
                    // use 60 second timeout for slow operations (like ClawHub API calls)
                    // NanoHTTPD.SOCKET_READ_TIMEOUT is 5000ms by default, too short
                    server?.start(60000, false)  // 60 seconds
                    isRunning = true
                    Log.i(TAG,"Gateway WebSocket server started on port $port with 60s timeout")
                    Log.i(TAG,"Access UI at http://localhost:$port/")
                } catch (e: IOexception) {
                    Log.e(TAG, "Failed to start Gateway server", e)
                    isRunning = false
                }
            }

        } catch (e: exception) {
            Log.e(TAG, "Failed to initialize Gateway", e)
            throw e
        }
    }

    /**
     * Stop the Gateway WebSocket server
     */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG,"Gateway not running")
            return
        }

        try {
            server?.stop()
            server = null
            if (::talkMethods.isInitialized) talkMethods.shutdown()
            isRunning = false
            Log.i(TAG, "Gateway WebSocket server stopped")
        } catch (e: exception) {
            Log.e(TAG, "Error stopping Gateway", e)
        }
    }

    /**
     * Generate a new authentication token
     */
    fun generateToken(label: String = "generated", ttlMs: Long? = null): String? {
        return tokenAuth?.generateToken(label, ttlMs)
    }

    /**
     * Revoke an authentication token
     */
    fun revokeToken(token: String): Boolean {
        return tokenAuth?.revokeToken(token) ?: false
    }

    /**
     * Get server info
     */
    fun getInfo(): Map<String, Any> {
        return mapOf(
            "running" to isRunning,
            "port" to port,
            "authenticated" to (tokenAuth != null),
            "connections" to (server?.getActiveConnections() ?: 0),
            "url" to "ws://localhost:$port"
        )
    }

    // helper methods to parse params
    // OpenClaw Protocol v3: params is Any? (can be Map, List, primitive, etc.)

    /**
     * Convert LegacyMessage.content (String or List<ContentBlock>) to
     * the OpenClaw format: List<Map<type, text?>>
     * Client parseHistory expects a JsonArray of content parts.
     */
    @Suppress("UNCHECKED_CAST")
    private fun legacyContentToOpenClaw(content: Any?): List<Map<String, Any?>> {
        return when (content) {
            is String -> listOf(mapOf("type" to "text", "text" to content))
            is List<*> -> content.mapnotNull { block ->
                when (block) {
                    is com.xiaomo.androidforclaw.providers.ContentBlock -> when (block.type) {
                        "text" -> mapOf("type" to "text", "text" to (block.text ?: ""))
                        "image_url" -> {
                            val dataUrl = block.imageUrl?.url ?: ""
                            // "data:image/png;base64,..." → extract mimeType
                            val mimeType = dataUrl.removePrefix("data:").substringbefore(";")
                            mapOf(
                                "type" to "image_url",
                                "mimeType" to mimeType.ifEmpty { "image/jpeg" },
                                "content" to dataUrl.substringafter("base64,")
                            )
                        }
                        else -> null
                    }
                    is Map<*, *> -> (block as? Map<String, Any?>)  // raw map (attachment / post-JSONL-load)
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseagentParams(params: Any?): agentParams {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object for agent method")

        return agentParams(
            sessionKey = paramsMap["sessionKey"] as? String
                ?: throw IllegalArgumentexception("sessionKey required"),
            message = paramsMap["message"] as? String
                ?: throw IllegalArgumentexception("message required"),
            thinking = paramsMap["thinking"] as? String,
            model = paramsMap["model"] as? String
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseagentWaitParams(params: Any?): agentWaitParams {
        val paramsMap = params as? Map<String, Any?>
            ?: throw IllegalArgumentexception("params must be an object for agent.wait method")

        return agentWaitParams(
            runId = paramsMap["runId"] as? String
                ?: throw IllegalArgumentexception("runId required"),
            timeout = (paramsMap["timeout"] as? Number)?.toLong()
        )
    }

    /**
     * 本地Processinside直接call RPC Method(bypass WebSocket), Return JSON String. 
     * 供 LocalGatewaychannel call. 
     */
    suspend fun handleLocalRequest(method: String, paramsJson: String?): String {
        val srv = server ?: throw IllegalStateexception("Gateway not started")
        val result = srv.handleLocalRequest(method, paramsJson)
        return com.google.gson.Gson().toJson(result)
    }
}
