package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/provider-capabilities.ts
 *
 * androidforClaw adaptation: per-provider capability resolution.
 */

import com.xiaomo.androidforclaw.config.providerRegistry

/**
 * Per-provider capability flags.
 * Aligned with OpenClaw providerCapabilities type.
 */
data class providerCapabilities(
    val anthropictoolschemaMode: String = "native",           // "native" | "openai-functions"
    val anthropictoolChoiceMode: String = "native",           // "native" | "openai-string-modes"
    val providerFamily: String = "default",                   // "default" | "openai" | "anthropic"
    val preserveAnthropicThinkingSignatures: Boolean = true,
    val openAiCompatTurnValidation: Boolean = true,
    val geminiThoughtSignatureSanitization: Boolean = false,
    val transcripttoolCallIdMode: String = "default",         // "default" | "strict9"
    val transcripttoolCallIdmodelHints: List<String> = emptyList(),
    val geminiThoughtSignaturemodelHints: List<String> = emptyList(),
    val dropThinkingBlockmodelHints: List<String> = emptyList()
) {
    companion object {
        /**
         * Default capabilities.
         * Aligned with OpenClaw DEFAULT_PROVIDER_CAPABILITIES.
         */
        val DEFAULT = providerCapabilities()
    }
}

/**
 * provider capability resolution.
 * Aligned with OpenClaw provider-capabilities.ts.
 */
object providerCapabilityResolver {

    /**
     * Core provider capabilities (hard-coded, not plugin-dependent).
     * Aligned with OpenClaw CORE_PROVIDER_CAPABILITIES.
     */
    private val CORE_PROVIDER_CAPABILITIES: Map<String, providerCapabilities> = mapOf(
        "anthropic-vertex" to providerCapabilities(
            providerFamily = "anthropic",
            dropThinkingBlockmodelHints = listOf("claude")
        ),
        "amazon-bedrock" to providerCapabilities(
            providerFamily = "anthropic",
            dropThinkingBlockmodelHints = listOf("claude")
        )
    )

    /**
     * Plugin capability fallbacks (used when no plugin provides capabilities).
     * Aligned with OpenClaw PLUGIN_CAPABILITIES_FALLBACKS.
     */
    private val PLUGIN_CAPABILITIES_FALLBACKS: Map<String, providerCapabilities> = mapOf(
        "anthropic" to providerCapabilities(
            providerFamily = "anthropic",
            dropThinkingBlockmodelHints = listOf("claude")
        ),
        "mistral" to providerCapabilities(
            transcripttoolCallIdMode = "strict9",
            transcripttoolCallIdmodelHints = listOf(
                "mistral", "mixtral", "codestral", "pixtral",
                "devstral", "ministral", "mistralai"
            )
        ),
        "opencode" to providerCapabilities(
            openAiCompatTurnValidation = false,
            geminiThoughtSignatureSanitization = true,
            geminiThoughtSignaturemodelHints = listOf("gemini")
        ),
        "opencode-go" to providerCapabilities(
            openAiCompatTurnValidation = false,
            geminiThoughtSignatureSanitization = true,
            geminiThoughtSignaturemodelHints = listOf("gemini")
        ),
        "openai" to providerCapabilities(
            providerFamily = "openai"
        )
    )

    /**
     * Resolve capabilities for a provider.
     * Merge order: DEFAULT → CORE → PLUGIN_FALLBACKS.
     *
     * Aligned with OpenClaw resolveproviderCapabilities.
     */
    fun resolveproviderCapabilities(provider: String): providerCapabilities {
        val normalized = providerRegistry.normalizeproviderId(provider)

        // Start with defaults
        var result = providerCapabilities.DEFAULT

        // Layer core capabilities
        CORE_PROVIDER_CAPABILITIES[normalized]?.let { core ->
            result = mergeCapabilities(result, core)
        }

        // Layer plugin fallbacks
        PLUGIN_CAPABILITIES_FALLBACKS[normalized]?.let { fallback ->
            result = mergeCapabilities(result, fallback)
        }

        return result
    }

    /**
     * Merge non-default fields from overlay onto base.
     */
    private fun mergeCapabilities(base: providerCapabilities, overlay: providerCapabilities): providerCapabilities {
        val default = providerCapabilities.DEFAULT
        return base.copy(
            anthropictoolschemaMode = if (overlay.anthropictoolschemaMode != default.anthropictoolschemaMode) overlay.anthropictoolschemaMode else base.anthropictoolschemaMode,
            anthropictoolChoiceMode = if (overlay.anthropictoolChoiceMode != default.anthropictoolChoiceMode) overlay.anthropictoolChoiceMode else base.anthropictoolChoiceMode,
            providerFamily = if (overlay.providerFamily != default.providerFamily) overlay.providerFamily else base.providerFamily,
            preserveAnthropicThinkingSignatures = if (!overlay.preserveAnthropicThinkingSignatures) overlay.preserveAnthropicThinkingSignatures else base.preserveAnthropicThinkingSignatures,
            openAiCompatTurnValidation = if (!overlay.openAiCompatTurnValidation) overlay.openAiCompatTurnValidation else base.openAiCompatTurnValidation,
            geminiThoughtSignatureSanitization = if (overlay.geminiThoughtSignatureSanitization) overlay.geminiThoughtSignatureSanitization else base.geminiThoughtSignatureSanitization,
            transcripttoolCallIdMode = if (overlay.transcripttoolCallIdMode != default.transcripttoolCallIdMode) overlay.transcripttoolCallIdMode else base.transcripttoolCallIdMode,
            transcripttoolCallIdmodelHints = if (overlay.transcripttoolCallIdmodelHints.isnotEmpty()) overlay.transcripttoolCallIdmodelHints else base.transcripttoolCallIdmodelHints,
            geminiThoughtSignaturemodelHints = if (overlay.geminiThoughtSignaturemodelHints.isnotEmpty()) overlay.geminiThoughtSignaturemodelHints else base.geminiThoughtSignaturemodelHints,
            dropThinkingBlockmodelHints = if (overlay.dropThinkingBlockmodelHints.isnotEmpty()) overlay.dropThinkingBlockmodelHints else base.dropThinkingBlockmodelHints
        )
    }

    // ── helper booleans ──

    /**
     * Whether the provider preserves Anthropic thinking signatures.
     * Aligned with OpenClaw preservesAnthropicThinkingSignatures.
     */
    fun preservesAnthropicThinkingSignatures(provider: String): Boolean {
        return resolveproviderCapabilities(provider).preserveAnthropicThinkingSignatures
    }

    /**
     * Whether the provider is in the OpenAI family.
     * Aligned with OpenClaw isOpenAiproviderFamily.
     */
    fun isOpenAiproviderFamily(provider: String): Boolean {
        return resolveproviderCapabilities(provider).providerFamily == "openai"
    }

    /**
     * Whether the provider is in the Anthropic family.
     * Aligned with OpenClaw isAnthropicproviderFamily.
     */
    fun isAnthropicproviderFamily(provider: String): Boolean {
        return resolveproviderCapabilities(provider).providerFamily == "anthropic"
    }

    /**
     * Whether thinking blocks should be dropped for a specific model.
     * Aligned with OpenClaw shouldDropThinkingBlocksformodel.
     */
    fun shouldDropThinkingBlocksformodel(provider: String, model: String): Boolean {
        val caps = resolveproviderCapabilities(provider)
        if (caps.dropThinkingBlockmodelHints.isEmpty()) return false
        val modelLower = model.lowercase()
        return caps.dropThinkingBlockmodelHints.any { hint -> modelLower.contains(hint) }
    }

    /**
     * Whether Gemini thought signatures should be sanitized for a specific model.
     * Aligned with OpenClaw shouldSanitizeGeminiThoughtSignaturesformodel.
     */
    fun shouldSanitizeGeminiThoughtSignatures(provider: String, model: String): Boolean {
        val caps = resolveproviderCapabilities(provider)
        if (!caps.geminiThoughtSignatureSanitization) return false
        if (caps.geminiThoughtSignaturemodelHints.isEmpty()) return true
        val modelLower = model.lowercase()
        return caps.geminiThoughtSignaturemodelHints.any { hint -> modelLower.contains(hint) }
    }

    /**
     * Resolve transcript tool call ID mode for a specific model.
     * Aligned with OpenClaw resolveTranscripttoolCallIdMode.
     */
    fun resolveTranscripttoolCallIdMode(provider: String, model: String): String {
        val caps = resolveproviderCapabilities(provider)
        if (caps.transcripttoolCallIdMode == "default") return "default"
        if (caps.transcripttoolCallIdmodelHints.isEmpty()) return caps.transcripttoolCallIdMode
        val modelLower = model.lowercase()
        return if (caps.transcripttoolCallIdmodelHints.any { hint -> modelLower.contains(hint) }) {
            caps.transcripttoolCallIdMode
        } else {
            "default"
        }
    }
}
