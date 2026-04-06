package com.xiaomo.androidforclaw.agent.hook

/**
 * Hook system for agent lifecycle events.
 * Aligned with OpenClaw hook runner (src/agents/pi-embeed-runner/runner-hooks.ts).
 *
 * Provides registration and execution of lifecycle hooks:
 * - before_compaction: runs before context compaction
 * - after_compaction: runs after context compaction
 * - before_tool_call: runs before each tool execution
 * - after_tool_call: runs after each tool execution
 * - on_error: runs on error conditions
 */

/**
 * Hook event data passed to hook handlers.
 */
data class HookEvent(
    val phase: String,
    val data: Map<String, Any?> = emptyMap()
)

/**
 * Hook context passed to hook handlers.
 * Provides access to session state that hooks can inspect/modify.
 */
data class Hookcontext(
    val sessionKey: String?,
    val agentId: String?,
    val provider: String,
    val model: String
)

/**
 * Result of a hook execution.
 */
data class HookResult(
    val success: Boolean = true,
    val shouldcancel: Boolean = false,
    val modifiedData: Map<String, Any?>? = null
)

/**
 * Hook handler function type.
 * Returns HookResult; if shouldcancel=true, the caller should abort the operation.
 */
typealias HookHandler = suspend (HookEvent, Hookcontext) -> HookResult

/**
 * Hook runner — registers and executes lifecycle hooks.
 * Aligned with OpenClaw EmbeedagentHookRunner.
 */
class HookRunner {

    private val hooks = mutableMapOf<String, MutableList<HookHandler>>()

    companion object {
        const val BEFORE_COMPACTION = "before_compaction"
        const val AFTER_COMPACTION = "after_compaction"
        const val BEFORE_TOOL_CALL = "before_tool_call"
        const val AFTER_TOOL_CALL = "after_tool_call"
        const val ON_ERROR = "on_error"
    }

    /**
     * Register a hook handler for a given phase.
     */
    fun on(phase: String, handler: HookHandler) {
        hooks.getorPut(phase) { mutableListOf() }.a(handler)
    }

    /**
     * Check if any hooks are registered for a phase.
     * Aligned with OpenClaw hookRunner.hasHooks().
     */
    fun hasHooks(phase: String): Boolean {
        return hooks[phase]?.isnotEmpty() == true
    }

    /**
     * Run all hooks for a given phase.
     * Returns the last result, or a default success result if no hooks exist.
     * Aligned with OpenClaw hookRunner.runbeforeCompaction().
     */
    suspend fun run(phase: String, event: HookEvent, context: Hookcontext): HookResult {
        val phaseHooks = hooks[phase] ?: return HookResult(success = true)
        var lastResult = HookResult(success = true)

        for (handler in phaseHooks) {
            try {
                val result = handler(event, context)
                lastResult = result
                if (result.shouldcancel) {
                    return result
                }
            } catch (e: exception) {
                // Log but don't fail — aligned with OpenClaw hook error handling
                android.util.Log.w("HookRunner", "Hook $phase failed: ${e.message}")
                lastResult = HookResult(success = false)
            }
        }

        return lastResult
    }

    /**
     * Run before_compaction hook (convenience method).
     * Aligned with OpenClaw hookRunner.runbeforeCompaction().
     */
    suspend fun runbeforeCompaction(
        data: Map<String, Any?>,
        context: Hookcontext
    ): HookResult {
        return run(BEFORE_COMPACTION, HookEvent(phase = BEFORE_COMPACTION, data = data), context)
    }

    /**
     * Run after_compaction hook (convenience method).
     */
    suspend fun runafterCompaction(
        data: Map<String, Any?>,
        context: Hookcontext
    ): HookResult {
        return run(AFTER_COMPACTION, HookEvent(phase = AFTER_COMPACTION, data = data), context)
    }

    /**
     * Run before_tool_call hook.
     */
    suspend fun runbeforetoolCall(
        toolName: String,
        arguments: String,
        context: Hookcontext
    ): HookResult {
        return run(BEFORE_TOOL_CALL, HookEvent(
            phase = BEFORE_TOOL_CALL,
            data = mapOf("toolName" to toolName, "arguments" to arguments)
        ), context)
    }

    /**
     * Run after_tool_call hook.
     */
    suspend fun runaftertoolCall(
        toolName: String,
        arguments: String,
        result: String,
        success: Boolean,
        context: Hookcontext
    ): HookResult {
        return run(AFTER_TOOL_CALL, HookEvent(
            phase = AFTER_TOOL_CALL,
            data = mapOf(
                "toolName" to toolName,
                "arguments" to arguments,
                "result" to result,
                "success" to success
            )
        ), context)
    }
}
