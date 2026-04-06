package com.xiaomo.base

/**
 * 本地ProcessInside gateway 通信Interface. 
 *
 * 替代 WebSocket Implementation, 允许同Process的 ChatController 直接与 GatewayController 交互, 
 * None需经过本地 localhost:8765 WebSocket 中转. 
 *
 * - 远程 gateway Connect仍使用 GatewaySession(WebSocket), 它Implementation本Interface. 
 * - 本地同ProcessConnect使用 LocalGatewayChannel(app Module), 同样Implementation本Interface. 
 */
interface IGatewayChannel {
    /**
     * 发起 RPC Request, Return JSON StringResponse. 
     */
    suspend fun request(method: String, paramsJson: String?, timeoutMs: Long = 15_000L): String

    /**
     * 发送NodeEvent(chat.subscribe 等). 
     * 本地Implementation可直接Ignore或Process. 
     */
    suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean

    /**
     * RegisterEventListener, 用于接收来自 gateway 的推送Event(agent Into度、chat Status等). 
     *
     * - LocalGatewayChannel: 将ListenerRegister到 GatewayController, Event直接投递, 不走 WebSocket. 
     * - GatewaySession: Default no-op, Event已通过构造时传入的 onEvent CallbackRoute. 
     */
    fun setEventListener(listener: ((event: String, payloadJson: String) -> Unit)?) {}
}
