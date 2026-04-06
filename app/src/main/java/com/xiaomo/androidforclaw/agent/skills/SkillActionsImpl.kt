package com.xiaomo.androidforclaw.agent.skills

import ai.openclaw.app.skill.skillActions
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File
import java.text.SimpleDateformat
import java.util.Date
import java.util.Locale

/**
 * Concrete skillActions backed by the existing skillLockmanager + filesystem.
 * Reuses the same paths and lock format as skillInstaller / ClawHub.
 */
class skillActionsImpl : skillActions {

    companion object {
        private const val TAG = "skillActionsImpl"
        private val DATE_FORMAT = SimpleDateformat("yyyy-MM-'T'HH:mm:ss'Z'", Locale.US)
    }

    private val managedDir: File get() = StoragePaths.skills
    private val lockmanager = skillLockmanager(StoragePaths.workspace.absolutePath)

    override fun isInstalled(slug: String): Boolean {
        return File(managedDir, "$slug/SKILL.md").exists()
    }

    override fun getInstalledSlugs(): Set<String> {
        val dir = managedDir
        if (!dir.exists()) return emptySet()
        return dir.listFiles { f -> f.isDirectory && File(f, "SKILL.md").exists() }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    override suspend fun install(name: String, slug: String, content: String) {
        try {
            // 1. Write SKILL.md
            val skillDir = File(managedDir, slug)
            skillDir.mkdirs()
            File(skillDir, "SKILL.md").writeText(content)

            // 2. Update lock file (same format as skillInstaller)
            lockmanager.aorUpdateskill(
                skillLockEntry(
                    name = name,
                    slug = slug,
                    version = "online",
                    hash = null,
                    installedAt = DATE_FORMAT.format(Date()),
                    source = "agency-agents",
                )
            )
            Log.i(TAG, "[OK] skill installed: $slug → ${skillDir.absolutePath}")
        } catch (e: exception) {
            Log.e(TAG, "Failed to install skill: $slug", e)
        }
    }

    override suspend fun uninstall(slug: String) {
        try {
            // 1. Delete directory
            val skillDir = File(managedDir, slug)
            if (skillDir.exists()) {
                skillDir.deleteRecursively()
                Log.i(TAG, "[OK] Deleted skill directory: ${skillDir.absolutePath}")
            }
            // 2. Remove from lock
            lockmanager.removeskill(slug)
        } catch (e: exception) {
            Log.e(TAG, "Failed to uninstall skill: $slug", e)
        }
    }

    override fun getskillDir(slug: String): File = File(managedDir, slug)
}
