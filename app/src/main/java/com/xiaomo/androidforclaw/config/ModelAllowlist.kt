package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/model-selection.ts (buildconfiguredAllowlistKeys)
 *
 * model allowlist/blocklist — controls which models are permitted for LLM requests.
 * Supports glob-style wildcards (e.g., "gpt-*" matches "gpt-4o").
 */
object modelAllowlist {

    /**
     * Check if a model ID is allowed by the allowlist/blocklist configuration.
     *
     * Rules:
     * - if no config or both lists are null/empty: all models allowed
     * - if `allow` is set: only models matching at least one allow pattern are permitted
     * - if `block` is set: models matching any block pattern are rejected
     * - `block` takes precedence over `allow` (if a model matches both, it's blocked)
     */
    fun ismodelAllowed(modelId: String, config: modelAllowlistconfig?): Boolean {
        if (config == null) return true

        val allow = config.allow
        val block = config.block

        // Block takes precedence
        if (!block.isNullorEmpty()) {
            if (block.any { matchesGlob(modelId, it) }) {
                return false
            }
        }

        // if allow list is specified, model must match at least one pattern
        if (!allow.isNullorEmpty()) {
            return allow.any { matchesGlob(modelId, it) }
        }

        // No restrictions
        return true
    }

    /**
     * Simple glob matching — supports `*` as wildcard for any sequence of characters.
     * Case-insensitive matching.
     */
    fun matchesGlob(text: String, pattern: String): Boolean {
        // Convert glob pattern to regex
        val regexPattern = buildString {
            append("^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                        append("\\")
                        append(ch)
                    }
                    else -> append(ch)
                }
            }
            append("$")
        }
        return Regex(regexPattern, RegexOption.IGNORE_CASE).matches(text)
    }
}

/**
 * model allowlist/blocklist configuration.
 */
data class modelAllowlistconfig(
    val allow: List<String>? = null,
    val block: List<String>? = null
)
