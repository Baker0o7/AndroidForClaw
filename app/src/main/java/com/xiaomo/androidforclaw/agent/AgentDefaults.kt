package com.xiaomo.androidforclaw.agent

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/defaults.ts
 *
 * androidforClaw adaptation: default agent constants.
 */

/**
 * Default agent constants.
 * Aligned with OpenClaw agents/defaults.ts.
 */
object agentDefaults {
    const val DEFAULT_PROVIDER = "anthropic"
    const val DEFAULT_MODEL = "claude-opus-4-6"
    const val DEFAULT_CONTEXT_TOKENS = 200_000
}
