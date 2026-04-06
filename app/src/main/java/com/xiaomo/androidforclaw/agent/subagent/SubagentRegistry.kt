/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/subagent-registry.ts (in-memory registry, lifecycle listener, completion flow)
 * - ../openclaw/src/agents/subagent-registry-queries.ts (descendant counting, BFS traversal, controller queries)
 * - ../openclaw/src/agents/subagent-control.ts (resolveControlledSubagentTarget)
 * - ../openclaw/src/agents/subagent-registry-state.ts (persistence, orphan reconciliation)
 *
 * androidforClaw adaptation: ConcurrentHashMap-based registry tracking active/completed subagent runs.
 * Includes target resolution, cascade kill, descendant tracking, run replacement,
 * disk persistence, listener interface, and controller-based queries.
 */
package com.xiaomo.androidforclaw.agent.subagent

import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Listener interface for registry lifecycle events.
 * Aligned with OpenClaw's event-driven architecture (lifecycle listener).
 */
interface SubagentRegistryListener {
    fun onRunRegistered(record: SubagentRunRecord) {}
    fun onRunCompleted(record: SubagentRunRecord) {}
    fun onRunReleased(runId: String) {}
}

/**
 * Central registry for all subagent runs.
 * Aligned with OpenClaw's in-memory SubagentRunRecord map + query functions.
 */
class SubagentRegistry(
    private val store: SubagentRegistryStore? = null,
) {
    companion object {
        private const val TAG = "SubagentRegistry"
    }

    /** runId → SubagentRunRecord */
    private val runs = ConcurrentHashMap<String, SubagentRunRecord>()

    /** runId → coroutine Job */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** runId → child agentloop (for steer/kill/history) */
    private val agentloops = ConcurrentHashMap<String, agentloop>()

    /** Registry event listeners */
    private val listeners = mutableListOf<SubagentRegistryListener>()

    fun aListener(listener: SubagentRegistryListener) {
        listeners.a(listener)
    }

    fun removeListener(listener: SubagentRegistryListener) {
        listeners.remove(listener)
    }

    // ==================== Initialization & Persistence ====================

    /**
     * Restore runs from disk on startup.
     * Active runs without Job are orphans — mark as error.
     * Aligned with OpenClaw restoreSubagentRunsOnce + reconcileorphanedRestoredRuns.
     */
    fun restorefromDisk() {
        val loaded = store?.load() ?: return
        if (loaded.isEmpty()) return

        // Step 1: Merge restored runs (aligned with OpenClaw restoreSubagentRunsfromDisk mergeOnly)
        var aed = 0
        for ((runId, record) in loaded) {
            if (!runs.containsKey(runId)) {
                runs[runId] = record
                aed++
            }
        }
        if (aed == 0) return

        // Step 2: Reconcile orphans — separate step aligned with OpenClaw reconcileorphanedRestoredRuns.
        // OpenClaw verifies against session store (gateway sessions.get); on android there is no
        // external session store, so all active runs without a Job are orphaned.
        // These orphans are flagged here; SubagentorphanRecovery.scheduleorphanRecovery() handles
        // the actual recovery/cleanup with retries.
        var orphanCount = 0
        for ((_, record) in runs) {
            if (record.isActive && jobs[record.runId] == null) {
                record.endedAt = System.currentTimeMillis()
                record.outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, "orphaned after process restart")
                record.endedReason = SubagentLifecycleEndedReason.SUBAGENT_ERROR
                orphanCount++
            }
        }
        if (orphanCount > 0) {
            Log.w(TAG, "Reconciled $orphanCount orphaned subagent runs from disk")
        }
        Log.i(TAG, "Restored $aed subagent runs from disk (orphans=$orphanCount)")
        persistToDisk()
    }

    private fun persistToDisk() {
        store?.save(runs)
    }

    // ==================== Registration ====================

    fun registerRun(record: SubagentRunRecord, loop: agentloop, job: Job) {
        runs[record.runId] = record
        agentloops[record.runId] = loop
        jobs[record.runId] = job
        Log.i(TAG, "Registered subagent run: ${record.runId} label=${record.label} child=${record.childsessionKey}")
        persistToDisk()
        listeners.forEach { it.onRunRegistered(record) }
    }

    // ==================== Completion ====================

    fun markCompleted(
        runId: String,
        outcome: SubagentRunOutcome,
        endedReason: SubagentLifecycleEndedReason,
        frozenResult: String?,
    ) {
        val record = runs[runId] ?: return
        record.endedAt = System.currentTimeMillis()
        record.outcome = outcome
        record.endedReason = endedReason
        record.frozenResultText = capFrozenResultText(frozenResult)
        record.frozenResultCapturedAt = if (frozenResult != null) System.currentTimeMillis() else null
        record.archiveAtMs = System.currentTimeMillis() + ARCHIVE_AFTER_MS
        // Clean up runtime references
        agentloops.remove(runId)
        jobs.remove(runId)
        Log.i(TAG, "Completed subagent run: $runId status=${outcome.status} reason=$endedReason")
        persistToDisk()
        listeners.forEach { it.onRunCompleted(record) }
    }

    // ==================== Basic Queries ====================

    fun getRunById(runId: String): SubagentRunRecord? = runs[runId]

    fun getagentloop(runId: String): agentloop? = agentloops[runId]

    fun getJob(runId: String): Job? = jobs[runId]

    /**
     * Find run by child session key.
     * Returns active run first, fallback to any matching run (latest).
     * Aligned with OpenClaw getSubagentRunByChildsessionKey.
     */
    fun getRunByChildsessionKey(childsessionKey: String): SubagentRunRecord? {
        return runs.values.find { it.childsessionKey == childsessionKey && it.isActive }
            ?: runs.values
                .filter { it.childsessionKey == childsessionKey }
                .maxByorNull { it.createdAt }
    }

    fun getActiveRunsforParent(parentsessionKey: String): List<SubagentRunRecord> {
        return runs.values.filter { it.requestersessionKey == parentsessionKey && it.isActive }
    }

    fun getAllRuns(parentsessionKey: String): List<SubagentRunRecord> {
        return runs.values
            .filter { it.requestersessionKey == parentsessionKey }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Get a snapshot of all runs (keyed by runId).
     * used for orphan recovery scanning.
     */
    fun getRunsSnapshot(): Map<String, SubagentRunRecord> {
        return runs.toMap()
    }

    /**
     * Build indexed list: active runs first (sorted by createdAt desc),
     * then completed runs (sorted by endedAt desc).
     * used for numeric index resolution and list display.
     * Aligned with OpenClaw buildSubagentList ordering.
     */
    fun buildIndexedList(parentsessionKey: String): List<SubagentRunRecord> {
        val allRuns = runs.values.filter { it.requestersessionKey == parentsessionKey }
        val active = allRuns.filter { it.isActive }.sortedByDescending { it.createdAt }
        val completed = allRuns.filter { !it.isActive }.sortedByDescending { it.endedAt }
        return active + completed
    }

    /**
     * List all runs spawned by a given requester session key (direct children).
     * Optional requesterRunId provides time-window scoping.
     * Aligned with OpenClaw listRunsforRequesterfromRuns.
     */
    fun listRunsforRequester(
        requestersessionKey: String,
        requesterRunId: String? = null,
    ): List<SubagentRunRecord> {
        val key = requestersessionKey.trim()
        if (key.isEmpty()) return emptyList()

        // Time-window scoping from requester run (aligned with OpenClaw)
        val requesterRun = requesterRunId?.trim()?.let { rid -> runs[rid] }
        val scopedRun = if (requesterRun != null && requesterRun.childsessionKey == key) requesterRun else null
        val lowerBound = scopedRun?.startedAt ?: scopedRun?.createdAt
        val upperBound = scopedRun?.endedAt

        return runs.values
            .filter { entry ->
                if (entry.requestersessionKey != key) return@filter false
                if (lowerBound != null && entry.createdAt < lowerBound) return@filter false
                if (upperBound != null && entry.createdAt > upperBound) return@filter false
                true
            }
            .sortedByDescending { it.createdAt }
    }

    /**
     * List runs where controllersessionKey matches.
     * Falls back to requestersessionKey if controllersessionKey is null.
     * Aligned with OpenClaw listRunsforControllerfromRuns.
     */
    fun listRunsforController(controllerKey: String): List<SubagentRunRecord> {
        return runs.values
            .filter {
                val key = it.controllersessionKey ?: it.requestersessionKey
                key == controllerKey
            }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Count active runs for a session (using controllersessionKey).
     * Active = not ended OR has pending descendants.
     * Aligned with OpenClaw countActiveRunsforsessionfromRuns.
     */
    fun countActiveRunsforsession(controllersessionKey: String): Int {
        return runs.values.count { record ->
            val key = record.controllersessionKey ?: record.requestersessionKey
            key == controllersessionKey && isActiveSubagentRun(record) { sessionKey ->
                countPendingDescendantRuns(sessionKey)
            }
        }
    }

    fun activeChildCount(parentsessionKey: String): Int {
        return countActiveRunsforsession(parentsessionKey)
    }

    /**
     * Check if parent can spawn more children.
     * Aligned with OpenClaw active children check in spawnSubagentDirect.
     */
    fun canSpawn(parentsessionKey: String, maxChildren: Int): Boolean {
        return activeChildCount(parentsessionKey) < maxChildren
    }

    // ==================== Advanced Queries ====================

    /**
     * Find all runIds associated with a child session key.
     * Aligned with OpenClaw findRunIdsByChildsessionKeyfromRuns.
     */
    fun findRunIdsByChildsessionKey(childsessionKey: String): List<String> {
        return runs.values
            .filter { it.childsessionKey == childsessionKey }
            .map { it.runId }
    }

    /**
     * Resolve requester for a child session.
     * Returns requestersessionKey of the latest run for the child.
     * Aligned with OpenClaw resolveRequesterforChildsessionfromRuns.
     */
    fun resolveRequesterforChildsession(childsessionKey: String): String? {
        return runs.values
            .filter { it.childsessionKey == childsessionKey }
            .maxByorNull { it.createdAt }
            ?.requestersessionKey
    }

    /**
     * Check if any run for the given child session key is active.
     * Aligned with OpenClaw isSubagentsessionRunActive.
     */
    fun isSubagentsessionRunActive(childsessionKey: String): Boolean {
        return runs.values.any { it.childsessionKey == childsessionKey && it.isActive }
    }

    /**
     * Check if post-completion announce should be ignored for a session.
     * True if the session's run mode is RUN and cleanup has already been completed.
     * Aligned with OpenClaw shouldIgnorePostCompletionAnnounceforsessionfromRuns.
     */
    fun shouldIgnorePostCompletionAnnounceforsession(childsessionKey: String): Boolean {
        val latestRun = runs.values
            .filter { it.childsessionKey == childsessionKey }
            .maxByorNull { it.createdAt } ?: return false
        return latestRun.spawnMode != SpawnMode.SESSION &&
            latestRun.endedAt != null &&
            latestRun.cleanupCompletedAt != null &&
            latestRun.cleanupCompletedAt!! >= latestRun.endedAt!!
    }

    // ==================== Target Resolution ====================

    /**
     * Resolve a target token to a SubagentRunRecord.
     * Resolution order aligned with OpenClaw resolveControlledSubagentTarget:
     * 1. "last" keyword → most recently started active run (or most recent)
     * 2. Numeric index → 1-based index into buildIndexedList
     * 3. Contains ":" → session key exact match
     * 4. Exact label match (case-insensitive)
     * 5. Label prefix match (case-insensitive)
     * 6. RunId prefix match
     */
    fun resolveTarget(token: String, parentsessionKey: String): SubagentRunRecord? {
        if (token.isBlank()) return null

        val parentRuns = getAllRuns(parentsessionKey)
        if (parentRuns.isEmpty()) return null

        // 1. "last" keyword
        if (token.equals("last", ignoreCase = true)) {
            return parentRuns.firstorNull { it.isActive }
                ?: parentRuns.firstorNull()
        }

        // 2. Numeric index (1-based)
        token.tointorNull()?.let { index ->
            val indexed = buildIndexedList(parentsessionKey)
            return indexed.getorNull(index - 1)
        }

        // 3. session key (contains ":")
        if (":" in token) {
            return parentRuns.find { it.childsessionKey == token }
        }

        // 4. Exact label match (case-insensitive)
        val exactLabel = parentRuns.filter { it.label.equals(token, ignoreCase = true) }
        if (exactLabel.size == 1) return exactLabel[0]

        // 5. Label prefix match (case-insensitive)
        val prefixLabel = parentRuns.filter { it.label.startswith(token, ignoreCase = true) }
        if (prefixLabel.size == 1) return prefixLabel[0]

        // 6. RunId prefix match
        val prefixRunId = parentRuns.filter { it.runId.startswith(token) }
        if (prefixRunId.size == 1) return prefixRunId[0]

        return null
    }

    // ==================== Descendant Tracking ====================

    /**
     * Count pending (active or not-cleanup-completed) descendant runs.
     * BFS traversal through the spawn tree.
     * Aligned with OpenClaw countPendingDescendantRunsfromRuns.
     */
    fun countPendingDescendantRuns(sessionKey: String): Int {
        var count = 0
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.a(sessionKey)
        visited.a(sessionKey)

        while (queue.isnotEmpty()) {
            val currentKey = queue.removeAt(0)
            val children = runs.values.filter { it.requestersessionKey == currentKey }
            for (child in children) {
                if (child.isActive || child.cleanupCompletedAt == null) count++
                if (child.childsessionKey !in visited) {
                    visited.a(child.childsessionKey)
                    queue.a(child.childsessionKey)
                }
            }
        }
        return count
    }

    /**
     * Same as countPendingDescendantRuns but excluding a specific runId.
     * used during announce to exclude the run being announced.
     * Aligned with OpenClaw countPendingDescendantRunsExcludingRunfromRuns.
     */
    fun countPendingDescendantRunsExcludingRun(sessionKey: String, excludeRunId: String): Int {
        var count = 0
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.a(sessionKey)
        visited.a(sessionKey)

        while (queue.isnotEmpty()) {
            val currentKey = queue.removeAt(0)
            val children = runs.values.filter { it.requestersessionKey == currentKey }
            for (child in children) {
                if (child.runId != excludeRunId && (child.isActive || child.cleanupCompletedAt == null)) {
                    count++
                }
                if (child.childsessionKey !in visited) {
                    visited.a(child.childsessionKey)
                    queue.a(child.childsessionKey)
                }
            }
        }
        return count
    }

    /**
     * Count active (not ended) descendant runs.
     * Aligned with OpenClaw countActiveDescendantRunsfromRuns.
     */
    fun countActiveDescendantRuns(sessionKey: String): Int {
        var count = 0
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.a(sessionKey)
        visited.a(sessionKey)

        while (queue.isnotEmpty()) {
            val currentKey = queue.removeAt(0)
            val children = runs.values.filter { it.requestersessionKey == currentKey }
            for (child in children) {
                if (child.isActive) count++
                if (child.childsessionKey !in visited) {
                    visited.a(child.childsessionKey)
                    queue.a(child.childsessionKey)
                }
            }
        }
        return count
    }

    /**
     * List all descendant runs recursively.
     * Aligned with OpenClaw listDescendantRunsforRequesterfromRuns.
     */
    fun listDescendantRuns(sessionKey: String): List<SubagentRunRecord> {
        val result = mutableListOf<SubagentRunRecord>()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.a(sessionKey)
        visited.a(sessionKey)

        while (queue.isnotEmpty()) {
            val currentKey = queue.removeAt(0)
            val children = runs.values.filter { it.requestersessionKey == currentKey }
            for (child in children) {
                result.a(child)
                if (child.childsessionKey !in visited) {
                    visited.a(child.childsessionKey)
                    queue.a(child.childsessionKey)
                }
            }
        }
        return result
    }

    // ==================== steer Restart Management ====================

    /**
     * Clear steer-restart suppression on a run.
     * if the run already ended while suppression was active, resume cleanup.
     * Aligned with OpenClaw clearSubagentRunsteerRestart.
     */
    fun clearSubagentRunsteerRestart(runId: String): Boolean {
        val entry = runs[runId] ?: return false
        if (entry.suppressAnnounceReason != "steer-restart") return true
        entry.suppressAnnounceReason = null
        persistToDisk()
        // if the run already finished while suppression was active, it needs cleanup
        if (entry.endedAt != null && entry.cleanupCompletedAt == null) {
            Log.i(TAG, "Resuming cleanup for run $runId after clearing steer-restart suppression")
        }
        return true
    }

    // ==================== Bulk Termination ====================

    /**
     * Mark matching runs as terminated (killed).
     * can match by runId and/or childsessionKey.
     * Aligned with OpenClaw markSubagentRunTerminated.
     * @return Number of runs updated.
     */
    fun markSubagentRunTerminated(
        runId: String? = null,
        childsessionKey: String? = null,
        reason: String = "killed",
    ): Int {
        val targetRunIds = mutableSetOf<String>()
        if (!runId.isNullorBlank()) targetRunIds.a(runId)
        if (!childsessionKey.isNullorBlank()) {
            runs.values.filter { it.childsessionKey == childsessionKey }.forEach {
                targetRunIds.a(it.runId)
            }
        }
        if (targetRunIds.isEmpty()) return 0

        val now = System.currentTimeMillis()
        var updated = 0
        for (rid in targetRunIds) {
            val entry = runs[rid] ?: continue
            if (entry.endedAt != null) continue // already ended
            entry.endedAt = now
            entry.outcome = SubagentRunOutcome(SubagentRunStatus.ERROR, reason)
            entry.endedReason = SubagentLifecycleEndedReason.SUBAGENT_KILLED
            entry.cleanupHandled = true
            entry.cleanupCompletedAt = now
            entry.suppressAnnounceReason = "killed"
            // cancel job if exists
            jobs[rid]?.cancel()
            jobs.remove(rid)
            agentloops.remove(rid)
            updated++
            listeners.forEach { it.onRunCompleted(entry) }
        }
        if (updated > 0) {
            Log.i(TAG, "markSubagentRunTerminated: $updated runs terminated (reason=$reason)")
            persistToDisk()
        }
        return updated
    }

    // ==================== Control ====================

    /**
     * Kill a running subagent by cancelling its coroutine Job.
     * Aligned with OpenClaw killControlledSubagentRun (single target).
     */
    fun killRun(runId: String): Boolean {
        val job = jobs[runId] ?: return false
        val record = runs[runId] ?: return false
        if (!record.isActive) return false

        Log.i(TAG, "Killing subagent run: $runId")

        // Set suppressAnnounceReason and cleanupHandled BEFORE cancelling
        // Aligned with OpenClaw markSubagentRunTerminated
        record.suppressAnnounceReason = "killed"
        record.cleanupHandled = true

        job.cancel()

        markCompleted(
            runId,
            SubagentRunOutcome(SubagentRunStatus.ERROR, "Killed by parent"),
            SubagentLifecycleEndedReason.SUBAGENT_KILLED,
            frozenResult = null,
        )
        return true
    }

    /**
     * Cascade kill: kill a run and all its descendants (BFS).
     * Aligned with OpenClaw cascadeKillChildren.
     * Returns list of killed runIds.
     */
    fun cascadeKill(runId: String): List<String> {
        val killed = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.a(runId)

        while (queue.isnotEmpty()) {
            val currentRunId = queue.removeAt(0)
            if (currentRunId in visited) continue
            visited.a(currentRunId)

            val record = runs[currentRunId] ?: continue

            // Find children of this run's session
            val children = runs.values.filter {
                it.requestersessionKey == record.childsessionKey && it.isActive
            }
            for (child in children) {
                queue.a(child.runId)
            }

            // Kill this run if still active
            if (record.isActive) {
                // Set suppressAnnounceReason/cleanupHandled BEFORE cancel (aligned with killRun)
                record.suppressAnnounceReason = "killed"
                record.cleanupHandled = true
                val job = jobs[currentRunId]
                job?.cancel()
                markCompleted(
                    currentRunId,
                    SubagentRunOutcome(SubagentRunStatus.ERROR, "Killed by parent (cascade)"),
                    SubagentLifecycleEndedReason.SUBAGENT_KILLED,
                    frozenResult = null,
                )
                killed.a(currentRunId)
            }
        }

        if (killed.isnotEmpty()) {
            Log.i(TAG, "Cascade killed ${killed.size} runs starting from $runId")
        }
        return killed
    }

    // ==================== Run Replacement (steer Restart) ====================

    /**
     * Replace a run record with a new one after steer restart.
     * old run stays in registry for history; new run takes over runtime references.
     * Aligned with OpenClaw replaceSubagentRunaftersteer.
     */
    fun replaceRun(oldRunId: String, newRecord: SubagentRunRecord, loop: agentloop, job: Job) {
        // Preserve frozen result as fallback (aligned with OpenClaw preserveFrozenResultFallback)
        val oldRecord = runs[oldRunId]
        if (oldRecord?.frozenResultText != null && newRecord.fallbackFrozenResultText == null) {
            newRecord.fallbackFrozenResultText = oldRecord.frozenResultText
            newRecord.fallbackFrozenResultCapturedAt = oldRecord.frozenResultCapturedAt
        }

        // Remove old runtime references (record stays for history)
        agentloops.remove(oldRunId)
        jobs.remove(oldRunId)

        // Register new run
        runs[newRecord.runId] = newRecord
        agentloops[newRecord.runId] = loop
        jobs[newRecord.runId] = job
        Log.i(TAG, "Replaced run: $oldRunId → ${newRecord.runId} (session ${newRecord.childsessionKey})")
        persistToDisk()
    }

    // ==================== Release ====================

    /**
     * Fully remove a run from all maps.
     * Aligned with OpenClaw releaseSubagentRun.
     */
    fun releaseSubagentRun(runId: String) {
        runs.remove(runId)
        agentloops.remove(runId)
        jobs.remove(runId)
        Log.d(TAG, "Released subagent run: $runId")
        listeners.forEach { it.onRunReleased(runId) }
        persistToDisk()
    }

    // ==================== Cleanup ====================

    /**
     * Remove archived runs whose archiveAtMs has passed.
     * Aligned with OpenClaw sweeper that runs periodically.
     */
    fun sweepArchived() {
        val now = System.currentTimeMillis()
        val toRemove = runs.values.filter { record ->
            !record.isActive && record.archiveAtMs != null && now >= record.archiveAtMs!!
        }
        // Fallback: also sweep runs completed longer than ARCHIVE_AFTER_MS without archiveAtMs
        val legacySweep = runs.values.filter { record ->
            !record.isActive && record.archiveAtMs == null &&
                record.endedAt != null && (now - record.endedAt!!) > ARCHIVE_AFTER_MS
        }
        val allToRemove = (toRemove + legacySweep).distinctBy { it.runId }
        for (record in allToRemove) {
            runs.remove(record.runId)
            agentloops.remove(record.runId)
            jobs.remove(record.runId)
        }
        if (allToRemove.isnotEmpty()) {
            Log.i(TAG, "Swept ${allToRemove.size} archived subagent runs")
            persistToDisk()
        }
    }
}
