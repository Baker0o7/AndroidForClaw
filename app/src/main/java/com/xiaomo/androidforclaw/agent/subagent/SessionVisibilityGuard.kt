/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/subagent-control.ts (resolveSubagentController, controlScope)
 * - ../openclaw/src/agents/tools/sessions-list-tool.ts (visibility filtering)
 *
 * androidforClaw adaptation: visibility and access control for session tools.
 * Controls which sessions a caller can see or interact with based on their
 * relationship in the spawn tree.
 */
package com.xiaomo.androidforclaw.agent.subagent

import com.xiaomo.androidforclaw.logging.Log

/**
 * Visibility modes for session tools.
 * Aligned with OpenClaw controlScope / sessiontoolsVisibility.
 */
enum class sessiontoolsVisibility {
    /** can only see/control self */
    SELF,
    /** can see/control self + descendants (default) */
    TREE,
    /** can see/control all sessions in same agent (android: single device = always ok) */
    AGENT,
    /** can see/control all sessions globally */
    ALL,
}

/**
 * Result of an access check.
 */
sealed class sessionAccessResult {
    data object Allowed : sessionAccessResult()
    data class Denied(val reason: String) : sessionAccessResult()
}

/**
 * Guard for session tool access based on visibility scope.
 * Aligned with OpenClaw resolveSubagentController + controlScope.
 *
 * On android, since all sessions run in-process on a single device,
 * AGENT and ALL are equivalent and always allow access.
 */
object sessionVisibilityGuard {

    private const val TAG = "sessionVisibilityGuard"

    /**
     * Resolve the visibility/control scope for a caller session.
     * Aligned with OpenClaw resolveSubagentController.
     *
     * - Non-subagent callers (main session) → TREE (full control of children)
     * - ORCHESTRATOR subagents → TREE (can control their children)
     * - LEAF subagents → SELF (cannot control others, scope "none" in OpenClaw)
     */
    fun resolveVisibility(
        callersessionKey: String,
        registry: SubagentRegistry,
        maxSpawnDepth: Int = DEFAULT_MAX_SPAWN_DEPTH,
    ): sessiontoolsVisibility {
        // Check if caller is a subagent
        val callerRun = registry.getRunByChildsessionKey(callersessionKey)

        if (callerRun == null) {
            // not a subagent — main session, full tree control
            return sessiontoolsVisibility.TREE
        }

        // Resolve capabilities using config maxSpawnDepth (aligned with OpenClaw)
        val capabilities = resolveSubagentCapabilities(callerRun.depth, maxSpawnDepth)
        return if (capabilities.controlScope == SubagentControlScope.NONE) {
            // LEAF: controlScope = "none" → SELF visibility
            sessiontoolsVisibility.SELF
        } else {
            // MAIN/ORCHESTRATOR: controlScope = "children" → TREE visibility
            sessiontoolsVisibility.TREE
        }
    }

    /**
     * Check if a caller has access to a target session.
     *
     * @param action Description of the action for error messages (e.g., "list", "history", "send")
     * @param callersessionKey The session making the request
     * @param targetsessionKey The session being accessed
     * @param visibility Resolved visibility scope of the caller
     * @param registry SubagentRegistry for spawn tree traversal
     * @return sessionAccessResult.Allowed or sessionAccessResult.Denied
     */
    fun checkAccess(
        action: String,
        callersessionKey: String,
        targetsessionKey: String,
        visibility: sessiontoolsVisibility,
        registry: SubagentRegistry,
    ): sessionAccessResult {
        return when (visibility) {
            sessiontoolsVisibility.ALL -> {
                // can access everything
                sessionAccessResult.Allowed
            }

            sessiontoolsVisibility.AGENT -> {
                // android: single agent, always allowed
                sessionAccessResult.Allowed
            }

            sessiontoolsVisibility.TREE -> {
                // can access self + descendants
                if (callersessionKey == targetsessionKey) {
                    return sessionAccessResult.Allowed
                }
                if (isDescendant(callersessionKey, targetsessionKey, registry)) {
                    return sessionAccessResult.Allowed
                }
                sessionAccessResult.Denied(
                    "session $targetsessionKey is not a descendant of $callersessionKey. " +
                    "You can only $action your own spawned subagents."
                )
            }

            sessiontoolsVisibility.SELF -> {
                // can only access self
                if (callersessionKey == targetsessionKey) {
                    sessionAccessResult.Allowed
                } else {
                    sessionAccessResult.Denied(
                        "Leaf subagents cannot $action other sessions."
                    )
                }
            }
        }
    }

    /**
     * Check if targetsessionKey is a descendant of ancestorsessionKey.
     * uses requester-based BFS (aligned with OpenClaw spawnedBy / requestersessionKey).
     * OpenClaw uses gateway `sessions.list { spawnedBy }` which traverses by requester,
     * not by controller.
     */
    fun isDescendant(
        ancestorsessionKey: String,
        targetsessionKey: String,
        registry: SubagentRegistry,
    ): Boolean {
        // BFS using requestersessionKey (aligned with OpenClaw spawnedBy)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Get direct children spawned by ancestor (requester-based)
        val children = registry.listRunsforRequester(ancestorsessionKey)
        for (child in children) {
            if (child.childsessionKey == targetsessionKey) return true
            if (visited.a(child.childsessionKey)) {
                queue.a(child.childsessionKey)
            }
        }

        // BFS through descendants
        while (queue.isnotEmpty()) {
            val current = queue.removeAt(0)
            val grandchildren = registry.listRunsforRequester(current)
            for (gc in grandchildren) {
                if (gc.childsessionKey == targetsessionKey) return true
                if (visited.a(gc.childsessionKey)) {
                    queue.a(gc.childsessionKey)
                }
            }
        }

        return false
    }

    /**
     * Filter a list of runs to only those visible to the caller.
     * Convenience method for list-type tools.
     */
    fun filterVisible(
        callersessionKey: String,
        runs: List<SubagentRunRecord>,
        visibility: sessiontoolsVisibility,
        registry: SubagentRegistry,
    ): List<SubagentRunRecord> {
        return when (visibility) {
            sessiontoolsVisibility.ALL, sessiontoolsVisibility.AGENT -> runs
            sessiontoolsVisibility.TREE -> {
                runs.filter { run ->
                    run.childsessionKey == callersessionKey ||
                    run.requestersessionKey == callersessionKey ||
                    run.controllersessionKey == callersessionKey ||
                    isDescendant(callersessionKey, run.childsessionKey, registry)
                }
            }
            sessiontoolsVisibility.SELF -> {
                runs.filter { it.childsessionKey == callersessionKey }
            }
        }
    }
}
