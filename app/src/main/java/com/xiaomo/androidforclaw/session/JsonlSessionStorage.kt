/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/session-dirs.ts, command/session-store.ts
 *
 * androidforClaw adaptation: session persistence.
 */
package com.xiaomo.androidforclaw.session

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * JSONL session Storage
 * Aligned with OpenClaw agents/main/sessions/ architecture
 *
 * JSONL format:
 * - Each message is one row JSON
 * - Incremental append, not override entire count files
 * - Easy to parse and stream read
 */
class JsonlsessionStorage(private val context: context) {

    companion object {
        private const val TAG = "JsonlsessionStorage"

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
     * Createnewsession
     */
    fun createsession(title: String = "new session"): String {
        val sessionId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        // Create session file
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        sessionFile.createnewFile()

        // Update index
        val index = loadsessionsIndex().toMutableMap()
        index[sessionId] = sessionMetadata(
            title = title,
            createdAt = now,
            lastMessageAt = now,
            messageCount = 0
        )
        savesessionsIndex(index)

        Log.i(TAG, "Createnewsession: $sessionId")
        return sessionId
    }

    /**
     * Append message to session (JSONL format)
     */
    fun appendMessage(sessionId: String, message: sessionMessage) {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (!sessionFile.exists()) {
            Log.e(TAG, "sessionfilesnotExists: $sessionId")
            return
        }

        // append one line of JSON
        val jsonLine = gson.toJson(message)
        sessionFile.appendText("$jsonLine\n")

        // Update index
        updatesessionMetadata(sessionId) { metadata ->
            metadata.copy(
                lastMessageAt = Instant.now().toString(),
                messageCount = metadata.messageCount + 1
            )
        }

        Log.d(TAG, "Appended message to session $sessionId: ${message.role}")
    }

    /**
     * Read session all messages
     */
    fun loadsession(sessionId: String): List<sessionMessage> {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (!sessionFile.exists()) {
            Log.w(TAG, "sessionfilesnotExists: $sessionId")
            return emptyList()
        }

        return try {
            sessionFile.readLines()
                .filter { it.isnotBlank() }
                .mapnotNull { line ->
                    try {
                        gson.fromJson(line, sessionMessage::class.java)
                    } catch (e: exception) {
                        Log.e(TAG, "ParseMessageFailed: $line", e)
                        null
                    }
                }
        } catch (e: exception) {
            Log.e(TAG, "LoadsessionFailed: $sessionId", e)
            emptyList()
        }
    }

    /**
     * Get all sessions list
     */
    fun listsessions(): Map<String, sessionMetadata> {
        return loadsessionsIndex()
    }

    /**
     * Get session metadata
     */
    fun getsessionMetadata(sessionId: String): sessionMetadata? {
        return loadsessionsIndex()[sessionId]
    }

    /**
     * Update session title
     */
    fun updatesessionTitle(sessionId: String, newTitle: String) {
        updatesessionMetadata(sessionId) { metadata ->
            metadata.copy(title = newTitle)
        }
    }

    /**
     * Delete session
     */
    fun deletesession(sessionId: String): Boolean {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        val deleted = sessionFile.delete()

        if (deleted) {
            // Remove from index
            val index = loadsessionsIndex().toMutableMap()
            index.remove(sessionId)
            savesessionsIndex(index)
            Log.i(TAG, "Deletesession: $sessionId")
        }

        return deleted
    }

    /**
     * Clear session (keep files but clear content)
     */
    fun clearsession(sessionId: String) {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        if (sessionFile.exists()) {
            sessionFile.writeText("")
            updatesessionMetadata(sessionId) { metadata ->
                metadata.copy(
                    messageCount = 0,
                    lastMessageAt = Instant.now().toString()
                )
            }
            Log.i(TAG, "Cleared session: $sessionId")
        }
    }

    /**
     * Export session for JSONL files
     */
    fun exportsession(sessionId: String, outputPath: String): Boolean {
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")
        val outputFile = File(outputPath)

        return try {
            sessionFile.copyTo(outputFile, overwrite = true)
            Log.i(TAG, "Exportsession $sessionId to $outputPath")
            true
        } catch (e: exception) {
            Log.e(TAG, "ExportsessionFailed", e)
            false
        }
    }

    /**
     * Import JSONL session files
     */
    fun importsession(inputPath: String, title: String? = null): String? {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            Log.e(TAG, "ImportfilesnotExists: $inputPath")
            return null
        }

        val sessionId = UUID.randomUUID().toString()
        val sessionFile = File(SESSIONS_DIR, "$sessionId.jsonl")

        return try {
            inputFile.copyTo(sessionFile)

            // Count messages
            val messageCount = sessionFile.readLines().count { it.isnotBlank() }

            // Create index
            val index = loadsessionsIndex().toMutableMap()
            index[sessionId] = sessionMetadata(
                title = title ?: "Imported session",
                createdAt = Instant.now().toString(),
                lastMessageAt = Instant.now().toString(),
                messageCount = messageCount
            )
            savesessionsIndex(index)

            Log.i(TAG, "ImportsessionSuccess: $sessionId")
            sessionId
        } catch (e: exception) {
            Log.e(TAG, "ImportsessionFailed", e)
            null
        }
    }

    /**
     * Get session statistics info
     */
    fun getsessionStats(sessionId: String): sessionStats? {
        val messages = loadsession(sessionId)
        if (messages.isEmpty()) return null

        val userMessages = messages.count { it.role == "user" }
        val assistantMessages = messages.count { it.role == "assistant" }
        val systemMessages = messages.count { it.role == "system" }

        val firstMessage = messages.firstorNull()
        val lastMessage = messages.lastorNull()

        return sessionStats(
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
            Log.d(TAG, "Create sessions directory: $SESSIONS_DIR")
        }
    }

    /**
     * Load sessions.json index
     */
    private fun loadsessionsIndex(): Map<String, sessionMetadata> {
        val indexFile = File(SESSIONS_INDEX_FILE)
        if (!indexFile.exists()) {
            return emptyMap()
        }

        return try {
            val json = indexFile.readText()
            val wrapper = gson.fromJson(json, sessionsIndexWrapper::class.java)
            wrapper.sessions
        } catch (e: exception) {
            Log.e(TAG, "Load sessions.json Failed", e)
            emptyMap()
        }
    }

    /**
     * Save sessions.json index
     */
    private fun savesessionsIndex(sessions: Map<String, sessionMetadata>) {
        val indexFile = File(SESSIONS_INDEX_FILE)

        try {
            val wrapper = sessionsIndexWrapper(sessions)
            val json = gson.toJson(wrapper)
            indexFile.writeText(json)
        } catch (e: exception) {
            Log.e(TAG, "Save sessions.json Failed", e)
        }
    }

    /**
     * Updatesession元Data
     */
    private fun updatesessionMetadata(
        sessionId: String,
        update: (sessionMetadata) -> sessionMetadata
    ) {
        val index = loadsessionsIndex().toMutableMap()
        val metadata = index[sessionId] ?: return

        index[sessionId] = update(metadata)
        savesessionsIndex(index)
    }
}

/**
 * session Message (JSONL 每Rowonecount)
 */
data class sessionMessage(
    val role: String,              // "user" | "assistant" | "system" | "tool"
    val content: String,           // Message content
    val timestamp: String,         // ISO 8601 timestamp
    val name: String? = null,      // Optional: tool name or username
    val toolCallId: String? = null, // Optional: tool call ID
    val metadata: Map<String, Any?>? = null  // Optional: extra metadata
)

/**
 * session 元Data (sessions.json 中Storage)
 */
data class sessionMetadata(
    val title: String,
    val createdAt: String,
    val lastMessageAt: String,
    val messageCount: Int
)

/**
 * sessions.json Package装器
 */
private data class sessionsIndexWrapper(
    val sessions: Map<String, sessionMetadata>
)

/**
 * session Statistics info
 */
data class sessionStats(
    val sessionId: String,
    val totalMessages: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val systemMessages: Int,
    val firstMessageTime: String?,
    val lastMessageTime: String?
)
