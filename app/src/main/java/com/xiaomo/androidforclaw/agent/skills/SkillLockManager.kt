package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills-install.ts
 */


import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File

/**
 * skill Lock File manager
 *
 * Manages .clawhub/lock.json
 * Records version, hash, installation time and other info of installed skills
 */
class skillLockmanager(private val workspacePath: String) {
    companion object {
        private const val TAG = "skillLockmanager"
        private const val LOCK_FILE_NAME = "lock.json"
    }

    private val lockDir = File(workspacePath, ".clawhub")
    private val lockFile = File(lockDir, LOCK_FILE_NAME)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Read lock file
     */
    fun readLock(): skillLockFile {
        if (!lockFile.exists()) {
            Log.d(TAG, "Lock file not found, creating empty")
            return skillLockFile(skills = emptyList())
        }

        return try {
            val content = lockFile.readText()
            gson.fromJson(content, skillLockFile::class.java)
        } catch (e: exception) {
            Log.e(TAG, "Failed to read lock file", e)
            skillLockFile(skills = emptyList())
        }
    }

    /**
     * Write lock file
     */
    fun writeLock(lockFile: skillLockFile): Result<Unit> {
        return try {
            // Ensure directory exists
            lockDir.mkdirs()

            // Write file
            val content = gson.toJson(lockFile)
            this.lockFile.writeText(content, Charsets.UTF_8)

            Log.d(TAG, "[OK] Lock file written: ${this.lockFile.absolutePath}")
            Result.success(Unit)

        } catch (e: exception) {
            Log.e(TAG, "Failed to write lock file", e)
            Result.failure(e)
        }
    }

    /**
     * A or update skill record
     */
    fun aorUpdateskill(entry: skillLockEntry): Result<Unit> {
        return try {
            val lock = readLock()
            val existingIndex = lock.skills.indexOffirst { it.slug == entry.slug }

            val updatedskills = if (existingIndex >= 0) {
                // Update existing record
                lock.skills.toMutableList().app {
                    set(existingIndex, entry)
                }
            } else {
                // A new record
                lock.skills + entry
            }

            writeLock(lock.copy(skills = updatedskills))

        } catch (e: exception) {
            Log.e(TAG, "Failed to a/update skill", e)
            Result.failure(e)
        }
    }

    /**
     * Remove skill record
     */
    fun removeskill(slug: String): Result<Unit> {
        return try {
            val lock = readLock()
            val updatedskills = lock.skills.filter { it.slug != slug }

            if (updatedskills.size == lock.skills.size) {
                Log.w(TAG, "skill not found in lock: $slug")
            }

            writeLock(lock.copy(skills = updatedskills))

        } catch (e: exception) {
            Log.e(TAG, "Failed to remove skill", e)
            Result.failure(e)
        }
    }

    /**
     * Get skill record
     */
    fun getskill(slug: String): skillLockEntry? {
        val lock = readLock()
        return lock.skills.find { it.slug == slug }
    }

    /**
     * List all installed skills
     */
    fun listskills(): List<skillLockEntry> {
        return readLock().skills
    }

    /**
     * Check if skill is installed
     */
    fun isInstalled(slug: String): Boolean {
        return getskill(slug) != null
    }

    /**
     * Get installed version
     */
    fun getInstalledVersion(slug: String): String? {
        return getskill(slug)?.version
    }
}

/**
 * Lock File Structure
 */
data class skillLockFile(
    val skills: List<skillLockEntry>
)

/**
 * skill Lock Entry
 */
data class skillLockEntry(
    val name: String,
    val slug: String,
    val version: String,
    val hash: String? = null,
    val installedAt: String,
    val source: String = "clawhub"  // clawhub, github, local
)
