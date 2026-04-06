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
 * 浏览器工具执Row器
 *
 * 职责:
 * - RegisterAllAvailable的浏览器工具
 * - according to工具NameRoute到Concrete工具
 * - 统一ProcessException
 */
object BrowserToolsExecutor {

    private val tools = mutableMapOf<String, BrowserTool>()

    /**
     * Initialize执Row器, RegisterAll工具
     *
     * Should在 Application.onCreate() 中call
     */
    fun init() {
        // 核心 5 个工具 (v0.3.0)
        register(BrowserNavigateTool())
        register(BrowserClickTool())
        register(BrowserTypeTool())
        register(BrowserScrollTool())
        register(BrowserGetContentTool())

        // New增 7 个工具 (v0.4.0)
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
     * Register工具
     *
     * @param tool 要Register的工具Instance
     */
    private fun register(tool: BrowserTool) {
        tools[tool.name] = tool
    }

    /**
     * 执Row工具
     *
     * @param toolName 工具Name
     * @param args Parameters Map
     * @return 执Rowresult
     */
    suspend fun execute(toolName: String, args: Map<String, Any?>): Toolresult {
        // 1. Find工具
        val tool = tools[toolName]
            ?: return Toolresult.error("Unknown tool: $toolName")

        // 2. 执Row工具
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            Toolresult.error("Tool execution failed: ${e.message}")
        }
    }

    /**
     * GetAllAvailable工具的Name
     *
     * @return 工具NameList
     */
    fun getAvailableTools(): List<String> {
        return tools.keys.toList()
    }

    /**
     * Check if tool exists
     *
     * @param toolName 工具Name
     * @return true if工具Exists
     */
    fun hasT(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }
}
