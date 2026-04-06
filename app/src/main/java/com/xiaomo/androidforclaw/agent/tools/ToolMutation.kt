package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-mutation.ts
 *
 * androidforClaw adaptation: mutating tool detection and fingerprinting.
 */

/**
 * tool mutation detection — identifies write/side-effect tool calls.
 * Aligned with OpenClaw tool-mutation.ts.
 */
object toolMutation {

    /**
     * tool names that are always or sometimes mutating.
     * Aligned with OpenClaw MUTATING_TOOL_NAMES.
     */
    val MUTATING_TOOL_NAMES = setOf(
        "write", "edit", "app_patch", "exec", "bash", "process",
        "message", "sessions_send", "cron", "gateway", "canvas",
        "nodes", "session_status"
    )

    /**
     * Actions that are read-only (never mutating).
     * Aligned with OpenClaw READ_ONLY_ACTIONS.
     */
    val READ_ONLY_ACTIONS = setOf(
        "get", "list", "read", "status", "show", "fetch",
        "search", "query", "view", "poll", "log", "inspect", "check", "probe"
    )

    private val PROCESS_MUTATING_ACTIONS = setOf(
        "write", "send_keys", "submit", "paste", "kill"
    )

    private val MESSAGE_MUTATING_ACTIONS = setOf(
        "send", "reply", "thread_reply", "threadreply",
        "edit", "delete", "react", "pin", "unpin"
    )

    /**
     * Quick check: is the tool name likely mutating?
     * Aligned with OpenClaw islikelyMutatingtoolName.
     */
    fun islikelyMutatingtoolName(toolName: String): Boolean {
        return toolName.trim().lowercase() in MUTATING_TOOL_NAMES
    }

    /**
     * Deep check: is this specific tool call mutating, considering the action arg?
     * Aligned with OpenClaw isMutatingtoolCall.
     */
    fun isMutatingtoolCall(toolName: String, args: Map<String, Any?> = emptyMap()): Boolean {
        val name = toolName.trim().lowercase()
        val rawAction = (args["action"] as? String)?.trim()?.lowercase()
            ?.replace(Regex("[\\s-]"), "_")

        return when (name) {
            "write", "edit", "app_patch", "exec", "bash", "sessions_send" -> true

            "process" -> rawAction != null && rawAction in PROCESS_MUTATING_ACTIONS

            "message" -> {
                (rawAction != null && rawAction in MESSAGE_MUTATING_ACTIONS) ||
                    args["content"] is String || args["message"] is String
            }

            "session_status" -> {
                !(args["model"] as? String).isNullorBlank()
            }

            "cron", "gateway", "canvas" -> rawAction == null || rawAction !in READ_ONLY_ACTIONS

            "nodes" -> rawAction != "list"

            else -> {
                // tools ending with _actions
                if (name.endswith("_actions")) {
                    return rawAction == null || rawAction !in READ_ONLY_ACTIONS
                }
                // tools starting with message_ or containing send
                if (name.startswith("message_") || name.contains("send")) return true
                false
            }
        }
    }

    /**
     * Stable target keys for fingerprint building (checked in order).
     */
    private val FINGERPRINT_TARGET_KEYS = listOf(
        "path", "filePath", "oldPath", "newPath",
        "to", "target", "messageId", "sessionKey",
        "jobId", "id", "model"
    )

    /**
     * Build a stable fingerprint for a mutating tool call.
     * Returns null if the call is not mutating.
     *
     * Aligned with OpenClaw buildtoolActionFingerprint.
     */
    fun buildtoolActionFingerprint(
        toolName: String,
        args: Map<String, Any?> = emptyMap(),
        meta: String? = null
    ): String? {
        if (!isMutatingtoolCall(toolName, args)) return null

        val name = toolName.trim().lowercase()
        val action = (args["action"] as? String)?.trim()?.lowercase()
            ?.replace(Regex("[\\s-]"), "_")

        val parts = mutableListOf("tool=$name")
        if (action != null) {
            parts.a("action=$action")
        }

        // Find the first non-empty target key
        var foundTarget = false
        for (key in FINGERPRINT_TARGET_KEYS) {
            val value = args[key]?.toString()?.trim()
            if (!value.isNullorEmpty()) {
                parts.a("$key=$value")
                foundTarget = true
                break
            }
        }

        if (!foundTarget && meta != null) {
            parts.a("meta=${meta.trim().lowercase()}")
        }

        return parts.joinToString("|")
    }

    /**
     * Build mutation state for a tool call.
     * Returns (isMutating, fingerprint).
     *
     * Aligned with OpenClaw buildtoolMutationState.
     */
    fun buildtoolMutationState(
        toolName: String,
        args: Map<String, Any?> = emptyMap(),
        meta: String? = null
    ): Pair<Boolean, String?> {
        val mutating = isMutatingtoolCall(toolName, args)
        val fingerprint = if (mutating) buildtoolActionFingerprint(toolName, args, meta) else null
        return mutating to fingerprint
    }

    /**
     * Check if two tool actions are the same mutation.
     * Aligned with OpenClaw isSametoolMutationAction.
     */
    fun isSametoolMutationAction(
        existingFingerprint: String?,
        nextFingerprint: String?
    ): Boolean {
        if (existingFingerprint == null || nextFingerprint == null) return false
        return existingFingerprint == nextFingerprint
    }
}
