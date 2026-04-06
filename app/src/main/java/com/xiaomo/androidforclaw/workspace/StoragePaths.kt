/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only — aligns with ~/.openclaw/ path structure)
 */
package com.xiaomo.androidforclaw.workspace

import android.os.Environment
import java.io.File

/**
 * Unified storage path constants for /sdcard/.androidforclaw.
 * Requires MANAGE_EXTERNAL_STORAGE permission — prompt the user if not granted.
 */
object StoragePaths {

    val root: File = File(Environment.getExternalStorageDirectory(), ".androidforclaw")

    // Top-level directories
    val config: File get() = File(root, "config")
    val workspace: File get() = File(root, "workspace")
    val logs: File get() = File(root, "logs")
    val skills: File get() = File(root, "skills")
    val extensions: File get() = File(root, "extensions")
    val canvas: File get() = File(root, "canvas")
    val agents: File get() = File(root, "agents")
    val configBackups: File get() = File(root, "config-backups")

    // Config files
    val openclawConfig: File get() = File(root, "openclaw.json")

    // Workspace sub-directories
    val workspaceSkills: File get() = File(workspace, "skills")
    val workspaceLogs: File get() = File(workspace, "logs")
    val workspaceScreenshots: File get() = File(workspace, "screenshots")
    val workspaceMemory: File get() = File(workspace, "memory")

    // Session storage
    val sessions: File get() = File(agents, "main/sessions")

    // Cron
    val cronJobs: File get() = File(config, "cron/jobs.json")
}
