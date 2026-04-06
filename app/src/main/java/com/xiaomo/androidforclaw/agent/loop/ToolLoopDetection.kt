package com.xiaomo.androidforclaw.agent.loop

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-loop-detection.ts
 *
 * androidforClaw adaptation: detect repetitive tool loops.
 * Aligned 1:1 with OpenClaw tool-loop-detection.ts.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.Gson
import java.security.MessageDigest

/**
 * configuration for tool loop detection.
 * Aligned with OpenClaw toolloopDetectionconfig (config/types.tools.ts).
 */
data class toolloopDetectionconfig(
    val enabled: Boolean? = null,
    val historySize: Int? = null,
    val warningThreshold: Int? = null,
    val criticalThreshold: Int? = null,
    val globalCircuitBreakerThreshold: Int? = null,
    val detectors: DetectorToggles? = null,
) {
    data class DetectorToggles(
        val genericRepeat: Boolean? = null,
        val knownPollNoProgress: Boolean? = null,
        val pingPong: Boolean? = null,
    )
}

/**
 * Resolved (validated) loop detection config.
 * Aligned with OpenClaw ResolvedloopDetectionconfig.
 */
internal data class ResolvedloopDetectionconfig(
    val enabled: Boolean,
    val historySize: Int,
    val warningThreshold: Int,
    val criticalThreshold: Int,
    val globalCircuitBreakerThreshold: Int,
    val detectors: ResolvedDetectorToggles,
) {
    data class ResolvedDetectorToggles(
        val genericRepeat: Boolean,
        val knownPollNoProgress: Boolean,
        val pingPong: Boolean,
    )
}

/**
 * loop detection result.
 * Aligned with OpenClaw loopDetectionResult.
 */
sealed class loopDetectionResult {
    data object Noloop : loopDetectionResult()

    data class loopDetected(
        val level: Level,
        val detector: loopDetectorKind,
        val count: Int,
        val message: String,
        val pairedtoolName: String? = null,
        val warningKey: String? = null,
    ) : loopDetectionResult()

    enum class Level { WARNING, CRITICAL }
}

/**
 * Detector kinds. Aligned with OpenClaw loopDetectorKind.
 */
enum class loopDetectorKind {
    GENERIC_REPEAT,
    KNOWN_POLL_NO_PROGRESS,
    PING_PONG,
    GLOBAL_CIRCUIT_BREAKER;

    val wireValue: String get() = name.lowercase()
}

/**
 * tool loop detector.
 * Reference: OpenClaw's tool-loop-detection.ts implementation.
 *
 * Detects the following loop patterns:
 * 1. generic_repeat - Generic repeated calls
 * 2. known_poll_no_progress - Known polling tools with no progress
 * 3. ping_pong - Two tools calling back and forth
 * 4. global_circuit_breaker - Global circuit breaker (critical loop)
 */
object toolloopDetection {
    private const val TAG = "toolloopDetection"

    // Default configuration (aligned with OpenClaw DEFAULT_LOOP_DETECTION_CONFIG)
    const val TOOL_CALL_HISTORY_SIZE = 30
    const val WARNING_THRESHOLD = 10
    const val CRITICAL_THRESHOLD = 20
    const val GLOBAL_CIRCUIT_BREAKER_THRESHOLD = 30

    private val gson = Gson()

    /**
     * tool call history record.
     * Aligned with OpenClaw sessionState.toolCallHistory entries.
     */
    data class toolCallRecord(
        val toolName: String,
        val argsHash: String,
        var resultHash: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val toolCallId: String? = null,
    )

    /**
     * session state (stores tool call history).
     * Aligned with OpenClaw sessionState (diagnostic-session-state.ts).
     * note: No reportedWarnings — OpenClaw returns warnings every time condition is met,
     * caller decides whether to act on duplicates.
     */
    class sessionState {
        var toolCallHistory: MutableList<toolCallRecord> = mutableListOf()
    }

    // ==================== config Resolution ====================

    /**
     * validation positive integer, fallback if invalid.
     * Aligned with OpenClaw asPositiveInt.
     */
    private fun asPositiveInt(value: Int?, fallback: Int): Int {
        if (value == null || value <= 0) return fallback
        return value
    }

    /**
     * Resolve and validate loop detection config.
     * Aligned with OpenClaw resolveloopDetectionconfig.
     */
    internal fun resolveloopDetectionconfig(config: toolloopDetectionconfig?): ResolvedloopDetectionconfig {
        var warningThreshold = asPositiveInt(config?.warningThreshold, WARNING_THRESHOLD)
        var criticalThreshold = asPositiveInt(config?.criticalThreshold, CRITICAL_THRESHOLD)
        var globalCircuitBreakerThreshold = asPositiveInt(
            config?.globalCircuitBreakerThreshold, GLOBAL_CIRCUIT_BREAKER_THRESHOLD
        )

        // Threshold validation (aligned with OpenClaw)
        if (criticalThreshold <= warningThreshold) {
            criticalThreshold = warningThreshold + 1
        }
        if (globalCircuitBreakerThreshold <= criticalThreshold) {
            globalCircuitBreakerThreshold = criticalThreshold + 1
        }

        return ResolvedloopDetectionconfig(
            enabled = config?.enabled ?: false, // OpenClaw: default false
            historySize = asPositiveInt(config?.historySize, TOOL_CALL_HISTORY_SIZE),
            warningThreshold = warningThreshold,
            criticalThreshold = criticalThreshold,
            globalCircuitBreakerThreshold = globalCircuitBreakerThreshold,
            detectors = ResolvedloopDetectionconfig.ResolvedDetectorToggles(
                genericRepeat = config?.detectors?.genericRepeat ?: true,
                knownPollNoProgress = config?.detectors?.knownPollNoProgress ?: true,
                pingPong = config?.detectors?.pingPong ?: true,
            ),
        )
    }

    // ==================== Hashing ====================

    /**
     * Hash a tool call for pattern matching (toolName + params).
     * Aligned with OpenClaw hashtoolCall.
     */
    fun hashtoolCall(toolName: String, params: Any?): String {
        val paramsHash = digestStable(params)
        return "$toolName:$paramsHash"
    }

    /**
     * Stable serialization (sorted keys).
     * Aligned with OpenClaw stableStringify.
     */
    private fun stableStringify(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> gson.toJson(value)
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> {
                val sorted = value.toSortedMap(compareBy { it.toString() })
                val entries = sorted.map { (k, v) ->
                    "${gson.toJson(k.toString())}:${stableStringify(v)}"
                }
                "{${entries.joinToString(",")}}"
            }
            is List<*> -> {
                val items = value.map { stableStringify(it) }
                "[${items.joinToString(",")}]"
            }
            else -> gson.toJson(value)
        }
    }

    /**
     * Stable serialization with fallback for errors.
     * Aligned with OpenClaw stableStringifyFallback.
     */
    private fun stableStringifyFallback(value: Any?): String {
        return try {
            stableStringify(value)
        } catch (_: exception) {
            when {
                value == null -> "null"
                value is String -> value
                value is Number || value is Boolean -> value.toString()
                value is Throwable -> "${value.javaClass.simpleName}:${value.message}"
                else -> value.toString()
            }
        }
    }

    /**
     * Stable hash (SHA-256).
     * Aligned with OpenClaw digestStable.
     */
    private fun digestStable(value: Any?): String {
        val serialized = stableStringifyFallback(value)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(serialized.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ==================== tool Result Parsing ====================

    /**
     * Extract text content from structured tool result.
     * Aligned with OpenClaw extractTextContent.
     */
    private fun extractTextContent(result: Any?): String {
        if (result !is Map<*, *>) return ""
        val content = result["content"]
        if (content !is List<*>) return ""
        return content
            .filterIsInstance<Map<*, *>>()
            .filter { it["type"] is String && it["text"] is String }
            .joinToString("\n") { it["text"] as String }
            .trim()
    }

    /**
     * format error for hashing.
     * Aligned with OpenClaw formatErrorforHash.
     */
    private fun formatErrorforHash(error: Any?): String {
        return when (error) {
            is Throwable -> error.message ?: error.javaClass.simpleName
            is String -> error
            is Number, is Boolean -> error.toString()
            else -> stableStringify(error)
        }
    }

    /**
     * Hash a tool call outcome (result or error).
     * Aligned with OpenClaw hashtoolOutcome — handles structured results with
     * extractTextContent and details extraction for known poll tools.
     */
    fun hashtoolOutcome(
        toolName: String,
        params: Any?,
        result: Any?,
        error: Any?,
    ): String? {
        if (error != null) {
            return "error:${digestStable(formatErrorforHash(error))}"
        }

        if (result !is Map<*, *>) {
            return if (result == null) null else digestStable(result)
        }

        val details = (result["details"] as? Map<*, *>) ?: emptyMap<Any, Any>()
        val text = extractTextContent(result)

        // Known poll tool specific hashing (aligned with OpenClaw)
        if (isKnownPolltoolCall(toolName, params) && toolName == "process" && params is Map<*, *>) {
            val action = params["action"]
            if (action == "poll") {
                return digestStable(mapOf(
                    "action" to action,
                    "status" to details["status"],
                    "exitCode" to (details["exitCode"]),
                    "exitSignal" to (details["exitSignal"]),
                    "aggregated" to (details["aggregated"]),
                    "text" to text,
                ))
            }
            if (action == "log") {
                return digestStable(mapOf(
                    "action" to action,
                    "status" to details["status"],
                    "totalLines" to (details["totalLines"]),
                    "totalChars" to (details["totalChars"]),
                    "truncated" to (details["truncated"]),
                    "exitCode" to (details["exitCode"]),
                    "exitSignal" to (details["exitSignal"]),
                    "text" to text,
                ))
            }
        }

        return digestStable(mapOf(
            "details" to details,
            "text" to text,
        ))
    }

    // ==================== Known Poll tools ====================

    /**
     * Check if it's a known polling tool.
     * Aligned with OpenClaw isKnownPolltoolCall.
     * android adaptation: includes android-specific polling tools.
     */
    private fun isKnownPolltoolCall(toolName: String, params: Any?): Boolean {
        // OpenClaw: command_status + process(action=poll/log)
        if (toolName == "command_status") return true
        if (toolName == "process" && params is Map<*, *>) {
            val action = params["action"]
            return action == "poll" || action == "log"
        }
        // android platform specific polling tools
        if (toolName == "wait" || toolName == "wait_for_element") return true
        return false
    }

    // ==================== Streak Detection ====================

    private data class NoProgressStreak(
        val count: Int,
        val latestResultHash: String?,
    )

    private data class PingPongStreak(
        val count: Int,
        val pairedtoolName: String?,
        val pairedSignature: String?,
        val noProgressEvidence: Boolean,
    )

    /**
     * Get no-progress streak count.
     * Aligned with OpenClaw getNoProgressStreak.
     */
    private fun getNoProgressStreak(
        history: List<toolCallRecord>,
        toolName: String,
        argsHash: String,
    ): NoProgressStreak {
        var streak = 0
        var latestResultHash: String? = null

        for (i in history.size - 1 downTo 0) {
            val record = history[i]
            if (record.toolName != toolName || record.argsHash != argsHash) continue
            if (record.resultHash == null) continue

            if (latestResultHash == null) {
                latestResultHash = record.resultHash
                streak = 1
                continue
            }

            if (record.resultHash != latestResultHash) {
                break
            }

            streak++
        }

        return NoProgressStreak(streak, latestResultHash)
    }

    /**
     * Get ping-pong streak count.
     * Aligned with OpenClaw getPingPongStreak — includes pairedtoolName.
     */
    private fun getPingPongStreak(
        history: List<toolCallRecord>,
        currentSignature: String,
    ): PingPongStreak {
        if (history.isEmpty()) {
            return PingPongStreak(0, null, null, false)
        }

        val last = history.last()

        // Find most recent different signature
        var otherSignature: String? = null
        var othertoolName: String? = null
        for (i in history.size - 2 downTo 0) {
            val call = history[i]
            if (call.argsHash != last.argsHash) {
                otherSignature = call.argsHash
                othertoolName = call.toolName
                break
            }
        }

        if (otherSignature == null || othertoolName == null) {
            return PingPongStreak(0, null, null, false)
        }

        // Calculate alternating tail length
        var alternatingTailCount = 0
        for (i in history.size - 1 downTo 0) {
            val call = history[i]
            val expected = if (alternatingTailCount % 2 == 0) last.argsHash else otherSignature
            if (call.argsHash != expected) break
            alternatingTailCount++
        }

        if (alternatingTailCount < 2) {
            return PingPongStreak(0, null, null, false)
        }

        // Check if current signature matches expected
        val expectedCurrentSignature = otherSignature
        if (currentSignature != expectedCurrentSignature) {
            return PingPongStreak(0, null, null, false)
        }

        // Check for no-progress evidence
        val tailStart = maxOf(0, history.size - alternatingTailCount)
        var firstHashA: String? = null
        var firstHashB: String? = null
        var noProgressEvidence = true

        for (i in tailStart until history.size) {
            val call = history[i]
            if (call.resultHash == null) {
                noProgressEvidence = false
                break
            }

            if (call.argsHash == last.argsHash) {
                if (firstHashA == null) {
                    firstHashA = call.resultHash
                } else if (firstHashA != call.resultHash) {
                    noProgressEvidence = false
                    break
                }
            } else if (call.argsHash == otherSignature) {
                if (firstHashB == null) {
                    firstHashB = call.resultHash
                } else if (firstHashB != call.resultHash) {
                    noProgressEvidence = false
                    break
                }
            } else {
                noProgressEvidence = false
                break
            }
        }

        // need repeated stable outcomes on both sides (aligned with OpenClaw)
        if (firstHashA == null || firstHashB == null) {
            noProgressEvidence = false
        }

        return PingPongStreak(
            count = alternatingTailCount + 1,
            pairedtoolName = last.toolName,
            pairedSignature = last.argsHash,
            noProgressEvidence = noProgressEvidence,
        )
    }

    /**
     * canonical pair key for ping-pong warning key (sorted).
     * Aligned with OpenClaw canonicalPairKey.
     */
    private fun canonicalPairKey(signatureA: String, signatureB: String): String {
        return listOf(signatureA, signatureB).sorted().joinToString("|")
    }

    // ==================== Detection ====================

    /**
     * Detect if an agent is stuck in a repetitive tool call loop.
     * Aligned with OpenClaw detecttoolCallloop.
     *
     * Returns Noloop when detection is disabled (config.enabled=false, the default).
     */
    fun detecttoolCallloop(
        state: sessionState,
        toolName: String,
        params: Any?,
        config: toolloopDetectionconfig? = null,
    ): loopDetectionResult {
        val resolvedconfig = resolveloopDetectionconfig(config)
        if (!resolvedconfig.enabled) {
            return loopDetectionResult.Noloop
        }

        val history = state.toolCallHistory
        val currentHash = hashtoolCall(toolName, params)
        val noProgress = getNoProgressStreak(history, toolName, currentHash)
        val noProgressStreak = noProgress.count
        val knownPolltool = isKnownPolltoolCall(toolName, params)
        val pingPong = getPingPongStreak(history, currentHash)

        // 1. Global circuit breaker (highest priority)
        if (noProgressStreak >= resolvedconfig.globalCircuitBreakerThreshold) {
            Log.e(TAG, "Global circuit breaker triggered: $toolName repeated $noProgressStreak times with no progress")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.CRITICAL,
                detector = loopDetectorKind.GLOBAL_CIRCUIT_BREAKER,
                count = noProgressStreak,
                message = "CRITICAL: $toolName has repeated identical no-progress outcomes $noProgressStreak times. " +
                    "session execution blocked by global circuit breaker to prevent runaway loops.",
                warningKey = "global:$toolName:$currentHash:${noProgress.latestResultHash ?: "none"}",
            )
        }

        // 2. Known poll no progress (critical)
        if (knownPolltool &&
            resolvedconfig.detectors.knownPollNoProgress &&
            noProgressStreak >= resolvedconfig.criticalThreshold
        ) {
            Log.e(TAG, "Critical polling loop detected: $toolName repeated $noProgressStreak times")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.CRITICAL,
                detector = loopDetectorKind.KNOWN_POLL_NO_PROGRESS,
                count = noProgressStreak,
                message = "CRITICAL: Called $toolName with identical arguments and no progress $noProgressStreak times. " +
                    "This appears to be a stuck polling loop. session execution blocked to prevent resource waste.",
                warningKey = "poll:$toolName:$currentHash:${noProgress.latestResultHash ?: "none"}",
            )
        }

        // 3. Known poll no progress (warning)
        if (knownPolltool &&
            resolvedconfig.detectors.knownPollNoProgress &&
            noProgressStreak >= resolvedconfig.warningThreshold
        ) {
            Log.w(TAG, "Polling loop warning: $toolName repeated $noProgressStreak times")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.WARNING,
                detector = loopDetectorKind.KNOWN_POLL_NO_PROGRESS,
                count = noProgressStreak,
                message = "WARNING: You have called $toolName $noProgressStreak times with identical arguments and no progress. " +
                    "Stop polling and either (1) increase wait time between checks, or (2) report the task as failed if the process is stuck.",
                warningKey = "poll:$toolName:$currentHash:${noProgress.latestResultHash ?: "none"}",
            )
        }

        // 4. Ping-pong detection
        val pingPongWarningKey = if (pingPong.pairedSignature != null) {
            "pingpong:${canonicalPairKey(currentHash, pingPong.pairedSignature)}"
        } else {
            "pingpong:$toolName:$currentHash"
        }

        if (resolvedconfig.detectors.pingPong &&
            pingPong.count >= resolvedconfig.criticalThreshold &&
            pingPong.noProgressEvidence
        ) {
            Log.e(TAG, "Critical ping-pong loop detected: alternating calls count=${pingPong.count} currenttool=$toolName")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.CRITICAL,
                detector = loopDetectorKind.PING_PONG,
                count = pingPong.count,
                message = "CRITICAL: You are alternating between repeated tool-call patterns (${pingPong.count} consecutive calls) with no progress. " +
                    "This appears to be a stuck ping-pong loop. session execution blocked to prevent resource waste.",
                pairedtoolName = pingPong.pairedtoolName,
                warningKey = pingPongWarningKey,
            )
        }

        if (resolvedconfig.detectors.pingPong &&
            pingPong.count >= resolvedconfig.warningThreshold
        ) {
            Log.w(TAG, "Ping-pong loop warning: alternating calls count=${pingPong.count} currenttool=$toolName")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.WARNING,
                detector = loopDetectorKind.PING_PONG,
                count = pingPong.count,
                message = "WARNING: You are alternating between repeated tool-call patterns (${pingPong.count} consecutive calls). " +
                    "This looks like a ping-pong loop; stop retrying and report the task as failed.",
                pairedtoolName = pingPong.pairedtoolName,
                warningKey = pingPongWarningKey,
            )
        }

        // 5. Generic repeat (last check, warn-only)
        val recentCount = history.count { it.toolName == toolName && it.argsHash == currentHash }

        if (!knownPolltool &&
            resolvedconfig.detectors.genericRepeat &&
            recentCount >= resolvedconfig.warningThreshold
        ) {
            Log.w(TAG, "loop warning: $toolName called $recentCount times with identical arguments")
            return loopDetectionResult.loopDetected(
                level = loopDetectionResult.Level.WARNING,
                detector = loopDetectorKind.GENERIC_REPEAT,
                count = recentCount,
                message = "WARNING: You have called $toolName $recentCount times with identical arguments. " +
                    "if this is not making progress, stop retrying and report the task as failed.",
                warningKey = "generic:$toolName:$currentHash",
            )
        }

        return loopDetectionResult.Noloop
    }

    // ==================== Recording ====================

    /**
     * Record a tool call in the session's history for loop detection.
     * Maintains sliding window of last N calls.
     * Aligned with OpenClaw recordtoolCall.
     */
    fun recordtoolCall(
        state: sessionState,
        toolName: String,
        params: Any?,
        toolCallId: String? = null,
        config: toolloopDetectionconfig? = null,
    ) {
        val resolvedconfig = resolveloopDetectionconfig(config)

        state.toolCallHistory.a(toolCallRecord(
            toolName = toolName,
            argsHash = hashtoolCall(toolName, params),
            toolCallId = toolCallId,
        ))

        if (state.toolCallHistory.size > resolvedconfig.historySize) {
            state.toolCallHistory.removeAt(0)
        }
    }

    /**
     * Record a completed tool call outcome so loop detection can identify no-progress repeats.
     * Aligned with OpenClaw recordtoolCallOutcome.
     */
    fun recordtoolCallOutcome(
        state: sessionState,
        toolName: String,
        toolParams: Any?,
        result: Any? = null,
        error: Any? = null,
        toolCallId: String? = null,
        config: toolloopDetectionconfig? = null,
    ) {
        val resolvedconfig = resolveloopDetectionconfig(config)
        val resultHash = hashtoolOutcome(toolName, toolParams, result, error) ?: return

        val argsHash = hashtoolCall(toolName, toolParams)
        var matched = false

        for (i in state.toolCallHistory.size - 1 downTo 0) {
            val call = state.toolCallHistory[i]
            if (toolCallId != null && call.toolCallId != toolCallId) continue
            if (call.toolName != toolName || call.argsHash != argsHash) continue
            if (call.resultHash != null) continue

            call.resultHash = resultHash
            matched = true
            break
        }

        if (!matched) {
            state.toolCallHistory.a(toolCallRecord(
                toolName = toolName,
                argsHash = argsHash,
                resultHash = resultHash,
                toolCallId = toolCallId,
            ))
        }

        // Trim to history size (aligned with OpenClaw splice approach)
        if (state.toolCallHistory.size > resolvedconfig.historySize) {
            val excess = state.toolCallHistory.size - resolvedconfig.historySize
            repeat(excess) { state.toolCallHistory.removeAt(0) }
        }
    }

    // ==================== Stats ====================

    /**
     * Get current tool call statistics for a session (for debugging/monitoring).
     * Aligned with OpenClaw gettoolCallStats.
     */
    fun gettoolCallStats(state: sessionState): toolCallStats {
        val history = state.toolCallHistory
        val patterns = mutableMapOf<String, toolCallStatEntry>()

        for (call in history) {
            val key = call.argsHash
            val existing = patterns[key]
            if (existing != null) {
                patterns[key] = existing.copy(count = existing.count + 1)
            } else {
                patterns[key] = toolCallStatEntry(toolName = call.toolName, count = 1)
            }
        }

        var mostFrequent: toolCallStatEntry? = null
        for (pattern in patterns.values) {
            if (mostFrequent == null || pattern.count > mostFrequent.count) {
                mostFrequent = pattern
            }
        }

        return toolCallStats(
            totalCalls = history.size,
            uniquePatterns = patterns.size,
            mostFrequent = mostFrequent,
        )
    }

    data class toolCallStatEntry(
        val toolName: String,
        val count: Int,
    )

    data class toolCallStats(
        val totalCalls: Int,
        val uniquePatterns: Int,
        val mostFrequent: toolCallStatEntry?,
    )
}
