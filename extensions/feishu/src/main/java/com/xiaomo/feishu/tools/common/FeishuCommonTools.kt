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
    override val description = "GetUserInfo. 不传 user_id 时Get当FrontUser自己的Info；传 user_id 时Get指定User的Info. " +
        "ReturnUser姓名、头Like、邮箱、手机号、Department等Info. "

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
                                "NonePermissionQuery该UserInfo. \n\n" +
                                "illustrate: useUserIdentity call contacts API 时, 可Action的PermissionRange不受apply的通讯录PermissionRange影响, " +
                                "而Yes受当FrontUser的Group织架构可见Range影响. 该RangeLimit了User在企业Inside可见的Group织架构DataRange. "
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
                            "NonePermissionQuery该UserInfo. \n\n" +
                            "illustrate: useUserIdentity call contacts API 时, 可Action的PermissionRange不受apply的通讯录PermissionRange影响, " +
                            "而Yes受当FrontUser的Group织架构可见Range影响. 该RangeLimit了User在企业Inside可见的Group织架构DataRange. "
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
                            "NonePermissionQuery该UserInfo. \n\n" +
                            "illustrate: useUserIdentity call contacts API 时, 可Action的PermissionRange不受apply的通讯录PermissionRange影响, " +
                            "而Yes受当FrontUser的Group织架构可见Range影响. 该RangeLimit了User在企业Inside可见的Group织架构DataRange. \n\n" +
                            "suggest: 请联系Manage员adjust当FrontUser的Group织架构可见Range, 或useapply身份(tenant_access_token)call API. "
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
                        "NonePermissionQuery该UserInfo. \n\n" +
                        "illustrate: useUserIdentity call contacts API 时, 可Action的PermissionRange不受apply的通讯录PermissionRange影响, " +
                        "而Yes受当FrontUser的Group织架构可见Range影响. 该RangeLimit了User在企业Inside可见的Group织架构DataRange. \n\n" +
                        "suggest: 请联系Manage员adjust当FrontUser的Group织架构可见Range, 或useapply身份(tenant_access_token)call API. "
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
                    "user_id" to PropertySchema("string", "User ID(格式such as ou_xxx). 若不传入, 则Get当FrontUser自己的Info"),
                    "user_id_type" to PropertySchema("string", "User ID Type(Default open_id)",
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
    override val description = "Search员工Info(通过关Key词Search姓名、手机号、邮箱). Returnmatch的员工List, " +
        "Contains姓名、Department、open_id 等Info. "

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
                    "query" to PropertySchema("string", "Search关Key词, 用于matchUser名(Required)"),
                    "page_size" to PropertySchema("integer", "Page size, 控制each timeReturn的User数量(Default20, Max200)"),
                    "page_token" to PropertySchema("string", "Paginate标识. 首次RequestNone需填写；当Returnresult中Contains page_token 时, 可传入该ValueContinueRequestDown一页")
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
