package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feishu Contact Directory
 * Aligned with OpenClaw directory.ts
 *
 * Feature: 
 * - List configured users and groups
 * - Get user and group list from API in real-time
 * - Support search and limit
 */
object FeishuDirectory {
    private const val TAG = "FeishuDirectory"

    /**
     * User info
     */
    data class DirectoryPeer(
        val kind: String = "user",
        val id: String,
        val name: String? = null
    )

    /**
     * Group info
     */
    data class DirectoryGroup(
        val kind: String = "group",
        val id: String,
        val name: String? = null
    )

    /**
     * List configured users
     *
     * @param config Feishu config
     * @param query Search query (optional)
     * @param limit Limit number (optional)
     * @return User list
     */
    fun listConfiguredPeers(
        config: FeishuConfig,
        query: String? = null,
        limit: Int? = null
    ): List<DirectoryPeer> {
        val q = query?.trim()?.lowercase() ?: ""
        val ids = mutableSetOf<String>()

        // Get from allowFrom
        for (entry in config.allowFrom) {
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty() && trimmed != "*") {
                ids.add(trimmed)
            }
        }

        // Filter and limit
        return ids
            .filter { id -> q.isEmpty() || id.lowercase().contains(q) }
            .let { list -> if (limit != null && limit > 0) list.take(limit) else list }
            .map { id -> DirectoryPeer(id = id) }
    }

    /**
     * List configured groups
     *
     * @param config Feishu config
     * @param query Search query (optional)
     * @param limit Limit number (optional)
     * @return Group list
     */
    fun listConfiguredGroups(
        config: FeishuConfig,
        query: String? = null,
        limit: Int? = null
    ): List<DirectoryGroup> {
        val q = query?.trim()?.lowercase() ?: ""
        val ids = mutableSetOf<String>()

        // Get from groupAllowFrom
        for (entry in config.groupAllowFrom) {
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty() && trimmed != "*") {
                ids.add(trimmed)
            }
        }

        // Filter and limit
        return ids
            .filter { id -> q.isEmpty() || id.lowercase().contains(q) }
            .let { list -> if (limit != null && limit > 0) list.take(limit) else list }
            .map { id -> DirectoryGroup(id = id) }
    }

    /**
     * Get user list from API in real-time
     *
     * @param client Feishu Client
     * @param config Feishu config
     * @param query Search query (optional)
     * @param limit Limit number (default 50)
     * @return User list
     */
    suspend fun listPeersLive(
        client: FeishuClient,
        config: FeishuConfig,
        query: String? = null,
        limit: Int = 50
    ): List<DirectoryPeer> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("/open-apis/contact/v3/users?page_size=${minOf(limit, 50)}")

            if (response.isFailure) {
                Log.w(TAG, "Failed to list users live: ${response.exceptionOrNull()?.message}")
                return@withContext listConfiguredPeers(config, query, limit)
            }

            val data = response.getOrNull()
            val code = data?.get("code")?.asInt ?: -1

            if (code != 0) {
                Log.w(TAG, "List users API error: ${data?.get("msg")?.asString}")
                return@withContext listConfiguredPeers(config, query, limit)
            }

            val dataMap = data?.getAsJsonObject("data")
            val items = dataMap?.getAsJsonArray("items") ?: return@withContext emptyList()

            val q = query?.trim()?.lowercase() ?: ""
            val peers = mutableListOf<DirectoryPeer>()

            for (item in items) {
                val user = item.asJsonObject
                val openId = user.get("open_id")?.asString ?: continue
                val name = user.get("name")?.asString ?: ""

                // Search filter
                if (q.isNotEmpty() && !openId.lowercase().contains(q) && !name.lowercase().contains(q)) {
                    continue
                }

                peers.add(DirectoryPeer(id = openId, name = name.ifEmpty { null }))

                if (peers.size >= limit) {
                    break
                }
            }

            return@withContext peers

        } catch (e: Exception) {
            Log.e(TAG, "Exception listing users live", e)
            return@withContext listConfiguredPeers(config, query, limit)
        }
    }

    /**
     * Get group list from API in real-time
     *
     * @param client Feishu Client
     * @param config Feishu config
     * @param query Search query (optional)
     * @param limit Limit number (default 50)
     * @return Group list
     */
    suspend fun listGroupsLive(
        client: FeishuClient,
        config: FeishuConfig,
        query: String? = null,
        limit: Int = 50
    ): List<DirectoryGroup> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("/open-apis/im/v1/chats?page_size=${minOf(limit, 100)}")

            if (response.isFailure) {
                Log.w(TAG, "Failed to list groups live: ${response.exceptionOrNull()?.message}")
                return@withContext listConfiguredGroups(config, query, limit)
            }

            val data = response.getOrNull()
            val code = data?.get("code")?.asInt ?: -1

            if (code != 0) {
                Log.w(TAG, "List groups API error: ${data?.get("msg")?.asString}")
                return@withContext listConfiguredGroups(config, query, limit)
            }

            val dataMap = data?.getAsJsonObject("data")
            val items = dataMap?.getAsJsonArray("items") ?: return@withContext emptyList()

            val q = query?.trim()?.lowercase() ?: ""
            val groups = mutableListOf<DirectoryGroup>()

            for (item in items) {
                val chat = item.asJsonObject
                val chatId = chat.get("chat_id")?.asString ?: continue
                val name = chat.get("name")?.asString ?: ""

                // Search filter
                if (q.isNotEmpty() && !chatId.lowercase().contains(q) && !name.lowercase().contains(q)) {
                    continue
                }

                groups.add(DirectoryGroup(id = chatId, name = name.ifEmpty { null }))

                if (groups.size >= limit) {
                    break
                }
            }

            return@withContext groups

        } catch (e: Exception) {
            Log.e(TAG, "Exception listing groups live", e)
            return@withContext listConfiguredGroups(config, query, limit)
        }
    }
}
