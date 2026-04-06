/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/session-dirs.ts, command/session-store.ts
 *
 * AndroidForClaw adaptation: session persistence.
 */
package com.xiaomo.androidforclaw.session

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * JSONL Session Storage
 * Aligned with OpenClaw 的 agents/main/sessions/ 架构
 *
 * JSONL 格式:
 * - 每条Message一Row JSON
 * - 增量追加, 不Override整个文件
 * - 易于Parse和流式Read
 */
class JsonlSessionStorage(private val context: Context) {

    companion object {
        private const val TAG = "JsonlSessionStorage"

        // Align with OpenClaw: agents/main/sessions/
        private val SESSIONS_DIR = StoragePaths.sessions.absolutePath
        private val SESSIONS_INDEX_FILE = "$SESSIONS_DIR/sessions.json"
    }

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    init {
        ensureDirectoryExists()
    }

    /**
     * CreateNewSession
     */
    fun createSession(title: String = "New Session"): String {
        val sessionId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        // Create session file
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        sessionFile.createNewFile()

        // Update index
        val index = loadSessionsIndex().toMutableMap()
        index[sessionId] = SessionMetadata(
            title = title,
            createdAt = now,
            lastMessageAt = now,
            messageCount = 0
        )
        saveSessionsIndex(index)

        Log.i(TAG, "CreateNewSession: $sessionId")
        return sessionId
    }

    /**
     * 追加Message到Session (JSONL 格式)
     */
    fun appendMessage(sessionId: String, message: SessionMessage) {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (!sessionFile.exists()) {
            Log.e(TAG, "Session文件不Exists: $sessionId")
            return
        }

        // Append one line of JSON
        val jsonLine = gson.toJson(message)
        sessionFile.appendText("$jsonLine\n")

        // Update index
        updateSessionMetadata(sessionId) { metadata ->
            metadata.copy(
                lastMessageAt = Instant.now().toString(),
                messageCount = metadata.messageCount + 1
            )
        }

        Log.d(TAG, "追加Message到Session $sessionId: ${message.role}")
    }

    /**
     * ReadSessionAllMessage
     */
    fun loadSession(sessionId: String): List<SessionMessage> {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (!sessionFile.exists()) {
            Log.w(TAG, "Session文件不Exists: $sessionId")
            return emptyList()
        }

        return try {
            sessionFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        gson.fromJson(line, SessionMessage::class.java)
                    } catch (e: Exception) {
                        Log.e(TAG, "ParseMessageFailed: $line", e)
                        null
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "LoadSessionFailed: $sessionId", e)
            emptyList()
        }
    }

    /**
     * GetAllSessionList
     */
    fun listSessions(): Map<String, SessionMetadata> {
        return loadSessionsIndex()
    }

    /**
     * GetSession元Data
     */
    fun getSessionMetadata(sessionId: String): SessionMetadata? {
        return loadSessionsIndex()[sessionId]
    }

    /**
     * UpdateSessionTitle
     */
    fun updateSessionTitle(sessionId: String, newTitle: String) {
        updateSessionMetadata(sessionId) { metadata ->
            metadata.copy(title = newTitle)
        }
    }

    /**
     * DeleteSession
     */
    fun deleteSession(sessionId: String): Boolean {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        val deleted = sessionFile.delete()

        if (deleted) {
            // Remove from index
            val index = loadSessionsIndex().toMutableMap()
            index.remove(sessionId)
            saveSessionsIndex(index)
            Log.i(TAG, "DeleteSession: $sessionId")
        }

        return deleted
    }

    /**
     * 清NullSession(保留文件但清NullInside容)
     */
    fun clearSession(sessionId: String) {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (sessionFile.exists()) {
            sessionFile.writeText("")
            updateSessionMetadata(sessionId) { metadata ->
                metadata.copy(
                    messageCount = 0,
                    lastMessageAt = Instant.now().toString()
                )
            }
            Log.i(TAG, "清NullSession: $sessionId")
        }
    }

    /**
     * ExportSession为 JSONL 文件
     */
    fun exportSession(sessionId: String, outputPath: String): Boolean {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        val outputFile = File(outputPath)

        return try {
            sessionFile.copyTo(outputFile, overwrite = true)
            Log.i(TAG, "ExportSession $sessionId 到 $outputPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ExportSessionFailed", e)
            false
        }
    }

    /**
     * Import JSONL Session文件
     */
    fun importSession(inputPath: String, title: String? = null): String? {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            Log.e(TAG, "Import文件不Exists: $inputPath")
            return null
        }

        val sessionId = UUID.randomUUID().toString()
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")

        return try {
            inputFile.copyTo(sessionFile)

            // Count messages
            val messageCount = sessionFile.readLines().count { it.isNotBlank() }

            // Create index
            val index = loadSessionsIndex().toMutableMap()
            index[sessionId] = SessionMetadata(
                title = title ?: "Imported Session",
                createdAt = Instant.now().toString(),
                lastMessageAt = Instant.now().toString(),
                messageCount = messageCount
            )
            saveSessionsIndex(index)

            Log.i(TAG, "ImportSessionSuccess: $sessionId")
            sessionId
        } catch (e: Exception) {
            Log.e(TAG, "ImportSessionFailed", e)
            null
        }
    }

    /**
     * GetSessionStatistics info
     */
    fun getSessionStats(sessionId: String): SessionStats? {
        val messages = loadSession(sessionId)
        if (messages.isEmpty()) return null

        val userMessages = messages.count { it.role == "user" }
        val assistantMessages = messages.count { it.role == "assistant" }
        val systemMessages = messages.count { it.role == "system" }

        val firstMessage = messages.firstOrNull()
        val lastMessage = messages.lastOrNull()

        return SessionStats(
            sessionId = sessionId,
            totalMessages = messages.size,
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            systemMessages = systemMessages,
            firstMessageTime = firstMessage?.timestamp,
            lastMessageTime = lastMessage?.timestamp
        )
    }

    // ==================== Private methods ====================

    private fun ensureDirectoryExists() {
        val dir = File(SESSIONS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            Log.d(TAG, "Create sessions 目录: $SESSIONS_DIR")
        }
    }

    /**
     * Load sessions.json Index
     */
    private fun loadSessionsIndex(): Map<String, SessionMetadata> {
        val indexFile = File(SESSIONS_INDEX_FILE)
        if (!indexFile.exists()) {
            return emptyMap()
        }

        return try {
            val json = indexFile.readText()
            val wrapper = gson.fromJson(json, SessionsIndexWrapper::class.java)
            wrapper.sessions
        } catch (e: Exception) {
            Log.e(TAG, "Load sessions.json Failed", e)
            emptyMap()
        }
    }

    /**
     * Save sessions.json Index
     */
    private fun saveSessionsIndex(sessions: Map<String, SessionMetadata>) {
        val indexFile = File(SESSIONS_INDEX_FILE)

        try {
            val wrapper = SessionsIndexWrapper(sessions)
            val json = gson.toJson(wrapper)
            indexFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Save sessions.json Failed", e)
        }
    }

    /**
     * UpdateSession元Data
     */
    private fun updateSessionMetadata(
        sessionId: String,
        update: (SessionMetadata) -> SessionMetadata
    ) {
        val index = loadSessionsIndex().toMutableMap()
        val metadata = index[sessionId] ?: return

        index[sessionId] = update(metadata)
        saveSessionsIndex(index)
    }
}

/**
 * Session Message (JSONL 每Row一条)
 */
data class SessionMessage(
    val role: String,              // "user" | "assistant" | "system" | "tool"
    val content: String,           // Message content
    val timestamp: String,         // ISO 8601 timestamp
    val name: String? = null,      // Optional: tool name or username
    val toolCallId: String? = null, // Optional: tool call ID
    val metadata: Map<String, Any?>? = null  // Optional: extra metadata
)

/**
 * Session 元Data (sessions.json 中Storage)
 */
data class SessionMetadata(
    val title: String,
    val createdAt: String,
    val lastMessageAt: String,
    val messageCount: Int
)

/**
 * sessions.json Package装器
 */
private data class SessionsIndexWrapper(
    val sessions: Map<String, SessionMetadata>
)

/**
 * Session Statistics info
 */
data class SessionStats(
    val sessionId: String,
    val totalMessages: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val systemMessages: Int,
    val firstMessageTime: String?,
    val lastMessageTime: String?
)
