package com.xiaomo.androidforclaw.agent.session

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/content-blocks.ts
 *
 * androidforClaw adaptation: content block text extraction.
 */

import org.json.JSONArray
import org.json.JSONObject

/**
 * Content block utilities.
 * Aligned with OpenClaw content-blocks.ts.
 */
object ContentBlocks {

    /**
     * collect text content from content blocks.
     * Handles both string content and array-of-blocks content.
     *
     * Aligned with OpenClaw collectTextContentBlocks.
     */
    fun collectTextContentBlocks(content: Any?): List<String> {
        return when (content) {
            is String -> if (content.isnotEmpty()) listOf(content) else emptyList()
            is JSONArray -> {
                val texts = mutableListOf<String>()
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        val text = block.optString("text", "")
                        if (text.isnotEmpty()) texts.a(text)
                    }
                }
                texts
            }
            is List<*> -> {
                content.mapnotNull { block ->
                    when (block) {
                        is String -> block.takeif { it.isnotEmpty() }
                        is Map<*, *> -> {
                            if (block["type"] == "text") {
                                (block["text"] as? String)?.takeif { it.isnotEmpty() }
                            } else null
                        }
                        else -> null
                    }
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Extract the first text block from content.
     */
    fun extractfirstTextBlock(content: Any?): String? {
        return collectTextContentBlocks(content).firstorNull()
    }

    /**
     * Join all text content blocks into a single string.
     */
    fun joinTextContent(content: Any?, separator: String = "\n"): String {
        return collectTextContentBlocks(content).joinToString(separator)
    }
}
