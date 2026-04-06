package com.xiaomo.base

/**
 * In-process gateway communication interface.
 *
 * Alternative to WebSocket implementation, allows ChatController in the same process
 * to directly interact with GatewayController, without going through local localhost:8765 WebSocket relay.
 *
 * - Remote gateway connection still uses GatewaySession(WebSocket), which implements this interface.
 * - Local in-process connection uses LocalGatewayChannel (app module), which also implements this interface.
 */
interface IGatewayChannel {
    /**
     * Initiate RPC request, return JSON string response.
     */
    suspend fun request(method: String, paramsJson: String?, timeoutMs: Long = 15_000L): String

    /**
     * Send NodeEvent (e.g., chat.subscribe).
     * Local implementation can directly ignore or process.
     */
    suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean

    /**
     * Register event listener to receive push events from gateway (agent input, chat status, etc.).
     *
     * - LocalGatewayChannel: registers listener to GatewayController, events delivered directly, no WebSocket.
     * - GatewaySession: default no-op, events already routed via onEvent callback passed during construction.
     */
    fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?) {}
}
