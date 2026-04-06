package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-catalog.ts
 *
 * AndroidForClaw adaptation: agent tool implementation.
 */


import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.memory.MemoryManager
import com.xiaomo.androidforclaw.camera.CameraCaptureManager
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.memory.MemoryGetSkill
import com.xiaomo.androidforclaw.agent.tools.memory.MemorySearchSkill
import com.xiaomo.androidforclaw.agent.tools.device.DeviceToolSkillAdapter
import com.xiaomo.androidforclaw.data.model.TaskDataManager
import com.xiaomo.androidforclaw.providers.ToolDefinition

/**
 * Android tool Registry
 *
 * Manages Android platform-specific tools (Platform-specific tools)
 *
 * Aligned with OpenClaw architecture:
 * - toolRegistry: Universal tools (read, write, exec, web_fetch)
 * - AndroidToolRegistry: Android platform tools (tap, screenshot, open_app, memory)
 * - skillsLoader: Markdown skills (mobile-operations.md)
 *
 * Reference: Platform-specific capabilities in OpenClaw's pi-tools.ts
 */
class AndroidToolRegistry(
    private val context: Context,
    private val taskDataManager: TaskDataManager,
    private val memoryManager: MemoryManager? = null,
    private val workspacePath: String = StoragePaths.workspace.absolutePath,
    private val cameraCaptureManager: CameraCaptureManager? = null,
) {
    companion object {
        private const val TAG = "AndroidToolRegistry"
    }

    private val tools = mutableMapOf<String, Skill>()

    init {
        registerAndroidTools()
        registerMemoryTools()
    }

    /**
     * Register Android platform-specific tools
     */
    private fun registerAndroidTools() {
        // === Unified device tool (Playwright-aligned) ===
        // Single entry point for ALL screen operations via ref-based interaction
        // Replaces: screenshot, get_view_tree, tap, swipe, type, long_press, home, back, open_app, wait
        register(DeviceToolSkillAdapter(context))

        // === Android System API (directly call system API, replace UI Automation) ===
        register(AndroidApiSkill(context))

        // === App management tools ===
        register(ListInstalledAppsSkill(context))  // List apps
        register(InstallAppSkill(context))         // Install APK
        register(StartActivityTool(context))       // Start Activity

        // === Control tools ===
        register(StopSkill(taskDataManager)) // Stop
        register(LogSkill())                 // Log

        // === Feishu image (kept as direct tool — media upload needs special handling) ===
        register(FeishuSendImageSkill(context))

        // === Eye (Aligned with OpenClaw camera — Phone camera as agent eyes) ===
        if (cameraCaptureManager != null) {
            register(EyeSkill(context, cameraCaptureManager))
        } else {
            Log.d(TAG, "[WARN] CameraCaptureManager not provided, skipping eye skill")
        }

        Log.d(TAG, "[OK] Registered ${tools.size} Android platform tools")
    }

    /**
     * Register memory tools
     */
    private fun registerMemoryTools() {
        if (memoryManager == null) {
            Log.d(TAG, "[WARN] MemoryManager not provided, skipping memory tools")
            return
        }

        // === Memory tools (Memory) ===
        register(MemoryGetSkill(memoryManager, workspacePath))
        register(MemorySearchSkill(memoryManager, workspacePath))

        Log.d(TAG, "[OK] Registered memory tools")
    }

    /**
     * Register a tool
     */
    private fun register(tool: Skill) {
        tools[tool.name] = tool
        Log.d(TAG, "  [APP] ${tool.name}")
    }

    /**
     * Check if the specified tool exists
     */
    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * Execute tool
     */
    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
        val tool = tools[name]
        if (tool == null) {
            Log.e(TAG, "Unknown Android tool: $name")
            return SkillResult.error("Unknown Android tool: $name")
        }

        Log.d(TAG, "Executing Android tool: $name with args: $args")
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Android tool execution failed: $name", e)
            SkillResult.error("Execution failed: ${e.message}")
        }
    }

    /**
     * Get all tool definitions (for LLM function calling)
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.getToolDefinition() }
    }

    /**
     * Get all tools description (for building system prompt)
     */
    fun getToolsDescription(excludeTools: Set<String> = emptySet()): String {
        return buildString {
            appendLine("## Android Platform tools")
            appendLine()
            appendLine("Android device-specific capabilities, provided via Accessibility Service and System API:")
            appendLine()

            // Organize by category
            val categories = mapOf(
                "Observation" to listOf("screenshot", "get_view_tree"),
                "Interaction" to listOf("tap", "swipe", "type", "long_press"),
                "Navigation" to listOf("home", "back", "open_app"),
                "App Management" to listOf("list_installed_apps", "install_app", "start_activity"),
                "Control" to listOf("wait", "stop", "log"),
                "Browser" to listOf("browser")
            )

            categories.forEach { (category, toolNames) ->
                val filtered = toolNames.filter { it !in excludeTools }
                if (filtered.isNotEmpty()) {
                    appendLine("### $category")
                    filtered.forEach { name ->
                        tools[name]?.let { tool ->
                            appendLine("- **${tool.name}**: ${tool.description.lines().first()}")
                        }
                    }
                    appendLine()
                }
            }
        }
    }

    /**
     * Get tool count
     */
    fun getToolCount(): Int = tools.size
}
