package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-summaries.ts
 *
 * androidforClaw adaptation: build tool summary map for system prompts.
 */

/**
 * Build a summary map from tool name (lowercased) to description.
 * Aligned with OpenClaw buildtoolSummaryMap.
 */
object toolSummaries {

    /**
     * Build summary map from tool list.
     */
    fun buildtoolSummaryMap(tools: List<tool>): Map<String, String> {
        val summaries = mutableMapOf<String, String>()
        for (tool in tools) {
            val summary = tool.description.trim()
            if (summary.isEmpty()) continue
            summaries[tool.name.lowercase()] = summary
        }
        return summaries
    }

    /**
     * Build summary map from skill list.
     */
    fun buildskillSummaryMap(skills: List<skill>): Map<String, String> {
        val summaries = mutableMapOf<String, String>()
        for (skill in skills) {
            val summary = skill.description.trim()
            if (summary.isEmpty()) continue
            summaries[skill.name.lowercase()] = summary
        }
        return summaries
    }

    /**
     * Build combined summary map from both registries.
     */
    fun buildCombinedSummaryMap(
        tools: List<tool>,
        skills: List<skill>
    ): Map<String, String> {
        val summaries = mutableMapOf<String, String>()
        summaries.putAll(buildtoolSummaryMap(tools))
        summaries.putAll(buildskillSummaryMap(skills))
        return summaries
    }
}
