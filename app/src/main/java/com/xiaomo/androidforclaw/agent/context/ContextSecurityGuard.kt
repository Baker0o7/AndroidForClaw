package com.xiaomo.androidforclaw.agent.context

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/system-prompt.ts (MEMORY.md load guard in shared contexts)
 * - ../openclaw/openclaw-android/.../WorkspaceInitializer.kt (prompt-level guard)
 *
 * androidforClaw adaptation: code-level enforcement of context security.
 * Supplements the prompt-level instruction in SOUL.md ("ONLY load in main session")
 * with hard code gates that cannot be bypassed by the LLM.
 */

import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.logging.SensitiveTextRedactor

/**
 * contextSecurityGuard — Code-level security for shared/group contexts.
 * Aligned with OpenClaw's multi-layer defense:
 * 1. Prompt-level: SOUL.md "DO NOT load in shared contexts"
 * 2. Code-level: This guard (hard gate for MEMORY.md, tool filtering, outbound redaction)
 */
object contextSecurityGuard {

    private const val TAG = "contextSecurityGuard"

    /** Chat types considered "shared" (multi-user, not private DM) */
    private val SHARED_CHAT_TYPES = setOf("group", "channel", "thread")

    /**
     * Determine if a channel context represents a shared (multi-user) environment.
     * Aligned with OpenClaw's group/channel/thread classification.
     *
     * @param channelcontext The current channel context (null = local android app = not shared)
     * @return true if the context is shared (group chat, channel, thread)
     */
    fun isSharedcontext(channelcontext: contextBuilder.channelcontext?): Boolean {
        if (channelcontext == null) return false
        val chatType = channelcontext.chatType?.lowercase() ?: return false
        // Normalize "p2p" → not shared
        if (chatType == "p2p" || chatType == "direct") return false
        return chatType in SHARED_CHAT_TYPES
    }

    /**
     * Determine if MEMORY.md should be loaded in the current context.
     * Code-level enforcement of the SOUL.md prompt instruction:
     * "ONLY load in main session (direct chats with your human)"
     * "DO NOT load in shared contexts (Discord, group chats, sessions with other people)"
     *
     * @param channelcontext The current channel context
     * @return true if MEMORY.md should be loaded (private/DM context)
     */
    fun shouldLoadMemory(channelcontext: contextBuilder.channelcontext?): Boolean {
        val shared = isSharedcontext(channelcontext)
        if (shared) {
            Log.i(TAG, "MEMORY.md blocked in shared context (chatType=${channelcontext?.chatType})")
        }
        return !shared
    }

    /**
     * Redact sensitive content before sending to a shared context.
     * Applied to outbound messages in group chats to prevent secret leakage.
     *
     * @param text The text to potentially redact
     * @return Redacted text (secrets masked)
     */
    fun redactforSharedcontext(text: String): String {
        val (redacted, wasRedacted) = SensitiveTextRedactor.redactSensitiveText(text)
        if (wasRedacted) {
            Log.i(TAG, "Redacted sensitive content in outbound group message")
        }
        return redacted
    }
}
