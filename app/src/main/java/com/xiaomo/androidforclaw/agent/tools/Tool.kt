package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.providers.toolDefinition

/**
 * tool interface - Low-level tools (inspired by nanobot's tool base class)
 *
 * tools are low-level, universal capabilities, such as:
 * - exec: Execute shell commands
 * - read_file: Read files
 * - write_file: Write files
 *
 * Difference from skill:
 * - tool: Code-level implementation, low-level operations (file, network, shell)
 * - skill: android-specific capabilities, business-level operations (tap, screenshot)
 */
interface tool {
    /**
     * tool name (corresponds to function name)
     */
    val name: String

    /**
     * tool description
     */
    val description: String

    /**
     * Get tool Definition (for LLM function calling)
     */
    fun gettoolDefinition(): toolDefinition

    /**
     * Execute tool
     * @param args Parameter map
     * @return toolResult Execution result
     */
    suspend fun execute(args: Map<String, Any?>): toolResult
}

// tool and skill share the same Result type
typealias toolResult = skillResult
