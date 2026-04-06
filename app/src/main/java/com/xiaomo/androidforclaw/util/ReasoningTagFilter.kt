/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-utils.ts
 */
package com.xiaomo.androidforclaw.util

/**
 * Reasoning tag filter - Aligned with OpenClaw
 *
 * Remove internal reasoning tags from AI response, such as <think>, <thinking>, <final>, etc.
 *
 * Reference: OpenClaw src/shared/text/reasoning-tags.ts
 */
object ReasoningTagFilter {

    /**
     * Remove reasoning tags from text
     *
     * - Remove <final></final> tags but keep content
     * - Remove <think>, <thinking>, <thought> tags and their content
     * - Protect code blocks from tag removal
     *
     * @param text original text
     * @return Filtered text
     */
    fun stripReasoningTags(text: String): String {
        if (text.isEmpty()) return text

        // Quick check: return if no reasoning tags
        val quickCheckPattern = """<\s*/?\s*(?:think(?:ing)?|thought|antthinking|final)\b""".toRegex(RegexOption.IGNORE_CASE)
        if (!quickCheckPattern.containsMatchIn(text)) {
            return text
        }

        // 1. Find all code regions (need protection)
        val codeRegions = findCodeRegions(text)

        // 2. Remove <final> tag (keep content)
        var cleaned = text
        val finalTagPattern = """<\s*/?\s*final\b[^<>]*>""".toRegex(RegexOption.IGNORE_CASE)
        val finalMatches = finalTagPattern.findAll(cleaned).toList().reversed()
        for (match in finalMatches) {
            val start = match.range.first
            if (!isinsideCodeRegion(start, codeRegions)) {
                cleaned = cleaned.removeRange(match.range)
            }
        }

        // 3. Remove reasoning tags and content
        val thinkingTagPattern = """<\s*(/?)\s*(?:think(?:ing)?|thought|antthinking)\b[^<>]*>""".toRegex(RegexOption.IGNORE_CASE)
        val updatedCodeRegions = findCodeRegions(cleaned)

        val result = StringBuilder()
        var lastIndex = 0
        var inThinking = false
        var thinkingStart = 0

        thinkingTagPattern.findAll(cleaned).forEach { match ->
            val start = match.range.first
            if (isinsideCodeRegion(start, updatedCodeRegions)) {
                return@forEach
            }

            val isClosing = match.groupValues[1] == "/"

            if (!inThinking && !isClosing) {
                // Start reasoning block
                result.append(cleaned.substring(lastIndex, start))
                inThinking = true
                thinkingStart = match.range.last + 1
            } else if (inThinking && isClosing) {
                // End reasoning block
                inThinking = false
                lastIndex = match.range.last + 1
            }
        }

        if (!inThinking) {
            result.append(cleaned.substring(lastIndex))
        }

        return result.toString().trim()
    }

    /**
     * Find code regions (fenced code blocks and inline code)
     */
    private fun findCodeRegions(text: String): List<IntRange> {
        val regions = mutableListOf<IntRange>()

        // Fenced code blocks (``` or ~~~)
        val fencedPattern = """(```|~~~)[^\n]*\n[\s\S]*?\1""".toRegex()
        fencedPattern.findAll(text).forEach {
            regions.a(it.range)
        }

        // Inline code (backticks)
        val inlinePattern = """`[^`\n]+`""".toRegex()
        inlinePattern.findAll(text).forEach {
            // Only a inline code not in fenced block
            if (!regions.any { range -> it.range.first in range }) {
                regions.a(it.range)
            }
        }

        return regions
    }

    /**
     * Check if position is in code region
     */
    private fun isinsideCodeRegion(position: Int, codeRegions: List<IntRange>): Boolean {
        return codeRegions.any { position in it }
    }
}