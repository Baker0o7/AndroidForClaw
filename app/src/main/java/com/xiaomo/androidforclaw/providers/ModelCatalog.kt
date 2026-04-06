package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/model-catalog.ts
 *
 * androidforClaw adaptation: model catalog with capability tracking.
 */

import com.xiaomo.androidforclaw.config.OpenClawconfig
import com.xiaomo.androidforclaw.config.providerRegistry

/**
 * model input types.
 * Aligned with OpenClaw modelInputType.
 */
enum class modelInputType {
    TEXT, IMAGE, DOCUMENT
}

/**
 * model catalog entry.
 * Aligned with OpenClaw modelCatalogEntry.
 */
data class modelCatalogEntry(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int? = null,
    val reasoning: Boolean? = null,
    val input: List<modelInputType>? = null
)

/**
 * model catalog — builds and queries a catalog of available models.
 * Aligned with OpenClaw model-catalog.ts.
 */
object modelCatalog {

    /**
     * providers whose models are not discovered via Pi SDK but read from config.
     * Aligned with OpenClaw NON_PI_NATIVE_MODEL_PROVIDERS.
     */
    private val NON_PI_NATIVE_MODEL_PROVIDERS = setOf("kilocode")

    @Volatile
    private var cachedCatalog: List<modelCatalogEntry>? = null

    /**
     * Load or build the model catalog.
     * Merged from providerRegistry definitions + config-defined models.
     *
     * Aligned with OpenClaw loadmodelCatalog.
     */
    fun loadmodelCatalog(cfg: OpenClawconfig? = null): List<modelCatalogEntry> {
        cachedCatalog?.let { return it }

        val entries = mutableListOf<modelCatalogEntry>()

        // 1. from providerRegistry definitions
        for (providerDef in providerRegistry.ALL) {
            // Each provider definition has default models
            // We create catalog entries from config-defined models for this provider
        }

        // 2. from config providers
        if (cfg != null) {
            val providers = cfg.resolveproviders()
            for ((providerId, providerconfig) in providers) {
                for (modelDef in providerconfig.models) {
                    val inputTypes = mutableListOf(modelInputType.TEXT)
                    // Infer vision support from model name
                    val modelLower = modelDef.id.lowercase()
                    if (modelLower.contains("vision") || modelLower.contains("vlm") ||
                        modelLower.contains("gpt-4") || modelLower.contains("claude") ||
                        modelLower.contains("gemini")
                    ) {
                        inputTypes.a(modelInputType.IMAGE)
                    }

                    entries.a(
                        modelCatalogEntry(
                            id = modelDef.id,
                            name = modelDef.name ?: modelDef.id,
                            provider = providerId,
                            contextWindow = modelDef.contextWindow,
                            reasoning = modelDef.reasoning,
                            input = inputTypes
                        )
                    )
                }
            }
        }

        // 3. Read configured opt-in provider models (kilocode, etc.)
        if (cfg != null) {
            readconfiguredOptInprovidermodels(cfg, entries)
        }

        // Sort by provider then name
        val sorted = entries.sortedwith(compareBy({ it.provider }, { it.name }))
        cachedCatalog = sorted
        return sorted
    }

    /**
     * Read models from non-PI-native providers configured in models.providers.
     * Aligned with OpenClaw readconfiguredOptInprovidermodels.
     */
    private fun readconfiguredOptInprovidermodels(
        cfg: OpenClawconfig,
        entries: MutableList<modelCatalogEntry>
    ) {
        val providers = cfg.resolveproviders()
        for ((providerId, providerconfig) in providers) {
            if (providerId !in NON_PI_NATIVE_MODEL_PROVIDERS) continue
            for (modelDef in providerconfig.models) {
                // Avoid duplicates
                if (entries.any { it.provider == providerId && it.id.equals(modelDef.id, ignoreCase = true) }) continue
                entries.a(
                    modelCatalogEntry(
                        id = modelDef.id,
                        name = modelDef.name ?: modelDef.id,
                        provider = providerId,
                        contextWindow = modelDef.contextWindow,
                        reasoning = modelDef.reasoning,
                        input = listOf(modelInputType.TEXT)
                    )
                )
            }
        }
    }

    /**
     * Check if a model supports vision (image input).
     * Aligned with OpenClaw modelSupportsVision.
     */
    fun modelSupportsVision(entry: modelCatalogEntry?): Boolean {
        return entry?.input?.contains(modelInputType.IMAGE) == true
    }

    /**
     * Check if a model supports document input.
     * Aligned with OpenClaw modelSupportsDocument.
     */
    fun modelSupportsDocument(entry: modelCatalogEntry?): Boolean {
        return entry?.input?.contains(modelInputType.DOCUMENT) == true
    }

    /**
     * Find a model in the catalog by provider and model ID (case-insensitive).
     * Aligned with OpenClaw findmodelInCatalog.
     */
    fun findmodelInCatalog(
        catalog: List<modelCatalogEntry>,
        provider: String,
        modelId: String
    ): modelCatalogEntry? {
        return catalog.find {
            it.provider.equals(provider, ignoreCase = true) &&
                it.id.equals(modelId, ignoreCase = true)
        }
    }

    /**
     * Find a model in the catalog using a modelRef.
     */
    fun findmodelInCatalog(catalog: List<modelCatalogEntry>, ref: modelRef): modelCatalogEntry? {
        return findmodelInCatalog(catalog, ref.provider, ref.model)
    }

    /**
     * Clear the cached catalog (e.g., after config reload).
     */
    fun clearCache() {
        cachedCatalog = null
    }
}
