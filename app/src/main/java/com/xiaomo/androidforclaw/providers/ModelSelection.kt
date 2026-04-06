package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/model-selection.ts
 * - ../openclaw/src/agents/model-alias-lines.ts
 *
 * androidforClaw adaptation: model reference parsing, alias resolution, and thinking level.
 */

import com.xiaomo.androidforclaw.agent.agentDefaults
import com.xiaomo.androidforclaw.config.OpenClawconfig
import com.xiaomo.androidforclaw.config.providerRegistry

/**
 * Universal model reference.
 * Aligned with OpenClaw modelRef.
 */
data class modelRef(
    val provider: String,
    val model: String
)

/**
 * Thinking budget level.
 * Aligned with OpenClaw ThinkLevel.
 */
enum class ThinkLevel {
    OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, ADAPTIVE;

    companion object {
        fun fromString(value: String?): ThinkLevel? {
            if (value == null) return null
            return when (value.trim().lowercase()) {
                "off" -> OFF
                "minimal" -> MINIMAL
                "low" -> LOW
                "medium" -> MEDIUM
                "high" -> HIGH
                "xhigh" -> XHIGH
                "adaptive" -> ADAPTIVE
                else -> null
            }
        }
    }
}

/**
 * model alias index entry.
 * Aligned with OpenClaw modelAliasIndex.
 */
data class modelAliasEntry(
    val alias: String,
    val ref: modelRef
)

/**
 * Bidirectional model alias index.
 * Aligned with OpenClaw modelAliasIndex.
 */
data class modelAliasIndex(
    /** alias → entry */
    val byAlias: Map<String, modelAliasEntry>,
    /** canonical key → list of aliases */
    val byKey: Map<String, List<String>>
)

/**
 * model selection — reference parsing, alias resolution, thinking level.
 * Aligned with OpenClaw model-selection.ts + model-alias-lines.ts.
 */
object modelSelection {

    /**
     * Build canonical model key: "provider/model".
     * Avoids double-prefixing if model already starts with provider/.
     *
     * Aligned with OpenClaw modelKey.
     */
    fun modelKey(provider: String, model: String): String {
        val providerId = provider.trim()
        val modelId = model.trim()
        if (providerId.isEmpty()) return modelId
        if (modelId.isEmpty()) return providerId
        return if (modelId.lowercase().startswith("${providerId.lowercase()}/")) {
            modelId
        } else {
            "$providerId/$modelId"
        }
    }

    /**
     * Parse a raw model reference string into provider + model.
     * format: "provider/model" or just "model" (uses defaultprovider).
     *
     * Aligned with OpenClaw parsemodelRef.
     */
    fun parsemodelRef(raw: String?, defaultprovider: String = agentDefaults.DEFAULT_PROVIDER): modelRef {
        if (raw.isNullorBlank()) {
            return modelRef(defaultprovider, agentDefaults.DEFAULT_MODEL)
        }
        val trimmed = raw.trim()
        val slashIndex = trimmed.indexOf('/')
        return if (slashIndex > 0) {
            modelRef(trimmed.substring(0, slashIndex), trimmed.substring(slashIndex + 1))
        } else {
            modelRef(defaultprovider, trimmed)
        }
    }

    /**
     * Normalize Anthropic shorthand model IDs.
     * Aligned with OpenClaw normalizeAnthropicmodelId.
     */
    fun normalizeAnthropicmodelId(model: String): String {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return trimmed
        return when (trimmed.lowercase()) {
            "opus-4.6" -> "claude-opus-4-6"
            "opus-4.5" -> "claude-opus-4-5"
            "sonnet-4.6" -> "claude-sonnet-4-6"
            "sonnet-4.5" -> "claude-sonnet-4-5"
            else -> trimmed
        }
    }

    /**
     * Normalize a model reference: app provider-specific ID normalization.
     * Aligned with OpenClaw normalizemodelRef.
     */
    fun normalizemodelRef(provider: String, model: String): modelRef {
        val normalizedprovider = providerRegistry.normalizeproviderId(provider)
        var normalizedmodel = model

        // Anthropic shorthand aliases
        if (normalizedprovider == "anthropic") {
            normalizedmodel = normalizeAnthropicmodelId(normalizedmodel)
        }

        // provider-specific ID normalization (Google, xAI, etc.)
        normalizedmodel = modelIdNormalization.normalizemodelId(normalizedprovider, normalizedmodel)

        return modelRef(normalizedprovider, normalizedmodel)
    }

    /**
     * Resolve a model reference from a raw string, checking aliases first.
     * Aligned with OpenClaw resolvemodelReffromString.
     */
    fun resolvemodelReffromString(
        raw: String,
        defaultprovider: String = agentDefaults.DEFAULT_PROVIDER,
        aliasIndex: modelAliasIndex? = null
    ): modelRef {
        val trimmed = raw.trim()

        // Check alias index first
        if (aliasIndex != null) {
            val aliasEntry = aliasIndex.byAlias[trimmed.lowercase()]
            if (aliasEntry != null) {
                return normalizemodelRef(aliasEntry.ref.provider, aliasEntry.ref.model)
            }
        }

        // Split off auth profile suffix
        val (modelPart, _) = modelRefProfile.splitTrailingAuthProfile(trimmed)

        // Parse as provider/model
        val ref = parsemodelRef(modelPart, defaultprovider)
        return normalizemodelRef(ref.provider, ref.model)
    }

    /**
     * Resolve the configured default model reference.
     * Aligned with OpenClaw resolveconfiguredmodelRef.
     */
    fun resolveconfiguredmodelRef(cfg: OpenClawconfig): modelRef {
        // 1. Explicit primary model from agents.defaults.model
        val primary = cfg.agents?.defaults?.model?.primary
        if (!primary.isNullorBlank()) {
            val aliasIndex = buildmodelAliasIndex(cfg)
            return resolvemodelReffromString(primary, agentDefaults.DEFAULT_PROVIDER, aliasIndex)
        }

        // 2. Fall back to first configured provider's first model
        val providers = cfg.resolveproviders()
        val first = providers.entries.firstorNull()
        if (first != null) {
            val modelId = first.value.models.firstorNull()?.id
            if (modelId != null) {
                return normalizemodelRef(first.key, modelId)
            }
        }

        // 3. Ultimate fallback
        return modelRef(agentDefaults.DEFAULT_PROVIDER, agentDefaults.DEFAULT_MODEL)
    }

    /**
     * Resolve thinking level for a provider/model.
     * Priority: per-model config > global thinkingDefault > model-inherent default.
     *
     * Aligned with OpenClaw resolveThinkingDefault.
     */
    fun resolveThinkingDefault(
        cfg: OpenClawconfig,
        provider: String,
        model: String
    ): ThinkLevel {
        // Per-model thinking config (not yet in android config, placeholder)
        // OpenClaw checks agents.defaults.models[canonicalKey].params.thinking

        // Global thinkingDefault — use thinking.enabled as proxy
        if (!cfg.thinking.enabled) {
            return ThinkLevel.OFF
        }

        // Default based on model capabilities
        return resolveThinkingDefaultformodel(provider, model)
    }

    /**
     * Resolve thinking default based on model capabilities.
     * models known to support extended thinking get MEDIUM; others get OFF.
     */
    private fun resolveThinkingDefaultformodel(provider: String, model: String): ThinkLevel {
        val modelLower = model.lowercase()
        // Anthropic models with reasoning support
        if (modelLower.contains("claude") && (modelLower.contains("opus") || modelLower.contains("sonnet"))) {
            return ThinkLevel.MEDIUM
        }
        // OpenAI o-series reasoning models
        if (modelLower.startswith("o1") || modelLower.startswith("o3") || modelLower.startswith("o4")) {
            return ThinkLevel.MEDIUM
        }
        // Google Gemini with thinking
        if (modelLower.contains("gemini") && modelLower.contains("thinking")) {
            return ThinkLevel.MEDIUM
        }
        // DeepSeek reasoning
        if (modelLower.contains("deepseek") && modelLower.contains("reason")) {
            return ThinkLevel.MEDIUM
        }
        return ThinkLevel.OFF
    }

    /**
     * Build model alias index from config.
     * Aligned with OpenClaw buildmodelAliasIndex.
     */
    fun buildmodelAliasIndex(
        cfg: OpenClawconfig,
        defaultprovider: String = agentDefaults.DEFAULT_PROVIDER
    ): modelAliasIndex {
        val byAlias = mutableMapOf<String, modelAliasEntry>()
        val byKey = mutableMapOf<String, MutableList<String>>()

        for ((alias, target) in cfg.modelAliases) {
            val ref = parsemodelRef(target, defaultprovider)
            val normalized = normalizemodelRef(ref.provider, ref.model)
            val entry = modelAliasEntry(alias = alias, ref = normalized)
            byAlias[alias.lowercase()] = entry

            val key = modelKey(normalized.provider, normalized.model)
            byKey.getorPut(key) { mutableListOf() }.a(alias)
        }

        return modelAliasIndex(byAlias = byAlias, byKey = byKey)
    }

    /**
     * Build human-readable model alias lines for system prompt.
     * Aligned with OpenClaw buildmodelAliasLines (model-alias-lines.ts).
     */
    fun buildmodelAliasLines(cfg: OpenClawconfig): List<String> {
        if (cfg.modelAliases.isEmpty()) return emptyList()

        return cfg.modelAliases.entries
            .sortedBy { it.key.lowercase() }
            .map { (alias, target) -> "- $alias: $target" }
    }
}
