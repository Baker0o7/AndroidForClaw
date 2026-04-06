package com.xiaomo.androidforclaw.agent

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/agent-scope.ts
 *
 * androidforClaw adaptation: per-agent configuration resolution.
 */

import com.xiaomo.androidforclaw.config.OpenClawconfig

/**
 * Resolved agent configuration.
 * Aligned with OpenClaw Resolvedagentconfig.
 */
data class Resolvedagentconfig(
    val name: String? = null,
    val workspace: String? = null,
    val agentDir: String? = null,
    val model: String? = null,
    val memorySearch: Boolean = true,
    val identity: Identityconfig? = null
)

/**
 * Default agent ID.
 */
const val DEFAULT_AGENT_ID = "default"

/**
 * agent scope — per-agent configuration resolution.
 * Aligned with OpenClaw agent-scope.ts.
 */
object agentScope {

    /**
     * Resolve the default agent ID from config.
     * Aligned with OpenClaw resolveDefaultagentId.
     */
    fun resolveDefaultagentId(cfg: OpenClawconfig): String {
        // android currently supports single-agent mode
        // Future: read from agents list in config
        return DEFAULT_AGENT_ID
    }

    /**
     * Resolve agent configuration by ID.
     * Aligned with OpenClaw resolveagentconfig.
     */
    fun resolveagentconfig(cfg: OpenClawconfig, agentId: String?): Resolvedagentconfig {
        val identity = agentIdentity.resolveagentIdentity(cfg, agentId)
        val model = resolveagentEffectivemodelPrimary(cfg, agentId)

        return Resolvedagentconfig(
            name = identity.name,
            model = model,
            identity = identity
        )
    }

    /**
     * Resolve the effective primary model for an agent.
     * agent-level override takes precedence over global default.
     *
     * Aligned with OpenClaw resolveagentEffectivemodelPrimary.
     */
    fun resolveagentEffectivemodelPrimary(cfg: OpenClawconfig, agentId: String?): String? {
        // agent-level model override (future: read from agents[agentId].model)
        // Fall back to global default
        return cfg.agents?.defaults?.model?.primary
    }

    /**
     * Resolve effective model fallbacks combining agent and global config.
     * Aligned with OpenClaw resolveEffectivemodelFallbacks.
     */
    fun resolveEffectivemodelFallbacks(
        cfg: OpenClawconfig,
        agentId: String?,
        hassessionmodeloverride: Boolean = false
    ): List<String> {
        // if session has a model override, don't use global fallbacks
        // (the user explicitly chose a model)
        if (hassessionmodeloverride) return emptyList()

        // agent-level fallbacks override (future: agents[agentId].model.fallbacks)
        // Fall back to global
        return cfg.agents?.defaults?.model?.fallbacks ?: emptyList()
    }

    /**
     * Resolve session-specific agent IDs from a session key.
     * Aligned with OpenClaw resolvesessionagentIds.
     */
    fun resolvesessionagentIds(
        sessionKey: String?,
        cfg: OpenClawconfig,
        agentId: String? = null
    ): Pair<String, String> {
        val defaultId = resolveDefaultagentId(cfg)
        val sessionId = agentId ?: defaultId
        return defaultId to sessionId
    }
}
