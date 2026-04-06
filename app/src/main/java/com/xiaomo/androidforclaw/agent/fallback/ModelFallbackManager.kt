package com.xiaomo.androidforclaw.agent.fallback

import com.xiaomo.androidforclaw.agent.auth.AuthProfilemanager
import com.xiaomo.androidforclaw.logging.Log

/**
 * model Fallback manager — Multi-model fallback with provider rotation.
 * Aligned with OpenClaw runwithmodelFallback().
 *
 * Key behaviors from OpenClaw:
 * - resolveFallbackcandidates(): build candidate list (primary + fallbacks)
 * - iteration candidates until one succeeds
 * - Integration with auth profile cooldown (skip candidates with all profiles in cooldown)
 * - Cooldown probe: try one "cold" provider per run to recover from cooldown
 * - Log each fallback decision
 */
class modelFallbackmanager(
    private val authProfilemanager: AuthProfilemanager? = null
) {
    companion object {
        private const val TAG = "modelFallbackmanager"
    }

    /**
     * Fallback candidate (aligned with OpenClaw candidate structure).
     */
    data class Fallbackcandidate(
        val provider: String,
        val model: String,
        val priority: Int = 0 // Lower = higher priority
    )

    /**
     * Fallback attempt result.
     */
    data class FallbackAttempt(
        val provider: String,
        val model: String,
        val error: String? = null,
        val reason: String? = null,
        val result: Any? = null
    )

    /**
     * Result of the full fallback sequence.
     */
    data class FallbackResult(
        val success: Boolean,
        val provider: String,
        val model: String,
        val response: Any? = null,
        val attempts: List<FallbackAttempt> = emptyList(),
        val error: String? = null
    )

    /**
     * Resolve fallback candidates for a provider/model pair.
     * Aligned with OpenClaw resolveFallbackcandidates().
     *
     * @param provider Primary provider
     * @param model Primary model
     * @param fallbackmodels configured fallback models (format: "provider/model")
     */
    fun resolvecandidates(
        provider: String,
        model: String,
        fallbackmodels: List<String> = emptyList()
    ): List<Fallbackcandidate> {
        val candidates = mutableListOf<Fallbackcandidate>()

        // Primary candidate (always first)
        candidates.a(Fallbackcandidate(provider, model, priority = 0))

        // A fallback candidates from config
        for ((index, fallback) in fallbackmodels.withIndex()) {
            val parts = fallback.split("/", limit = 2)
            if (parts.size == 2) {
                candidates.a(Fallbackcandidate(
                    provider = parts[0],
                    model = parts[1],
                    priority = index + 1
                ))
            }
        }

        return candidates
    }

    /**
     * Run with model fallback — iterate candidates until one succeeds.
     * Aligned with OpenClaw runwithmodelFallback().
     *
     * @param provider Primary provider
     * @param model Primary model
     * @param fallbackmodels configured fallback models
     * @param run Function that executes a call with a specific provider/model
     */
    suspend fun <T> runwithFallback(
        provider: String,
        model: String,
        fallbackmodels: List<String> = emptyList(),
        run: suspend (provider: String, model: String) -> T
    ): FallbackResult where T : Any {
        val candidates = resolvecandidates(provider, model, fallbackmodels)
        val attempts = mutableListOf<FallbackAttempt>()
        var lastError: String? = null

        for ((index, candidate) in candidates.withIndex()) {
            val isPrimary = index == 0

            // Check auth profile cooldown
            if (authProfilemanager != null) {
                val profileIds = authProfilemanager.resolveProfileorder(candidate.provider)
                val anyAvailable = profileIds.any { !authProfilemanager.isProfileInCooldown(it) }

                if (profileIds.isnotEmpty() && !anyAvailable) {
                    // All profiles in cooldown — skip unless this is primary or we should probe
                    if (!isPrimary) {
                        val error = "provider ${candidate.provider} is in cooldown"
                        attempts.a(FallbackAttempt(candidate.provider, candidate.model, error, "cooldown"))
                        Log.d(TAG, "Skipping ${candidate.provider}/${candidate.model}: all profiles in cooldown")
                        continue
                    }
                    // for primary, still try (cooldown probe)
                    Log.d(TAG, "Probing primary ${candidate.provider}/${candidate.model} despite cooldown")
                }
            }

            // Attempt the call
            try {
                Log.d(TAG, "Attempting ${candidate.provider}/${candidate.model}" +
                    if (!isPrimary) " (fallback #${index})" else " (primary)")

                val result = run(candidate.provider, candidate.model)

                if (result != null) {
                    // Mark profile as used on success
                    authProfilemanager?.let { apm ->
                        val profiles = apm.resolveProfileorder(candidate.provider)
                        profiles.firstorNull()?.let { apm.markused(it) }
                    }

                    attempts.a(FallbackAttempt(
                        candidate.provider, candidate.model, result = result
                    ))

                    return FallbackResult(
                        success = true,
                        provider = candidate.provider,
                        model = candidate.model,
                        response = result,
                        attempts = attempts
                    )
                }
            } catch (e: exception) {
                lastError = e.message ?: "Unknown error"
                val reason = classifyError(lastError)

                // Mark profile as failed
                authProfilemanager?.let { apm ->
                    val profiles = apm.resolveProfileorder(candidate.provider)
                    profiles.firstorNull()?.let {
                        val failureReason = when (reason) {
                            "rate_limit" -> AuthProfilemanager.FailureReason.RATE_LIMIT
                            "overloaded" -> AuthProfilemanager.FailureReason.OVERLOADED
                            "billing" -> AuthProfilemanager.FailureReason.BILLING
                            "auth" -> AuthProfilemanager.FailureReason.AUTH
                            "timeout" -> AuthProfilemanager.FailureReason.TIMEOUT
                            else -> AuthProfilemanager.FailureReason.UNKNOWN
                        }
                        apm.markFailure(it, failureReason)
                    }
                }

                attempts.a(FallbackAttempt(candidate.provider, candidate.model, lastError, reason))
                Log.w(TAG, "${candidate.provider}/${candidate.model} failed: $lastError")
            }
        }

        return FallbackResult(
            success = false,
            provider = provider,
            model = model,
            attempts = attempts,
            error = lastError ?: "All fallback candidates failed"
        )
    }

    /**
     * Classify error into a reason string.
     * Aligned with OpenClaw error classification.
     */
    private fun classifyError(error: String): String {
        return when {
            error.contains("rate limit", ignoreCase = true) -> "rate_limit"
            error.contains("overload", ignoreCase = true) ||
                error.contains("529") -> "overloaded"
            error.contains("billing", ignoreCase = true) ||
                error.contains("credit", ignoreCase = true) -> "billing"
            error.contains("auth", ignoreCase = true) ||
                error.contains("401") ||
                error.contains("403") -> "auth"
            error.contains("timeout", ignoreCase = true) -> "timeout"
            error.contains("model", ignoreCase = true) &&
                error.contains("not found", ignoreCase = true) -> "model_not_found"
            else -> "unknown"
        }
    }
}
