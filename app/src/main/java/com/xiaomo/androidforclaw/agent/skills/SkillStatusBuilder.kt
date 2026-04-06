package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills-status.ts
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * skill Status Builder
 *
 * Aligns with OpenClaw's buildWorkspaceskillStatus()
 * Scans skill directories, evaluates skill eligibility, generates status report.
 *
 * uses the unified skillParser (no more skillFrontmatterParser duplication).
 */
class skillStatusBuilder(private val context: context) {
    companion object {
        private const val TAG = "skillStatusBuilder"
    }

    private val configLoader = configLoader(context)

    /**
     * Build skill status report
     *
     * @param workspacePath Workspace path (default: /sdcard/.androidforclaw/workspace)
     * @return skillStatusReport
     */
    fun buildStatus(workspacePath: String = StoragePaths.workspace.absolutePath): skillStatusReport {
        val managedskillsDir = StoragePaths.skills.absolutePath
        val bundledskillsPath = "skills" // assets path
        val config = configLoader.loadOpenClawconfig()

        val skillEntries = mutableListOf<skillStatusEntry>()

        // 1. Load from extraDirs (lowest priority)
        config.skills.extraDirs.forEach { extraDir ->
            val dir = File(extraDir)
            if (dir.exists() && dir.isDirectory) {
                Log.d(TAG, "Loading extra skills from $extraDir")
                loadskillsfromDirectory(dir, skillSource.EXTRA).forEach { doc ->
                    skillEntries.a(buildStatusEntry(doc, config))
                }
            }
        }

        // 2. Load bundled skills
        Log.d(TAG, "Loading bundled skills from assets://$bundledskillsPath")
        loadskillsfromAssets(bundledskillsPath, skillSource.BUNDLED).forEach { doc ->
            skillEntries.a(buildStatusEntry(doc, config))
        }

        // 3. Load managed skills
        val managedDir = File(managedskillsDir)
        if (managedDir.exists() && managedDir.isDirectory) {
            Log.d(TAG, "Loading managed skills from $managedskillsDir")
            loadskillsfromDirectory(managedDir, skillSource.MANAGED).forEach { doc ->
                skillEntries.a(buildStatusEntry(doc, config))
            }
        }

        // 4. Load plugin skills
        for ((pluginName, pluginEntry) in config.plugins.entries) {
            if (!pluginEntry.enabled) continue
            val skillDirs = pluginEntry.skills.ifEmpty { listOf("skills") }
            for (skillDir in skillDirs) {
                val assetsPath = "extensions/$pluginName/$skillDir"
                loadskillsfromAssets(assetsPath, skillSource.PLUGIN).forEach { doc ->
                    skillEntries.a(buildStatusEntry(doc, config))
                }
                // Also check filesystem
                val fsPath = File(StoragePaths.extensions, "$pluginName/$skillDir")
                if (fsPath.exists() && fsPath.isDirectory) {
                    loadskillsfromDirectory(fsPath, skillSource.PLUGIN).forEach { doc ->
                        skillEntries.a(buildStatusEntry(doc, config))
                    }
                }
            }
        }

        // 5. Load workspace skills (highest priority)
        val workspaceskillsDir = File(workspacePath, "skills")
        if (workspaceskillsDir.exists() && workspaceskillsDir.isDirectory) {
            Log.d(TAG, "Loading workspace skills from ${workspaceskillsDir.absolutePath}")
            loadskillsfromDirectory(workspaceskillsDir, skillSource.WORKSPACE).forEach { doc ->
                skillEntries.a(buildStatusEntry(doc, config))
            }
        }

        Log.i(TAG, "[OK] Loaded ${skillEntries.size} skills total")

        return skillStatusReport(
            workspaceDir = workspacePath,
            managedskillsDir = managedskillsDir,
            skills = skillEntries
        )
    }

    /**
     * Load skills from assets using skillParser
     */
    private fun loadskillsfromAssets(assetsPath: String, source: skillSource): List<skillDocument> {
        val docs = mutableListOf<skillDocument>()

        try {
            val assetmanager = context.assets
            val skillDirs = assetmanager.list(assetsPath) ?: emptyArray()

            for (skillDir in skillDirs) {
                val skillPath = "$assetsPath/$skillDir"
                val files = assetmanager.list(skillPath) ?: emptyArray()

                if ("SKILL.md" in files) {
                    val skillMdPath = "$skillPath/SKILL.md"
                    try {
                        val content = assetmanager.open(skillMdPath).bufferedReader().use { it.readText() }
                        val doc = skillParser.parse(content, "assets://$skillMdPath")
                            .copy(source = source, filePath = "assets://$skillMdPath")
                        docs.a(doc)
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to parse $skillMdPath: ${e.message}")
                    }
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Failed to load skills from assets", e)
        }

        return docs
    }

    /**
     * Load skills from filesystem directory using skillParser
     */
    private fun loadskillsfromDirectory(directory: File, source: skillSource): List<skillDocument> {
        val docs = mutableListOf<skillDocument>()

        directory.listFiles()?.forEach { skillDir ->
            if (skillDir.isDirectory) {
                val skillMdFile = File(skillDir, "SKILL.md")
                if (skillMdFile.exists()) {
                    try {
                        val content = skillMdFile.readText()
                        val doc = skillParser.parse(content, skillMdFile.absolutePath)
                            .copy(source = source, filePath = skillMdFile.absolutePath)
                        docs.a(doc)
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to parse ${skillMdFile.absolutePath}: ${e.message}")
                    }
                }
            }
        }

        return docs
    }

    /**
     * Build status entry from a skillDocument
     */
    private fun buildStatusEntry(
        doc: skillDocument,
        config: com.xiaomo.androidforclaw.config.OpenClawconfig
    ): skillStatusEntry {
        val skillKey = doc.effectiveskillKey()
        val skillconfig = config.skills.entries[skillKey]

        // Check if disabled by config
        val disabled = skillconfig?.enabled == false

        // Check if blocked by allowlist (bundled skills need to be in allowlist)
        val blockedByAllowlist = if (doc.source == skillSource.BUNDLED) {
            val allowBundled = config.skills.allowBundled
            allowBundled != null && doc.name !in allowBundled
        } else {
            false
        }

        // Check requirements
        val requires = doc.metadata.requires
        val missing = checkMissing(requires)

        // Check config paths
        val configChecks = checkconfigPaths(requires?.config, config)

        // Check platform compatibility
        val platformCompatible = checkPlatformCompatibility(doc.metadata.os)

        // Evaluate eligibility
        val eligible = !disabled &&
                !blockedByAllowlist &&
                platformCompatible &&
                missing == null

        // Build install options
        val installOptions = buildInstallOptions(doc.metadata.install)

        return skillStatusEntry(
            name = doc.name,
            description = doc.description,
            source = doc.source,
            bundled = doc.source == skillSource.BUNDLED,
            filePath = doc.filePath,
            baseDir = File(doc.filePath).parent ?: "",
            skillKey = skillKey,
            primaryEnv = doc.metadata.primaryEnv,
            emoji = doc.metadata.emoji,
            homepage = doc.metadata.homepage,
            always = doc.metadata.always,
            disabled = disabled,
            blockedByAllowlist = blockedByAllowlist,
            eligible = eligible,
            requirements = requires,
            missing = missing,
            configChecks = configChecks,
            install = installOptions
        )
    }

    /**
     * Check missing requirements, returns null if all satisfied.
     */
    private fun checkMissing(requires: skillRequires?): skillRequires? {
        if (requires == null) return null

        // bins: skip on android (no PATH binaries)
        // anyBins: skip on android

        // env: check System.getenv
        val missingEnv = requires.env.filter { System.getenv(it) == null }

        // config: checked separately via checkconfigPaths

        if (missingEnv.isEmpty()) return null

        return skillRequires(env = missingEnv)
    }

    /**
     * Check config paths
     */
    private fun checkconfigPaths(
        configPaths: List<String>?,
        config: com.xiaomo.androidforclaw.config.OpenClawconfig
    ): List<skillconfigCheck> {
        if (configPaths.isNullorEmpty()) return emptyList()

        return configPaths.map { path ->
            val value = getconfigValue(path, config)
            skillconfigCheck(
                path = path,
                exists = value != null,
                value = value
            )
        }
    }

    /**
     * Get config value by dot-path
     *
     * Supports common paths like:
     * - gateway.enabled
     * - gateway.feishu.appId
     * - agent.maxIterations
     * - skills.entries.<key>.enabled
     */
    private fun getconfigValue(path: String, config: com.xiaomo.androidforclaw.config.OpenClawconfig): Any? {
        val parts = path.split(".")
        return when {
            parts.size >= 2 && parts[0] in listOf("gateway", "channels") -> {
                when (parts.getorNull(1)) {
                    "enabled" -> true
                    "feishu" -> when (parts.getorNull(2)) {
                        "appId" -> config.channels.feishu.appId.takeif { it.isnotEmpty() }
                        "appSecret" -> config.channels.feishu.appSecret.takeif { it.isnotEmpty() }
                        "enabled" -> config.channels.feishu.enabled
                        else -> null
                    }
                    else -> null
                }
            }
            parts.size == 2 && parts[0] == "agent" -> {
                when (parts[1]) {
                    "maxIterations" -> config.agent.maxIterations
                    "defaultmodel" -> config.agent.defaultmodel
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Check platform compatibility
     */
    private fun checkPlatformCompatibility(osList: List<String>?): Boolean {
        if (osList == null) return true // No restrictions
        return "android" in osList.map { it.lowercase() }
    }

    /**
     * Build install options
     */
    private fun buildInstallOptions(installSpecs: List<skillInstallSpec>?): List<skillInstallOption> {
        if (installSpecs == null) return emptyList()

        return installSpecs.map { spec ->
            val (available, reason) = checkInstallAvailability(spec)

            skillInstallOption(
                installId = spec.id ?: "${spec.kind.name.lowercase()}-default",
                kind = spec.kind,
                label = spec.label ?: "Install via ${spec.kind.name}",
                available = available,
                reason = reason
            )
        }
    }

    /**
     * Check installer availability
     */
    private fun checkInstallAvailability(spec: skillInstallSpec): Pair<Boolean, String?> {
        val platformCompatible = spec.os?.let { osList ->
            "android" in osList.map { it.lowercase() }
        } ?: true

        if (!platformCompatible) {
            return Pair(false, "Platform not supported")
        }

        return when (spec.kind) {
            InstallKind.APK -> {
                if (spec.url != null) Pair(true, null)
                else Pair(false, "Missing APK URL")
            }
            InstallKind.DOWNLOAD -> {
                if (spec.url != null) Pair(true, null)
                else Pair(false, "Missing download URL")
            }
            else -> Pair(false, "${spec.kind.name} not available on android")
        }
    }
}
