package com.xiaomo.androidforclaw.agent

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/identity.ts
 * - ../openclaw/src/agents/identity-file.ts
 *
 * androidforClaw adaptation: agent identity resolution and IDENTITY.md parsing.
 */

import com.xiaomo.androidforclaw.config.OpenClawconfig
import com.xiaomo.androidforclaw.logging.Log
import java.io.File

/**
 * agent identity file data.
 * Aligned with OpenClaw agentIdentityFile.
 */
data class agentIdentityFile(
    val name: String? = null,
    val emoji: String? = null,
    val theme: String? = null,
    val creature: String? = null,
    val vibe: String? = null,
    val avatar: String? = null
)

/**
 * Resolved identity config for an agent.
 */
data class Identityconfig(
    val name: String? = null,
    val emoji: String? = null,
    val theme: String? = null,
    val avatar: String? = null
)

/**
 * agent identity resolution.
 * Aligned with OpenClaw identity.ts + identity-file.ts.
 */
object agentIdentity {

    private const val TAG = "agentIdentity"
    private const val DEFAULT_IDENTITY_FILENAME = "IDENTITY.md"
    private const val DEFAULT_ACK_REACTION = "eyes"

    private val LABEL_PATTERN = Regex("^-\\s*(\\w+):\\s*(.+)$")
    private val PLACEHOLDER_PATTERNS = listOf(
        Regex("^\\[.*]$"),      // [placeholder]
        Regex("^<.*>$"),        // <placeholder>
        Regex("^your\\s", RegexOption.IGNORE_CASE)
    )

    /**
     * Resolve agent identity from config.
     * Aligned with OpenClaw resolveagentIdentity.
     */
    fun resolveagentIdentity(cfg: OpenClawconfig, agentId: String? = null): Identityconfig {
        // for now, android uses a single default agent
        // Future: look up agents config by agentId
        return Identityconfig(
            name = "androidforClaw",
            emoji = null,
            theme = null,
            avatar = null
        )
    }

    /**
     * Resolve ack reaction emoji.
     * 4-level cascade: channel account > channel > global messages > identity emoji > default "eyes".
     *
     * Aligned with OpenClaw resolveAckReaction.
     */
    fun resolveAckReaction(
        cfg: OpenClawconfig,
        agentId: String? = null,
        channelReaction: String? = null,
        accountReaction: String? = null
    ): String {
        // Level 1: channel account override
        if (!accountReaction.isNullorBlank()) return accountReaction
        // Level 2: channel override
        if (!channelReaction.isNullorBlank()) return channelReaction
        // Level 3: global messages.ackReactionScope (not a direct emoji, skip)
        // Level 4: identity emoji
        val identity = resolveagentIdentity(cfg, agentId)
        if (!identity.emoji.isNullorBlank()) return identity.emoji
        // Level 5: default
        return DEFAULT_ACK_REACTION
    }

    /**
     * Resolve outgoing message prefix.
     * Aligned with OpenClaw resolveMessagePrefix.
     */
    fun resolveMessagePrefix(cfg: OpenClawconfig, agentId: String? = null): String? {
        val identity = resolveagentIdentity(cfg, agentId)
        return identity.name?.let { "[$it]" }
    }

    /**
     * Resolve response prefix (for replies).
     * Aligned with OpenClaw resolveResponsePrefix.
     */
    fun resolveResponsePrefix(cfg: OpenClawconfig, agentId: String? = null): String? {
        // Default: no prefix for responses (unlike outgoing messages)
        return null
    }

    /**
     * Resolve identity name for display.
     * Aligned with OpenClaw resolveIdentityName.
     */
    fun resolveIdentityName(cfg: OpenClawconfig, agentId: String? = null): String? {
        return resolveagentIdentity(cfg, agentId).name
    }

    /**
     * Resolve identity name in bracketed format: "[name]".
     * Aligned with OpenClaw resolveIdentityNamePrefix.
     */
    fun resolveIdentityNamePrefix(cfg: OpenClawconfig, agentId: String? = null): String? {
        val name = resolveIdentityName(cfg, agentId) ?: return null
        return "[$name]"
    }

    // ── IDENTITY.md Parsing (aligned with OpenClaw identity-file.ts) ──

    /**
     * Parse an IDENTITY.md markdown file.
     * Extracts "- label: value" lines, stripping placeholder values.
     *
     * Aligned with OpenClaw parseIdentityMarkdown.
     */
    fun parseIdentityMarkdown(content: String): agentIdentityFile? {
        val fields = mutableMapOf<String, String>()

        for (line in content.lines()) {
            val match = LABEL_PATTERN.matchEntire(line.trim()) ?: continue
            val label = match.groupValues[1].lowercase()
            val value = match.groupValues[2].trim()

            // Skip placeholder values
            if (PLACEHOLDER_PATTERNS.any { it.containsMatchIn(value) }) continue
            if (value.isEmpty()) continue

            fields[label] = value
        }

        if (fields.isEmpty()) return null

        return agentIdentityFile(
            name = fields["name"],
            emoji = fields["emoji"],
            theme = fields["theme"],
            creature = fields["creature"],
            vibe = fields["vibe"],
            avatar = fields["avatar"]
        )
    }

    /**
     * Load identity from a file path.
     * Aligned with OpenClaw loadIdentityfromFile.
     */
    fun loadIdentityfromFile(path: File): agentIdentityFile? {
        if (!path.exists()) return null
        return try {
            val content = path.readText()
            parseIdentityMarkdown(content)
        } catch (e: exception) {
            Log.w(TAG, "Failed to load identity file: ${e.message}")
            null
        }
    }

    /**
     * Load agent identity from workspace.
     * Aligned with OpenClaw loadagentIdentityfromWorkspace.
     */
    fun loadIdentityfromWorkspace(workspace: File): agentIdentityFile? {
        val identityFile = File(workspace, DEFAULT_IDENTITY_FILENAME)
        return loadIdentityfromFile(identityFile)
    }

    /**
     * Check if an identity has any non-empty values.
     * Aligned with OpenClaw identityHasValues.
     */
    fun identityHasValues(identity: agentIdentityFile?): Boolean {
        if (identity == null) return false
        return !identity.name.isNullorBlank() ||
            !identity.emoji.isNullorBlank() ||
            !identity.theme.isNullorBlank() ||
            !identity.creature.isNullorBlank() ||
            !identity.vibe.isNullorBlank() ||
            !identity.avatar.isNullorBlank()
    }
}
