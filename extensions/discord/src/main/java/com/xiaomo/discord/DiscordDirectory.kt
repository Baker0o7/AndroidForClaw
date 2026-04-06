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
 * Discord 目录Service
 * 参考:
 * - OpenClaw Discord directory section
 * - Feishu FeishuDirectory.kt
 *
 * Feature:
 * - listPeers: List DM Contact
 * - listGroups: List Guild/Channel
 * - Autodiscover
 */
class DiscordDirectory(private val client: DiscordClient) {
    companion object {
        private const val TAG = "DiscordDirectory"
    }

    /**
     * List DM Contact (从Config)
     */
    suspend fun listPeersFromConfig(config: DiscordConfig): List<DirectoryPeer> = withContext(Dispatchers.IO) {
        val peers = mutableListOf<DirectoryPeer>()

        try {
            val allowFrom = config.dm?.allowFrom ?: emptyList()

            allowFrom.forEach { userId ->
                // TODO: Can通过 Discord API GetUserInfo
                // GET /users/{userId}
                peers.add(
                    DirectoryPeer(
                        id = userId,
                        name = userId, // 暂时use ID 作为Name
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
     * ListGroup (从Config)
     */
    suspend fun listGroupsFromConfig(config: DiscordConfig): List<DirectoryGroup> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<DirectoryGroup>()

        try {
            val guilds = config.guilds ?: emptyMap()

            guilds.forEach { (guildId, guildConfig) ->
                // Get Guild Info
                val guildresult = client.getGuild(guildId)
                val guildName = if (guildresult.isSuccess) {
                    guildresult.getOrNull()?.get("name")?.asString ?: guildId
                } else {
                    guildId
                }

                // AddConfig的 Channels
                val channels = guildConfig.channels ?: emptyList()
                channels.forEach { channelId ->
                    val channelresult = client.getChannel(channelId)
                    val channelName = if (channelresult.isSuccess) {
                        channelresult.getOrNull()?.get("name")?.asString ?: channelId
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
     * List DM Contact (实时)
     * Need扫描most近的 DM
     */
    suspend fun listPeersLive(): List<DirectoryPeer> = withContext(Dispatchers.IO) {
        // Discord Bot API does not provide a direct endpoint for listing DM peers.
        // DM relationships are tracked via Gateway MESSAGE_CREATE events.
        // Fall back to config-based listing.
        Log.d(TAG, "Live peer listing: Discord Bot API does not expose DM list, use config-based listing")
        emptyList()
    }

    /**
     * ListGroup (实时)
     * 扫描 Bot 所在的All Guild 和 Channel
     */
    suspend fun listGroupsLive(): List<DirectoryGroup> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<DirectoryGroup>()

        try {
            val guildsresult = client.getUserGuilds()
            if (guildsresult.isFailure) {
                Log.e(TAG, "Failed to get guilds: ${guildsresult.exceptionOrNull()?.message}")
                return@withContext groups
            }

            val guilds = guildsresult.getOrNull() ?: return@withContext groups

            for (guildElement in guilds) {
                val guild = guildElement.asJsonObject
                val guildId = guild.get("id")?.asString ?: continue
                val guildName = guild.get("name")?.asString ?: guildId

                val channelsresult = client.getGuildChannels(guildId)
                if (channelsresult.isFailure) continue

                val channels = channelsresult.getOrNull() ?: continue
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
     * ParseUser白名单
     */
    suspend fun resolveUserAllowlist(
        entries: List<String>
    ): List<ResolvedUser> = withContext(Dispatchers.IO) {
        entries.map { entry ->
            try {
                // AttemptGetUserInfo
                // TODO: Need specific permission for Discord APIGetUserInfo
                // 暂时Return基本Info
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
     * ParseChannel白名单
     */
    suspend fun resolveChannelAllowlist(
        entries: List<String>
    ): List<ResolvedChannel> = withContext(Dispatchers.IO) {
        entries.map { entry ->
            try {
                // AttemptParse为 Guild ID 或 Channel ID
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
 * 目录Contact
 */
data class DirectoryPeer(
    val id: String,
    val name: String,
    val type: String // "user"
)

/**
 * 目录Group
 */
data class DirectoryGroup(
    val id: String,
    val name: String,
    val type: String, // "channel", "thread"
    val guildId: String? = null,
    val guildName: String? = null
)

/**
 * Parse的User
 */
data class ResolvedUser(
    val input: String,
    val resolved: Boolean,
    val id: String?,
    val name: String?,
    val note: String?
)

/**
 * Parse的Channel
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
