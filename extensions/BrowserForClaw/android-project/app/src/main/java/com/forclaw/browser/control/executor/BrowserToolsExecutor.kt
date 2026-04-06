/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.executor

import com.forclaw.browser.control.model.Toolresult
import com.forclaw.browser.control.tools.*

/**
 * Browser Tool Executor
 *
 * Responsibilities:
 * - Register all available browser tools
 * - Route to concrete tool according to tool name
 * - Uniform exception handling
 */
object BrowserToolsExecutor {

    private val tools = mutableMapOf<String, BrowserTool>()

    /**
     * Initialize executor, register all tools
     *
     * Should be called in Application.onCreate()
     */
    fun init() {
        // Core 5 tools (v0.3.0)
        register(BrowserNavigateTool())
        register(BrowserClickTool())
        register(BrowserTypeTool())
        register(BrowserScrollTool())
        register(BrowserGetContentTool())

        // Added 7 tools (v0.4.0)
        register(BrowserWaitTool())
        register(BrowserExecuteTool())
        register(BrowserPressTool())
        register(BrowserHoverTool())
        register(BrowserSelectTool())
        register(BrowserScreenshotTool())
        register(BrowserGetCookiesTool())
        register(BrowserSetCookiesTool())
    }

    /**
     * Register tool
     *
     * @param tool Tool instance to register
     */
    private fun register(tool: BrowserTool) {
        tools[tool.name] = tool
    }

    /**
     * Execute tool
     *
     * @param toolName Tool name
     * @param args Parameters map
     * @return Execution result
     */
    suspend fun execute(toolName: String, args: Map<String, Any?>): Toolresult {
        // 1. Find tool
        val tool = tools[toolName]
            ?: return Toolresult.error("Unknown tool: $toolName")

        // 2. Execute tool
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            Toolresult.error("Tool execution failed: ${e.message}")
        }
    }

    /**
     * Get all available tool names
     *
     * @return Tool name list
     */
    fun getAvailableTools(): List<String> {
        return tools.keys.toList()
    }

    /**
     * Check if tool exists
     *
     * @param toolName Tool name
     * @return true if tool exists
     */
    fun hasT(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }
}
