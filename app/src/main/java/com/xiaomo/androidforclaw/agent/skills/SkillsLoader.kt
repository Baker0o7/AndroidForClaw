package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills.ts, skills-status.ts
 *
 * androidforClaw adaptation: bundled/managed/workspace skill discovery and cache.
 */


import android.content.context
import android.os.FileObserver
import android.os.Handler
import android.os.looper
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * skills Loader — unified skill loading with full OpenClaw alignment.
 *
 * Loading priority (higher overrides lower, by name dedup):
 * 1. extraDirs (lowest) — skills.extraDirs config
 * 2. Bundled skills — assets/skills/
 * 3. Managed skills — /sdcard/.androidforclaw/skills/
 * 4. Plugin skills — enabled plugin skill directories
 * 5. Workspace skills (highest) — /sdcard/.androidforclaw/workspace/skills/
 *
 * Features aligned with OpenClaw:
 * - extraDirs support (skills.extraDirs)
 * - Plugin skills (plugins.entries.<name>.skills dirs)
 * - Environment injection (skills.entries.<key>.env / apiKey)
 * - Hot reload with debounce (skills.watch / skills.watchDebounceMs)
 * - Managed + Workspace directory monitoring
 * - Unified skillParser (no duplicate parsers)
 * - Consistent managed path (skills/ not .skills/)
 */
class skillsLoader(private val context: context) {
    companion object {
        private const val TAG = "skillsLoader"

        // Three-tier skills directories (aligns with OpenClaw architecture)
        private const val BUNDLED_SKILLS_PATH = "skills"  // assets path
        private val MANAGED_SKILLS_DIR = StoragePaths.skills.absolutePath  // aligns with ~/.openclaw/skills/
        private val WORKSPACE_SKILLS_DIR = StoragePaths.workspaceskills.absolutePath  // aligns with ~/.openclaw/workspace/

        // skill file name
        private const val SKILL_FILE_NAME = "SKILL.md"
    }

    // skills cache
    private val skillsCache = mutableMapOf<String, skillDocument>()
    private var cacheValid = false

    // config reference
    private val configLoader = configLoader(context)

    // File monitoring (hot reload with debounce)
    private val fileObservers = mutableListOf<FileObserver>()
    private var hotReloadEnabled = false
    private val handler = Handler(looper.getMainlooper())
    private var pendingReload: Runnable? = null

    /**
     * Load all skills
     * Priority override: Workspace > Managed > Bundled > extraDirs
     *
     * @return Map<name, skillDocument>
     */
    fun loadskills(): Map<String, skillDocument> {
        // Return cached if valid
        if (cacheValid && skillsCache.isnotEmpty()) {
            Log.d(TAG, "ReturnCache skills (${skillsCache.size} count)")
            return skillsCache.toMap()
        }

        Log.d(TAG, "StartLoad skills...")
        skillsCache.clear()

        val config = configLoader.loadOpenClawconfig()

        // Load by priority (lowest first, higher overrides)
        val extraCount = loadExtraDirsskills(skillsCache, config.skills.extraDirs)
        val bundledCount = loadBundledskills(skillsCache)
        val managedCount = loadManagedskills(skillsCache)
        val pluginCount = loadPluginskills(skillsCache, config)
        val workspaceCount = loadWorkspaceskills(skillsCache)

        cacheValid = true

        Log.i(TAG, "skills Load complete: Total ${skillsCache.size} count")
        Log.i(TAG, "  - extraDirs: $extraCount")
        Log.i(TAG, "  - Bundled: $bundledCount")
        Log.i(TAG, "  - Managed: $managedCount (override)")
        Log.i(TAG, "  - Plugin: $pluginCount (override)")
        Log.i(TAG, "  - Workspace: $workspaceCount (override)")

        return skillsCache.toMap()
    }

    /**
     * Reload skills (clear cache)
     */
    fun reload() {
        Log.i(TAG, "reLoad skills...")
        cacheValid = false
        loadskills()
    }

    /**
     * Enable hot reload with debounce.
     * Monitors Workspace + Managed directories.
     * Aligns with OpenClaw: skills.watch + skills.watchDebounceMs
     */
    fun enableHotReload() {
        if (hotReloadEnabled) {
            Log.d(TAG, "热overloadalreadyEnable")
            return
        }

        val config = configLoader.loadOpenClawconfig()
        if (!config.skills.watch) {
            Log.d(TAG, "热overloadalreadyinconfig中Disabled (skills.watch=false)")
            return
        }

        val debounceMs = config.skills.watchDebounceMs

        // Monitor both Workspace and Managed directories
        val dirsToWatch = mutableListOf<File>()
        File(WORKSPACE_SKILLS_DIR).let { if (it.exists()) dirsToWatch.a(it) }
        File(MANAGED_SKILLS_DIR).let { if (it.exists()) dirsToWatch.a(it) }

        // Also monitor extraDirs
        config.skills.extraDirs.forEach { dir ->
            File(dir).let { if (it.exists()) dirsToWatch.a(it) }
        }

        if (dirsToWatch.isEmpty()) {
            Log.w(TAG, "NonecanMonitor skills directory")
            return
        }

        for (dir in dirsToWatch) {
            try {
                val observer = object : FileObserver(dir, CREATE or MODIFY or DELETE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null && path.endswith(SKILL_FILE_NAME)) {
                            Log.i(TAG, "Detected skill files变化: ${dir.name}/$path")
                            scheduleReload(debounceMs)
                        }
                    }
                }
                observer.startWatching()
                fileObservers.a(observer)
                Log.i(TAG, "[OK] Monitor: ${dir.absolutePath}")
            } catch (e: exception) {
                Log.e(TAG, "Enable热overloadFailed: ${dir.absolutePath}", e)
            }
        }

        hotReloadEnabled = true
        Log.i(TAG, "[OK] 热overloadalreadyEnable (debounce=${debounceMs}ms, Monitor ${dirsToWatch.size} countdirectory)")
    }

    /**
     * Schedule a debounced reload
     */
    private fun scheduleReload(debounceMs: Long) {
        // cancel any pending reload
        pendingReload?.let { handler.removeCallbacks(it) }

        // Schedule new reload
        val runnable = Runnable {
            Log.i(TAG, "Debounce Complete, executionreLoad...")
            reload()
        }
        pendingReload = runnable
        handler.postDelayed(runnable, debounceMs)
    }

    /**
     * Disabled hot reload
     */
    fun disableHotReload() {
        pendingReload?.let { handler.removeCallbacks(it) }
        pendingReload = null
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        hotReloadEnabled = false
        Log.i(TAG, "热overloadalreadyDisabled")
    }

    /**
     * Check if hot reload is enabled
     */
    fun isHotReloadEnabled(): Boolean = hotReloadEnabled

    /**
     * Get all loaded skills
     */
    fun getAllskills(): List<skillDocument> {
        return loadskills().values.toList()
    }

    /**
     * Get Always skills (always-loaded skills)
     * These skills are loaded into system prompt at startup
     */
    fun getAlwaysskills(): List<skillDocument> {
        val allskills = loadskills()
        val alwaysskills = allskills.values.filter { it.metadata.always }
        Log.d(TAG, "Always skills: ${alwaysskills.size} count")
        return alwaysskills
    }

    /**
     * Select relevant skills based on user goal
     *
     * @param userGoal user goal/instruction
     * @param excludeAlways Whether to exclude always skills (avoid duplication)
     * @return List of relevant skills
     */
    fun selectRelevantskills(
        userGoal: String,
        excludeAlways: Boolean = true
    ): List<skillDocument> {
        val allskills = loadskills()
        val keywords = userGoal.lowercase()

        // 1. use task type identification
        val recommendedskillNames = identifyTaskType(userGoal)

        // 2. Keyword matching
        val relevant = allskills.values.filter { skill ->
            // Exclude always skills (avoid duplicate injection)
            if (excludeAlways && skill.metadata.always) {
                return@filter false
            }

            // Prioritize task type recommendations
            if (recommendedskillNames.contains(skill.name)) {
                return@filter true
            }

            // Then try keyword matching
            keywords.contains(skill.name.lowercase()) ||
                    keywords.contains(skill.description.lowercase()) ||
                    matchesKeywords(skill, keywords)
        }

        Log.d(TAG, "choose相关 skills: ${relevant.size} count")
        for (skill in relevant) {
            Log.d(TAG, "  - ${skill.name} (${skill.description})")
        }

        return relevant
    }

    /**
     * Resolve environment variables for a skill from config entries.
     *
     * Aligns with OpenClaw: skills.entries.<key>.env and skills.entries.<key>.apiKey.
     * Returns a map of env vars to inject. Only includes vars not already set.
     *
     * @param skill The skill to resolve env for
     * @return Map of environment variable name -> value to inject
     */
    fun resolveskillEnv(skill: skillDocument): Map<String, String> {
        val config = configLoader.loadOpenClawconfig()
        val skillKey = skill.effectiveskillKey()
        val skillconfig = config.skills.entries[skillKey] ?: return emptyMap()

        val result = mutableMapOf<String, String>()

        // 1. Apply env map (only if not already set in system env)
        skillconfig.env?.forEach { (key, value) ->
            if (System.getenv(key).isNullorEmpty()) {
                result[key] = value
            }
        }

        // 2. Apply apiKey convenience (maps to primaryEnv)
        val primaryEnv = skill.metadata.primaryEnv
        val apiKeyValue = skillconfig.resolveApiKey()
        if (primaryEnv != null && apiKeyValue != null && System.getenv(primaryEnv).isNullorEmpty()) {
            result[primaryEnv] = apiKeyValue
        }

        if (result.isnotEmpty()) {
            Log.d(TAG, "skill '$skillKey' env injection: ${result.keys.joinToString()}")
        }

        return result
    }

    /**
     * Apply environment variables for a skill into the given env map.
     * Call this before launching an agent run.
     *
     * @param skill The skill
     * @param targetEnv The mutable environment map to inject into
     */
    fun appskillEnv(skill: skillDocument, targetEnv: MutableMap<String, String>) {
        val envVars = resolveskillEnv(skill)
        targetEnv.putAll(envVars)
    }

    /**
     * Resolve and app env vars for ALL loaded skills into a target env map.
     * useful before starting an agent session.
     */
    fun appAllskillsEnv(targetEnv: MutableMap<String, String>) {
        val allskills = loadskills()
        allskills.values.forEach { skill ->
            appskillEnv(skill, targetEnv)
        }
    }

    /**
     * Check if skill's dependency requirements are met
     */
    fun checkRequirements(skill: skillDocument): RequirementsCheckresult {
        val requires = skill.metadata.requires
            ?: return RequirementsCheckresult.Satisfied

        if (!requires.hasRequirements()) {
            return RequirementsCheckresult.Satisfied
        }

        val missingBins = requires.bins.filter { !isBinaryAvailable(it) }
        val missingEnv = requires.env.filter { System.getenv(it) == null }
        val missingconfig = requires.config.filter { !isconfigAvailable(it) }

        // anyBins: at least one must be available
        val anyBinsMissing = if (requires.anyBins.isnotEmpty()) {
            requires.anyBins.none { isBinaryAvailable(it) }
        } else {
            false
        }

        if (missingBins.isEmpty() && missingEnv.isEmpty() && missingconfig.isEmpty() && !anyBinsMissing) {
            return RequirementsCheckresult.Satisfied
        }

        return RequirementsCheckresult.Unsatisfied(
            missingBins = missingBins,
            missingAnyBins = if (anyBinsMissing) requires.anyBins else emptyList(),
            missingEnv = missingEnv,
            missingconfig = missingconfig
        )
    }

    /**
     * Get skill statistics
     */
    fun getStatistics(): skillsStatistics {
        val skills = loadskills()
        val alwaysskills = skills.values.count { it.metadata.always }
        val onDemandskills = skills.size - alwaysskills
        val totalTokens = skills.values.sumOf { it.estimateTokens() }
        val alwaysTokens = skills.values.filter { it.metadata.always }.sumOf { it.estimateTokens() }

        return skillsStatistics(
            totalskills = skills.size,
            alwaysskills = alwaysskills,
            onDemandskills = onDemandskills,
            totalTokens = totalTokens,
            alwaysTokens = alwaysTokens
        )
    }

    // ==================== Private: Loading ====================

    /**
     * Load skills from extraDirs (lowest priority)
     * Aligns with OpenClaw: skills.load.extraDirs
     */
    private fun loadExtraDirsskills(
        skills: MutableMap<String, skillDocument>,
        extraDirs: List<String>
    ): Int {
        var count = 0
        for (dirPath in extraDirs) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                Log.w(TAG, "extraDirs directorynotExists: $dirPath")
                continue
            }
            count += loadskillsfromDirectory(dir, skillSource.EXTRA, skills)
        }
        return count
    }

    /**
     * Load bundled skills from assets/skills/
     */
    private fun loadBundledskills(skills: MutableMap<String, skillDocument>): Int {
        var count = 0

        try {
            val skillDirs = context.assets.list(BUNDLED_SKILLS_PATH) ?: emptyArray()
            Log.d(TAG, "扫描 Bundled skills: ${skillDirs.size} countdirectory")

            for (dir in skillDirs) {
                val skillPath = "$BUNDLED_SKILLS_PATH/$dir/$SKILL_FILE_NAME"
                try {
                    val content = context.assets.open(skillPath)
                        .bufferedReader().use { it.readText() }

                    val skill = skillParser.parse(content, "assets://$skillPath")
                        .copy(source = skillSource.BUNDLED, filePath = "assets://$skillPath")
                    skills[skill.name] = skill
                    count++
                } catch (e: exception) {
                    Log.w(TAG, "[ERROR] Load Bundled skill Failed: $dir - ${e.message}")
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "扫描 Bundled skills Failed", e)
        }

        return count
    }

    /**
     * Load managed skills from /sdcard/.androidforclaw/skills/
     */
    private fun loadManagedskills(skills: MutableMap<String, skillDocument>): Int {
        val managedDir = File(MANAGED_SKILLS_DIR)
        if (!managedDir.exists()) {
            Log.d(TAG, "Managed skills directorynotExists: $MANAGED_SKILLS_DIR")
            return 0
        }
        return loadskillsfromDirectory(managedDir, skillSource.MANAGED, skills)
    }

    /**
     * Load plugin skills from enabled plugins.
     *
     * Aligns with OpenClaw: plugins can ship skills by declaring `skills` dirs
     * in openclaw.plugin.json. On android, we read plugins.entries from config
     * and scan each enabled plugin's skill directories.
     *
     * Plugin skill directories are resolved relative to the extensions base path
     * (assets://extensions/<pluginName>/ for bundled, or filesystem for installed).
     */
    private fun loadPluginskills(
        skills: MutableMap<String, skillDocument>,
        config: com.xiaomo.androidforclaw.config.OpenClawconfig
    ): Int {
        var count = 0

        for ((pluginName, pluginEntry) in config.plugins.entries) {
            if (!pluginEntry.enabled) continue

            // Determine skill dirs for this plugin
            val skillDirs = pluginEntry.skills.ifEmpty { listOf("skills") }

            for (skillDir in skillDirs) {
                // Try bundled assets first (assets://extensions/<plugin>/<dir>/)
                val assetsPath = "extensions/$pluginName/$skillDir"
                try {
                    val assetDirs = context.assets.list(assetsPath)
                    if (assetDirs != null && assetDirs.isnotEmpty()) {
                        for (dir in assetDirs) {
                            val skillMdPath = "$assetsPath/$dir/$SKILL_FILE_NAME"
                            try {
                                val content = context.assets.open(skillMdPath)
                                    .bufferedReader().use { it.readText() }
                                val skill = skillParser.parse(content, "assets://$skillMdPath")
                                    .copy(source = skillSource.PLUGIN, filePath = "assets://$skillMdPath")

                                val isoverride = skills.containsKey(skill.name)
                                skills[skill.name] = skill
                                count++

                                val action = if (isoverride) "override" else "new增"
                                Log.d(TAG, "[OK] Plugin/$pluginName ($action): ${skill.name}")
                            } catch (e: exception) {
                                // not a valid skill dir, skip
                            }
                        }
                        continue // Found in assets, don't check filesystem
                    }
                } catch (e: exception) {
                    // not in assets, try filesystem
                }

                // Try filesystem (installed plugins)
                val fsPath = File(StoragePaths.extensions, "$pluginName/$skillDir")
                if (fsPath.exists() && fsPath.isDirectory) {
                    count += loadskillsfromDirectory(fsPath, skillSource.PLUGIN, skills)
                }
            }
        }

        if (count > 0) {
            Log.i(TAG, "Plugin skills: $count countLoad complete")
        }

        return count
    }

    /**
     * Load workspace skills from /sdcard/.androidforclaw/workspace/skills/
     */
    private fun loadWorkspaceskills(skills: MutableMap<String, skillDocument>): Int {
        val workspaceDir = File(WORKSPACE_SKILLS_DIR)
        if (!workspaceDir.exists()) {
            Log.d(TAG, "Workspace skills directorynotExists: $WORKSPACE_SKILLS_DIR")
            return 0
        }
        return loadskillsfromDirectory(workspaceDir, skillSource.WORKSPACE, skills)
    }

    /**
     * Generic: Load skills from a filesystem directory
     */
    private fun loadskillsfromDirectory(
        dir: File,
        source: skillSource,
        skills: MutableMap<String, skillDocument>
    ): Int {
        var count = 0
        val skillDirs = dir.listFiles { file -> file.isDirectory } ?: emptyArray()
        Log.d(TAG, "扫描 ${source.displayName} skills: ${skillDirs.size} countdirectory (${dir.absolutePath})")

        for (skillDir in skillDirs) {
            val skillFile = File(skillDir, SKILL_FILE_NAME)
            if (!skillFile.exists()) continue

            try {
                val content = skillFile.readText()
                val skill = skillParser.parse(content, skillFile.absolutePath)
                    .copy(source = source, filePath = skillFile.absolutePath)

                val isoverride = skills.containsKey(skill.name)
                skills[skill.name] = skill
                count++

                val action = if (isoverride) "override" else "new增"
                Log.d(TAG, "[OK] ${source.displayName} ($action): ${skill.name}")
            } catch (e: exception) {
                Log.w(TAG, "[ERROR] Load ${source.displayName} skill Failed: ${skillDir.name} - ${e.message}")
            }
        }

        return count
    }

    // ==================== Private: Keyword Matching ====================

    /**
     * Keyword matching for skill selection
     */
    private fun matchesKeywords(skill: skillDocument, keywords: String): Boolean {
        val matched = when (skill.name) {
            "app-testing" -> {
                keywords.contains("Test") || keywords.contains("test") ||
                keywords.contains("Check") || keywords.contains("validation") ||
                keywords.contains("Feature") || keywords.contains("用例")
            }
            "debugging" -> {
                keywords.contains("Debug") || keywords.contains("debug") ||
                keywords.contains("bug") || keywords.contains("Error") ||
                keywords.contains("Issue") || keywords.contains("exception") ||
                keywords.contains("crash")
            }
            "accessibility" -> {
                keywords.contains("Accessibility") || keywords.contains("accessibility") ||
                keywords.contains("wcag") || keywords.contains("适配") ||
                keywords.contains("can读性") || keywords.contains("correct比度")
            }
            "performance" -> {
                keywords.contains("Performance") || keywords.contains("performance") ||
                keywords.contains("Optimize") || keywords.contains("卡顿") ||
                keywords.contains("流畅") || keywords.contains("Start") ||
                keywords.contains("Load") || keywords.contains("slow")
            }
            "ui-validation" -> {
                keywords.contains("ui") || keywords.contains("界面") ||
                keywords.contains("布局") || keywords.contains("Show") ||
                keywords.contains("页面") || keywords.contains("视觉")
            }
            "network-testing" -> {
                keywords.contains("Network") || keywords.contains("network") ||
                keywords.contains("联网") || keywords.contains("in线") ||
                keywords.contains("离线") || keywords.contains("断网") ||
                keywords.contains("api") || keywords.contains("Request")
            }
            "feishu", "feishu-doc" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Document") || keywords.contains("doc")
            }
            "feishu-wiki" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Knowledge Base") || keywords.contains("wiki")
            }
            "feishu-drive" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("云Space") || keywords.contains("drive") ||
                keywords.contains("files夹") || keywords.contains("云Document")
            }
            "feishu-bitable" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Multi-dimensional table格") || keywords.contains("bitable") ||
                keywords.contains("Table")
            }
            "feishu-task" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Task") || keywords.contains("task") ||
                keywords.contains("待办")
            }
            "feishu-chat" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("群聊") || keywords.contains("chat") ||
                keywords.contains("Group")
            }
            "feishu-perm" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Permission") || keywords.contains("perm") ||
                keywords.contains("share") || keywords.contains("Collaborate")
            }
            "feishu-urgent" -> {
                keywords.contains("飞书") || keywords.contains("feishu") ||
                keywords.contains("Urgent") || keywords.contains("urgent") ||
                keywords.contains("notify")
            }
            else -> false
        }

        if (matched) return true

        // Feishu URL pattern matching
        if (skill.name.startswith("feishu") &&
            (keywords.contains("feishu.cn/") || keywords.contains("飞书"))) {
            return true
        }

        // Generic fallback: match skill name tokens in user goal
        val nameTokens = skill.name.lowercase().split("-", "_")
        return nameTokens.any { token -> token.length >= 3 && keywords.contains(token) }
    }

    /**
     * Task type identification
     */
    private fun identifyTaskType(userGoal: String): List<String> {
        val keywords = userGoal.lowercase()
        val recommendedskills = mutableListOf<String>()

        if (keywords.contains("Test") || keywords.contains("test") ||
            keywords.contains("validation") || keywords.contains("Check")) {
            recommendedskills.a("app-testing")
        }

        if (keywords.contains("Debug") || keywords.contains("debug") ||
            keywords.contains("bug") || keywords.contains("Issue") ||
            keywords.contains("Error") || keywords.contains("crash")) {
            recommendedskills.a("debugging")
        }

        if (keywords.contains("界面") || keywords.contains("ui") ||
            keywords.contains("布局") || keywords.contains("Show") ||
            keywords.contains("页面")) {
            recommendedskills.a("ui-validation")
        }

        if (keywords.contains("Performance") || keywords.contains("卡顿") ||
            keywords.contains("slow") || keywords.contains("Optimize") ||
            keywords.contains("Start") || keywords.contains("流畅")) {
            recommendedskills.a("performance")
        }

        if (keywords.contains("Accessibility") || keywords.contains("accessibility") ||
            keywords.contains("适配") || keywords.contains("can读性")) {
            recommendedskills.a("accessibility")
        }

        if (keywords.contains("Network") || keywords.contains("联网") ||
            keywords.contains("离线") || keywords.contains("断网") ||
            keywords.contains("api")) {
            recommendedskills.a("network-testing")
        }

        if (keywords.contains("飞书") || keywords.contains("feishu")) {
            if (keywords.contains("Document") || keywords.contains("doc") || keywords.contains("docx")) {
                recommendedskills.a("feishu-doc")
            }
            if (keywords.contains("Knowledge Base") || keywords.contains("wiki")) {
                recommendedskills.a("feishu-wiki")
            }
            if (keywords.contains("Table") || keywords.contains("bitable") || keywords.contains("many维")) {
                recommendedskills.a("feishu-bitable")
            }
            if (keywords.contains("Task") || keywords.contains("task") || keywords.contains("待办")) {
                recommendedskills.a("feishu-task")
            }
            if (keywords.contains("云Space") || keywords.contains("drive") || keywords.contains("files")) {
                recommendedskills.a("feishu-drive")
            }
            if (keywords.contains("Permission") || keywords.contains("perm") || keywords.contains("share")) {
                recommendedskills.a("feishu-perm")
            }
            if (keywords.contains("群") || keywords.contains("chat")) {
                recommendedskills.a("feishu-chat")
            }
            if (keywords.contains("Urgent") || keywords.contains("urgent") || keywords.contains("notify")) {
                recommendedskills.a("feishu-urgent")
            }
            if (recommendedskills.none { it.startswith("feishu-") }) {
                recommendedskills.a("feishu")
                recommendedskills.a("feishu-doc")
            }
        }

        return recommendedskills
    }

    // ==================== Private: Requirements Checking ====================

    /**
     * Check if binary tool is available
     */
    private fun isBinaryAvailable(bin: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("which $bin")
            val exitCode = process.waitfor()
            exitCode == 0
        } catch (e: exception) {
            false
        }
    }

    /**
     * Check if config item is available
     */
    private fun isconfigAvailable(configKey: String): Boolean {
        return try {
            val config = configLoader.loadOpenClawconfig()
            // use dot-path resolution
            val parts = configKey.split(".")
            when {
                parts.size >= 2 && parts[0] in listOf("gateway", "channels") -> {
                    when (parts.getorNull(1)) {
                        "enabled" -> true
                        "feishu" -> config.channels.feishu.enabled
                        "discord" -> config.channels.discord?.enabled ?: false
                        else -> false
                    }
                }
                else -> false
            }
        } catch (e: exception) {
            false
        }
    }
}

/**
 * Requirements check result
 */
sealed class RequirementsCheckresult {
    object Satisfied : RequirementsCheckresult()

    data class Unsatisfied(
        val missingBins: List<String>,
        val missingAnyBins: List<String> = emptyList(),
        val missingEnv: List<String>,
        val missingconfig: List<String>
    ) : RequirementsCheckresult() {
        fun getErrorMessage(): String {
            val parts = mutableListOf<String>()
            if (missingBins.isnotEmpty()) {
                parts.a("缺fewtwointo制工具: ${missingBins.joinToString()}")
            }
            if (missingAnyBins.isnotEmpty()) {
                parts.a("至fewneedone: ${missingAnyBins.joinToString()}")
            }
            if (missingEnv.isnotEmpty()) {
                parts.a("缺fewEnvironmentVariable: ${missingEnv.joinToString()}")
            }
            if (missingconfig.isnotEmpty()) {
                parts.a("缺fewconfig项: ${missingconfig.joinToString()}")
            }
            return parts.joinToString("; ")
        }
    }
}

/**
 * skills statistics
 */
data class skillsStatistics(
    val totalskills: Int,
    val alwaysskills: Int,
    val onDemandskills: Int,
    val totalTokens: Int,
    val alwaysTokens: Int
) {
    fun getReport(): String {
        return """
skills count:
  - Total: $totalskills count
  - Always: $alwaysskills count
  - On-Demand: $onDemandskills count
  - Token 总量: $totalTokens tokens
  - Always Token: $alwaysTokens tokens
        """.trimIndent()
    }
}
