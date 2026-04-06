package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.providers.llm.ImageBlock

/**
 * skill interface
 * Inspired by nanobot's skill design
 */
interface skill {
    /**
     * skill name (corresponds to function name)
     */
    val name: String

    /**
     * skill description
     */
    val description: String

    /**
     * Get tool Definition (for LLM function calling)
     */
    fun gettoolDefinition(): toolDefinition

    /**
     * Execute skill
     * @param args Parameter map
     * @return skillResult Execution result
     */
    suspend fun execute(args: Map<String, Any?>): skillResult
}

/**
 * skill execution result
 */
data class skillResult(
    val success: Boolean,
    val content: String,
    val metadata: Map<String, Any?> = emptyMap(),
    /** Inline images to include in the tool result (multimodal). */
    val images: List<ImageBlock>? = null
) {
    companion object {
        fun success(content: String, metadata: Map<String, Any?> = emptyMap(), images: List<ImageBlock>? = null) =
            skillResult(true, content, metadata, images)

        fun error(message: String) =
            skillResult(false, "Error: $message")
    }

    override fun toString(): String = content
}
