package com.xiaomo.androidforclaw.agent.context

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/context-window-guard.ts (evaluatecontextWindowGuard, resolvecontextWindowInfo)
 * - ../openclaw/src/agents/context.ts (model context-window token resolution cache)
 *
 * androidforClaw adaptation: context budget guard + context window token resolution.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.config.modelDefinition

/**
 * context Window Guard — Gap 2 alignment with OpenClaw context-window-guard.ts
 *
 * Resolves the effective context window from config → model metadata → default,
 * with hard min / warn thresholds.
 */
object contextWindowGuard {
    private const val TAG = "contextWindowGuard"

    const val CONTEXT_WINDOW_HARD_MIN_TOKENS = 16_000
    const val CONTEXT_WINDOW_WARN_BELOW_TOKENS = 32_000
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000  // Aligned with OpenClaw DEFAULT_CONTEXT_TOKENS = 2e5

    enum class contextWindowSource { MODEL, MODELS_CONFIG, AGENT_CONTEXT_TOKENS, DEFAULT }

    data class contextWindowInfo(
        val tokens: Int,
        val source: contextWindowSource
    )

    data class contextWindowGuardResult(
        val tokens: Int,
        val source: contextWindowSource,
        val shouldWarn: Boolean,
        val shouldBlock: Boolean
    )

    /**
     * Resolve the effective context window info.
     *
     * Priority:
     * 1. modelsconfig: model definition contextWindow from openclaw.json
     * 2. modelMetadata: provider-reported context window
     * 3. default: DEFAULT_CONTEXT_WINDOW_TOKENS
     *
     * Then capped by agents.defaults.contextTokens if set.
     */
    fun resolvecontextWindowInfo(
        configLoader: configLoader?,
        providerName: String?,
        modelId: String?,
        modelcontextWindow: Int? = null,
        defaultTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS
    ): contextWindowInfo {
        // 1. Try from models config
        val frommodelsconfig = if (configLoader != null && providerName != null && modelId != null) {
            val modelDef = configLoader.getmodelDefinition(providerName, modelId)
            if (modelDef != null && modelDef.contextWindow > 0) modelDef.contextWindow else null
        } else null

        // 2. Try from model metadata
        val frommodel = if (modelcontextWindow != null && modelcontextWindow > 0) modelcontextWindow else null

        val baseInfo = when {
            frommodelsconfig != null -> contextWindowInfo(frommodelsconfig, contextWindowSource.MODELS_CONFIG)
            frommodel != null -> contextWindowInfo(frommodel, contextWindowSource.MODEL)
            else -> contextWindowInfo(defaultTokens, contextWindowSource.DEFAULT)
        }

        // 3. Cap by agents.defaults.contextTokens if set
        // note: OpenClaw has this config field; androidforClaw doesn't yet — placeholder for future
        // configLoader?.loadOpenClawconfig()?.agents?.defaults?.contextTokens

        Log.d(TAG, "Resolved context window: ${baseInfo.tokens} tokens (source: ${baseInfo.source})")
        return baseInfo
    }

    /**
     * Evaluate whether the context window triggers warnings or blocks.
     */
    fun evaluatecontextWindowGuard(
        info: contextWindowInfo,
        warnbelowTokens: Int = CONTEXT_WINDOW_WARN_BELOW_TOKENS,
        hardMinTokens: Int = CONTEXT_WINDOW_HARD_MIN_TOKENS
    ): contextWindowGuardResult {
        val tokens = maxOf(0, info.tokens)
        val shouldWarn = tokens in 1 until warnbelowTokens
        val shouldBlock = tokens in 1 until hardMinTokens

        if (shouldBlock) {
            Log.e(TAG, "context window too small: $tokens tokens (hard min: $hardMinTokens)")
        } else if (shouldWarn) {
            Log.w(TAG, "context window below recommended: $tokens tokens (recommend: $warnbelowTokens+)")
        }

        return contextWindowGuardResult(
            tokens = tokens,
            source = info.source,
            shouldWarn = shouldWarn,
            shouldBlock = shouldBlock
        )
    }

    /**
     * Convenience: resolve + evaluate in one call.
     */
    fun resolveandEvaluate(
        configLoader: configLoader?,
        providerName: String?,
        modelId: String?
    ): contextWindowGuardResult {
        val info = resolvecontextWindowInfo(configLoader, providerName, modelId)
        return evaluatecontextWindowGuard(info)
    }
}
