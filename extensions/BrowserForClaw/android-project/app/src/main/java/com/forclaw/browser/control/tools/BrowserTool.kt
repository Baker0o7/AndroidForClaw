/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import com.forclaw.browser.control.model.Toolresult

/**
 * 浏览器工具Interface
 *
 * All浏览器工具MustImplementation此Interface
 */
interface BrowserTool {
    /**
     * 工具Name (such as "browser_navigate")
     */
    val name: String

    /**
     * 执Row工具
     *
     * @param args Parameters Map
     * @return 执Rowresult
     */
    suspend fun execute(args: Map<String, Any?>): Toolresult
}
