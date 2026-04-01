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
    override val description = "获取User Info。不传 user_id 时Get currentUser自己的Info；传 user_id 时获取指定User的Info。" +
        "BackUser姓名、Avatar、邮箱、手机号、部门等Info。"

    override fun isEnabled() = config.enableCommonTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
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
                            return@withContext ToolResult.error(
                                "NonePermission查询该User Info。\n\n" +
                                "Description：使用User身份调用通讯录 API 时，可操作的Permission范围不受应用的通讯录Permission范围影响，" +
                                "而Yes受当前User的组织架构Visible范围影响。该范围限制了User在企业内Visible的组织架构数据范围。"
                            )
                        }
                        return@withContext ToolResult.error(errMsg.ifBlank { "Failed to get current user info" })
                    }
                    Log.i(TAG, "get_user: current user fetched successfully")
                    val response = JsonObject().apply {
                        add("user", result.getOrNull()?.getAsJsonObject("data"))
                    }
                    return@withContext ToolResult.success(response)
                } catch (invokeErr: Exception) {
                    // Check for error code 41050
                    if (invokeErr.message?.contains("41050") == true) {
                        return@withContext ToolResult.error(
                            "NonePermission查询该User Info。\n\n" +
                            "Description：使用User身份调用通讯录 API 时，可操作的Permission范围不受应用的通讯录Permission范围影响，" +
                            "而Yes受当前User的组织架构Visible范围影响。该范围限制了User在企业内Visible的组织架构数据范围。"
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
                        return@withContext ToolResult.error(
                            "NonePermission查询该User Info。\n\n" +
                            "Description：使用User身份调用通讯录 API 时，可操作的Permission范围不受应用的通讯录Permission范围影响，" +
                            "而Yes受当前User的组织架构Visible范围影响。该范围限制了User在企业内Visible的组织架构数据范围。\n\n" +
                            "Recommend：Please联系Admin调整当前User的组织架构Visible范围，或使用应用身份（tenant_access_token）调用 API。"
                        )
                    }
                    return@withContext ToolResult.error(errMsg.ifBlank { "Failed to get user info" })
                }
                Log.i(TAG, "get_user: user $userId fetched successfully")
                val response = JsonObject().apply {
                    add("user", result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("user"))
                }
                ToolResult.success(response)
            } catch (invokeErr: Exception) {
                if (invokeErr.message?.contains("41050") == true) {
                    return@withContext ToolResult.error(
                        "NonePermission查询该User Info。\n\n" +
                        "Description：使用User身份调用通讯录 API 时，可操作的Permission范围不受应用的通讯录Permission范围影响，" +
                        "而Yes受当前User的组织架构Visible范围影响。该范围限制了User在企业内Visible的组织架构数据范围。\n\n" +
                        "Recommend：Please联系Admin调整当前User的组织架构Visible范围，或使用应用身份（tenant_access_token）调用 API。"
                    )
                }
                throw invokeErr
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_get_user failed", e)
            ToolResult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "user_id" to PropertySchema("string", "User ID（Format如 ou_xxx）。若不传入，则Get currentUser自己的Info"),
                    "user_id_type" to PropertySchema("string", "User ID Type（Default open_id）",
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
    override val description = "Search员工Info（通过Off键词Search姓名、手机号、邮箱）。Back匹配的员工List，" +
        "包含姓名、部门、open_id 等Info。"

    override fun isEnabled() = config.enableCommonTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args["query"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: query")
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
                return@withContext ToolResult.error(
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
            ToolResult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_search_user failed", e)
            ToolResult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "SearchOff键词，用于匹配User名（Required）"),
                    "page_size" to PropertySchema("integer", "Page Size，控制每次Back的UserCount（Default20，Maximum200）"),
                    "page_token" to PropertySchema("string", "分页ID。首次RequestNone需填写；当Back结果Medium包含 page_token 时，可传入该ValueContinueRequest下一页")
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
        return getAllTools().filter { it.isEnabled() }.map { it.getToolDefinition() }
    }
}
