package com.xiaomo.androidforclaw.gateway

import com.xiaomo.base.IGatewayChannel

/**
 * 本地ProcessInside gateway channel, 直接call GatewayController, None需 WebSocket. 
 *
 * 用于 AndroidForClaw 自身(ChatController 和 GatewayController 在同一Process中). 
 * 远程 OpenClaw gateway Connect仍use GatewaySession(WebSocket Implementation). 
 */
class LocalGatewayChannel(private val controller: GatewayController) : IGatewayChannel {

    /** NodeRuntime Register的EventCallback, GatewayController Broadcast时直接call, 不走 WebSocket.  */
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
        // 本地In mode chat.subscribe 等NodeEvent直接Ignore(None远程 gateway 订阅)
        return true
    }
}
