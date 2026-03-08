package com.xiaomo.androidforclaw.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaomo.androidforclaw.channel.ChannelManager
import com.xiaomo.androidforclaw.ui.compose.ChatMessage
import com.xiaomo.androidforclaw.ui.compose.MessageStatus
import com.xiaomo.androidforclaw.ui.session.SessionManager
import com.xiaomo.androidforclaw.util.ReasoningTagFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天界面的 ViewModel - 支持多会话
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // 使用 UI 的 SessionManager (仅用于显示)
    private val uiSessionManager = SessionManager()
    private val channelManager = ChannelManager(application)

    // 暴露 session 相关的 flows
    val sessions: StateFlow<List<SessionManager.Session>> = uiSessionManager.sessions
    val currentSession: StateFlow<SessionManager.Session> = uiSessionManager.currentSession

    // 消息列表
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // 监听当前会话变化，更新消息列表
        viewModelScope.launch {
            uiSessionManager.currentSession.collect { session ->
                _messages.value = session.messages
            }
        }

        // 🔄 启动时从 MainEntryNew 的 SessionManager 加载历史消息
        loadSessionHistory()

        // 🔄 定时刷新 (每 3 秒)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                loadSessionHistory()
            }
        }
    }

    /**
     * 从 MainEntryNew 的 SessionManager 加载历史
     */
    private fun loadSessionHistory() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "📥 [ViewModel] 加载会话历史...")

                // 确保 MainEntryNew 已初始化
                com.xiaomo.androidforclaw.core.MainEntryNew.initialize(getApplication())

                // 🔄 加载所有后端 sessions（飞书、Discord、WebSocket）
                uiSessionManager.loadSessionsFromBackend()

                val agentSessionManager = com.xiaomo.androidforclaw.core.MainEntryNew.getSessionManager()
                val agentSession = agentSessionManager?.get("default")

                if (agentSession != null && agentSession.messageCount() > 0) {
                    Log.d(TAG, "✅ [ViewModel] 找到历史消息: ${agentSession.messageCount()} 条")

                    // 转换为 UI 的 ChatMessage
                    val chatMessages = mutableListOf<ChatMessage>()
                    agentSession.messages.forEach { msg ->
                        val contentStr = when (val content = msg.content) {
                            is String -> content
                            null -> ""
                            else -> content.toString()
                        }

                        when (msg.role) {
                            "user" -> {
                                if (contentStr.isNotEmpty()) {
                                    chatMessages.add(ChatMessage(
                                        content = contentStr,
                                        isUser = true,
                                        status = MessageStatus.SENT
                                    ))
                                }
                            }
                            "assistant" -> {
                                if (contentStr.isNotEmpty()) {
                                    // 过滤推理标签 (<think>, <final> 等)
                                    val cleanContent = ReasoningTagFilter.stripReasoningTags(contentStr)

                                    if (cleanContent.isNotEmpty()) {
                                        chatMessages.add(ChatMessage(
                                            content = cleanContent,
                                            isUser = false,
                                            status = MessageStatus.SENT
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    // 更新 UI Session
                    Log.d(TAG, "🔄 [ViewModel] 更新 UI 会话...")
                    _messages.value = chatMessages
                    Log.d(TAG, "✅ [ViewModel] 历史消息已加载: ${chatMessages.size} 条")
                } else {
                    Log.d(TAG, "ℹ️ [ViewModel] 没有历史消息")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ [ViewModel] 加载历史失败", e)
            }
        }
    }


    /**
     * 发送用户消息
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        Log.d(TAG, "💬 [ViewModel] 发送消息: $content")

        // 记录 inbound（用户消息）
        channelManager.recordInbound()

        // 添加用户消息到 UI
        val userMessage = ChatMessage(
            content = content,
            isUser = true,
            status = MessageStatus.SENT
        )
        addMessage(userMessage)

        // 添加思考中消息
        val thinkingMessage = ChatMessage(
            content = "正在思考...",
            isUser = false,
            status = MessageStatus.SENDING
        )
        addMessage(thinkingMessage)

        // 🎯 使用 MainEntryNew 统一执行 (自动处理 Session 和广播)
        Log.d(TAG, "🚀 [ViewModel] 调用 MainEntryNew.runWithSession...")
        com.xiaomo.androidforclaw.core.MainEntryNew.runWithSession(
            userInput = content,
            sessionId = "default",
            application = getApplication()
        )

        // 移除思考中消息 (定时刷新会自动加载新消息)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            removeMessage(thinkingMessage)
        }
    }

    /**
     * 添加消息到当前会话
     */
    private fun addMessage(message: ChatMessage) {
        uiSessionManager.addMessageToCurrentSession(message)

        // 如果是用户消息且当前会话标题还是默认的，自动生成标题
        if (message.isUser && currentSession.value.title == "新对话") {
            uiSessionManager.autoGenerateCurrentSessionTitle()
        }
    }

    /**
     * 从当前会话移除消息
     */
    private fun removeMessage(message: ChatMessage) {
        uiSessionManager.removeMessageFromCurrentSession(message.id)
    }

    // === Session Management ===

    /**
     * 创建新会话
     */
    fun createNewSession() {
        uiSessionManager.createSession()
    }

    /**
     * 切换会话
     */
    fun switchSession(sessionId: String) {
        uiSessionManager.switchSession(sessionId)
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        uiSessionManager.deleteSession(sessionId)
    }

    /**
     * 清空当前会话
     */
    fun clearCurrentSession() {
        uiSessionManager.clearCurrentSession()
        // 同时清空 Agent 的 Session
        val agentSessionManager = com.xiaomo.androidforclaw.core.MainEntryNew.getSessionManager()
        agentSessionManager?.clear("default")
    }
}
