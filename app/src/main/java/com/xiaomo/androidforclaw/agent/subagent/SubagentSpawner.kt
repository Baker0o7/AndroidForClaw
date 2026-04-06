/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/subagent-spawn.ts (spawnSubagentDirect)
 * - ../openclaw/src/agents/subagent-announce.ts (runSubagentAnnounceFlow, announceToParent)
 * - ../openclaw/src/agents/subagent-control.ts (killControlledSubagentRun, steerControlledSubagentRun)
 * - ../openclaw/src/agents/subagent-registry-completion.ts (emitSubagentEndedHookOnce)
 *
 * androidforClaw adaptation: in-process coroutine-based subagent spawning.
 * Replaces OpenClaw's gateway WebSocket communication with direct steerchannel injection.
 */
package com.xiaomo.androidforclaw.agent.subagent

import com.xiaomo.androidforclaw.agent.context.contextmanager
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.sessionsHistorytool
import com.xiaomo.androidforclaw.agent.tools.sessionsKilltool
import com.xiaomo.androidforclaw.agent.tools.sessionsListtool
import com.xiaomo.androidforclaw.agent.tools.sessionsSendtool
import com.xiaomo.androidforclaw.agent.tools.sessionsSpawntool
import com.xiaomo.androidforclaw.agent.tools.sessionStatustool
import com.xiaomo.androidforclaw.agent.tools.sessionsYieldtool
import com.xiaomo.androidforclaw.agent.tools.Subagentstool
import com.xiaomo.androidforclaw.agent.tools.tool
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.config.Subagentsconfig
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutorNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core subagent spawner — validates, creates, and manages subagent agentloop instances.
 * Aligned with OpenClaw spawnSubagentDirect + announce flow + control operations.
 *
 * android-specific: subagents run as in-process coroutines with direct steerchannel communication
 * (no gateway WebSocket).
 */
class SubagentSpawner(
    val registry: SubagentRegistry,
    private val configLoader: configLoader,
    private val llmprovider: UnifiedLLMprovider,
    private val toolRegistry: toolRegistry,
    private val androidtoolRegistry: androidtoolRegistry,
    val hooks: SubagentHooks = SubagentHooks(),
) {
    companion object {
        private const val TAG = "SubagentSpawner"

        /**
         * Build the set of subagent tools for a given parent session.
         * LEAF agents get no subagent tools (they cannot spawn).
         * Aligned with OpenClaw per-session tool injection.
         */
        fun buildSubagenttools(
            spawner: SubagentSpawner,
            parentsessionKey: String,
            parentagentloop: agentloop,
            parentDepth: Int,
            configLoader: configLoader,
        ): List<tool> {
            return listOf(
                sessionsSpawntool(spawner, parentsessionKey, parentagentloop, parentDepth),
                sessionsListtool(spawner.registry, parentsessionKey),
                sessionsSendtool(spawner, parentsessionKey, parentagentloop),
                sessionsKilltool(spawner, parentsessionKey),
                sessionsHistorytool(spawner.registry, parentsessionKey),
                sessionsYieldtool(parentagentloop),
                Subagentstool(spawner, spawner.registry, parentsessionKey, parentagentloop),
                sessionStatustool(spawner.registry, parentsessionKey, configLoader),
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Rate limit: last steer time per (caller, target) pair. Aligned with OpenClaw steerRateLimit Map. */
    private val laststeerTime = ConcurrentHashMap<String, Long>()

    // ==================== Ownership Check ====================

    /**
     * Verify that the caller owns (controls) the given run.
     * Aligned with OpenClaw ensureControllerOwnsRun.
     * Returns error message if not authorized, null if ok.
     */
    private fun ensureControllerOwnsRun(callersessionKey: String, record: SubagentRunRecord): String? {
        val controller = record.controllersessionKey ?: record.requestersessionKey
        return if (callersessionKey != controller) {
            "Caller $callersessionKey does not control run ${record.runId} (controller: $controller)"
        } else null
    }

    // ==================== Spawn ====================

    /**
     * Spawn a subagent.
     * Aligned with OpenClaw spawnSubagentDirect validation + launch flow.
     */
    suspend fun spawn(
        params: SpawnSubagentParams,
        parentsessionKey: String,
        parentagentloop: agentloop,
        parentDepth: Int,
    ): SpawnSubagentResult {
        val config = try {
            configLoader.loadOpenClawconfig().agents?.defaults?.subagents ?: Subagentsconfig()
        } catch (e: exception) {
            Log.w(TAG, "Failed to load subagents config, using defaults: ${e.message}")
            Subagentsconfig()
        }

        if (!config.enabled) {
            return SpawnSubagentResult(
                status = SpawnStatus.FORBIDDEN,
                note = "Subagents are disabled in configuration."
            )
        }

        // ACP runtime check — not supported on android
        if (params.runtime == "acp") {
            return SpawnSubagentResult(
                status = SpawnStatus.ERROR,
                error = "ACP runtime is not supported on android."
            )
        }

        // SESSION mode requires thread=true (aligned with OpenClaw)
        if (params.mode == SpawnMode.SESSION && params.thread != true) {
            return SpawnSubagentResult(
                status = SpawnStatus.ERROR,
                error = "mode=\"session\" requires thread=true so the subagent can stay bound to a thread."
            )
        }

        // 1. Depth check (aligned with OpenClaw: callerDepth >= maxSpawnDepth → forbien)
        val childDepth = parentDepth + 1
        if (parentDepth >= config.maxSpawnDepth) {
            return SpawnSubagentResult(
                status = SpawnStatus.FORBIDDEN,
                note = "Maximum spawn depth (${config.maxSpawnDepth}) reached. cannot spawn at depth $childDepth."
            )
        }

        // 2. Active children check (aligned with OpenClaw: activeChildren >= maxChildrenPeragent → forbien)
        if (!registry.canSpawn(parentsessionKey, config.maxChildrenPeragent)) {
            return SpawnSubagentResult(
                status = SpawnStatus.FORBIDDEN,
                note = "Maximum concurrent children (${config.maxChildrenPeragent}) reached for this session."
            )
        }

        // 3. Generate identifiers
        val runId = UUID.randomUUID().toString()
        val childsessionKey = "agent:main:subagent:$runId"

        // 3a. Run SUBAGENT_SPAWNING hook (can deny spawn, aligned with OpenClaw)
        // Label defaults to empty string (aligned with OpenClaw: params.label?.trim() || "")
        // Display label is resolved later via resolveSubagentLabel()
        val label = params.label?.trim() ?: ""
        val spawningResult = hooks.runSpawning(SubagentSpawningEvent(
            childsessionKey = childsessionKey,
            label = label,
            mode = params.mode,
            requestersessionKey = parentsessionKey,
            threadRequested = params.thread == true,
        ))
        if (spawningResult is SubagentSpawningResult.Error) {
            return SpawnSubagentResult(
                status = SpawnStatus.FORBIDDEN,
                note = "Spawn denied by hook: ${spawningResult.error}"
            )
        }

        // 4. Resolve model
        val model = params.model ?: config.model
            ?: try { configLoader.loadOpenClawconfig().resolveDefaultmodel() } catch (_: exception) { null }

        // 5. Resolve capabilities for child
        val childCapabilities = resolveSubagentCapabilities(childDepth, config.maxSpawnDepth)

        // 6. Build system prompt
        val systemPrompt = SubagentPromptBuilder.build(
            task = params.task,
            label = label,
            capabilities = childCapabilities,
            parentsessionKey = parentsessionKey,
            childsessionKey = childsessionKey,
        )

        // 7. Create child agentloop
        val childcontextmanager = contextmanager(llmprovider)
        val childloop = agentloop(
            llmprovider = llmprovider,
            toolRegistry = toolRegistry,
            androidtoolRegistry = androidtoolRegistry,
            contextmanager = childcontextmanager,
            modelRef = model,
            configLoader = configLoader,
        )

        // 8. if child can spawn (ORCHESTRATOR), inject subagent tools
        if (childCapabilities.canSpawn) {
            val childSubagenttools = buildSubagenttools(
                spawner = this,
                parentsessionKey = childsessionKey,
                parentagentloop = childloop,
                parentDepth = childDepth,
                configLoader = configLoader,
            )
            childloop.extratools = childSubagenttools
        }

        // 9. Create run record (with new fields: controllersessionKey, requesterDisplayKey, sessionStartedAt)
        val timeoutSeconds = params.runTimeoutSeconds ?: config.defaultTimeoutSeconds
        val record = SubagentRunRecord(
            runId = runId,
            childsessionKey = childsessionKey,
            controllersessionKey = parentsessionKey,
            requestersessionKey = parentsessionKey,
            requesterDisplayKey = parentsessionKey,
            task = params.task,
            label = label,
            model = model,
            cleanup = params.cleanup,
            spawnMode = params.mode,
            workspaceDir = params.cwd,
            createdAt = System.currentTimeMillis(),
            runTimeoutSeconds = timeoutSeconds,
            depth = childDepth,
        ).app {
            sessionStartedAt = System.currentTimeMillis()
            // Aligned with OpenClaw: defaults to true unless explicitly set to false
            expectsCompletionMessage = params.expectsCompletionMessage != false
        }

        // 10. Timeout
        val timeoutMs = if (timeoutSeconds > 0) timeoutSeconds * 1000L else 0L

        // 11. Launch child coroutine
        val job = scope.launch {
            record.startedAt = System.currentTimeMillis()
            Log.i(TAG, "Subagent started: $runId label=$label model=$model timeout=${timeoutSeconds}s")

            try {
                val result = if (timeoutMs > 0) {
                    withTimeoutorNull(timeoutMs) {
                        childloop.run(
                            systemPrompt = systemPrompt,
                            userMessage = params.task,
                            reasoningEnabled = true,
                        )
                    }
                } else {
                    childloop.run(
                        systemPrompt = systemPrompt,
                        userMessage = params.task,
                        reasoningEnabled = true,
                    )
                }

                if (result == null) {
                    // Timeout
                    Log.w(TAG, "Subagent timed out: $runId after ${timeoutSeconds}s")
                    childloop.stop()
                    val outcome = SubagentRunOutcome(SubagentRunStatus.TIMEOUT, "Timed out after ${timeoutSeconds}s")
                    registry.markCompleted(runId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                    emitSubagentEndedHookOnce(record, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                    announceToParent(parentagentloop, record, outcome)
                } else {
                    // Success
                    val outcome = SubagentRunOutcome(SubagentRunStatus.OK)
                    val frozenText = capFrozenResultText(result.finalContent)
                    record.frozenResultText = frozenText
                    registry.markCompleted(runId, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE, frozenText)
                    emitSubagentEndedHookOnce(record, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE)
                    announceToParent(parentagentloop, record, outcome)
                    Log.i(TAG, "Subagent completed: $runId iterations=${result.iterations} tools=${result.toolsused.size}")
                }

                // Check if any ancestor needs waking after this completion
                checkDescendantSettle(record, parentagentloop)

            } catch (e: kotlinx.coroutines.cancellationexception) {
                // Killed by parent or steer restart
                Log.i(TAG, "Subagent cancelled: $runId")
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, "Killed by parent")
                registry.markCompleted(runId, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED, null)
                emitSubagentEndedHookOnce(record, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED)
                announceToParent(parentagentloop, record, outcome)
            } catch (e: exception) {
                // Check for transient errors — delay before marking terminal
                if (isTransientError(e)) {
                    Log.w(TAG, "Subagent transient error: $runId, waiting ${LIFECYCLE_ERROR_RETRY_GRACE_MS}ms before marking terminal")
                    delay(LIFECYCLE_ERROR_RETRY_GRACE_MS)
                }
                Log.e(TAG, "Subagent error: $runId", e)
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, e.message ?: "Unknown error")
                registry.markCompleted(runId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                emitSubagentEndedHookOnce(record, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                announceToParent(parentagentloop, record, outcome)
            }
        }

        // 12. Register in registry
        registry.registerRun(record, childloop, job)

        Log.i(TAG, "Subagent spawned: $runId → $childsessionKey depth=$childDepth role=${childCapabilities.role}")

        // Fire SUBAGENT_SPAWNED hook (aligned with OpenClaw)
        scope.launch {
            try {
                hooks.runSpawned(SubagentSpawnedEvent(
                    runId = runId,
                    childsessionKey = childsessionKey,
                    label = label,
                    mode = params.mode,
                    requestersessionKey = parentsessionKey,
                ))
            } catch (e: exception) {
                Log.w(TAG, "Error running subagent spawned hook: ${e.message}")
            }
        }

        return SpawnSubagentResult(
            status = SpawnStatus.ACCEPTED,
            childsessionKey = childsessionKey,
            runId = runId,
            mode = params.mode,
            note = if (params.mode == SpawnMode.SESSION) SPAWN_SESSION_ACCEPTED_NOTE else SPAWN_ACCEPTED_NOTE,
            modelApplied = model != null,
        )
    }

    // ==================== Transient Error Detection ====================

    /**
     * Check if an error is transient (may self-resolve).
     * Aligned with OpenClaw lifecycle error grace period.
     */
    private fun isTransientError(e: exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("timeout") || msg.contains("rate limit") ||
            msg.contains("429") || msg.contains("503") ||
            msg.contains("unavailable") || msg.contains("econnreset")
    }

    // ==================== Lifecycle Hook ====================

    /**
     * Emit subagent_ended hook exactly once per run.
     * Aligned with OpenClaw emitSubagentEndedHookOnce.
     */
    private fun emitSubagentEndedHookOnce(
        record: SubagentRunRecord,
        outcome: SubagentRunOutcome,
        reason: SubagentLifecycleEndedReason,
    ) {
        if (record.endedHookEmittedAt != null) return
        record.endedHookEmittedAt = System.currentTimeMillis()
        Log.d(TAG, "Subagent ended hook: ${record.runId} status=${outcome.status} reason=$reason")

        // Fire hooks asynchronously (aligned with OpenClaw emitSubagentEndedHookOnce)
        scope.launch {
            try {
                hooks.runEnded(SubagentEndedEvent(
                    targetsessionKey = record.childsessionKey,
                    targetKind = SubagentLifecycleTargetKind.SUBAGENT,
                    reason = reason.wireValue,
                    runId = record.runId,
                    endedAt = record.endedHookEmittedAt,
                    outcome = resolveLifecycleOutcome(outcome),
                    error = outcome.error,
                ))
            } catch (e: exception) {
                Log.w(TAG, "Error running subagent ended hook: ${e.message}")
            }
        }
    }

    // ==================== Announce ====================

    /**
     * Announce subagent completion to parent via steerchannel.
     * Aligned with OpenClaw runSubagentAnnounceFlow:
     * 1. Check suppressed announce (steer-restart, killed)
     * 2. Check pending descendants → defer if > 0
     * 3. Check expectsCompletionMessage
     * 4. collect child completion findings
     * 5. retry with exponential backoff
     * 6. Complete parent's yield signal if present
     */
    private suspend fun announceToParent(
        parentagentloop: agentloop,
        record: SubagentRunRecord,
        outcome: SubagentRunOutcome,
    ) {
        // Check if announce is suppressed (steer-restart or killed)
        if (record.suppressAnnounceReason == "steer-restart" || record.suppressAnnounceReason == "killed") {
            Log.d(TAG, "Announce suppressed for ${record.runId}: ${record.suppressAnnounceReason}")
            return
        }

        // Check if post-completion announce should be ignored
        if (registry.shouldIgnorePostCompletionAnnounceforsession(record.childsessionKey)) {
            Log.d(TAG, "Ignoring post-completion announce for ${record.runId}")
            return
        }

        // 1. Check pending descendants — if > 0, defer announce
        val pendingDescendants = registry.countPendingDescendantRunsExcludingRun(record.childsessionKey, record.runId)
        if (pendingDescendants > 0) {
            Log.i(TAG, "Deferring announce for ${record.runId}: $pendingDescendants pending descendants")
            record.suppressAnnounceReason = "pending_descendants:$pendingDescendants"
            record.wakeOnDescendantSettle = true
            return
        }

        // 2. Check expectsCompletionMessage — if false, only freeze text
        if (!record.expectsCompletionMessage) {
            Log.d(TAG, "Skipping steerchannel announce for ${record.runId}: expectsCompletionMessage=false")
            record.cleanupCompletedAt = System.currentTimeMillis()
            registry.sweepArchived()
            return
        }

        // 3. collect child completion findings
        val children = registry.listRunsforRequester(record.childsessionKey)
        val findings = SubagentPromptBuilder.buildChildCompletionFindings(children)

        // 4. Build announcement using output text selection
        // Determine if requester is itself a subagent (for reply instruction)
        val requesterIsSubagent = registry.getRunByChildsessionKey(record.requestersessionKey) != null
        val announcement = SubagentPromptBuilder.buildAnnouncement(
            record, outcome, findings, requesterIsSubagent
        )

        // 5. retry with fixed delay table (aligned with OpenClaw DIRECT_ANNOUNCE_TRANSIENT_RETRY_DELAYS_MS)
        var sent = false
        val maxAttempts = ANNOUNCE_RETRY_DELAYS_MS.size + 1
        for (attempt in 0 until maxAttempts) {
            val result = parentagentloop.steerchannel.trySend(announcement)
            if (result.isSuccess) {
                sent = true
                record.announceretryCount = attempt
                Log.i(TAG, "Announced ${record.runId} to parent (attempt ${attempt + 1}/$maxAttempts)")
                break
            }

            record.lastAnnounceretryAt = System.currentTimeMillis()
            val delayMs = computeAnnounceretryDelayMs(attempt)
            if (delayMs != null) {
                Log.w(TAG, "Announce retry ${attempt + 1}/$maxAttempts for ${record.runId}, waiting ${delayMs}ms")
                delay(delayMs)
            } else {
                break // No more retries
            }
        }

        if (!sent) {
            Log.e(TAG, "Failed to announce ${record.runId} after $maxAttempts attempts")
            record.suppressAnnounceReason = "channel_full_after_retries"
        }

        // Mark cleanup completed
        record.cleanupCompletedAt = System.currentTimeMillis()

        // 6. Complete parent's yield signal if present (sessions_yield)
        parentagentloop.yieldSignal?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(announcement)
                Log.i(TAG, "Completed yield signal for parent after announcing ${record.runId}")
            }
        }

        // Sweep old archived runs
        registry.sweepArchived()
    }

    /**
     * Check if any ancestor has wakeOnDescendantSettle and all descendants
     * are now settled. if so, re-announce the ancestor with collected findings.
     * Called after every run completion.
     * Aligned with OpenClaw descendant settle wake logic.
     */
    private suspend fun checkDescendantSettle(
        completedRecord: SubagentRunRecord,
        parentagentloop: agentloop,
    ) {
        // Walk up: find the parent run that spawned the completed child
        val parentRun = registry.getRunByChildsessionKey(completedRecord.requestersessionKey) ?: return

        if (!parentRun.wakeOnDescendantSettle) return

        val remaining = registry.countPendingDescendantRunsExcludingRun(
            parentRun.childsessionKey,
            parentRun.runId
        )
        if (remaining > 0) {
            Log.d(TAG, "Parent ${parentRun.runId} still has $remaining pending descendants")
            return
        }

        // All descendants settled — collect findings and announce
        Log.i(TAG, "All descendants settled for ${parentRun.runId}, triggering deferred announce")
        parentRun.wakeOnDescendantSettle = false
        parentRun.suppressAnnounceReason = null

        val outcome = parentRun.outcome ?: SubagentRunOutcome(SubagentRunStatus.OK)
        announceToParent(parentagentloop, parentRun, outcome)
    }

    // ==================== Control Operations ====================

    /**
     * Kill a running subagent, optionally with cascade.
     * Aligned with OpenClaw killControlledSubagentRun + cascadeKillChildren.
     *
     * @return Pair of (success, list of killed runIds)
     */
    fun kill(runId: String, cascade: Boolean = false, callersessionKey: String? = null): Pair<Boolean, List<String>> {
        // ControlScope check (aligned with OpenClaw: leaf subagents cannot kill)
        if (callersessionKey != null) {
            val callerRun = registry.getRunByChildsessionKey(callersessionKey)
            if (callerRun != null) {
                val callerCaps = resolveSubagentCapabilities(callerRun.depth)
                if (callerCaps.controlScope == SubagentControlScope.NONE) {
                    Log.w(TAG, "Kill denied: leaf subagents cannot control other sessions")
                    return Pair(false, emptyList())
                }
            }
        }

        // Ownership check
        if (callersessionKey != null) {
            val record = registry.getRunById(runId) ?: return Pair(false, emptyList())
            val error = ensureControllerOwnsRun(callersessionKey, record)
            if (error != null) {
                Log.w(TAG, "Kill denied: $error")
                return Pair(false, emptyList())
            }
        }

        return if (cascade) {
            val killed = registry.cascadeKill(runId)
            Pair(killed.isnotEmpty(), killed)
        } else {
            val success = registry.killRun(runId)
            Pair(success, if (success) listOf(runId) else emptyList())
        }
    }

    /**
     * Admin kill: kill a subagent by session key without ownership check.
     * Includes cascade to descendants.
     * Aligned with OpenClaw killSubagentRunAdmin.
     */
    fun killAdmin(sessionKey: String): Map<String, Any?> {
        val entry = registry.getRunByChildsessionKey(sessionKey)
            ?: return mapOf("found" to false, "killed" to false)

        val (killed, killedIds) = kill(entry.runId, cascade = true, callersessionKey = null)
        val cascadeKilled = if (killedIds.size > 1) killedIds.size - 1 else 0

        return mapOf(
            "found" to true,
            "killed" to killed,
            "runId" to entry.runId,
            "sessionKey" to entry.childsessionKey,
            "cascadeKilled" to cascadeKilled,
        )
    }

    /**
     * steer a running subagent: abort current run and restart with new message.
     * Aligned with OpenClaw steerControlledSubagentRun (abort + restart semantics).
     *
     * Flow:
     * 1. Ownership check
     * 2. Self-steer prevention
     * 3. Rate limit check (2s per caller-target pair)
     * 4. Mark for steer-restart (suppress announce)
     * 5. cancel the child coroutine Job (abort)
     * 6. Clear steer channel
     * 7. Wait for abort to settle (5s, aligned with OpenClaw STEER_ABORT_SETTLE_TIMEOUT_MS)
     * 8. Reset agentloop internal state
     * 9. Accumulate old runtime
     * 10. Mark old run completed
     * 11. Create new run record (preserving session key)
     * 12. Launch new run() with steer message
     * 13. Replace run record in registry
     */
    suspend fun steer(
        runId: String,
        message: String,
        callersessionKey: String,
        parentagentloop: agentloop,
    ): Pair<Boolean, String?> {
        val record = registry.getRunById(runId) ?: return Pair(false, "Run not found: $runId")
        if (!record.isActive) return Pair(false, "Run already completed: $runId")
        val childloop = registry.getagentloop(runId) ?: return Pair(false, "agentloop not found for: $runId")
        val job = registry.getJob(runId) ?: return Pair(false, "Job not found for: $runId")

        // 1. ControlScope check (aligned with OpenClaw: leaf subagents cannot steer)
        val callerRun = registry.getRunByChildsessionKey(callersessionKey)
        if (callerRun != null) {
            val callerCaps = resolveSubagentCapabilities(callerRun.depth)
            if (callerCaps.controlScope == SubagentControlScope.NONE) {
                return Pair(false, "Leaf subagents cannot control other sessions.")
            }
        }

        // 2. Ownership check
        val ownershipError = ensureControllerOwnsRun(callersessionKey, record)
        if (ownershipError != null) {
            return Pair(false, ownershipError)
        }

        // 3. Self-steer prevention (aligned with OpenClaw)
        if (callersessionKey == record.childsessionKey) {
            return Pair(false, "cannot steer own session")
        }

        // 4. Message length check (aligned with OpenClaw MAX_STEER_MESSAGE_CHARS)
        if (message.length > MAX_STEER_MESSAGE_CHARS) {
            return Pair(false, "Message too long: ${message.length} > $MAX_STEER_MESSAGE_CHARS chars")
        }

        // 5. Rate limit check (aligned with OpenClaw: key is caller:childsessionKey, not caller:runId)
        val rateKey = "$callersessionKey:${record.childsessionKey}"
        val now = System.currentTimeMillis()
        val lastTime = laststeerTime[rateKey]
        if (lastTime != null && (now - lastTime) < STEER_RATE_LIMIT_MS) {
            val waitMs = STEER_RATE_LIMIT_MS - (now - lastTime)
            return Pair(false, "Rate limited: wait ${waitMs}ms")
        }
        laststeerTime[rateKey] = now

        // 5. Mark for steer-restart (suppress old run's announce)
        record.suppressAnnounceReason = "steer-restart"

        // 6. cancel the child coroutine Job (abort)
        Log.i(TAG, "steer: aborting run $runId for restart")
        job.cancel()

        // 7. Clear steer channel
        while (childloop.steerchannel.tryReceive().isSuccess) { /* drain */ }

        // 8. Wait for abort to settle (aligned with OpenClaw STEER_ABORT_SETTLE_TIMEOUT_MS = 5s)
        try {
            delay(STEER_ABORT_SETTLE_TIMEOUT_MS)
        } catch (_: exception) { }

        // 9. Reset agentloop state
        childloop.reset()

        // 10. Accumulate runtime from old run
        val oldRuntimeMs = record.runtimeMs

        // 11. Mark old run as completed (steer-restarted)
        registry.markCompleted(
            runId,
            SubagentRunOutcome(SubagentRunStatus.OK, "steered (restarted)"),
            SubagentLifecycleEndedReason.SUBAGENT_COMPLETE,
            frozenResult = null,
        )

        // 12. Create new run record (preserving session key, controllersessionKey, sessionStartedAt)
        val newRunId = UUID.randomUUID().toString()
        val newRecord = SubagentRunRecord(
            runId = newRunId,
            childsessionKey = record.childsessionKey,
            controllersessionKey = record.controllersessionKey,
            requestersessionKey = record.requestersessionKey,
            requesterDisplayKey = record.requesterDisplayKey,
            task = message,
            label = record.label,
            model = record.model,
            cleanup = record.cleanup,
            spawnMode = record.spawnMode,
            workspaceDir = record.workspaceDir,
            createdAt = System.currentTimeMillis(),
            runTimeoutSeconds = record.runTimeoutSeconds,
            depth = record.depth,
        ).app {
            accumulatedRuntimeMs = oldRuntimeMs
            sessionStartedAt = record.sessionStartedAt ?: record.startedAt
            expectsCompletionMessage = record.expectsCompletionMessage
        }

        // 13. Rebuild system prompt
        val config = try {
            configLoader.loadOpenClawconfig().agents?.defaults?.subagents ?: Subagentsconfig()
        } catch (_: exception) { Subagentsconfig() }
        val childCapabilities = resolveSubagentCapabilities(record.depth, config.maxSpawnDepth)
        val systemPrompt = SubagentPromptBuilder.build(
            task = message,
            label = record.label,
            capabilities = childCapabilities,
            parentsessionKey = record.requestersessionKey,
            childsessionKey = record.childsessionKey,
        )

        // 14. Launch new coroutine with conversation context from previous run
        val timeoutMs = (record.runTimeoutSeconds ?: config.defaultTimeoutSeconds).let {
            if (it > 0) it * 1000L else 0L
        }
        val previousMessages = childloop.conversationMessages.toList()

        val newJob = scope.launch {
            newRecord.startedAt = System.currentTimeMillis()
            Log.i(TAG, "steer restart: $newRunId (was $runId)")

            try {
                val result = if (timeoutMs > 0) {
                    withTimeoutorNull(timeoutMs) {
                        childloop.run(
                            systemPrompt = systemPrompt,
                            userMessage = message,
                            contextHistory = previousMessages.drop(1), // Skip system prompt
                            reasoningEnabled = true,
                        )
                    }
                } else {
                    childloop.run(
                        systemPrompt = systemPrompt,
                        userMessage = message,
                        contextHistory = previousMessages.drop(1),
                        reasoningEnabled = true,
                    )
                }

                if (result == null) {
                    val outcome = SubagentRunOutcome(SubagentRunStatus.TIMEOUT)
                    registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                    emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                    announceToParent(parentagentloop, newRecord, outcome)
                } else {
                    val outcome = SubagentRunOutcome(SubagentRunStatus.OK)
                    val frozenText = capFrozenResultText(result.finalContent)
                    newRecord.frozenResultText = frozenText
                    registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE, frozenText)
                    emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE)
                    announceToParent(parentagentloop, newRecord, outcome)
                }

                checkDescendantSettle(newRecord, parentagentloop)
            } catch (e: kotlinx.coroutines.cancellationexception) {
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, "Killed")
                registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED, null)
                emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED)
                announceToParent(parentagentloop, newRecord, outcome)
            } catch (e: exception) {
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, e.message)
                registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                announceToParent(parentagentloop, newRecord, outcome)
            }
        }

        // 15. Replace in registry
        registry.replaceRun(runId, newRecord, childloop, newJob)

        Log.i(TAG, "steer complete: $runId → $newRunId")
        return Pair(true, "steered: run restarted as $newRunId")
    }

    // ==================== session Reactivation ====================

    /**
     * Reactivate a completed SESSION-mode subagent with a new message.
     * Creates a new run record reusing the same child session key and agentloop.
     * Aligned with OpenClaw session reactivation (follow-up messages to completed SESSION-mode subagents).
     *
     * @param childsessionKey The session key of the completed subagent
     * @param message The new message to send
     * @param callersessionKey The caller requesting reactivation
     * @param parentagentloop The parent's agentloop for announce
     * @return Pair of (success, message/error)
     */
    suspend fun reactivatesession(
        childsessionKey: String,
        message: String,
        callersessionKey: String,
        parentagentloop: agentloop,
    ): Pair<Boolean, String?> {
        // Find the latest completed run for this session key
        val runIds = registry.findRunIdsByChildsessionKey(childsessionKey)
        if (runIds.isEmpty()) {
            return Pair(false, "No runs found for session: $childsessionKey")
        }

        val latestRunId = runIds.last()
        val record = registry.getRunById(latestRunId)
            ?: return Pair(false, "Run not found: $latestRunId")

        if (record.isActive) {
            return Pair(false, "session is still active, use sessions_send instead")
        }

        if (record.spawnMode != SpawnMode.SESSION) {
            return Pair(false, "cannot reactivate non-SESSION mode subagent (mode: ${record.spawnMode.wireValue})")
        }

        // Ownership check
        val ownershipError = ensureControllerOwnsRun(callersessionKey, record)
        if (ownershipError != null) {
            return Pair(false, ownershipError)
        }

        // Get the existing agentloop (SESSION mode keeps it alive)
        val childloop = registry.getagentloop(latestRunId)
            ?: return Pair(false, "agentloop not found for completed session (may have been cleaned up)")

        // Create new run record reusing session key
        val newRunId = UUID.randomUUID().toString()
        val config = try {
            configLoader.loadOpenClawconfig().agents?.defaults?.subagents ?: Subagentsconfig()
        } catch (_: exception) { Subagentsconfig() }

        val timeoutSeconds = record.runTimeoutSeconds ?: config.defaultTimeoutSeconds
        val newRecord = SubagentRunRecord(
            runId = newRunId,
            childsessionKey = childsessionKey,
            controllersessionKey = record.controllersessionKey,
            requestersessionKey = record.requestersessionKey,
            requesterDisplayKey = record.requesterDisplayKey,
            task = message,
            label = record.label,
            model = record.model,
            cleanup = record.cleanup,
            spawnMode = SpawnMode.SESSION,
            workspaceDir = record.workspaceDir,
            createdAt = System.currentTimeMillis(),
            runTimeoutSeconds = timeoutSeconds,
            depth = record.depth,
        ).app {
            sessionStartedAt = record.sessionStartedAt ?: record.startedAt
            accumulatedRuntimeMs = record.accumulatedRuntimeMs + record.runtimeMs
            expectsCompletionMessage = true
        }

        // Build system prompt for continuation
        val childCapabilities = resolveSubagentCapabilities(record.depth, config.maxSpawnDepth)
        val systemPrompt = SubagentPromptBuilder.build(
            task = message,
            label = record.label,
            capabilities = childCapabilities,
            parentsessionKey = record.requestersessionKey,
            childsessionKey = childsessionKey,
        )

        val timeoutMs = if (timeoutSeconds > 0) timeoutSeconds * 1000L else 0L
        val previousMessages = childloop.conversationMessages.toList()

        // Launch new run
        val newJob = scope.launch {
            newRecord.startedAt = System.currentTimeMillis()
            Log.i(TAG, "session reactivated: $newRunId (session=$childsessionKey)")

            try {
                val result = if (timeoutMs > 0) {
                    withTimeoutorNull(timeoutMs) {
                        childloop.run(
                            systemPrompt = systemPrompt,
                            userMessage = message,
                            contextHistory = previousMessages.drop(1),
                            reasoningEnabled = true,
                        )
                    }
                } else {
                    childloop.run(
                        systemPrompt = systemPrompt,
                        userMessage = message,
                        contextHistory = previousMessages.drop(1),
                        reasoningEnabled = true,
                    )
                }

                if (result == null) {
                    val outcome = SubagentRunOutcome(SubagentRunStatus.TIMEOUT)
                    registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                    emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                    announceToParent(parentagentloop, newRecord, outcome)
                } else {
                    val outcome = SubagentRunOutcome(SubagentRunStatus.OK)
                    val frozenText = capFrozenResultText(result.finalContent)
                    newRecord.frozenResultText = frozenText
                    registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE, frozenText)
                    emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_COMPLETE)
                    announceToParent(parentagentloop, newRecord, outcome)
                }

                checkDescendantSettle(newRecord, parentagentloop)
            } catch (e: kotlinx.coroutines.cancellationexception) {
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, "Killed")
                registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED, null)
                emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_KILLED)
                announceToParent(parentagentloop, newRecord, outcome)
            } catch (e: exception) {
                val outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, e.message)
                registry.markCompleted(newRunId, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR, null)
                emitSubagentEndedHookOnce(newRecord, outcome, SubagentLifecycleEndedReason.SUBAGENT_ERROR)
                announceToParent(parentagentloop, newRecord, outcome)
            }
        }

        // Register new run
        registry.registerRun(newRecord, childloop, newJob)

        Log.i(TAG, "session reactivated: $childsessionKey → new run $newRunId")
        return Pair(true, "session reactivated as run $newRunId")
    }
}
