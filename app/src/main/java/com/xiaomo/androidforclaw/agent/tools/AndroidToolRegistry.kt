package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-catalog.ts
 *
 * androidforClaw adaptation: agent tool implementation.
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.memory.Memorymanager
import com.xiaomo.androidforclaw.camera.CameraCapturemanager
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.agent.tools.memory.MemoryGetskill
import com.xiaomo.androidforclaw.agent.tools.memory.MemorySearchskill
import com.xiaomo.androidforclaw.agent.tools.device.DevicetoolskillAdapter
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * android tool Registry
 *
 * Manages android platform-specific tools (Platform-specific tools)
 *
 * Aligned with OpenClaw architecture:
 * - toolRegistry: Universal tools (read, write, exec, web_fetch)
 * - androidtoolRegistry: android platform tools (tap, screenshot, open_app, memory)
 * - skillsLoader: Markdown skills (mobile-operations.md)
 *
 * Reference: Platform-specific capabilities in OpenClaw's pi-tools.ts
 */
class androidtoolRegistry(
    private val context: context,
    private val taskDatamanager: TaskDatamanager,
    private val memorymanager: Memorymanager? = null,
    private val workspacePath: String = StoragePaths.workspace.absolutePath,
    private val cameraCapturemanager: CameraCapturemanager? = null,
) {
    companion object {
        private const val TAG = "androidtoolRegistry"
    }

    private val tools = mutableMapOf<String, skill>()

    init {
        registerandroidtools()
        registerMemorytools()
    }

    /**
     * Register android platform-specific tools
     */
    private fun registerandroidtools() {
        // === Unified device tool (Playwright-aligned) ===
        // Single entry point for ALL screen operations via ref-based interaction
        // Replaces: screenshot, get_view_tree, tap, swipe, type, long_press, home, back, open_app, wait
        register(DevicetoolskillAdapter(context))

        // === android System API (directly call system API, replace UI Automation) ===
        register(androidApiskill(context))

        // === App management tools ===
        register(ListInstalledAppsskill(context))  // List apps
        register(InstallAppskill(context))         // Install APK
        register(StartActivitytool(context))       // Start Activity

        // === Control tools ===
        register(Stopskill(taskDatamanager)) // Stop
        register(Logskill())                 // Log

        // === Feishu image (kept as direct tool — media upload needs special handling) ===
        register(FeishuSendImageskill(context))

        // === Eye (Aligned with OpenClaw camera — Phone cameralike头作for agent eyes) ===
        if (cameraCapturemanager != null) {
            register(Eyeskill(context, cameraCapturemanager))
        } else {
            Log.d(TAG, "[WARN] CameraCapturemanager not provided, skipping eye skill")
        }

        Log.d(TAG, "[OK] Registered ${tools.size} android platform tools")
    }

    /**
     * Register memory tools
     */
    private fun registerMemorytools() {
        if (memorymanager == null) {
            Log.d(TAG, "[WARN] Memorymanager not provided, skipping memory tools")
            return
        }

        // === Memory tools (Memory) ===
        register(MemoryGetskill(memorymanager, workspacePath))
        register(MemorySearchskill(memorymanager, workspacePath))

        Log.d(TAG, "[OK] Registered memory tools")
    }

    /**
     * Register a tool
     */
    private fun register(tool: skill) {
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
    suspend fun execute(name: String, args: Map<String, Any?>): skillresult {
        val tool = tools[name]
        if (tool == null) {
            Log.e(TAG, "Unknown android tool: $name")
            return skillresult.error("Unknown android tool: $name")
        }

        Log.d(TAG, "Executing android tool: $name with args: $args")
        return try {
            tool.execute(args)
        } catch (e: exception) {
            Log.e(TAG, "android tool execution failed: $name", e)
            skillresult.error("Execution failed: ${e.message}")
        }
    }

    /**
     * Get all tool Definitions (for LLM function calling)
     */
    fun gettoolDefinitions(): List<toolDefinition> {
        return tools.values.map { it.gettoolDefinition() }
    }

    /**
     * Get all tools description (for building system prompt)
     */
    fun gettoolsDescription(excludetools: Set<String> = emptySet()): String {
        return buildString {
            appendLine("## android Platform tools")
            appendLine()
            appendLine("android Device专属Capability,through Accessibilityservice and系统 API 提供: ")
            appendLine()

            // organize by category
            val categories = mapOf(
                "观察" to listOf("screenshot", "get_view_tree"),
                "interaction" to listOf("tap", "swipe", "type", "long_press"),
                "导航" to listOf("home", "back", "open_app"),
                "appManage" to listOf("list_installed_apps", "install_app", "start_activity"),
                "控制" to listOf("wait", "stop", "log"),
                "浏览器" to listOf("browser")
            )

            categories.forEach { (category, toolNames) ->
                val filtered = toolNames.filter { it !in excludetools }
                if (filtered.isnotEmpty()) {
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
    fun gettoolCount(): Int = tools.size
}
