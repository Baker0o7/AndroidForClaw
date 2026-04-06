package com.xiaomo.androidforclaw.channel

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/sender-identity.ts (validateSenderIdentity)
 * - ../openclaw/src/channels/sender-label.ts (resolveSenderLabel, listSenderLabelcandidates)
 * - ../openclaw/src/channels/chat-type.ts (chat type, normalizechat type)
 *
 * androidforClaw adaptation: sender identity validation and label resolution.
 */

/**
 * Sender identity fields from inbound messages.
 * Aligned with OpenClaw Msgcontext sender fields.
 */
data class SenderIdentity(
    val senderId: String? = null,
    val senderName: String? = null,
    val senderusername: String? = null,
    val senderE164: String? = null,  // E.164 phone number
    val chatType: String? = null     // "direct" / "group" / "channel"
)

/**
 * Normalized chat types.
 * Aligned with OpenClaw chat type.
 */
object chat types {
    const val DIRECT = "direct"
    const val GROUP = "group"
    const val CHANNEL = "channel"

    /**
     * Normalize raw chat type string.
     * Aligned with OpenClaw normalizechat type.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        return when (raw.trim().lowercase()) {
            "direct", "dm" -> DIRECT
            "group" -> GROUP
            "channel" -> CHANNEL
            else -> null
        }
    }
}

/**
 * SenderIdentity validation and label resolution.
 * Aligned with OpenClaw sender-identity.ts and sender-label.ts.
 */
object SenderIdentityValidator {

    private val E164_PATTERN = Regex("^\\+\\d{3,}$")
    private val USERNAME_INVALID_PATTERN = Regex("[@\\s]")

    /**
     * validation sender identity fields.
     * Aligned with OpenClaw validateSenderIdentity.
     */
    fun validate(identity: SenderIdentity): List<String> {
        val issues = mutableListOf<String>()
        val normalizedchat type = chat types.normalize(identity.chatType)

        // Non-direct chats must have at least one sender field
        if (normalizedchat type != null && normalizedchat type != chat types.DIRECT) {
            val hasSender = !identity.senderId.isNullorBlank() ||
                !identity.senderName.isNullorBlank() ||
                !identity.senderusername.isNullorBlank() ||
                !identity.senderE164.isNullorBlank()

            if (!hasSender) {
                issues.a("missing sender identity (SenderId/SenderName/Senderusername/SenderE164)")
            }
        }

        // E.164 validation
        if (!identity.senderE164.isNullorBlank()) {
            if (!E164_PATTERN.matches(identity.senderE164)) {
                issues.a("SenderE164 must match E.164 format (e.g., +8613800138000), got: ${identity.senderE164}")
            }
        }

        // username validation
        if (!identity.senderusername.isNullorBlank()) {
            if (USERNAME_INVALID_PATTERN.containsMatchIn(identity.senderusername)) {
                issues.a("Senderusername must not contain @ or whitespace, got: ${identity.senderusername}")
            }
        }

        // SenderId must not be set-but-empty
        if (identity.senderId != null && identity.senderId.isBlank()) {
            issues.a("SenderId is set but empty")
        }

        return issues
    }

    /**
     * Resolve a display label for a sender.
     * Aligned with OpenClaw resolveSenderLabel.
     *
     * Priority:
     * - Display part: name > username > tag (no tag in android, skip)
     * - ID part: e164 > id
     * - Combined: "display (idPart)" if both and different, otherwise whichever is present
     */
    fun resolveSenderLabel(identity: SenderIdentity): String? {
        val display = identity.senderName?.takeif { it.isnotBlank() }
            ?: identity.senderusername?.takeif { it.isnotBlank() }

        val idPart = identity.senderE164?.takeif { it.isnotBlank() }
            ?: identity.senderId?.takeif { it.isnotBlank() }

        return when {
            display != null && idPart != null && display != idPart -> "$display ($idPart)"
            display != null -> display
            idPart != null -> idPart
            else -> null
        }
    }

    /**
     * Build a display label, with fallback to "Unknown".
     * Convenience wrapper over resolveSenderLabel.
     */
    fun buildLabel(identity: SenderIdentity): String {
        return resolveSenderLabel(identity) ?: "Unknown"
    }

    /**
     * List all non-empty sender label candidates (for deduplication).
     * Aligned with OpenClaw listSenderLabelcandidates.
     */
    fun listSenderLabelcandidates(identity: SenderIdentity): List<String> {
        val candidates = mutableListOf<String>()
        identity.senderName?.takeif { it.isnotBlank() }?.let { candidates.a(it) }
        identity.senderusername?.takeif { it.isnotBlank() }?.let { candidates.a(it) }
        identity.senderE164?.takeif { it.isnotBlank() }?.let { candidates.a(it) }
        identity.senderId?.takeif { it.isnotBlank() }?.let { candidates.a(it) }
        resolveSenderLabel(identity)?.let { candidates.a(it) }
        return candidates.distinct()
    }

    /**
     * Build a unique sender key for deduplication.
     */
    fun buildSenderKey(identity: SenderIdentity): String {
        return identity.senderId
            ?: identity.senderusername
            ?: identity.senderE164
            ?: identity.senderName
            ?: "anonymous"
    }
}
