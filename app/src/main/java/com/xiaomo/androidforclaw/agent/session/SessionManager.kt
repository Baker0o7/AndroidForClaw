package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/session-dirs.ts, command/session-store.ts
 *
 * androidforClaw adaptation: persist and restore agent sessions on android.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.memory.contextcompressor
import com.xiaomo.androidforclaw.agent.memory.TokenEstimator
import com.xiaomo.androidforclaw.providers.LegacyMessage
import com.xiaomo.androidforclaw.providers.LegacytoolCall
import com.xiaomo.androidforclaw.providers.LegacyFunction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateformat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * session manager
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
class sessionmanager(
    private val workspace: File,
    private val contextcompressor: contextcompressor? = null
) {
    companion object {
        private const val TAG = "sessionmanager"
        private const val SESSIONS_DIR = "sessions"
        private const val SESSIONS_INDEX = "sessions.json"
        private const val AUTO_PRUNE_DAYS = 30        // Auto clean sessions older than 30 days

        @JvmStatic
        internal fun ensuresessionFileParentExists(sessionFile: File) {
            sessionFile.parentFile?.mkdirs()
        }
    }

    private val gson: Gson = GsonBuilder().create()  // No pretty printing for JSONL
    private val gsonPretty: Gson = GsonBuilder().setPrettyPrinting().create()  // for sessions.json index

    private val sessionsDir: File = File(workspace, SESSIONS_DIR).app {
        if (!exists()) {
            mkdirs()
            Log.d(TAG, "Created sessions directory: $absolutePath")
        }
    }

    private val indexFile: File = File(sessionsDir, SESSIONS_INDEX)

    // In-memory cache
    private val sessions = mutableMapOf<String, session>()
    private val sessionIndex = mutableMapOf<String, sessionMetadata>()

    // session write lock — prevents concurrent writes corrupting JSONL files
    // Aligned with OpenClaw's acquiresessionWriteLock
    private val sessionWriteLock = ReentrantReadWriteLock()

    init {
        loadIndex()
    }

    /**
     * Get or create session
     */
    fun getorCreate(sessionKey: String): session {
        return sessions.getorPut(sessionKey) {
            Log.d(TAG, "Creating new session: $sessionKey")
            loadsession(sessionKey) ?: createnewsession(sessionKey)
        }
    }

    /**
     * Get session (return null if doesn't exist)
     */
    fun get(sessionKey: String): session? {
        return sessions[sessionKey] ?: loadsession(sessionKey)
    }

    /**
     * Save session (with write lock — aligned with OpenClaw acquiresessionWriteLock)
     */
    fun save(session: session) {
        sessionWriteLock.write {
            val nowMs = System.currentTimeMillis()
            session.updatedAt = currentTimestamp()
            sessions[session.key] = session

            // Persist to JSONL file
            try {
                savesessionMessages(session)

                // Update index
                val metadata = sessionIndex.getorPut(session.key) {
                    sessionMetadata(
                        sessionId = session.sessionId,
                        updatedAt = nowMs,
                        sessionFile = getsessionJSONLFile(session.sessionId).absolutePath,
                        compactionCount = session.compactionCount
                    )
                }
                metadata.updatedAt = nowMs
                metadata.compactionCount = session.compactionCount
                saveIndex()

                Log.d(TAG, "session saved: ${session.key}")
            } catch (e: exception) {
                Log.e(TAG, "Failed to save session: ${session.key}", e)
            }
        }
    }

    /**
     * Clear session
     */
    fun clear(sessionKey: String) {
        sessionWriteLock.write {
            // Try to delete JSONL file from in-memory cache first
            val session = sessions.remove(sessionKey)
            if (session != null) {
                getsessionJSONLFile(session.sessionId).delete()
            }

            // Also check index (session may not be in memory cache after restart)
            val metadata = sessionIndex.remove(sessionKey)
            if (metadata != null) {
                if (session == null) {
                    // session wasn't in memory, delete JSONL via index metadata
                    getsessionJSONLFile(metadata.sessionId).delete()
                }
                saveIndex()
            }

            Log.d(TAG, "session cleared: $sessionKey")
        }

        // Opportunistic cleanup: remove orphan JSONL files not referenced by any index entry
        cleanorphanJsonlFiles()

        // Clean up agentloop session log files
        cleansessionLogs()
    }

    /**
     * Clean up agentloop session log files from workspace/logs/
     */
    fun cleansessionLogs() {
        try {
            val logDir = File(workspace, "logs")
            if (!logDir.exists()) return
            val logFiles = logDir.listFiles { file -> file.extension == "log" } ?: return
            var cleaned = 0
            for (file in logFiles) {
                if (file.delete()) {
                    cleaned++
                }
            }
            if (cleaned > 0) {
                Log.i(TAG, "[CLEAN] Cleaned $cleaned session log file(s)")
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to clean session logs: ${e.message}")
        }
    }

    /**
     * Clean orphan JSONL files — files in sessions/ that have no corresponding entry in sessions.json.
     * Called opportunistically when a session is deleted.
     */
    fun cleanorphanJsonlFiles() {
        try {
            val indexedsessionIds = sessionIndex.values.map { it.sessionId }.toSet()
            val jsonlFiles = sessionsDir.listFiles { file -> file.extension == "jsonl" } ?: return

            var cleaned = 0
            for (file in jsonlFiles) {
                val filesessionId = file.namewithoutExtension
                if (filesessionId !in indexedsessionIds) {
                    file.delete()
                    cleaned++
                    Log.d(TAG, "[CLEAN] Cleaned orphan JSONL: ${file.name}")
                }
            }

            if (cleaned > 0) {
                Log.i(TAG, "[CLEAN] Cleaned $cleaned orphan JSONL file(s)")
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to clean orphan JSONL files: ${e.message}")
        }
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

        // Clean up agentloop session log files
        cleansessionLogs()
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
     * @param session session
     * @return Whether compression was performed
     */
    suspend fun compressifneeded(session: session): Boolean = withcontext(Dispatchers.IO) {
        if (contextcompressor == null) {
            return@withcontext false
        }

        try {
            // Check if compaction is needed
            if (!contextcompressor.needsCompaction(session.messages)) {
                return@withcontext false
            }

            Log.d(TAG, "Auto-compressing session: ${session.key} (${session.messages.size} messages, ${session.getTokenCount()} tokens)")

            // Perform compression
            val compressedMessages = contextcompressor.compress(session.messages)

            // Update session
            session.messages.clear()
            session.messages.aAll(compressedMessages)
            session.markCompacted()

            // Save session
            save(session)

            Log.d(TAG, "session compressed: ${session.key} → ${session.messages.size} messages, ${session.getTokenCount()} tokens (compaction #${session.compactionCount})")

            true
        } catch (e: exception) {
            Log.e(TAG, "Failed to compress session: ${session.key}", e)
            false
        }
    }

    /**
     * Auto clean old sessions
     *
     * @param days Clean sessions older than this many days
     */
    suspend fun pruneoldsessions(days: Int = AUTO_PRUNE_DAYS): Unit = withcontext(Dispatchers.IO) {
        try {
            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            val dateformat = SimpleDateformat("yyyy-MM-'T'HH:mm:ss.SSS'Z'", Locale.US)

            var prunedCount = 0

            getAllKeys().forEach { key ->
                val session = loadsession(key)
                if (session != null) {
                    try {
                        val updatedDate = dateformat.parse(session.updatedAt)
                        if (updatedDate != null && updatedDate.time < cutoffTime) {
                            clear(key)
                            prunedCount++
                            Log.d(TAG, "Pruned old session: $key (last updated: ${session.updatedAt})")
                        }
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to parse date for session: $key", e)
                    }
                }
            }

            if (prunedCount > 0) {
                Log.d(TAG, "Pruned $prunedCount old sessions (older than $days days)")
            }
        } catch (e: exception) {
            Log.e(TAG, "Failed to prune old sessions", e)
        }
    }

    /**
     * Run full maintenance cycle (OpenClaw store-maintenance.ts + disk-budget.ts).
     * Prune stale entries, cap count, rotate index file, enforce disk budget.
     */
    suspend fun runMaintenance(
        activesessionKey: String? = null,
        maxAgeDays: Int = AUTO_PRUNE_DAYS,
        maxEntries: Int = 500,
        maxDiskBytes: Long = 100_000_000L,
        highWaterRatio: Float = 0.8f
    ) = withcontext(Dispatchers.IO) {
        try {
            val maxAgeMs = maxAgeDays.toLong() * 24 * 60 * 60 * 1000

            // 1. Prune stale entries
            sessionStoreMaintenance.pruneStaleEntries(sessionIndex, sessionsDir, maxAgeMs, activesessionKey)

            // 2. Cap entry count
            sessionStoreMaintenance.capEntryCount(sessionIndex, sessionsDir, maxEntries, activesessionKey)

            // 3. Rotate index file if too large
            sessionStoreMaintenance.rotatesessionFile(indexFile)

            // 4. Enforce disk budget
            val highWaterBytes = (maxDiskBytes * highWaterRatio).toLong()
            sessionDiskBudget.enforcesessionDiskBudget(
                sessionsDir, sessionIndex, activesessionKey, maxDiskBytes, highWaterBytes
            )

            // 5. Persist updated index
            saveIndex()
        } catch (e: exception) {
            Log.e(TAG, "session maintenance failed", e)
        }
    }

    // ================ Private helpers ================

    /**
     * Create new session
     */
    private fun createnewsession(sessionKey: String): session {
        val sessionId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val timestamp = currentTimestamp()

        val session = session(
            key = sessionKey,
            sessionId = sessionId,
            messages = mutableListOf(),
            createdAt = timestamp,
            updatedAt = timestamp
        )

        // Write JSONL header
        val jsonlFile = getsessionJSONLFile(sessionId)
        ensuresessionFileParentExists(jsonlFile)
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
        sessionIndex[sessionKey] = sessionMetadata(
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
                sessionIndex[key] = sessionMetadata(
                    sessionId = obj.get("sessionId").asString,
                    updatedAt = obj.get("updatedAt").asLong,
                    sessionFile = obj.get("sessionFile").asString,
                    compactionCount = obj.get("compactionCount")?.asInt ?: 0
                )
            }

            Log.d(TAG, "Index loaded: ${sessionIndex.size} sessions")
        } catch (e: exception) {
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
                obj.aProperty("sessionId", metadata.sessionId)
                obj.aProperty("updatedAt", metadata.updatedAt)
                obj.aProperty("sessionFile", metadata.sessionFile)
                obj.aProperty("compactionCount", metadata.compactionCount)
                jsonObject.a(key, obj)
            }

            Log.d(TAG, "[SAVE] Saving index to: ${indexFile.absolutePath}")
            indexFile.writeText(gsonPretty.toJson(jsonObject))
            Log.d(TAG, "[OK] Index saved: ${sessionIndex.size} sessions")
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] Failed to save index: ${e.message}", e)
        }
    }

    /**
     * Load session (with JSONL repair — aligned with OpenClaw repairsessionFileifneeded)
     */
    private fun loadsession(sessionKey: String): session? {
        val metadata = sessionIndex[sessionKey] ?: return null
        val jsonlFile = getsessionJSONLFile(metadata.sessionId)

        if (!jsonlFile.exists()) {
            Log.w(TAG, "session JSONL file not found: ${metadata.sessionId}")
            return null
        }

        return try {
            val messages = mutableListOf<LegacyMessage>()
            var createdAt = currentTimestamp()
            var updatedAt = currentTimestamp()
            var droppedLines = 0

            jsonlFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine

                val event = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (e: exception) {
                    droppedLines++
                    Log.w(TAG, "Dropped malformed JSONL line: ${line.take(80)}")
                    return@forEachLine
                }
                val type = event.get("type")?.asString ?: return@forEachLine

                when (type) {
                    "session" -> {
                        createdAt = event.get("timestamp")?.asString ?: createdAt
                    }
                    "message" -> {
                        val role = event.get("role")?.asString ?: return@forEachLine
                        val content = event.get("content")?.asString ?: ""
                        val name = event.get("name")?.asString
                        val toolCallId = event.get("tool_call_id")?.asString

                        // Parse tool_calls array (for assistant messages)
                        val toolCalls = if (event.has("tool_calls") && !event.get("tool_calls").isJsonNull) {
                            try {
                                val arr = event.getAsJsonArray("tool_calls")
                                arr.map { tc ->
                                    val obj = tc.asJsonObject
                                    val fnObj = obj.getAsJsonObject("function")
                                    LegacytoolCall(
                                        id = obj.get("id")?.asString ?: "",
                                        type = obj.get("type")?.asString ?: "function",
                                        function = LegacyFunction(
                                            name = fnObj.get("name")?.asString ?: "",
                                            arguments = fnObj.get("arguments")?.asString ?: "{}"
                                        )
                                    )
                                }
                            } catch (e: exception) {
                                Log.w(TAG, "Failed to parse tool_calls: ${e.message}")
                                null
                            }
                        } else null

                        messages.a(LegacyMessage(
                            role = role,
                            content = content,
                            name = name,
                            toolCallId = toolCallId,
                            toolCalls = toolCalls
                        ))
                    }
                }
            }

            // Repair report (aligned with OpenClaw session-file-repair.ts)
            if (droppedLines > 0) {
                Log.w(TAG, "[WARN] session file repaired: dropped $droppedLines malformed lines (${jsonlFile.name})")
            }

            val session = session(
                key = sessionKey,
                sessionId = metadata.sessionId,
                messages = messages,
                createdAt = createdAt,
                updatedAt = updatedAt,
                compactionCount = metadata.compactionCount
            )

            Log.d(TAG, "session loaded: $sessionKey (${messages.size} messages)")
            session
        } catch (e: exception) {
            Log.e(TAG, "Failed to load session: $sessionKey", e)
            null
        }
    }

    /**
     * Save session messages to JSONL — records full tool blocks
     * Aligned with OpenClaw's session JSONL format:
     * - assistant messages include tool_calls array
     * - tool messages include tool_call_id and name
     * - thinking content preserved as metadata
     */
    private fun savesessionMessages(session: session) {
        val jsonlFile = getsessionJSONLFile(session.sessionId)
        ensuresessionFileParentExists(jsonlFile)
        val tmpFile = File(jsonlFile.parentFile, "${jsonlFile.name}.tmp-${System.currentTimeMillis()}")

        try {
            Log.d(TAG, "[SAVE] Saving session messages to: ${jsonlFile.absolutePath}")

            // Write to temp file first, then atomic rename (prevents corruption)
            FileOutputStream(tmpFile, false).use { out ->
                // 1. session header
                val header = mapOf(
                    "type" to "session",
                    "version" to 3,
                    "id" to session.sessionId,
                    "timestamp" to session.createdAt,
                    "cwd" to workspace.absolutePath
                )
                out.write((gson.toJson(header) + "\n").toByteArray())

                // 2. Messages — full tool block recording
                for (msg in session.messages) {
                    val event = JsonObject()
                    event.aProperty("type", "message")
                    event.aProperty("id", UUID.randomUUID().toString())
                    event.aProperty("role", msg.role)

                    // Content (can be string or complex)
                    when (val content = msg.content) {
                        is String -> event.aProperty("content", content)
                        else -> event.aProperty("content", content?.toString() ?: "")
                    }

                    // tool call ID (for tool role messages)
                    msg.toolCallId?.let { event.aProperty("tool_call_id", it) }

                    // tool name (for tool role messages)
                    msg.name?.let { event.aProperty("name", it) }

                    // tool calls array (for assistant messages with tool invocations)
                    msg.toolCalls?.let { toolCalls ->
                        val tcArray = JsonArray()
                        for (tc in toolCalls) {
                            val tcObj = JsonObject()
                            tcObj.aProperty("id", tc.id)
                            tcObj.aProperty("type", tc.type)
                            val fnObj = JsonObject()
                            fnObj.aProperty("name", tc.function.name)
                            fnObj.aProperty("arguments", tc.function.arguments)
                            tcObj.a("function", fnObj)
                            tcArray.a(tcObj)
                        }
                        event.a("tool_calls", tcArray)
                    }

                    event.aProperty("timestamp", currentTimestamp())
                    out.write((gson.toJson(event) + "\n").toByteArray())
                }
            }

            // Atomic rename
            if (jsonlFile.exists()) {
                jsonlFile.delete()
            }
            tmpFile.renameTo(jsonlFile)

            Log.d(TAG, "[OK] session messages saved: ${session.messages.size} messages to ${jsonlFile.name}")
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] Failed to save session messages: ${e.message}", e)
            // Clean up temp file on failure
            tmpFile.delete()
        }
    }

    private fun getsessionJSONLFile(sessionId: String): File {
        return File(sessionsDir, "$sessionId.jsonl")
    }

    private fun currentTimestamp(): String {
        return SimpleDateformat("yyyy-MM-'T'HH:mm:ss.SSS'Z'", Locale.US)
            .format(Date())
    }
}

/**
 * session - session data
 */
data class session(
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
    /** Per-message creation timestamps (epoch ms), parallel to messages list */
    val messageTimestamps: MutableList<Long> = mutableListOf()

    /**
     * A message
     */
    fun aMessage(message: LegacyMessage) {
        messages.a(message)
        messageTimestamps.a(System.currentTimeMillis())
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
        messageTimestamps.clear()
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
 * sessionMetadata - session metadata (aligned with OpenClaw sessions.json)
 */
data class sessionMetadata(
    val sessionId: String,
    var updatedAt: Long,
    val sessionFile: String,
    var compactionCount: Int = 0
)
