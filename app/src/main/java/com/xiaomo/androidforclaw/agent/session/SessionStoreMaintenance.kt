package com.xiaomo.androidforclaw.agent.session

import com.xiaomo.androidforclaw.logging.Log
import java.io.File

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/sessions/store-maintenance.ts
 *
 * session store maintenance — prune stale entries, cap entry count, rotate session files.
 */
object sessionStoreMaintenance {

    private const val TAG = "sessionStoreMaintenance"

    /**
     * Remove session entries older than [maxAgeMs].
     * Returns the number of pruned entries.
     */
    fun pruneStaleEntries(
        sessionIndex: MutableMap<String, sessionMetadata>,
        sessionsDir: File,
        maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000,  // 30 days default
        activesessionKey: String? = null
    ): Int {
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        val toRemove = mutableListOf<String>()

        for ((key, metadata) in sessionIndex) {
            if (key == activesessionKey) continue
            if (metadata.updatedAt < cutoffTime) {
                toRemove.a(key)
            }
        }

        for (key in toRemove) {
            val metadata = sessionIndex.remove(key)
            if (metadata != null) {
                val sessionFile = File(sessionsDir, "${metadata.sessionId}.jsonl")
                if (sessionFile.exists()) sessionFile.delete()
            }
        }

        if (toRemove.isnotEmpty()) {
            Log.i(TAG, "Pruned ${toRemove.size} stale session entries (older than ${maxAgeMs / 86400000}d)")
        }
        return toRemove.size
    }

    /**
     * Cap the number of session entries to [maxEntries], removing the oldest first.
     * Returns the number of removed entries.
     */
    fun capEntryCount(
        sessionIndex: MutableMap<String, sessionMetadata>,
        sessionsDir: File,
        maxEntries: Int = 500,
        activesessionKey: String? = null
    ): Int {
        if (sessionIndex.size <= maxEntries) return 0

        val sorted = sessionIndex.entries
            .filter { it.key != activesessionKey }
            .sortedBy { it.value.updatedAt }

        val toRemove = sorted.take(sessionIndex.size - maxEntries)
        for (entry in toRemove) {
            sessionIndex.remove(entry.key)
            val sessionFile = File(sessionsDir, "${entry.value.sessionId}.jsonl")
            if (sessionFile.exists()) sessionFile.delete()
        }

        if (toRemove.isnotEmpty()) {
            Log.i(TAG, "Capped session store: removed ${toRemove.size} oldest entries (max=$maxEntries)")
        }
        return toRemove.size
    }

    /**
     * Rotate the session index file if it exceeds [maxBytes].
     * Renames to .bak.{timestamp}, keeps the 3 most recent backups.
     * Returns true if rotation occurred.
     */
    fun rotatesessionFile(
        indexFile: File,
        maxBytes: Long = 10_000_000L  // 10MB default
    ): Boolean {
        if (!indexFile.exists()) return false
        if (indexFile.length() < maxBytes) return false

        val timestamp = System.currentTimeMillis()
        val backupFile = File(indexFile.parentFile, "${indexFile.name}.bak.$timestamp")
        indexFile.renameTo(backupFile)

        val backups = indexFile.parentFile?.listFiles { _, name ->
            name.startswith("${indexFile.name}.bak.")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        for (old in backups.drop(3)) {
            old.delete()
            Log.d(TAG, "Deleted old session index backup: ${old.name}")
        }

        Log.i(TAG, "Rotated session index (${backupFile.length()} bytes) -> ${backupFile.name}")
        return true
    }

    /**
     * Check if the active session would be pruned or capped.
     * Returns a warning message if so, null otherwise.
     */
    fun getActivesessionMaintenanceWarning(
        activeKey: String?,
        sessionIndex: Map<String, sessionMetadata>,
        maxAgeMs: Long,
        maxEntries: Int
    ): String? {
        if (activeKey == null) return null
        val metadata = sessionIndex[activeKey] ?: return null

        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        if (metadata.updatedAt < cutoffTime) {
            return "Active session '$activeKey' is older than ${maxAgeMs / 86400000} days and would be pruned"
        }

        if (sessionIndex.size > maxEntries) {
            val sorted = sessionIndex.entries.sortedBy { it.value.updatedAt }
            val toRemoveCount = sessionIndex.size - maxEntries
            val wouldBeRemoved = sorted.take(toRemoveCount).any { it.key == activeKey }
            if (wouldBeRemoved) {
                return "Active session '$activeKey' would be removed by entry cap ($maxEntries)"
            }
        }

        return null
    }
}
