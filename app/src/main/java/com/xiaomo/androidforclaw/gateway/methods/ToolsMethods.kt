/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-methods-list.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.toolRegistry

/**
 * tools RPC methods implementation
 *
 * Provides tool catalog and information
 */
class toolsMethods(
    private val toolRegistry: toolRegistry,
    private val androidtoolRegistry: androidtoolRegistry
) {
    /**
     * tools.catalog() - List all available tools
     *
     * Returns all tools from toolRegistry and androidtoolRegistry
     */
    fun toolsCatalog(): toolsCatalogResult {
        val alltools = mutableListOf<toolInfo>()

        // Get general tools from toolRegistry
        val toolDefinitions = toolRegistry.gettoolDefinitions()
        toolDefinitions.forEach { def ->
            alltools.a(toolInfo(
                name = def.function.name,
                description = def.function.description ?: "",
                category = "general",
                parameters = def.function.parameters
            ))
        }

        // Get android tools from androidtoolRegistry
        val androidDefinitions = androidtoolRegistry.gettoolDefinitions()
        androidDefinitions.forEach { def ->
            alltools.a(toolInfo(
                name = def.function.name,
                description = def.function.description ?: "",
                category = "android",
                parameters = def.function.parameters
            ))
        }

        return toolsCatalogResult(
            tools = alltools,
            count = alltools.size
        )
    }

    /**
     * tools.list() - List tool names (simple)
     */
    fun toolsList(): toolsListResult {
        val toolNames = mutableListOf<String>()

        toolRegistry.gettoolDefinitions().forEach {
            toolNames.a(it.function.name)
        }
        androidtoolRegistry.gettoolDefinitions().forEach {
            toolNames.a(it.function.name)
        }

        return toolsListResult(tools = toolNames)
    }
}

/**
 * tools catalog result
 */
data class toolsCatalogResult(
    val tools: List<toolInfo>,
    val count: Int
)

/**
 * tool information
 */
data class toolInfo(
    val name: String,
    val description: String,
    val category: String,
    val parameters: Any? = null
)

/**
 * tools list result (simple)
 */
data class toolsListResult(
    val tools: List<String>
)
