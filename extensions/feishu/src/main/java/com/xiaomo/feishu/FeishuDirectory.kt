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
 * 飞书Contact目录
 * Aligned with OpenClaw directory.ts
 *
 * Feature: 
 * - ListConfig的User和Group
 * - 从 API 实时GetUser和GroupList
 * - SupportSearch和Limit数量
 */
object FeishuDirectory {
    private const val TAG = "FeishuDirectory"

    /**
     * UserInfo
     */
    data class DirectoryPeer(
        val kind: String = "user",
        val id: String,
        val name: String? = null
    )

    /**
     * GroupInfo
     */
    data class DirectoryGroup(
        val kind: String = "group",
        val id: String,
        val name: String? = null
    )

    /**
     * ListConfig的User
     *
     * @param config 飞书Config
     * @param query SearchQuery(Optional)
     * @param limit Limit数量(Optional)
     * @return UserList
     */
    fun listConfiguredPeers(
        config: FeishuConfig,
        query: String? = null,
        limit: Int? = null
    ): List<DirectoryPeer> {
        val q = query?.trim()?.lowercase() ?: ""
        val ids = mutableSetOf<String>()

        // 从 allowFrom Get
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
     * ListConfig的Group
     *
     * @param config 飞书Config
     * @param query SearchQuery(Optional)
     * @param limit Limit数量(Optional)
     * @return GroupList
     */
    fun listConfiguredGroups(
        config: FeishuConfig,
        query: String? = null,
        limit: Int? = null
    ): List<DirectoryGroup> {
        val q = query?.trim()?.lowercase() ?: ""
        val ids = mutableSetOf<String>()

        // 从 groupAllowFrom Get
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
     * 从 API 实时GetUserList
     *
     * @param client Feishu Client
     * @param config 飞书Config
     * @param query SearchQuery(Optional)
     * @param limit Limit数量(Default 50)
     * @return UserList
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

                // SearchFilter
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
     * 从 API 实时GetGroupList
     *
     * @param client Feishu Client
     * @param config 飞书Config
     * @param query SearchQuery(Optional)
     * @param limit Limit数量(Default 50)
     * @return GroupList
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

                // SearchFilter
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
