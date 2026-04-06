package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-catalog.ts, openclaw-tools.ts
 *
 * androidforClaw adaptation: register app, android, config, and extension tools.
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.gateway.methods.configMethods
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * tool Registry - Manages universal low-level tools
 * Inspired by OpenClaw's pi-tools (from Pi Coding agent)
 *
 * tools are cross-platform universal capabilities:
 * - read_file, write_file, edit_file: File operations
 * - list_dir: Directory listing
 * - exec: Execute shell commands (auto-routes to embeed Termux or internal shell)
 * - web_fetch: Web fetching
 *
 * note: android-specific capabilities are managed in androidtoolRegistry
 */
class toolRegistry(
    private val context: context,
    private val taskDatamanager: TaskDatamanager
) {
    companion object {
        private const val TAG = "toolRegistry"
    }

    private val tools = mutableMapOf<String, tool>()

    init {
        registerDefaulttools()
    }

    /**
     * Register universal tools (cross-platform capabilities)
     */
    private fun registerDefaulttools() {
        // use external storage workspace (aligned with OpenClaw ~/.openclaw/workspace/)
        val workspace = StoragePaths.workspace
        workspace.mkdirs()

        // === File system tools (from Pi Coding agent) ===
        register(ReadFiletool(workspace = workspace))
        register(WriteFiletool(workspace = workspace))
        register(EditFiletool(workspace = workspace))
        register(ListDirtool(workspace = workspace))

        // === Memory tools (Memory Recall) ===
        
        // Memory tools registered in androidtoolRegistry (MemorySearchskill/MemoryGetskill)

        // === Patch tool (OpenClaw app-patch.ts) ===
        register(ApplyPatchtool(workspace = workspace))

        // === Shell tools ===
        // Single exec entry with backend routing (auto/termux/internal).
        register(ExecFacadetool(context, workingDir = workspace.absolutePath))

        // === Network tools ===
        register(WebFetchtool())
        register(WebSearchtool {
            // Resolve Brave API key from environment or openclaw.json
            System.getenv("BRAVE_API_KEY") ?: try {
                val json = org.json.JSONObject(
                    StoragePaths.openclawconfig.readText()
                )
                json.optJSONObject("tools")
                    ?.optJSONObject("web")
                    ?.optJSONObject("search")
                    ?.optString("apiKey", null)
            } catch (_: exception) { null }
        })

        // === canvas tool (Screen Tab WebView) ===
        register(canvastool(context))

        // === config tools ===
        val configMethods = configMethods(context)
        register(configGettool(configMethods))
        register(configSettool(configMethods))

        // === TTS tool (OpenClaw tts-tool.ts) ===
        register(Ttstool(context))

        // === Body tool (agent's virtual body) — only when enabled ===
        val bodyEnabled = context.getSharedPreferences("forclaw_avatar", android.content.context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (bodyEnabled) {
            Avatartool.appcontext = context.applicationcontext
            register(Avatartool())
        }

        // === ClawHub skill hub tools ===
        // Aligned with OpenClaw gateway RPC: skills.search / skills.install
        register(skillsSearchtool(context))
        register(skillsInstalltool(context))
        register(ClawHubconfigtool(context))

        // === Lark CLI (飞书官方 CLI) ===
        register(LarkClitool(context))

        Log.d(TAG, "[OK] Registered ${tools.size} universal tools (memory tools in androidtoolRegistry)")
    }

    /**
     * Register a tool
     */
    fun register(tool: tool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }

    /**
     * Check if the specified tool exists
     */
    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * Execute tool
     */
    suspend fun execute(name: String, args: Map<String, Any?>): toolresult {
        val tool = tools[name]
        if (tool == null) {
            Log.e(TAG, "Unknown tool: $name")
            return toolresult.error("Unknown tool: $name")
        }

        Log.d(TAG, "Executing tool: $name with args: $args")
        return try {
            tool.execute(args)
        } catch (e: exception) {
            Log.e(TAG, "tool execution failed: $name", e)
            toolresult.error("Execution failed: ${e.message}")
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
            appendLine("## Universal tools")
            appendLine()
            appendLine("跨platform通用工具, from Pi Coding agent and OpenClaw: ")
            appendLine()
            tools.values
                .filter { it.name !in excludetools }
                .forEach { tool ->
                    appendLine("### ${tool.name}")
                    appendLine(tool.description)
                    appendLine()
                }
        }
    }

    /**
     * Get tool count
     */
    fun gettoolCount(): Int = tools.size
}
