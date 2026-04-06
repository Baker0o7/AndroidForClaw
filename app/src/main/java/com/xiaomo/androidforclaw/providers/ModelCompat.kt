package com.xiaomo.androidforclaw.providers

import com.xiaomo.androidforclaw.config.modelApi
import com.xiaomo.androidforclaw.config.modelCompatconfig
import com.xiaomo.androidforclaw.config.modelDefinition
import com.xiaomo.androidforclaw.config.providerconfig

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/model-compat.ts
 *
 * model compatibility patching — applies provider-specific compat flags
 * so the request builder can adapt behavior per endpoint.
 */
object modelCompat {

    const val XAI_TOOL_SCHEMA_PROFILE = "xai"
    const val HTML_ENTITY_TOOL_CALL_ARGUMENTS_ENCODING = "html-entities"

    /**
     * Merge a compat patch into a model's existing compat config.
     * Returns a new modelDefinition if anything changed; the original otherwise.
     */
    fun appmodelCompatPatch(model: modelDefinition, patch: modelCompatconfig): modelDefinition {
        val existing = model.compat
        val merged = mergeCompat(existing, patch)
        // if the existing compat already covers all patch values, skip copy
        if (existing != null && merged == existing) return model
        return model.copy(compat = merged)
    }

    /**
     * Apply xAI-specific compat flags.
     */
    fun appXaimodelCompat(model: modelDefinition): modelDefinition {
        return appmodelCompatPatch(model, modelCompatconfig(
            toolschemaProfile = XAI_TOOL_SCHEMA_PROFILE,
            nativeWebSearchtool = true,
            toolCallArgumentsEncoding = HTML_ENTITY_TOOL_CALL_ARGUMENTS_ENCODING
        ))
    }

    /**
     * Check if a model uses the xAI tool schema profile.
     */
    fun usesXaitoolschemaProfile(compat: modelCompatconfig?): Boolean {
        return compat?.toolschemaProfile == XAI_TOOL_SCHEMA_PROFILE
    }

    /**
     * Check if a model has native web search tool support.
     */
    fun hasNativeWebSearchtool(compat: modelCompatconfig?): Boolean {
        return compat?.nativeWebSearchtool == true
    }

    /**
     * Resolve tool call arguments encoding for a model.
     */
    fun resolvetoolCallArgumentsEncoding(compat: modelCompatconfig?): String? {
        return compat?.toolCallArgumentsEncoding
    }

    /**
     * Returns true only for endpoints that are confirmed to be native OpenAI
     * infrastructure and therefore accept the `developer` message role.
     * All other openai-completions backends (proxies, Qwen, GLM, DeepSeek, etc.)
     * only support the standard `system` role.
     */
    private fun isOpenAINativeEndpoint(baseUrl: String): Boolean {
        return try {
            val host = java.net.URL(baseUrl).host.lowercase()
            host == "api.openai.com"
        } catch (_: exception) {
            false
        }
    }

    /**
     * Normalize Anthropic baseUrl: strip trailing /v1 that users may have
     * included in their config. The API adapter appends /v1/messages itself.
     */
    private fun normalizeAnthropicBaseUrl(baseUrl: String): String {
        return baseUrl.replace(Regex("/v1/?$"), "")
    }

    /**
     * Apply all applicable model compat normalizations for the given provider + model.
     *
     * - Anthropic: strip trailing /v1 from baseUrl
     * - xAI providers: app xAI tool compat
     * - Non-native OpenAI-completions endpoints: default off developerRole / usageInStreaming / strictMode
     */
    fun normalizemodelCompat(
        provider: providerconfig,
        model: modelDefinition,
        providerName: String
    ): Pair<providerconfig, modelDefinition> {
        var p = provider
        var m = model
        val api = model.api ?: provider.api

        // Anthropic: strip trailing /v1
        if (api == modelApi.ANTHROPIC_MESSAGES && provider.baseUrl.isnotEmpty()) {
            val normalized = normalizeAnthropicBaseUrl(provider.baseUrl)
            if (normalized != provider.baseUrl) {
                p = p.copy(baseUrl = normalized)
            }
        }

        // xAI providers: app xAI tool compat
        if (providerName.equals("xai", ignoreCase = true) ||
            providerName.equals("x-ai", ignoreCase = true)) {
            m = appXaimodelCompat(m)
        }

        // Non-native OpenAI-completions endpoints: default off unsupported features
        if (api == modelApi.OPENAI_COMPLETIONS && provider.baseUrl.isnotEmpty()) {
            val needsforce = !isOpenAINativeEndpoint(provider.baseUrl)
            if (needsforce) {
                val compat = m.compat
                val alreadyconfigured = compat != null &&
                    compat.supportsDeveloperRole != null &&
                    compat.supportsUsageInStreaming != null &&
                    compat.supportsStrictMode != null
                if (!alreadyconfigured) {
                    val forcedDeveloperRole = compat?.supportsDeveloperRole == true
                    val hasStreamingoverride = compat?.supportsUsageInStreaming != null
                    val targetStrictMode = compat?.supportsStrictMode ?: false
                    val patch = modelCompatconfig(
                        supportsDeveloperRole = if (forcedDeveloperRole) true else false,
                        supportsUsageInStreaming = if (hasStreamingoverride) compat?.supportsUsageInStreaming else false,
                        supportsStrictMode = targetStrictMode
                    )
                    m = appmodelCompatPatch(m, patch)
                }
            }
        }

        return Pair(p, m)
    }

    /**
     * Merge two compat configs, with patch values overriding base values.
     * null patch values do not override existing base values.
     */
    private fun mergeCompat(base: modelCompatconfig?, patch: modelCompatconfig): modelCompatconfig {
        if (base == null) return patch
        return modelCompatconfig(
            supportsStore = patch.supportsStore ?: base.supportsStore,
            supportsReasoningEffort = patch.supportsReasoningEffort ?: base.supportsReasoningEffort,
            maxTokensField = patch.maxTokensField ?: base.maxTokensField,
            thinkingformat = patch.thinkingformat ?: base.thinkingformat,
            requirestoolResultName = patch.requirestoolResultName ?: base.requirestoolResultName,
            requiresAssistantaftertoolResult = patch.requiresAssistantaftertoolResult ?: base.requiresAssistantaftertoolResult,
            toolschemaProfile = patch.toolschemaProfile ?: base.toolschemaProfile,
            nativeWebSearchtool = patch.nativeWebSearchtool ?: base.nativeWebSearchtool,
            toolCallArgumentsEncoding = patch.toolCallArgumentsEncoding ?: base.toolCallArgumentsEncoding,
            supportsDeveloperRole = patch.supportsDeveloperRole ?: base.supportsDeveloperRole,
            supportsUsageInStreaming = patch.supportsUsageInStreaming ?: base.supportsUsageInStreaming,
            supportsStrictMode = patch.supportsStrictMode ?: base.supportsStrictMode
        )
    }
}
