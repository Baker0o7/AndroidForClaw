/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import com.google.gson.annotations.SerializedName

/**
 * Discord Config
 * Reference OpenClaw Discord extension config structure
 */
data class DiscordConfig(
    @SerializedName("enabled")
    val enabled: Boolean = false,

    @SerializedName("token")
    val token: String? = null,

    @SerializedName("name")
    val name: String? = null,

    // DM (Private Message) Policy
    @SerializedName("dm")
    val dm: DmConfig? = null,

    // Group Policy
    @SerializedName("groupPolicy")
    val groupPolicy: String? = null, // "open", "allowlist", "denylist"

    // Guild (Server) Config
    @SerializedName("guilds")
    val guilds: Map<String, GuildConfig>? = null,

    // Reply Mode
    @SerializedName("replyToMode")
    val replyToMode: String? = null, // "off", "always", "threads"

    // Multi-Account Support
    @SerializedName("accounts")
    val accounts: Map<String, DiscordAccountConfig>? = null
) {
    data class DmConfig(
        @SerializedName("policy")
        val policy: String = "pairing", // "open", "pairing", "allowlist", "denylist"

        @SerializedName("allowFrom")
        val allowFrom: List<String> = emptyList()
    )

    data class GuildConfig(
        @SerializedName("channels")
        val channels: List<String>? = null, // Channel IDs

        @SerializedName("requireMention")
        val requireMention: Boolean = true,

        @SerializedName("toolPolicy")
        val toolPolicy: String? = null // "default", "restricted", "full"
    )

    data class DiscordAccountConfig(
        @SerializedName("enabled")
        val enabled: Boolean = true,

        @SerializedName("token")
        val token: String? = null,

        @SerializedName("name")
        val name: String? = null,

        @SerializedName("dm")
        val dm: DmConfig? = null,

        @SerializedName("guilds")
        val guilds: Map<String, GuildConfig>? = null
    )

    companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}

/**
 * Discord Account Info
 */
data class DiscordAccount(
    val accountId: String,
    val token: String,
    val name: String?,
    val config: DiscordConfig,
    val enabled: Boolean = true
)
