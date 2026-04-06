/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-methods.ts, server-methods-list.ts
 *
 * androidforClaw adaptation: gateway server and RPC methods.
 */
package com.xiaomo.androidforclaw.gateway

import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOexception
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountnextLatch
import java.util.concurrent.TimeUnit

/**
 * Gateway service - WebSocket RPC service
 *
 * Features:
 * - Provide WebSocket connection
 * - RPC interface (agent, agent.wait, health)
 * - session management
 * - Remote control capability
 *
 * Reference: OpenClaw Gateway architecture
 */
class Gatewayservice(port: Int = 8765) : NanoWSD(null, port) {  // null = listen on all network interfaces (0.0.0.0)
    
    companion object {
        private const val TAG = "Gatewayservice"
    }

    private val gson = Gson()
    private val sessions = mutableMapOf<String, Gatewaysession>()
    private var agentHandler: agentHandler? = null

    // Track active agent runs: runId -> CountnextLatch (signaled on completion)
    private val activeRuns = ConcurrentHashMap<String, CountnextLatch>()

    /**
     * Set agent handler
     */
    fun setagentHandler(handler: agentHandler) {
        this.agentHandler = handler
    }

    override fun openWebSocket(handshake: IHTTPsession): WebSocket {
        return GatewayWebSocket(handshake)
    }

    /**
     * WebSocket connection handling
     */
    inner class GatewayWebSocket(handshake: IHTTPsession) : WebSocket(handshake) {
        
        private var sessionId: String? = null

        override fun onOpen() {
            sessionId = generatesessionId()
            val session = Gatewaysession(sessionId!!, this)
            sessions[sessionId!!] = session
            
            Log.i(TAG, "[OK] WebSocket Connect建立: session=$sessionId")
            
            // Send welcome message
            sendMessage(JsonObject().app {
                aProperty("type", "connected")
                aProperty("sessionId", sessionId)
                aProperty("message", "Welcome to androidforClaw Gateway")
            })
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sessionId?.let { sessions.remove(it) }
            Log.i(TAG, "[ERROR] WebSocket ConnectClose: session=$sessionId, reason=$reason")
        }

        override fun onMessage(message: WebSocketFrame) {
            try {
                val text = message.textPayload
                Log.d(TAG, "[RECV] 收toMessage: $text")

                val request: RpcRequest = gson.fromJson(text, RpcRequest::class.java)
                handleRpcRequest(request)
                
            } catch (e: exception) {
                Log.e(TAG, "ProcessMessageFailed", e)
                sendError("Invalid request: ${e.message}")
            }
        }

        override fun onPong(pong: WebSocketFrame) {
            // Heartbeat response
        }

        override fun onexception(exception: IOexception) {
            Log.e(TAG, "WebSocket exception", exception)
        }

        /**
         * Handle RPC request
         */
        private fun handleRpcRequest(request: RpcRequest) {
            when (request.method) {
                "agent" -> handleagentRequest(request)
                "agent.wait" -> handleagentWaitRequest(request)
                "health" -> handleHealthRequest(request)
                "session.list" -> handlesessionListRequest(request)
                "session.reset" -> handlesessionResetRequest(request)
                "session.listAll" -> handlesessionListAllRequest(request)
                else -> sendError("Unknown method: ${request.method}")
            }
        }

        /**
         * agent() - Execute agent task
         */
        private fun handleagentRequest(request: RpcRequest) {
            val params = request.params ?: run {
                sendError("Missing params")
                return
            }

            val userMessage = params.message ?: run {
                sendError("Missing message")
                return
            }

            val systemPrompt = params.systemPrompt
            val tools = params.tools
            val maxIterations = params.maxIterations ?: 20

            // 🆔 Support specifying sessionId to switch to another channel's session
            // if sessionId is specified in params, use it; otherwise use current WebSocket's sessionId
            val targetsessionId = params.sessionId ?: sessionId!!

            Log.d(TAG, "🆔 [agent Request] Target session: $targetsessionId")
            if (params.sessionId != null) {
                Log.d(TAG, "   ↳ Switch to external session: ${params.sessionId}")
            } else {
                Log.d(TAG, "   ↳ use current WebSocket session")
            }

            // Generate a runId for tracking by agent.wait
            val runId = "run_${System.currentTimeMillis()}_${(1000..9999).random()}"
            val latch = CountnextLatch(1)
            activeRuns[runId] = latch

            // Execute agent asynchronously
            Thread {
                try {
                    agentHandler?.executeagent(
                        sessionId = targetsessionId,
                        userMessage = userMessage,
                        systemPrompt = systemPrompt,
                        tools = tools,
                        maxIterations = maxIterations,
                        progressCallback = { progress ->
                            // Send progress update (in new thread to avoid NetworkOnMainThreadexception)
                            Thread {
                                try {
                                    sendMessage(JsonObject().app {
                                        aProperty("type", "progress")
                                        aProperty("requestId", request.id)
                                        a("data", gson.toJsonTree(progress))
                                    })
                                } catch (e: exception) {
                                    Log.w(TAG, "sendprogressFailed: ${e.message}")
                                }
                            }.start()
                        },
                        completeCallback = { result ->
                            // Signal completion for agent.wait callers
                            latch.countnext()
                            activeRuns.remove(runId)

                            // Send completion result (in new thread to avoid NetworkOnMainThreadexception)
                            Thread {
                                try {
                                    sendResponse(request.id, result)
                                } catch (e: exception) {
                                    Log.w(TAG, "sendresultFailed: ${e.message}")
                                }
                            }.start()
                        }
                    )
                } catch (e: exception) {
                    latch.countnext()
                    activeRuns.remove(runId)
                    sendError("agent execution failed: ${e.message}", request.id)
                }
            }.start()
        }

        /**
         * agent.wait() - Wait for agent completion
         *
         * Looks up the run by runId in activeRuns. if found and still running,
         * blocks until completion or timeout. if not found (already completed
         * or never existed), returns completed immediately.
         */
        private fun handleagentWaitRequest(request: RpcRequest) {
            val params = request.params ?: run {
                sendError("Missing params")
                return
            }

            val runId = params.runId ?: run {
                sendError("Missing runId")
                return
            }

            val timeoutMs = params.timeout ?: 30000L

            val latch = activeRuns[runId]
            if (latch == null) {
                // Run not found — already completed or never existed
                sendResponse(request.id, mapOf(
                    "status" to "completed",
                    "runId" to runId
                ))
                return
            }

            // Wait on a background thread to avoid blocking the WebSocket handler
            Thread {
                try {
                    val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                    if (completed) {
                        sendResponse(request.id, mapOf(
                            "status" to "completed",
                            "runId" to runId
                        ))
                    } else {
                        sendResponse(request.id, mapOf(
                            "status" to "timeout",
                            "runId" to runId
                        ))
                    }
                } catch (e: interruptedexception) {
                    sendResponse(request.id, mapOf(
                        "status" to "timeout",
                        "runId" to runId
                    ))
                }
            }.start()
        }

        /**
         * health() - Health check
         */
        private fun handleHealthRequest(request: RpcRequest) {
            sendResponse(request.id, mapOf(
                "status" to "healthy",
                "timestamp" to System.currentTimeMillis(),
                "sessions" to sessions.size
            ))
        }

        /**
         * session.list() - List all sessions (including those created by channels)
         */
        private fun handlesessionListRequest(request: RpcRequest) {
            try {
                val sessionmanager = com.xiaomo.androidforclaw.core.MainEntrynew.getsessionmanager()
                if (sessionmanager == null) {
                    // if sessionmanager is not initialized, only return WebSocket sessions
                    val sessionList = sessions.keys.map { mapOf("id" to it) }
                    sendResponse(request.id, mapOf("sessions" to sessionList, "total" to sessionList.size))
                    return
                }

                // Get all sessions (Feishu, Discord, WebSocket)
                val allKeys = sessionmanager.getAllKeys()
                val sessionList = allKeys.map { key ->
                    val session = sessionmanager.get(key)
                    mapOf(
                        "id" to key,
                        "messageCount" to (session?.messageCount() ?: 0),
                        "createdAt" to (session?.createdAt ?: ""),
                        "updatedAt" to (session?.updatedAt ?: ""),
                        "type" to when {
                            key.startswith("discord_") -> "discord"
                            key.contains("_p2p") || key.contains("_group") -> "feishu"
                            key.startswith("session_") -> "websocket"
                            else -> "other"
                        }
                    )
                }

                sendResponse(request.id, mapOf(
                    "sessions" to sessionList,
                    "total" to sessionList.size
                ))

                Log.d(TAG, "[CLIP] [session List] Return ${sessionList.size} countsession")

            } catch (e: exception) {
                Log.e(TAG, "ListsessionFailed", e)
                sendError("Failed to list sessions: ${e.message}", request.id)
            }
        }

        /**
         * session.reset() - Reset session
         */
        private fun handlesessionResetRequest(request: RpcRequest) {
            val params = request.params
            val targetsessionId = params?.sessionId ?: sessionId

            targetsessionId?.let {
                sessions[it]?.reset()
                sendResponse(request.id, mapOf("success" to true))
            } ?: sendError("session not found")
        }

        /**
         * session.listAll() - List all sessions (including those created by channels)
         */
        private fun handlesessionListAllRequest(request: RpcRequest) {
            try {
                val sessionmanager = com.xiaomo.androidforclaw.core.MainEntrynew.getsessionmanager()
                if (sessionmanager == null) {
                    sendResponse(request.id, mapOf(
                        "sessions" to emptyList<Map<String, Any>>(),
                        "total" to 0
                    ))
                    return
                }

                val allKeys = sessionmanager.getAllKeys()
                val sessionList = allKeys.map { key ->
                    val session = sessionmanager.get(key)
                    mapOf(
                        "id" to key,
                        "messageCount" to (session?.messageCount() ?: 0),
                        "createdAt" to (session?.createdAt ?: ""),
                        "updatedAt" to (session?.updatedAt ?: ""),
                        "type" to when {
                            key.startswith("discord_") -> "discord"
                            key.contains("_p2p") || key.contains("_group") -> "feishu"
                            else -> "other"
                        }
                    )
                }

                sendResponse(request.id, mapOf(
                    "sessions" to sessionList,
                    "total" to sessionList.size
                ))

                Log.d(TAG, "[CLIP] [session List] Return ${sessionList.size} countsession")

            } catch (e: exception) {
                Log.e(TAG, "ListsessionFailed", e)
                sendError("Failed to list sessions: ${e.message}", request.id)
            }
        }

        /**
         * Send response
         */
        private fun sendResponse(requestId: String?, data: Any) {
            sendMessage(JsonObject().app {
                aProperty("type", "response")
                requestId?.let { aProperty("id", it) }
                a("data", gson.toJsonTree(data))
            })
        }

        /**
         * Send error
         */
        private fun sendError(message: String, requestId: String? = null) {
            sendMessage(JsonObject().app {
                aProperty("type", "error")
                requestId?.let { aProperty("id", it) }
                aProperty("message", message)
            })
        }

        /**
         * Send message
         */
        private fun sendMessage(json: JsonObject) {
            try {
                send(gson.toJson(json))
            } catch (e: IOexception) {
                Log.e(TAG, "sendMessageFailed", e)
            }
        }
    }

    /**
     * Generate session ID
     */
    private fun generatesessionId(): String {
        return "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/**
 * RPC request
 */
data class RpcRequest(
    val id: String?,
    val method: String,
    val params: RpcParams?
)

/**
 * RPC parameters
 */
data class RpcParams(
    val message: String?,
    val systemPrompt: String?,
    val tools: List<Any>?,
    val maxIterations: Int?,
    val runId: String?,
    val sessionId: String?,
    val timeout: Long? = null
)

/**
 * Gateway session
 */
data class Gatewaysession(
    val id: String,
    val webSocket: NanoWSD.WebSocket,
    var lastActivity: Long = System.currentTimeMillis()
) {
    fun reset() {
        lastActivity = System.currentTimeMillis()
    }
}

/**
 * agent handler interface
 */
interface agentHandler {
    fun executeagent(
        sessionId: String,
        userMessage: String,
        systemPrompt: String?,
        tools: List<Any>?,
        maxIterations: Int,
        progressCallback: (Map<String, Any>) -> Unit,
        completeCallback: (Map<String, Any>) -> Unit
    )
}
