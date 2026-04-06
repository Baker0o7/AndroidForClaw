package com.xiaomo.androidforclaw.gateway

import com.xiaomo.base.IGatewaychannel

/**
 * 本地Processinside gateway channel, 直接call GatewayController, Noneneed WebSocket. 
 *
 * 用于 androidforClaw 自身(ChatController and GatewayController in同oneProcess中). 
 * 远程 OpenClaw gateway Connect仍use Gatewaysession(WebSocket implementation). 
 */
class LocalGatewaychannel(private val controller: GatewayController) : IGatewaychannel {

    /** NodeRuntime RegisterEventCallback, GatewayController Broadcasthour直接call, not走 WebSocket.  */
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
