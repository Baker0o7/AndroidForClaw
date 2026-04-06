package com.xiaomo.androidforclaw.gateway

import com.xiaomo.base.IGatewaychannel

/**
 * Local Process inside gateway channel, directly call GatewayController, no need WebSocket.
 *
 * Used for androidforClaw itself (ChatController and GatewayController in the same process).
 * Remote OpenClaw gateway connect still use GatewaySession (WebSocket implementation).
 */
class LocalGatewayChannel(private val controller: GatewayController) : IGatewayChannel {

    /** NodeRuntime registerEventCallback, GatewayController broadcast hour directly call, not go through WebSocket. */
    @Volatile
    private var eventListener: ((event: String, payloadJson: String) -> Unit)? = null

    override fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?) {
        eventListener = listener
        controller.localEventSink = listener
    }

    override suspend fun request(method: String, paramsJson: String?, timeoutMs: Long): String {
        return controller.handleLocalRequest(method, paramsJson)
    }

    override suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
        // In local mode, chat.subscribe and other NodeEvents are directly ignored (no remote gateway subscription)
        return true
    }
}
