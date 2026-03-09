package com.xiaomo.androidforclaw.agent.session

import android.util.Log
import com.xiaomo.androidforclaw.agent.memory.ContextCompressor
import com.xiaomo.androidforclaw.agent.memory.TokenEstimator
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Session Manager
 * Aligned with OpenClaw session management
 *
 * Storage format (OpenClaw Protocol):
 * - sessions.json: Metadata index {"agent:main:main": {"sessionId":"uuid", "updatedAt":1234567890, "sessionFile":"/path/to/uuid.jsonl", ...}}
 * - {sessionId}.jsonl: Message history (JSONL, one event per line)
 *
 * Responsibilities:
 * 1. Manage conversation history
 * 2. Persist session data (JSONL format)
 * 3. Auto context compression
 * 4. Token budget management
 * 5. Provide session create, get, save, clear functions
 */
class SessionManager(
    private val workspace: File,
    private val contextCompressor: ContextCompressor? = null
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val SESSIONS_DIR = "sessions"
        private const val SESSIONS_INDEX = "sessions.json"
        private const val AUTO_PRUNE_DAYS = 30        // Auto clean sessions older than 30 days
    }

    private val gson: Gson = GsonBuilder().create()  // No pretty printing for JSONL
    private val gsonPretty: Gson = GsonBuilder().setPrettyPrinting().create()  // For sessions.json index

    private val sessionsDir: File = File(workspace, SESSIONS_DIR).apply {
        if (!exists()) {
            mkdirs()
            Log.d(TAG, "Created sessions directory: $absolutePath")
        }
    }

    private val indexFile: File = File(sessionsDir, SESSIONS_INDEX)

    // In-memory cache
    private val sessions = mutableMapOf<String, Session>()
    private val sessionIndex = mutableMapOf<String, SessionMetadata>()

    init {
        loadIndex()
    }

    /**
     * Get or create session
     */
    fun getOrCreate(sessionKey: String): Session {
        return sessions.getOrPut(sessionKey) {
            Log.d(TAG, "Creating new session: $sessionKey")
            loadSession(sessionKey) ?: createNewSession(sessionKey)
        }
    }

    /**
     * Get session (return null if doesn't exist)
     */
    fun get(sessionKey: String): Session? {
        return sessions[sessionKey] ?: loadSession(sessionKey)
    }

    /**
     * Save session
     */
    fun save(session: Session) {
        val nowMs = System.currentTimeMillis()
        session.updatedAt = currentTimestamp()
        sessions[session.key] = session

        // Persist to JSONL file
        try {
            saveSessionMessages(session)

            // Update index
            val metadata = sessionIndex.getOrPut(session.key) {
                SessionMetadata(
                    sessionId = session.sessionId,
                    updatedAt = nowMs,
                    sessionFile = getSessionJSONLFile(session.sessionId).absolutePath,
                    compactionCount = session.compactionCount
                )
            }
            metadata.updatedAt = nowMs
            metadata.compactionCount = session.compactionCount
            saveIndex()

            Log.d(TAG, "Session saved: ${session.key}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${session.key}", e)
        }
    }

    /**
     * Clear session
     */
    fun clear(sessionKey: String) {
        val session = sessions.remove(sessionKey)
        if (session != null) {
            getSessionJSONLFile(session.sessionId).delete()
            sessionIndex.remove(sessionKey)
            saveIndex()
        }
        Log.d(TAG, "Session cleared: $sessionKey")
    }

    /**
     * Clear all sessions
     */
    fun clearAll() {
        sessions.clear()
        sessionIndex.clear()
        sessionsDir.listFiles()?.forEach {
            if (it.extension == "jsonl") {
                it.delete()
            }
        }
        indexFile.delete()
        Log.d(TAG, "All sessions cleared")
    }

    /**
     * Get all session keys (only return new format sessions)
     */
    fun getAllKeys(): List<String> {
        loadIndex()
        // Only return sessions from index (new format), ignore old .json files
        return sessionIndex.keys.toList()
    }

    /**
     * Check and auto compress session
     *
     * @param session Session
     * @return Whether compression was performed
     */
    suspend fun compressIfNeeded(session: Session): Boolean = withContext(Dispatchers.IO) {
        if (contextCompressor == null) {
            return@withContext false
        }

        try {
            // Check if compaction is needed
            if (!contextCompressor.needsCompaction(session.messages)) {
                return@withContext false
            }

            Log.d(TAG, "Auto-compressing session: ${session.key} (${session.messages.size} messages, ${session.getTokenCount()} tokens)")

            // Perform compression
            val compressedMessages = contextCompressor.compress(session.messages)

            // Update session
            session.messages.clear()
            session.messages.addAll(compressedMessages)
            session.markCompacted()

            // Save session
            save(session)

            Log.d(TAG, "Session compressed: ${session.key} → ${session.messages.size} messages, ${session.getTokenCount()} tokens (compaction #${session.compactionCount})")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress session: ${session.key}", e)
            false
        }
    }

    /**
     * Auto clean old sessions
     *
     * @param days Clean sessions older than this many days
     */
    suspend fun pruneOldSessions(days: Int = AUTO_PRUNE_DAYS): Unit = withContext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

            var prunedCount = 0

            getAllKeys().forEach { key ->
                val session = loadSession(key)
                if (session != null) {
                    try {
                        val updatedDate = dateFormat.parse(session.updatedAt)
                        if (updatedDate != null && updatedDate.time < cutoffTime) {
                            clear(key)
                            prunedCount++
                            Log.d(TAG, "Pruned old session: $key (last updated: ${session.updatedAt})")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse date for session: $key", e)
                    }
                }
            }

            if (prunedCount > 0) {
                Log.d(TAG, "Pruned $prunedCount old sessions (older than $days days)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune old sessions", e)
        }
    }

    // ================ Private Helpers ================

    /**
     * Create new session
     */
    private fun createNewSession(sessionKey: String): Session {
        val sessionId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val timestamp = currentTimestamp()

        val session = Session(
            key = sessionKey,
            sessionId = sessionId,
            messages = mutableListOf(),
            createdAt = timestamp,
            updatedAt = timestamp
        )

        // 写入 JSONL header
        val jsonlFile = getSessionJSONLFile(sessionId)
        FileOutputStream(jsonlFile, false).use { out ->
            val header = mapOf(
                "type" to "session",
                "version" to 3,
                "id" to sessionId,
                "timestamp" to timestamp,
                "cwd" to workspace.absolutePath
            )
            out.write((gson.toJson(header) + "\n").toByteArray())
        }

        // Update index
        sessionIndex[sessionKey] = SessionMetadata(
            sessionId = sessionId,
            updatedAt = nowMs,
            sessionFile = jsonlFile.absolutePath,
            compactionCount = 0
        )
        saveIndex()

        return session
    }

    /**
     * Load index file
     */
    private fun loadIndex() {
        if (!indexFile.exists()) {
            return
        }

        try {
            val json = indexFile.readText()
            val jsonObject = JsonParser.parseString(json).asJsonObject

            sessionIndex.clear()
            for ((key, value) in jsonObject.entrySet()) {
                val obj = value.asJsonObject
                sessionIndex[key] = SessionMetadata(
                    sessionId = obj.get("sessionId").asString,
                    updatedAt = obj.get("updatedAt").asLong,
                    sessionFile = obj.get("sessionFile").asString,
                    compactionCount = obj.get("compactionCount")?.asInt ?: 0
                )
            }

            Log.d(TAG, "Index loaded: ${sessionIndex.size} sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load index", e)
        }
    }

    /**
     * Save index file
     */
    private fun saveIndex() {
        try {
            val jsonObject = JsonObject()
            for ((key, metadata) in sessionIndex) {
                val obj = JsonObject()
                obj.addProperty("sessionId", metadata.sessionId)
                obj.addProperty("updatedAt", metadata.updatedAt)
                obj.addProperty("sessionFile", metadata.sessionFile)
                obj.addProperty("compactionCount", metadata.compactionCount)
                jsonObject.add(key, obj)
            }

            Log.d(TAG, "💾 Saving index to: ${indexFile.absolutePath}")
            indexFile.writeText(gsonPretty.toJson(jsonObject))
            Log.d(TAG, "✅ Index saved: ${sessionIndex.size} sessions")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save index: ${e.message}", e)
        }
    }

    /**
     * Load session
     */
    private fun loadSession(sessionKey: String): Session? {
        val metadata = sessionIndex[sessionKey] ?: return null
        val jsonlFile = getSessionJSONLFile(metadata.sessionId)

        if (!jsonlFile.exists()) {
            Log.w(TAG, "Session JSONL file not found: ${metadata.sessionId}")
            return null
        }

        return try {
            val messages = mutableListOf<LegacyMessage>()
            var createdAt = currentTimestamp()
            var updatedAt = currentTimestamp()

            jsonlFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine

                val event = JsonParser.parseString(line).asJsonObject
                val type = event.get("type")?.asString ?: return@forEachLine

                when (type) {
                    "session" -> {
                        createdAt = event.get("timestamp")?.asString ?: createdAt
                    }
                    "message" -> {
                        val role = event.get("role")?.asString ?: return@forEachLine
                        val content = event.get("content")?.asString ?: ""

                        messages.add(LegacyMessage(
                            role = role,
                            content = content
                        ))
                    }
                }
            }

            val session = Session(
                key = sessionKey,
                sessionId = metadata.sessionId,
                messages = messages,
                createdAt = createdAt,
                updatedAt = updatedAt,
                compactionCount = metadata.compactionCount
            )

            Log.d(TAG, "Session loaded: $sessionKey (${messages.size} messages)")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session: $sessionKey", e)
            null
        }
    }

    /**
     * Save session messages to JSONL
     */
    private fun saveSessionMessages(session: Session) {
        val jsonlFile = getSessionJSONLFile(session.sessionId)

        try {
            Log.d(TAG, "💾 Saving session messages to: ${jsonlFile.absolutePath}")

            // Rewrite entire file
            FileOutputStream(jsonlFile, false).use { out ->
                // 1. Session header
                val header = mapOf(
                    "type" to "session",
                    "version" to 3,
                    "id" to session.sessionId,
                    "timestamp" to session.createdAt,
                    "cwd" to workspace.absolutePath
                )
                out.write((gson.toJson(header) + "\n").toByteArray())

                // 2. Messages
                for (msg in session.messages) {
                    val event = mapOf(
                        "type" to "message",
                        "id" to UUID.randomUUID().toString(),
                        "role" to msg.role,
                        "content" to msg.content,
                        "timestamp" to currentTimestamp()
                    )
                    out.write((gson.toJson(event) + "\n").toByteArray())
                }
            }

            Log.d(TAG, "✅ Session messages saved: ${session.messages.size} messages to ${jsonlFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save session messages: ${e.message}", e)
        }
    }

    private fun getSessionJSONLFile(sessionId: String): File {
        return File(sessionsDir, "$sessionId.jsonl")
    }

    private fun currentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .format(Date())
    }
}

/**
 * Session - Session data
 */
data class Session(
    val key: String,
    val sessionId: String,                     // UUID (aligned with OpenClaw)
    var messages: MutableList<LegacyMessage>,
    var createdAt: String,
    var updatedAt: String,
    var metadata: MutableMap<String, Any?> = mutableMapOf(),
    var compactionCount: Int = 0,              // Compaction count
    var totalTokens: Int = 0,                  // Total token count
    var totalTokensFresh: Boolean = false      // Whether token data is fresh
) {
    /**
     * Add message
     */
    fun addMessage(message: LegacyMessage) {
        messages.add(message)
        totalTokensFresh = false  // Mark token count as stale
    }

    /**
     * Get recent N messages
     */
    fun getRecentMessages(count: Int): List<LegacyMessage> {
        return if (messages.size <= count) {
            messages.toList()
        } else {
            messages.takeLast(count)
        }
    }

    /**
     * Clear messages
     */
    fun clearMessages() {
        messages.clear()
        totalTokens = 0
        totalTokensFresh = true
    }

    /**
     * Get message count
     */
    fun messageCount(): Int {
        return messages.size
    }

    /**
     * Update token count
     */
    fun updateTokenCount() {
        totalTokens = TokenEstimator.estimateMessagesTokens(messages)
        totalTokensFresh = true
    }

    /**
     * Get token count (recalculate if not fresh)
     */
    fun getTokenCount(): Int {
        if (!totalTokensFresh) {
            updateTokenCount()
        }
        return totalTokens
    }

    /**
     * Mark as compacted
     */
    fun markCompacted() {
        compactionCount++
        totalTokensFresh = false
    }
}

/**
 * SessionMetadata - Session metadata (aligned with OpenClaw sessions.json)
 */
data class SessionMetadata(
    val sessionId: String,
    var updatedAt: Long,
    val sessionFile: String,
    var compactionCount: Int = 0
)
