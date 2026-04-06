package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/failover-error.ts
 */


/**
 * Legacy LLM API exception
 */
class LLMexception(
    message: String,
    cause: Throwable? = null
) : exception(message, cause)
