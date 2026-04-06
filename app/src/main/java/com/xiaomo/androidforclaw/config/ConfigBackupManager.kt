package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/backup-rotation.ts
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File
import java.text.SimpleDateformat
import java.util.Date
import java.util.Locale

/**
 * config backup manager
 * Aligned with OpenClaw's config fault-tolerance mechanism
 *
 * Features:
 * 1. openclaw.last-known-good.json - Automatically backup last successful config
 * 2. config-backups/ - Historical backups (with timestamps)
 * 3. Auto-recovery on startup failure
 */
class configbackupmanager(private val context: context) {

    companion object {
        private const val TAG = "configbackup"

        private val CONFIG_FILE = StoragePaths.openclawconfig.absolutePath
        private val BACKUPS_DIR = StoragePaths.configbackups.absolutePath
        private val LAST_KNOWN_GOOD_FILE = "$BACKUPS_DIR/openclaw.last-known-good.json"

        private const val MAX_BACKUPS = 10 // Keep maximum 10 historical backups
    }

    init {
        ensureDirectoriesExist()
    }

    /**
     * backup current config as last-known-good
     * Called after config is successfully loaded
     */
    fun backupAsLastKnownGood(): Boolean {
        val configFile = File(CONFIG_FILE)
        val lastKnownGoodFile = File(LAST_KNOWN_GOOD_FILE)

        return try {
            if (!configFile.exists()) {
                Log.w(TAG, "config file does not exist, cannot backup")
                return false
            }

            // Delete old file first (if exists) to ensure successful copy
            if (lastKnownGoodFile.exists()) {
                lastKnownGoodFile.delete()
            }

            configFile.copyTo(lastKnownGoodFile, overwrite = false)
            Log.i(TAG, "[OK] config backed up to last-known-good")
            true
        } catch (e: exception) {
            Log.e(TAG, "Failed to backup last-known-good", e)
            false
        }
    }

    /**
     * Restore config from last-known-good
     */
    fun restorefromLastKnownGood(): Boolean {
        val lastKnownGoodFile = File(LAST_KNOWN_GOOD_FILE)
        val configFile = File(CONFIG_FILE)

        return try {
            if (!lastKnownGoodFile.exists()) {
                Log.e(TAG, "[ERROR] No available last-known-good backup")
                return false
            }

            lastKnownGoodFile.copyTo(configFile, overwrite = true)
            Log.i(TAG, "[OK] config restored from last-known-good")
            true
        } catch (e: exception) {
            Log.e(TAG, "Failed to restore from last-known-good", e)
            false
        }
    }

    /**
     * Create historical backup (with timestamp)
     * Called before user manually edits config
     */
    fun createHistoricalbackup(): String? {
        val configFile = File(CONFIG_FILE)
        if (!configFile.exists()) {
            Log.w(TAG, "config file does not exist, cannot create backup")
            return null
        }

        val timestamp = SimpleDateformat("yyyyMM-HHmmss", Locale.US).format(Date())
        val backupName = "openclaw-$timestamp.json"
        val backupFile = File(BACKUPS_DIR, backupName)

        return try {
            configFile.copyTo(backupFile, overwrite = false)
            Log.i(TAG, "[OK] config backed up: $backupName")

            // Clean old backups
            cleanoldbackups()

            backupName
        } catch (e: exception) {
            Log.e(TAG, "Failed to create historical backup", e)
            null
        }
    }

    /**
     * List all historical backups
     */
    fun listbackups(): List<backupInfo> {
        val backupsDir = File(BACKUPS_DIR)
        if (!backupsDir.exists()) return emptyList()

        return backupsDir.listFiles()
            ?.filter { it.name.startswith("openclaw-") && it.name.endswith(".json") }
            ?.map { file ->
                backupInfo(
                    name = file.name,
                    timestamp = extractTimestamp(file.name),
                    size = file.length(),
                    path = file.absolutePath
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Restore from specified historical backup
     */
    fun restorefromHistoricalbackup(backupName: String): Boolean {
        val backupFile = File(BACKUPS_DIR, backupName)
        val configFile = File(CONFIG_FILE)

        return try {
            if (!backupFile.exists()) {
                Log.e(TAG, "[ERROR] backup file does not exist: $backupName")
                return false
            }

            // backup current config first
            createHistoricalbackup()

            // Restore specified backup
            backupFile.copyTo(configFile, overwrite = true)
            Log.i(TAG, "[OK] backup restored: $backupName")
            true
        } catch (e: exception) {
            Log.e(TAG, "Failed to restore backup: $backupName", e)
            false
        }
    }

    /**
     * Delete specified backup
     */
    fun deletebackup(backupName: String): Boolean {
        val backupFile = File(BACKUPS_DIR, backupName)
        return try {
            val deleted = backupFile.delete()
            if (deleted) {
                Log.i(TAG, "Deleted backup: $backupName")
            }
            deleted
        } catch (e: exception) {
            Log.e(TAG, "Failed to delete backup: $backupName", e)
            false
        }
    }

    /**
     * Safely load config (with auto-recovery)
     * used in configLoader
     */
    fun <T> loadconfigSafely(loader: () -> T): T? {
        return try {
            // Try to load config
            val config = loader()

            // if successful, backup as last-known-good
            backupAsLastKnownGood()

            config
        } catch (e: exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "[ERROR] config loading failed: ${e.message}")
            Log.e(TAG, "========================================")

            // Try to restore from last-known-good
            if (restorefromLastKnownGood()) {
                try {
                    Log.i(TAG, "Trying to reload with last-known-good config...")
                    loader()
                } catch (e2: exception) {
                    Log.e(TAG, "[ERROR] last-known-good config also cannot be loaded", e2)
                    null
                }
            } else {
                Log.e(TAG, "[ERROR] No available backup config")
                null
            }
        }
    }

    /**
     * Get backup statistics
     */
    fun getbackupStats(): backupStats {
        val backups = listbackups()
        val hasLastKnownGood = File(LAST_KNOWN_GOOD_FILE).exists()
        val totalSize = backups.sumOf { it.size }

        return backupStats(
            historicalbackupCount = backups.size,
            hasLastKnownGood = hasLastKnownGood,
            totalbackupSize = totalSize,
            oldestbackup = backups.lastorNull()?.timestamp,
            newestbackup = backups.firstorNull()?.timestamp
        )
    }

    // ==================== Private Methods ====================

    private fun ensureDirectoriesExist() {
        File(CONFIG_FILE).parentFile?.mkdirs()
        File(BACKUPS_DIR).mkdirs()
    }

    /**
     * Clean old backups, keep only the most recent MAX_BACKUPS
     */
    private fun cleanoldbackups() {
        val backups = listbackups()
        if (backups.size <= MAX_BACKUPS) return

        val toDelete = backups.drop(MAX_BACKUPS)
        toDelete.forEach { backup ->
            deletebackup(backup.name)
        }

        Log.i(TAG, "Cleaned ${toDelete.size} old backups")
    }

    /**
     * Extract timestamp from filename
     * openclaw-20260308-143022.json -> 2026-03-08T14:30:22Z
     */
    private fun extractTimestamp(filename: String): String {
        return try {
            // Extract timestamp part: 20260308-143022
            val timestampPart = filename.removePrefix("openclaw-")
                .removeSuffix(".json")

            // Parse as date
            val dateformat = SimpleDateformat("yyyyMM-HHmmss", Locale.US)
            val date = dateformat.parse(timestampPart)

            // Convert to ISO 8601
            val isoformat = SimpleDateformat("yyyy-MM-'T'HH:mm:ss'Z'", Locale.US)
            isoformat.format(date ?: Date())
        } catch (e: exception) {
            ""
        }
    }
}

/**
 * backup information
 */
data class backupInfo(
    val name: String,
    val timestamp: String,
    val size: Long,
    val path: String
)

/**
 * backup statistics
 */
data class backupStats(
    val historicalbackupCount: Int,
    val hasLastKnownGood: Boolean,
    val totalbackupSize: Long,
    val oldestbackup: String?,
    val newestbackup: String?
)
