package com.xiaomo.androidforclaw.ui.session

import com.xiaomo.androidforclaw.ui.compose.ChatMessage
import com.xiaomo.androidforclaw.util.ReasoningTagFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Session Manager - 管理多会话
 *
 * 功能:
 * - 创建/删除会话
 * - 切换会话
 * - 每个会话独立的消息历史
 * - 会话元数据（标题、创建时间等）
 */
class SessionManager {

    data class Session(
        val id: String = UUID.randomUUID().toString(),
        val title: String = "新对话",
        val createdAt: Long = System.currentTimeMillis(),
        val messages: List<ChatMessage> = emptyList(),
        val isActive: Boolean = false
    ) {
        /**
         * 根据首条用户消息生成标题
         */
        fun generateTitle(): String {
            val firstUserMessage = messages.firstOrNull { it.isUser }
            return if (firstUserMessage != null) {
                val content = firstUserMessage.content
                if (content.length > 20) {
                    content.take(20) + "..."
                } else {
                    content
                }
            } else {
                "新对话 ${createdAt}"
            }
        }
    }

    companion object {
        private const val PREF_LAST_SESSION_ID = "last_session_id"
    }

    // MMKV 用于持久化存储
    private val mmkv by lazy {
        com.tencent.mmkv.MMKV.defaultMMKV()
    }

    private val _sessions = MutableStateFlow<List<Session>>(listOf(createDefaultSession()))
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<Session>(_sessions.value.first())
    val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    /**
     * 创建默认会话
     */
    private fun createDefaultSession(): Session {
        // 检查是否是首次启动 - OpenClaw 风格
        val welcomeMessage = getWelcomeMessage()

        return Session(
            title = "新对话",
            messages = listOf(
                ChatMessage(
                    content = welcomeMessage,
                    isUser = false
                )
            ),
            isActive = true
        )
    }

    /**
     * 获取欢迎消息 - OpenClaw 风格
     *
     * 如果 workspace 的 IDENTITY.md 为空或不存在，说明是首次启动
     */
    private fun getWelcomeMessage(): String {
        // 检查 /sdcard/.androidforclaw/workspace/ 是否存在
        val workspaceDir = java.io.File("/sdcard/.androidforclaw/workspace")
        val identityFile = java.io.File(workspaceDir, "IDENTITY.md")

        // 判断是否是首次启动（文件不存在或为空或包含模板文字）
        val isFirstRun = !identityFile.exists() ||
                         identityFile.readText().trim().isEmpty() ||
                         identityFile.readText().contains("Fill this in during your first conversation")

        return if (isFirstRun) {
            // 首次启动 - OpenClaw 风格的引导
            """
你好！👋

我是 AndroidForClaw，一个 AI 助手，运行在你的 Android 设备上。

在我们开始之前，我想更好地了解你，也让你了解我。

我注意到这是你第一次使用 AndroidForClaw。我们需要一起完成一些初始设置。

## 📝 需要配置的文件

你的 workspace 位于：`/sdcard/.androidforclaw/workspace/`

请使用文件管理器创建和编辑以下文件：

### 1. **IDENTITY.md** - 我是谁？
定义我的身份、个性和风格。

示例内容：
```markdown
# IDENTITY.md - Who Am I?

- **Name:** AndroidClaw
- **Creature:** AI Assistant
- **Vibe:** Helpful, precise, efficient
- **Emoji:** 🤖
```

### 2. **USER.md** - 关于你
告诉我关于你的信息，这样我可以更好地帮助你。

示例内容：
```markdown
# USER.md - About You

- **Name:** (你的名字)
- **Timezone:** Asia/Shanghai
- **Preferences:**
  - 语言: 中文
  - 风格: 简洁高效
```

### 3. **SOUL.md** - 我的性格（可选）
定义我应该如何行动和沟通。

---

完成这些配置后，我们就可以开始工作了！

你也可以直接告诉我"跳过配置"，我会使用默认设置。

需要帮助吗？😊
            """.trimIndent()
        } else {
            // 已配置 - 读取 IDENTITY 信息
            try {
                val identityContent = identityFile.readText()

                // 尝试解析 Name 和 Emoji
                val nameMatch = Regex("""[*-]\s*\*?Name\*?[：:]\s*(.+)""").find(identityContent)
                val emojiMatch = Regex("""[*-]\s*\*?Emoji\*?[：:]\s*(.+)""").find(identityContent)

                val name = nameMatch?.groupValues?.get(1)?.trim() ?: "AndroidForClaw"
                val emoji = emojiMatch?.groupValues?.get(1)?.trim() ?: "🤖"

                // 常规欢迎消息
                """
你好！$emoji 我是 $name

我可以帮你：
- 📱 控制和测试 Android 应用
- 🔍 UI 自动化和功能验证
- 🌐 浏览网页和信息搜索
- ⚙️ 设备操作和文件管理

需要什么帮助？
                """.trimIndent()
            } catch (e: Exception) {
                // 读取失败，使用默认消息
                "你好！🤖 我是 AndroidForClaw\n\n我可以帮你控制和测试 Android 应用。需要什么帮助？"
            }
        }
    }

    /**
     * 从后端 SessionManager 加载 sessions
     * （飞书、Discord、WebSocket 创建的 sessions）
     */
    fun loadSessionsFromBackend() {
        try {
            val backendSessionManager = com.xiaomo.androidforclaw.core.MainEntryNew.getSessionManager()
            if (backendSessionManager == null) {
                android.util.Log.w("SessionManager", "Backend SessionManager not initialized")
                return
            }

            val backendSessionKeys = backendSessionManager.getAllKeys()
            if (backendSessionKeys.isEmpty()) {
                android.util.Log.d("SessionManager", "No backend sessions found")
                return
            }

            // 转换后端 sessions 为 UI sessions
            val backendSessions = backendSessionKeys.mapNotNull { key ->
                val backendSession = backendSessionManager.get(key)
                if (backendSession != null) {
                    val type = when {
                        key.startsWith("discord_") -> "Discord"
                        key.contains("_p2p") || key.contains("_group") -> "飞书"
                        key.startsWith("session_") -> "WebSocket"
                        else -> "其他"
                    }

                    // 生成标题
                    val title = if (backendSession.messages.isNotEmpty()) {
                        val firstUserMsg = backendSession.messages.firstOrNull {
                            it.role == "user"
                        }
                        if (firstUserMsg != null && firstUserMsg.content != null) {
                            val content = when (val c = firstUserMsg.content) {
                                is String -> c
                                else -> c.toString()
                            }
                            if (content.length > 15) {
                                "[$type] ${content.take(15)}..."
                            } else {
                                "[$type] $content"
                            }
                        } else {
                            "[$type] ${key.take(10)}..."
                        }
                    } else {
                        "[$type] ${key.take(10)}..."
                    }

                    // 转换消息格式，过滤推理标签
                    val uiMessages = backendSession.messages.mapNotNull { msg ->
                        if (msg.role == "user" || msg.role == "assistant") {
                            val contentStr = when (val c = msg.content) {
                                is String -> c
                                null -> ""
                                else -> c.toString()
                            }

                            // 对 assistant 消息过滤推理标签
                            val cleanContent = if (msg.role == "assistant") {
                                ReasoningTagFilter.stripReasoningTags(contentStr)
                            } else {
                                contentStr
                            }

                            if (cleanContent.isNotEmpty()) {
                                ChatMessage(
                                    content = cleanContent,
                                    isUser = msg.role == "user"
                                )
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    Session(
                        id = key,
                        title = title,
                        createdAt = try {
                            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                                .parse(backendSession.createdAt)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        },
                        messages = uiMessages,
                        isActive = false
                    )
                } else {
                    null
                }
            }

            if (backendSessions.isNotEmpty()) {
                // 保留当前的 UI sessions（新对话按钮创建的）
                val uiOnlySessions = _sessions.value.filter { session ->
                    !backendSessionKeys.contains(session.id)
                }

                // 合并：后端 sessions + UI sessions
                val allSessions = (backendSessions + uiOnlySessions).sortedByDescending { it.createdAt }

                _sessions.value = allSessions

                // 🔄 恢复上次使用的 session
                val lastSessionId = mmkv.decodeString(PREF_LAST_SESSION_ID)
                if (lastSessionId != null) {
                    val lastSession = allSessions.find { it.id == lastSessionId }
                    if (lastSession != null) {
                        // 更新激活状态
                        _sessions.value = allSessions.map {
                            it.copy(isActive = it.id == lastSessionId)
                        }
                        _currentSession.value = lastSession.copy(isActive = true)
                        android.util.Log.d("SessionManager", "✅ 恢复上次会话: $lastSessionId")
                    }
                }

                android.util.Log.d("SessionManager", "✅ Loaded ${backendSessions.size} sessions from backend")
            }

        } catch (e: Exception) {
            android.util.Log.e("SessionManager", "Failed to load backend sessions", e)
        }
    }

    /**
     * 创建新会话
     */
    fun createSession(): Session {
        val newSession = createDefaultSession()

        // 将当前所有会话设置为非激活
        val updatedSessions = _sessions.value.map { it.copy(isActive = false) }

        // 添加新会话
        _sessions.value = updatedSessions + newSession.copy(isActive = true)
        _currentSession.value = newSession

        // 💾 保存当前 session ID 到 MMKV
        mmkv.encode(PREF_LAST_SESSION_ID, newSession.id)
        android.util.Log.d("SessionManager", "💾 保存新会话ID: ${newSession.id}")

        return newSession
    }

    /**
     * 切换到指定会话
     */
    fun switchSession(sessionId: String) {
        val session = _sessions.value.find { it.id == sessionId }
        if (session != null) {
            // 更新激活状态
            _sessions.value = _sessions.value.map {
                it.copy(isActive = it.id == sessionId)
            }
            _currentSession.value = session.copy(isActive = true)

            // 💾 保存当前 session ID 到 MMKV
            mmkv.encode(PREF_LAST_SESSION_ID, sessionId)
            android.util.Log.d("SessionManager", "💾 保存会话ID: $sessionId")
        }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        val currentSessions = _sessions.value
        if (currentSessions.size <= 1) {
            // 至少保留一个会话
            return
        }

        val remainingSessions = currentSessions.filter { it.id != sessionId }
        _sessions.value = remainingSessions

        // 如果删除的是当前会话，切换到最新的会话
        if (_currentSession.value.id == sessionId) {
            val newCurrent = remainingSessions.first()
            _currentSession.value = newCurrent.copy(isActive = true)
        }
    }

    /**
     * 添加消息到当前会话
     */
    fun addMessageToCurrentSession(message: ChatMessage) {
        val current = _currentSession.value
        val updatedMessages = current.messages + message
        val updatedSession = current.copy(messages = updatedMessages)

        // 更新会话列表
        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * 从当前会话移除消息
     */
    fun removeMessageFromCurrentSession(messageId: String) {
        val current = _currentSession.value
        val updatedMessages = current.messages.filter { it.id != messageId }
        val updatedSession = current.copy(messages = updatedMessages)

        // 更新会话列表
        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * 更新当前会话的标题
     */
    fun updateCurrentSessionTitle(title: String) {
        val current = _currentSession.value
        val updatedSession = current.copy(title = title)

        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * 自动生成当前会话的标题（基于首条用户消息）
     */
    fun autoGenerateCurrentSessionTitle() {
        val current = _currentSession.value
        val generatedTitle = current.generateTitle()
        if (generatedTitle != current.title) {
            updateCurrentSessionTitle(generatedTitle)
        }
    }

    /**
     * 清空当前会话的消息
     */
    fun clearCurrentSession() {
        val current = _currentSession.value
        val updatedSession = current.copy(
            messages = listOf(
                ChatMessage(
                    content = "聊天记录已清空。有什么可以帮到你的吗？",
                    isUser = false
                )
            )
        )

        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * 获取所有会话
     */
    fun getAllSessions(): List<Session> = _sessions.value

    /**
     * 获取会话数量
     */
    fun getSessionCount(): Int = _sessions.value.size
}
