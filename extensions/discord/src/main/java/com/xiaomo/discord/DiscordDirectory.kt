/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discord Directory Service
 * Reference:
 * - OpenClaw Discord directory section
 * - Feishu FeishuDirectory.kt
 *
 * Feature:
 * - listPeers: List DM Contacts
 * - listGroups: List Guilds/Channels
 * - Auto Discover
 */
class DiscordDirectory(private val client: DiscordClient) {
    companion object {
        private const val TAG = "DiscordDirectory"
    }

    /**
     * List DM Contacts (from Config)
     */
    suspend fun listPeersFromConfig(config: DiscordConfig): List<DirectoryPeer> = withContext(Dispatchers.IO) {
        val peers = mutableListOf<DirectoryPeer>()

        try {
            val allowFrom = config.dm?.allowFrom ?: emptyList()

            allowFrom.forEach { userId ->
                // TODO: Can get user info via Discord API
                // GET /users/{userId}
                peers.add(
                    DirectoryPeer(
                        id = userId,
                        name = userId, // Use ID as name temporarily
                        type = "user"
                    )
                )
            }

            Log.d(TAG, "Listed ${peers.size} peers from config")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing peers from config", e)
        }

        peers
    }

    /**
     * List Groups (from Config)
     */
    suspend fun listGroupsFromConfig(config: DiscordConfig): List<DirectoryGroup> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<DirectoryGroup>()

        try {
            val guilds = config.guilds ?: emptyMap()

            guilds.forEach { (guildId, guildConfig) ->
                // Get Guild Info
                val guildResult = client.getGuild(guildId)
                val guildName = if (guildResult.isSuccess) {
                    guildResult.getOrNull()?.get("name")?.asString ?: guildId
                } else {
                    guildId
                }

                // Add Configured Channels
                val channels = guildConfig.channels ?: emptyList()
                channels.forEach { channelId ->
                    val channelResult = client.getChannel(channelId)
                    val channelName = if (channelResult.isSuccess) {
                        channelResult.getOrNull()?.get("name")?.asString ?: channelId
                    } else {
                        channelId
                    }

                    groups.add(
                        DirectoryGroup(
                            id = channelId,
                            name = "$guildName / $channelName",
                            type = "channel",
                            guildId = guildId,
                            guildName = guildName
                        )
                    )
                }
            }

            Log.d(TAG, "Listed ${groups.size} groups from config")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing groups from config", e)
        }

        groups
    }

    /**
     * List DM Contacts (live)
     * Need to scan recent DMs
     */
    suspend fun listPeersLive(): List<DirectoryPeer> = withContext(Dispatchers.IO) {
        // Discord Bot API does not provide a direct endpoint for listing DM peers.
        // DM relationships are tracked via Gateway MESSAGE_CREATE events.
        // Fall back to config-based listing.
        Log.d(TAG, "Live peer listing: Discord Bot API does not expose DM list, use config-based listing")
        emptyList()
    }

    /**
     * List Groups (live)
     * Scan all Guilds and Channels the bot is in
     */
    suspend fun listGroupsLive(): List<DirectoryGroup> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<DirectoryGroup>()

        try {
            val guildsResult = client.getUserGuilds()
            if (guildsResult.isFailure) {
                Log.e(TAG, "Failed to get guilds: ${guildsResult.exceptionOrNull()?.message}")
                return@withContext groups
            }

            val guilds = guildsResult.getOrNull() ?: return@withContext groups

            for (guildElement in guilds) {
                val guild = guildElement.asJsonObject
                val guildId = guild.get("id")?.asString ?: continue
                val guildName = guild.get("name")?.asString ?: guildId

                val channelsResult = client.getGuildChannels(guildId)
                if (channelsResult.isFailure) continue

                val channels = channelsResult.getOrNull() ?: continue
                for (channelElement in channels) {
                    val channel = channelElement.asJsonObject
                    val channelType = channel.get("type")?.asInt ?: continue
                    // Only text channels (0) and announcement channels (5)
                    if (channelType != 0 && channelType != 5) continue

                    val channelId = channel.get("id")?.asString ?: continue
                    val channelName = channel.get("name")?.asString ?: channelId

                    groups.add(
                        DirectoryGroup(
                            id = channelId,
                            name = "$guildName / $channelName",
                            type = "channel",
                            guildId = guildId,
                            guildName = guildName
                        )
                    )
                }
            }

            Log.d(TAG, "Listed ${groups.size} groups live from ${guilds.size()} guilds")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing groups live", e)
        }

        groups
    }

    /**
     * Resolve User Allowlist
     */
    suspend fun resolveUserAllowlist(
        entries: List<String>
    ): List<ResolvedUser> = withContext(Dispatchers.IO) {
        entries.map { entry ->
            try {
                // Attempt to get user info
                // TODO: Need specific permission for Discord API to get user info
                // Return basic info temporarily
                ResolvedUser(
                    input = entry,
                    resolved = true,
                    id = entry,
                    name = null,
                    note = null
                )
            } catch (e: Exception) {
                ResolvedUser(
                    input = entry,
                    resolved = false,
                    id = null,
                    name = null,
                    note = e.message
                )
            }
        }
    }

    /**
     * Resolve Channel Allowlist
     */
    suspend fun resolveChannelAllowlist(
        entries: List<String>
    ): List<ResolvedChannel> = withContext(Dispatchers.IO) {
        entries.map { entry ->
            try {
                // Attempt to parse as Guild ID or Channel ID
                val result = client.getChannel(entry)

                if (result.isSuccess) {
                    val channel = result.getOrNull()
                    ResolvedChannel(
                        input = entry,
                        resolved = true,
                        channelId = channel?.get("id")?.asString,
                        channelName = channel?.get("name")?.asString,
                        guildId = channel?.get("guild_id")?.asString,
                        guildName = null,
                        note = null
                    )
                } else {
                    ResolvedChannel(
                        input = entry,
                        resolved = false,
                        channelId = null,
                        channelName = null,
                        guildId = null,
                        guildName = null,
                        note = result.exceptionOrNull()?.message
                    )
                }
            } catch (e: Exception) {
                ResolvedChannel(
                    input = entry,
                    resolved = false,
                    channelId = null,
                    channelName = null,
                    guildId = null,
                    guildName = null,
                    note = e.message
                )
            }
        }
    }
}

/**
 * Directory Contact
 */
data class DirectoryPeer(
    val id: String,
    val name: String,
    val type: String // "user"
)

/**
 * Directory Group
 */
data class DirectoryGroup(
    val id: String,
    val name: String,
    val type: String, // "channel", "thread"
    val guildId: String? = null,
    val guildName: String? = null
)

/**
 * Resolved User
 */
data class ResolvedUser(
    val input: String,
    val resolved: Boolean,
    val id: String?,
    val name: String?,
    val note: String?
)

/**
 * Resolved Channel
 */
data class ResolvedChannel(
    val input: String,
    val resolved: Boolean,
    val channelId: String?,
    val channelName: String?,
    val guildId: String?,
    val guildName: String?,
    val note: String?
)
