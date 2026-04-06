/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.tools

import com.forclaw.browser.control.model.Toolresult

/**
 * Browser Tool Interface
 *
 * All browser tools must implement this interface
 */
interface BrowserTool {
    /**
     * Tool name (such as "browser_navigate")
     */
    val name: String

    /**
     * Execute the tool
     *
     * @param args Parameters map
     * @return Execution result
     */
    suspend fun execute(args: Map<String, Any?>): Toolresult
}
