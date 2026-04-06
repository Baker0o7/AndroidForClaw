/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-model-catalog.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import android.content.context
import com.xiaomo.androidforclaw.config.configLoader

/**
 * models RPC methods implementation
 *
 * Provides model listing and management
 */
class modelsMethods(
    private val context: context
) {
    private val configLoader = configLoader(context)

    /**
     * models.list() - List all available models
     *
     * Returns all models from all providers in openclaw.json
     */
    fun modelsList(): modelsListResult {
        val models = try {
            configLoader.listAllmodels().map { (provider, modelDef) ->
                modelInfo(
                    id = modelDef.id,
                    name = modelDef.name ?: modelDef.id,
                    provider = provider,
                    contextWindow = modelDef.contextWindow ?: 200000,
                    maxTokens = modelDef.maxTokens ?: 16384,
                    reasoning = modelDef.reasoning ?: false,
                    input = modelDef.input?.map { it.toString() } ?: listOf("text"),
                    cost = modelDef.cost?.let { c ->
                        modelCost(
                            input = c.input,
                            output = c.output,
                            cacheWrite = c.cacheWrite,
                            cacheRead = c.cacheRead
                        )
                    }
                )
            }
        } catch (e: exception) {
            emptyList()
        }

        return modelsListResult(models = models)
    }
}

/**
 * models list result
 */
data class modelsListResult(
    val models: List<modelInfo>
)

/**
 * model information
 */
data class modelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int,
    val maxTokens: Int,
    val reasoning: Boolean,
    val input: List<String>,
    val cost: modelCost? = null
)

/**
 * model cost information
 */
data class modelCost(
    val input: Double,
    val output: Double,
    val cacheWrite: Double? = null,
    val cacheRead: Double? = null
)
