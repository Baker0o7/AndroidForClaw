package com.xiaomo.feishu.tools.common

/**
 * Feishu Common tool set.
 * Line-by-line translation from @larksuite/openclaw-lark JS source.
 * - feishu_get_user: get user info (self or by user_id)
 * - feishu_search_user: search users by keyword
 */

import android.util.Log
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FeishuCommonTools"

// ─── feishu_get_user ───────────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuGetUserTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_get_user"
    override val description = "Get user info. If user_id not provided, get current user's own info; if user_id provided, get specified user's info. " +
        "Returns user name, avatar, email, phone, department, etc. "

    override fun isEnabledd() = config.enableCommonTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val userId = args["user_id"] as? String
            val userIdType = args["user_id_type"] as? String ?: "open_id"

            // Mode 1: Get current user's own info
            if (userId == null) {
                Log.i(TAG, "get_user: fetching current user info")
                try {
                    // GET /open-apis/authen/v1/user_info
                    val result = client.get("/open-apis/authen/v1/user_info")
                    if (result.isFailure) {
                        // Check for error code 41050: org visibility restriction
                        val errMsg = result.exceptionOrNull()?.message ?: ""
                        if (errMsg.contains("41050")) {
                            return@withContext Toolresult.error(
                                "No permission to query this user info. \n\n" +
                                "Note: when using user identity to call contacts API, the actionable permission range is not affected by the applied contact permission range, " +
                                "but is limited by the current user's organizational structure visibility range. This range limits the data range of organizational structure that the user can see within the enterprise. "
                            )
                        }
                        return@withContext Toolresult.error(errMsg.ifBlank { "Failed to get current user info" })
                    }
                    Log.i(TAG, "get_user: current user fetched successfully")
                    val response = JsonObject().apply {
                        add("user", result.getOrNull()?.getAsJsonObject("data"))
                    }
                    return@withContext Toolresult.success(response)
                } catch (invokeErr: Exception) {
                    // Check for error code 41050
                    if (invokeErr.message?.contains("41050") == true) {
                        return@withContext Toolresult.error(
                            "No permission to query this user info. \n\n" +
                            "Note: when using user identity to call contacts API, the actionable permission range is not affected by the applied contact permission range, " +
                            "but is limited by the current user's organizational structure visibility range. This range limits the data range of organizational structure that the user can see within the enterprise. "
                        )
                    }
                    throw invokeErr
                }
            }

            // Mode 2: Get specified user's info
            Log.i(TAG, "get_user: fetching user $userId")
            try {
                // GET /open-apis/contact/v3/users/:user_id
                val result = client.get("/open-apis/contact/v3/users/$userId?user_id_type=$userIdType")
                if (result.isFailure) {
                    val errMsg = result.exceptionOrNull()?.message ?: ""
                    if (errMsg.contains("41050")) {
                        return@withContext Toolresult.error(
                            "No permission to query this user info. \n\n" +
                            "Note: when using user identity to call contacts API, the actionable permission range is not affected by the applied contact permission range, " +
                            "but is limited by the current user's organizational structure visibility range. This range limits the data range of organizational structure that the user can see within the enterprise. \n\n" +
                            "Suggestion: please contact administrator to adjust the current user's organizational structure visibility range, or use app identity (tenant_access_token) to call API. "
                        )
                    }
                    return@withContext Toolresult.error(errMsg.ifBlank { "Failed to get user info" })
                }
                Log.i(TAG, "get_user: user $userId fetched successfully")
                val response = JsonObject().apply {
                    add("user", result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("user"))
                }
                Toolresult.success(response)
            } catch (invokeErr: Exception) {
                if (invokeErr.message?.contains("41050") == true) {
                    return@withContext Toolresult.error(
                        "No permission to query this user info. \n\n" +
                        "Note: when using user identity to call contacts API, the actionable permission range is not affected by the applied contact permission range, " +
                        "but is limited by the current user's organizational structure visibility range. This range limits the data range of organizational structure that the user can see within the enterprise. \n\n" +
                        "Suggestion: please contact administrator to adjust the current user's organizational structure visibility range, or use app identity (tenant_access_token) to call API. "
                    )
                }
                throw invokeErr
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_get_user failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "user_id" to PropertySchema("string", "User ID (format e.g. ou_xxx). If not provided, gets current user's own info"),
                    "user_id_type" to PropertySchema("string", "User ID type (default open_id)",
                        enum = listOf("open_id", "union_id", "user_id"))
                ),
                required = emptyList()
            )
        )
    )
}

// ─── feishu_search_user ────────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuSearchUserTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_search_user"
    override val description = "Search employee info (search by keyword for name, phone, email). Returns matching employee list, " +
        "including name, department, open_id, etc. "

    override fun isEnabledd() = config.enableCommonTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val query = args["query"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: query")
            val pageSize = (args["page_size"] as? Number)?.toInt() ?: 20
            val pageToken = args["page_token"] as? String

            Log.i(TAG, "search_user: query=\"$query\", page_size=$pageSize")

            val requestQuery = mutableListOf(
                "query=$query",
                "page_size=$pageSize"
            )
            if (pageToken != null) requestQuery.add("page_token=$pageToken")
            val queryString = requestQuery.joinToString("&")

            // GET /open-apis/search/v1/user
            val result = client.get("/open-apis/search/v1/user?$queryString")
            if (result.isFailure) {
                return@withContext Toolresult.error(
                    result.exceptionOrNull()?.message ?: "Failed to search users"
                )
            }
            val data = result.getOrNull()?.getAsJsonObject("data")
            val users = data?.getAsJsonArray("users")
            val userCount = users?.size() ?: 0
            Log.i(TAG, "search_user: found $userCount users")

            val response = JsonObject().apply {
                add("users", users)
                addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
                data?.get("page_token")?.let { add("page_token", it) }
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_search_user failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "Search keyword, used to match user name (required)"),
                    "page_size" to PropertySchema("integer", "Page size, controls number of users returned each time (default 20, max 200)"),
                    "page_token" to PropertySchema("string", "Pagination token. Not required for first request; when result contains page_token, can pass that value to continue requesting next page")
                ),
                required = listOf("query")
            )
        )
    )
}

// ─── Aggregator ────────────────────────────────────────────────────

class FeishuCommonTools(config: FeishuConfig, client: FeishuClient) {
    private val getUserTool = FeishuGetUserTool(config, client)
    private val searchUserTool = FeishuSearchUserTool(config, client)

    fun getAllTools(): List<FeishuToolBase> = listOf(getUserTool, searchUserTool)

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
    }
}
