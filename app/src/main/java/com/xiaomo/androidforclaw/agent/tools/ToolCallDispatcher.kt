package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-tools.ts
 *
 * androidforClaw adaptation: unified function-call dispatcher across universal tools and android tools.
 */

import com.xiaomo.androidforclaw.logging.Log

/**
 * Unified tool/function dispatcher.
 *
 * Goal:
 * - Keep function-call scheduling closer to OpenClaw's single dispatch entry
 * - Avoid duplicating tool routing logic inside agentloop
 * - Let non-android backends such as Termux live in the universal tool layer
 */
class toolCallDispatcher(
    private val toolRegistry: toolRegistry,
    private val androidtoolRegistry: androidtoolRegistry,
    private val extratools: Map<String, tool> = emptyMap()
) {
    companion object {
        private const val TAG = "toolCallDispatcher"
    }

    fun resolve(name: String): DispatchTarget? {
        return when {
            extratools.containsKey(name) -> DispatchTarget.Extra(name)
            toolRegistry.contains(name) -> DispatchTarget.Universal(name)
            androidtoolRegistry.contains(name) -> DispatchTarget.android(name)
            else -> null
        }
    }

    suspend fun execute(name: String, args: Map<String, Any?>): skillResult {
        return when (val target = resolve(name)) {
            is DispatchTarget.Extra -> {
                Log.d(TAG, "Dispatch → extra tool: ${target.name}")
                extratools[target.name]!!.execute(args)
            }
            is DispatchTarget.Universal -> {
                Log.d(TAG, "Dispatch → universal tool: ${target.name}")
                toolRegistry.execute(target.name, args)
            }
            is DispatchTarget.android -> {
                Log.d(TAG, "Dispatch → android tool: ${target.name}")
                androidtoolRegistry.execute(target.name, args)
            }
            null -> {
                Log.e(TAG, "Unknown function: $name")
                skillResult.error("Unknown function: $name")
            }
        }
    }

    sealed class DispatchTarget(open val name: String) {
        data class Universal(override val name: String) : DispatchTarget(name)
        data class android(override val name: String) : DispatchTarget(name)
        data class Extra(override val name: String) : DispatchTarget(name)
    }
}
