package com.xiaomo.feishu.tools.bitable

/**
 * OpenClaw Source Reference:
 * - @larksuite/openclaw-lark bitable tools
 *   - src/tools/oapi/bitable/app.js
 *   - src/tools/oapi/bitable/app-table.js
 *   - src/tools/oapi/bitable/app-table-field.js
 *   - src/tools/oapi/bitable/app-table-record.js
 *   - src/tools/oapi/bitable/app-table-view.js
 *
 * AndroidForClaw adaptation: LINE-BY-LINE translation of official JS source.
 * Each tool corresponds to one API resource with multiple actions.
 */

import android.util.Log
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
// Aggregator
// ─────────────────────────────────────────────────────────────

class FeishuBitableTools(config: FeishuConfig, client: FeishuClient) {
    private val appTool = FeishuBitableAppTool(config, client)
    private val tableTool = FeishuBitableAppTableTool(config, client)
    private val fieldTool = FeishuBitableAppTableFieldTool(config, client)
    private val recordTool = FeishuBitableAppTableRecordTool(config, client)
    private val viewTool = FeishuBitableAppTableViewTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(appTool, tableTool, fieldTool, recordTool, viewTool)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabled() }.map { it.getToolDefinition() }
    }
}

// ─────────────────────────────────────────────────────────────
// Helper: build query string from param pairs
// ─────────────────────────────────────────────────────────────

private fun buildQuery(vararg pairs: Pair<String, Any?>): String {
    val parts = pairs.mapNotNull { (k, v) ->
        if (v != null) "$k=$v" else null
    }
    return if (parts.isNotEmpty()) "?" + parts.joinToString("&") else ""
}

// ═══════════════════════════════════════════════════════════════
// 1. FeishuBitableAppTool — 多维表格应用管理
//    Translated from: app.js
//    Actions: create, get, list, patch, copy
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app"
    override val description =
        "【以User身份】FeishuBitable应用ManagedTool。当User要求创建/查询/ManagedBitable时使用。" +
        "Actions: create（创建Bitable）, get（获取Bitable元数据）, list（Column出Bitable）, " +
        "patch（Update元数据）, delete（DeleteBitable）, copy（CopyBitable）。"

    override fun isEnabled() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: action")

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "create" -> {
                    val name = args["name"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: name")
                    val folderToken = args["folder_token"] as? String

                    Log.i(TAG, "create: name=$name, folder_token=${folderToken ?: "my_space"}")

                    val data = mutableMapOf<String, Any>("name" to name)
                    if (folderToken != null) {
                        data["folder_token"] = folderToken
                    }

                    val res = client.post("/open-apis/bitable/v1/apps", data)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created app ${json?.getAsJsonObject("data")?.getAsJsonObject("app")?.get("app_token")}")
                    ToolResult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val appToken = args["app_token"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: app_token")

                    Log.i(TAG, "get: app_token=$appToken")

                    val res = client.get("/open-apis/bitable/v1/apps/$appToken")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "get: returned app $appToken")
                    ToolResult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // LIST — 使用 Drive API 筛选 bitable 类型文件
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "list" -> {
                    val folderToken = args["folder_token"] as? String
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String

                    Log.i(TAG, "list: folder_token=${folderToken ?: "my_space"}, page_size=${pageSize ?: 50}")

                    val query = buildQuery(
                        "folder_token" to (folderToken ?: ""),
                        "page_size" to pageSize,
                        "page_token" to pageToken
                    )
                    val res = client.get("/open-apis/drive/v1/files$query")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")

                    // 筛选出 type === "bitable" 的文件
                    val files = data?.getAsJsonArray("files")
                    val bitables = mutableListOf<Any>()
                    if (files != null) {
                        for (f in files) {
                            val obj = f.asJsonObject
                            if (obj.get("type")?.asString == "bitable") {
                                bitables.add(obj)
                            }
                        }
                    }

                    Log.i(TAG, "list: returned ${bitables.size} bitable apps")
                    ToolResult.success(mapOf(
                        "apps" to bitables,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString }
                    ))
                }

                // -----------------------------------------------------------------
                // PATCH
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "patch" -> {
                    val appToken = args["app_token"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: app_token")
                    val name = args["name"] as? String
                    val isAdvanced = args["is_advanced"] as? Boolean

                    Log.i(TAG, "patch: app_token=$appToken, name=$name, is_advanced=$isAdvanced")

                    val updateData = mutableMapOf<String, Any>()
                    if (name != null) updateData["name"] = name
                    if (isAdvanced != null) updateData["is_advanced"] = isAdvanced

                    val res = client.patch("/open-apis/bitable/v1/apps/$appToken", updateData)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated app $appToken")
                    ToolResult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // COPY (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "copy" -> {
                    val appToken = args["app_token"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: app_token")
                    val name = args["name"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: name")
                    val folderToken = args["folder_token"] as? String

                    Log.i(TAG, "copy: app_token=$appToken, name=$name, folder_token=${folderToken ?: "my_space"}")

                    val data = mutableMapOf<String, Any>("name" to name)
                    if (folderToken != null) {
                        data["folder_token"] = folderToken
                    }

                    val res = client.post("/open-apis/bitable/v1/apps/$appToken/copy", data)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "copy: created copy ${json?.getAsJsonObject("data")?.getAsJsonObject("app")?.get("app_token")}")
                    ToolResult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                else -> ToolResult.error("Unknown action: $action. Supported: create, get, list, patch, copy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "操作Type",
                        enum = listOf("create", "get", "list", "patch", "copy")
                    ),
                    "app_token" to PropertySchema("string", "Bitable的Unique ID token（get/patch/copy Required）"),
                    "name" to PropertySchema("string", "BitableName（create/copy Required，patch Optional）"),
                    "folder_token" to PropertySchema("string", "所在文件夹 token（Default创建在我的Empty间）（create/copy/list Optional）"),
                    "is_advanced" to PropertySchema("boolean", "YesNoOn启AdvancedPermission（patch Optional）"),
                    "page_size" to PropertySchema("number", "每页Count，Default 50，Maximum 200（list Optional）"),
                    "page_token" to PropertySchema("string", "分页标记（list Optional）")
                ),
                required = listOf("action")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableAppTool"
    }
}

// ═══════════════════════════════════════════════════════════════
// 2. FeishuBitableAppTableTool — 数据表管理
//    Translated from: app-table.js
//    Actions: create, list, patch, batch_create
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table"
    override val description =
        "【以User身份】FeishuBitable数据表ManagedTool。当User要求创建/查询/Managed数据表时使用。" +
        "\n\nActions: create（创建数据表，Optional择在创建时传入 fields Array定义Field，或后续逐个添加）, list（Column出所Has数据表）, patch（Update数据表）, batch_create（批量创建）。" +
        "\n\n【Field定义方式】支持两种模式：1) 明确需求时，在 create Medium通过 table.fields One-time定义所HasField（减少 API 调用）；2) 探索式场景时，使用Default表 + feishu_bitable_app_table_field 逐步ModifiedField（更稳定，易调整）。"

    override fun isEnabled() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: app_token")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // 特殊处理：复选框（type=7）和超链接（type=15）字段不能传 property
                // -----------------------------------------------------------------
                "create" -> {
                    @Suppress("UNCHECKED_CAST")
                    val table = args["table"] as? Map<String, Any?>
                        ?: return@withContext ToolResult.error("Missing required parameter: table")

                    Log.i(TAG, "create: app_token=$appToken, table_name=${table["name"]}, fields_count=${(table["fields"] as? List<*>)?.size ?: 0}")

                    // 特殊处理：复选框（type=7）和超链接（type=15）字段不能传 property
                    val tableData = table.toMutableMap()
                    @Suppress("UNCHECKED_CAST")
                    val fields = tableData["fields"] as? List<Map<String, Any?>>
                    if (fields != null) {
                        tableData["fields"] = fields.map { field ->
                            val type = (field["type"] as? Number)?.toInt()
                            if ((type == 7 || type == 15) && field.containsKey("property")) {
                                val fieldTypeName = if (type == 15) "URL" else "Checkbox"
                                Log.w(TAG, "create: $fieldTypeName field (type=$type, name=\"${field["field_name"]}\") detected with property parameter. " +
                                    "Removing property to avoid API error. " +
                                    "$fieldTypeName fields must omit the property parameter entirely.")
                                field.toMutableMap().apply { remove("property") }
                            } else {
                                field
                            }
                        }
                    }

                    val body = mapOf("table" to tableData)
                    val res = client.post(basePath, body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "create: created table ${data?.get("table_id")}")
                    ToolResult.success(mapOf(
                        "table_id" to data?.get("table_id")?.let { if (it.isJsonNull) null else it.asString },
                        "default_view_id" to data?.get("default_view_id")?.let { if (it.isJsonNull) null else it.asString },
                        "field_id_list" to data?.getAsJsonArray("field_id_list")
                    ))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "list" -> {
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String

                    Log.i(TAG, "list: app_token=$appToken, page_size=${pageSize ?: 50}")

                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken
                    )
                    val res = client.get("$basePath$query")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} tables")
                    ToolResult.success(mapOf(
                        "tables" to data?.getAsJsonArray("items"),
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString }
                    ))
                }

                // -----------------------------------------------------------------
                // PATCH
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "patch" -> {
                    val tableId = args["table_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: table_id")
                    val tableName = args["name"] as? String

                    Log.i(TAG, "patch: app_token=$appToken, table_id=$tableId, name=$tableName")

                    val body = mutableMapOf<String, Any>()
                    if (tableName != null) body["name"] = tableName

                    val res = client.patch("$basePath/$tableId", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated table $tableId")
                    ToolResult.success(mapOf(
                        "name" to json?.getAsJsonObject("data")?.get("name")?.let { if (it.isJsonNull) null else it.asString }
                    ))
                }

                // -----------------------------------------------------------------
                // BATCH_CREATE (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "batch_create" -> {
                    @Suppress("UNCHECKED_CAST")
                    val tables = args["tables"] as? List<Map<String, Any?>>

                    if (tables == null || tables.isEmpty()) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "tables is required and cannot be empty"
                        ))
                    }

                    Log.i(TAG, "batch_create: app_token=$appToken, tables_count=${tables.size}")

                    val body = mapOf("tables" to tables)
                    val res = client.post("$basePath/batch_create", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_create: created ${tables.size} tables in app $appToken")
                    ToolResult.success(mapOf(
                        "table_ids" to json?.getAsJsonObject("data")?.getAsJsonArray("table_ids")
                    ))
                }

                else -> ToolResult.error("Unknown action: $action. Supported: create, list, patch, batch_create")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "操作Type",
                        enum = listOf("create", "list", "patch", "batch_create")
                    ),
                    "app_token" to PropertySchema("string", "Bitable token"),
                    "table_id" to PropertySchema("string", "数据表 ID（patch Required）"),
                    "name" to PropertySchema("string", "新的表名（patch Optional）"),
                    "table" to PropertySchema(
                        "object", "数据表定义（create Required），含 name、default_view_name、fields",
                        properties = mapOf(
                            "name" to PropertySchema("string", "数据表Name"),
                            "default_view_name" to PropertySchema("string", "DefaultViewName"),
                            "fields" to PropertySchema("array", "FieldList（Optional，但强烈Recommend在创建表时就传入所HasField，避免后续逐个添加）。不传则创建Empty表。",
                                items = PropertySchema("object", "Field定义，含 field_name、type、property"))
                        )
                    ),
                    "tables" to PropertySchema(
                        "array", "要批量创建的数据表List（batch_create Required）",
                        items = PropertySchema("object", "数据表定义，含 name")
                    ),
                    "page_size" to PropertySchema("number", "每页Count，Default 50，Maximum 100（list Optional）"),
                    "page_token" to PropertySchema("string", "分页标记（list Optional）")
                ),
                required = listOf("action", "app_token")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableAppTableTool"
    }
}

// ═══════════════════════════════════════════════════════════════
// 3. FeishuBitableAppTableFieldTool — 字段（列）管理
//    Translated from: app-table-field.js
//    Actions: create, list, update, delete
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableFieldTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_field"
    override val description =
        "【以User身份】FeishuBitableField（Column）ManagedTool。当User要求创建/查询/Update/DeleteField、调整表结构时使用。" +
        "Actions: create（创建Field）, list（Column出所HasField）, update（UpdateField，支持只传 field_name 改名）, delete（DeleteField）。"

    override fun isEnabled() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/fields"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // 特殊处理：超链接字段（type=15）和复选框字段（type=7）不能传 property
                // -----------------------------------------------------------------
                "create" -> {
                    val fieldName = args["field_name"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: field_name")
                    val type = (args["type"] as? Number)?.toInt()
                        ?: return@withContext ToolResult.error("Missing required parameter: type")

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId, field_name=$fieldName, type=$type")

                    // 特殊处理：超链接字段（type=15）和复选框字段（type=7）不能传 property，即使是空对象也会报错
                    @Suppress("UNCHECKED_CAST")
                    var propertyToSend = args["property"] as? Map<String, Any?>
                    if ((type == 15 || type == 7) && propertyToSend != null) {
                        val fieldTypeName = if (type == 15) "URL" else "Checkbox"
                        Log.w(TAG, "create: $fieldTypeName field (type=$type) detected with property parameter. " +
                            "Removing property to avoid API error. " +
                            "$fieldTypeName fields must omit the property parameter entirely.")
                        propertyToSend = null
                    }

                    val body = mutableMapOf<String, Any>(
                        "field_name" to fieldName,
                        "type" to type
                    )
                    if (propertyToSend != null) body["property"] = propertyToSend

                    val res = client.post(basePath, body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "create: created field ${data?.getAsJsonObject("field")?.get("field_id") ?: "unknown"}")
                    ToolResult.success(mapOf(
                        "field" to (data?.getAsJsonObject("field") ?: data)
                    ))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Pass view_id, page_size, page_token as query params
                // -----------------------------------------------------------------
                "list" -> {
                    val viewId = args["view_id"] as? String
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String

                    Log.i(TAG, "list: app_token=$appToken, table_id=$tableId, view_id=${viewId ?: "none"}")

                    val query = buildQuery(
                        "view_id" to viewId,
                        "page_size" to pageSize,
                        "page_token" to pageToken
                    )
                    val res = client.get("$basePath$query")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} fields")
                    ToolResult.success(mapOf(
                        "fields" to data?.getAsJsonArray("items"),
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString }
                    ))
                }

                // -----------------------------------------------------------------
                // UPDATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // field_name and type are OPTIONAL; auto-query fallback when missing
                // -----------------------------------------------------------------
                "update" -> {
                    val fieldId = args["field_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: field_id")

                    Log.i(TAG, "update: app_token=$appToken, table_id=$tableId, field_id=$fieldId")

                    // 如果缺少 type 或 field_name，自动查询当前字段信息
                    var finalFieldName = args["field_name"] as? String
                    var finalType = (args["type"] as? Number)?.toInt()
                    var finalProperty: Any? = args["property"]

                    if (finalType == null || finalFieldName == null) {
                        Log.i(TAG, "update: missing type or field_name, auto-querying field info")
                        val listRes = client.get("$basePath?page_size=500")
                        if (listRes.isFailure) {
                            return@withContext ToolResult.error(
                                "Failed to auto-query field info: ${listRes.exceptionOrNull()?.message}")
                        }
                        val listJson = listRes.getOrNull()
                        val listData = listJson?.getAsJsonObject("data")
                        val items = listData?.getAsJsonArray("items")

                        // Find matching field
                        var currentField: com.google.gson.JsonObject? = null
                        if (items != null) {
                            for (item in items) {
                                val obj = item.asJsonObject
                                if (obj.get("field_id")?.asString == fieldId) {
                                    currentField = obj
                                    break
                                }
                            }
                        }

                        if (currentField == null) {
                            return@withContext ToolResult.success(mapOf(
                                "error" to "field $fieldId does not exist",
                                "hint" to "Please verify field_id is correct. Use list action to view all fields."
                            ))
                        }

                        // 合并：用户传的优先，否则用查询到的
                        if (finalFieldName == null) finalFieldName = currentField.get("field_name")?.asString
                        if (finalType == null) finalType = currentField.get("type")?.asInt
                        if (args["property"] == null && currentField.has("property") && !currentField.get("property").isJsonNull) {
                            val gson = com.google.gson.Gson()
                            @Suppress("UNCHECKED_CAST")
                            finalProperty = gson.fromJson(currentField.get("property"), Map::class.java) as? Map<String, Any?>
                        }

                        Log.i(TAG, "update: auto-filled type=$finalType, field_name=$finalFieldName")
                    }

                    val updateData = mutableMapOf<String, Any>()
                    if (finalFieldName != null) updateData["field_name"] = finalFieldName
                    if (finalType != null) updateData["type"] = finalType
                    if (finalProperty != null) updateData["property"] = finalProperty

                    val res = client.put("$basePath/$fieldId", updateData)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val resData = json?.getAsJsonObject("data")
                    Log.i(TAG, "update: updated field $fieldId")
                    ToolResult.success(mapOf(
                        "field" to (resData?.getAsJsonObject("field") ?: resData)
                    ))
                }

                // -----------------------------------------------------------------
                // DELETE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "delete" -> {
                    val fieldId = args["field_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: field_id")

                    Log.i(TAG, "delete: app_token=$appToken, table_id=$tableId, field_id=$fieldId")

                    val res = client.delete("$basePath/$fieldId")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "delete: deleted field $fieldId")
                    ToolResult.success(mapOf(
                        "success" to true
                    ))
                }

                else -> ToolResult.error("Unknown action: $action. Supported: create, list, update, delete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "操作Type",
                        enum = listOf("create", "list", "update", "delete")
                    ),
                    "app_token" to PropertySchema("string", "Bitable token"),
                    "table_id" to PropertySchema("string", "数据表 ID"),
                    "field_id" to PropertySchema("string", "Field ID（update/delete Required）"),
                    "field_name" to PropertySchema("string", "FieldName（create Required，update Optional不传则不Modified）"),
                    "type" to PropertySchema("number", "FieldType（create Required，update Optional不传则Auto查询）：1=文本, 2=数字, 3=Single Select, 4=Multiple Select, 5=日期, 7=Checkbox, 11=人员, 13=电话, 15=超Link, 17=Attachment, 1001=Created At, 1002=Modified At等"),
                    "property" to PropertySchema("object",
                        "FieldPropertyConfigure（根据Type而定，例如Single Select/Multiple SelectNeed tooptions，数字Need toformatter等）。" +
                        "Important：超LinkField（type=15）Must完全省略此Parameter，传EmptyObject {} 也会报错（URLFieldPropertyError）。",
                        properties = emptyMap()),
                    "view_id" to PropertySchema("string", "View ID（list Optional）"),
                    "page_size" to PropertySchema("number", "每页Count，Default 50，Maximum 100（list Optional）"),
                    "page_token" to PropertySchema("string", "分页标记（list Optional）")
                ),
                required = listOf("action", "app_token", "table_id")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableFieldTool"
    }
}

// ═══════════════════════════════════════════════════════════════
// 4. FeishuBitableAppTableRecordTool — 记录（行）管理
//    Translated from: app-table-record.js
//    Actions: create, list, update, delete, batch_create, batch_update, batch_delete
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableRecordTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_record"
    override val description =
        "【以User身份】FeishuBitableRecord（Row）ManagedTool。当User要求创建/查询/Update/DeleteRecord、Search数据时使用。\n\n" +
        "Actions:\n" +
        "- create（创建单条Record，使用 fields Parameter）\n" +
        "- batch_create（批量创建Record，使用 records ArrayParameter）\n" +
        "- list（Column出/SearchRecord）\n" +
        "- update（UpdateRecord）\n" +
        "- delete（DeleteRecord）\n" +
        "- batch_update（批量Update）\n" +
        "- batch_delete（批量Delete）\n\n" +
        "\u26a0\ufe0f AttentionParameter区别：\n" +
        "- create 使用 'fields' Object（单条）\n" +
        "- batch_create 使用 'records' Array（批量）"

    override fun isEnabled() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/records"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'records' misuse
                // -----------------------------------------------------------------
                "create" -> {
                    // 参数验证：检查是否误用了 batch_create 的参数格式
                    if (args.containsKey("records")) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "create action does not accept 'records' parameter",
                            "hint" to "Use 'fields' for single record creation. For batch creation, use action: 'batch_create' with 'records' parameter.",
                            "correct_format" to mapOf(
                                "action" to "create",
                                "fields" to mapOf("Field名" to "FieldValue")
                            ),
                            "batch_create_format" to mapOf(
                                "action" to "batch_create",
                                "records" to listOf(mapOf("fields" to mapOf("Field名" to "FieldValue")))
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val fields = args["fields"] as? Map<String, Any?>
                    if (fields == null || fields.isEmpty()) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "fields is required and cannot be empty",
                            "hint" to "create action requires 'fields' parameter, e.g. { 'field_name': 'value', ... }"
                        ))
                    }

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId")

                    val body = mapOf("fields" to fields)
                    val res = client.post("$basePath?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created record ${json?.getAsJsonObject("data")?.getAsJsonObject("record")?.get("record_id")}")
                    ToolResult.success(mapOf(
                        "record" to json?.getAsJsonObject("data")?.getAsJsonObject("record")
                    ))
                }

                // -----------------------------------------------------------------
                // UPDATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'records' misuse
                // -----------------------------------------------------------------
                "update" -> {
                    // 参数验证：检查是否误用了 batch_update 的参数格式
                    if (args.containsKey("records")) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "update action does not accept 'records' parameter",
                            "hint" to "Use 'record_id' + 'fields' for single record update. For batch update, use action: 'batch_update' with 'records' parameter.",
                            "correct_format" to mapOf(
                                "action" to "update",
                                "record_id" to "recXXX",
                                "fields" to mapOf("Field名" to "FieldValue")
                            ),
                            "batch_update_format" to mapOf(
                                "action" to "batch_update",
                                "records" to listOf(mapOf("record_id" to "recXXX", "fields" to mapOf("Field名" to "FieldValue")))
                            )
                        ))
                    }

                    val recordId = args["record_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: record_id")
                    @Suppress("UNCHECKED_CAST")
                    val fields = args["fields"] as? Map<String, Any?>
                        ?: return@withContext ToolResult.error("Missing required parameter: fields")

                    Log.i(TAG, "update: app_token=$appToken, table_id=$tableId, record_id=$recordId")

                    val body = mapOf("fields" to fields)
                    val res = client.put("$basePath/$recordId?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "update: updated record $recordId")
                    ToolResult.success(mapOf(
                        "record" to json?.getAsJsonObject("data")?.getAsJsonObject("record")
                    ))
                }

                // -----------------------------------------------------------------
                // DELETE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "delete" -> {
                    val recordId = args["record_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: record_id")

                    Log.i(TAG, "delete: app_token=$appToken, table_id=$tableId, record_id=$recordId")

                    val res = client.delete("$basePath/$recordId")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "delete: deleted record $recordId")
                    ToolResult.success(mapOf(
                        "success" to true
                    ))
                }

                // -----------------------------------------------------------------
                // BATCH_CREATE (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'fields' misuse
                // Max 500 limit
                // -----------------------------------------------------------------
                "batch_create" -> {
                    // 参数验证：检查是否误用了 create 的参数格式
                    if (args.containsKey("fields")) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "batch_create action does not accept 'fields' parameter",
                            "hint" to "Use 'records' array for batch creation. For single record, use action: 'create' with 'fields' parameter.",
                            "correct_format" to mapOf(
                                "action" to "batch_create",
                                "records" to listOf(mapOf("fields" to mapOf("Field名" to "FieldValue")))
                            ),
                            "single_create_format" to mapOf(
                                "action" to "create",
                                "fields" to mapOf("Field名" to "FieldValue")
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val records = args["records"] as? List<Map<String, Any?>>
                    if (records == null || records.isEmpty()) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "records is required and cannot be empty",
                            "hint" to "batch_create requires 'records' array, e.g. [{ fields: {...} }, ...]"
                        ))
                    }
                    if (records.size > 500) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "records count exceeds limit (maximum 500)",
                            "received_count" to records.size
                        ))
                    }

                    Log.i(TAG, "batch_create: app_token=$appToken, table_id=$tableId, records_count=${records.size}")

                    val body = mapOf("records" to records)
                    val res = client.post("$basePath/batch_create?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_create: created ${records.size} records in table $tableId")
                    ToolResult.success(mapOf(
                        "records" to json?.getAsJsonObject("data")?.getAsJsonArray("records")
                    ))
                }

                // -----------------------------------------------------------------
                // BATCH_UPDATE (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'record_id'/'fields' misuse
                // Max 500 limit
                // -----------------------------------------------------------------
                "batch_update" -> {
                    // 参数验证：检查是否误用了 update 的参数格式
                    if (args.containsKey("record_id") || args.containsKey("fields")) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "batch_update action does not accept 'record_id' or 'fields' parameters",
                            "hint" to "Use 'records' array for batch update. For single record, use action: 'update' with 'record_id' + 'fields' parameters.",
                            "correct_format" to mapOf(
                                "action" to "batch_update",
                                "records" to listOf(mapOf("record_id" to "recXXX", "fields" to mapOf("Field名" to "FieldValue")))
                            ),
                            "single_update_format" to mapOf(
                                "action" to "update",
                                "record_id" to "recXXX",
                                "fields" to mapOf("Field名" to "FieldValue")
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val records = args["records"] as? List<Map<String, Any?>>
                    if (records == null || records.isEmpty()) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "records is required and cannot be empty",
                            "hint" to "batch_update requires 'records' array, e.g. [{ record_id: 'recXXX', fields: {...} }, ...]"
                        ))
                    }
                    if (records.size > 500) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "records cannot exceed 500 items"
                        ))
                    }

                    Log.i(TAG, "batch_update: app_token=$appToken, table_id=$tableId, records_count=${records.size}")

                    val body = mapOf("records" to records)
                    val res = client.post("$basePath/batch_update?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_update: updated ${records.size} records in table $tableId")
                    ToolResult.success(mapOf(
                        "records" to json?.getAsJsonObject("data")?.getAsJsonArray("records")
                    ))
                }

                // -----------------------------------------------------------------
                // BATCH_DELETE (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Max 500 limit
                // -----------------------------------------------------------------
                "batch_delete" -> {
                    @Suppress("UNCHECKED_CAST")
                    val recordIds = args["record_ids"] as? List<String>
                    if (recordIds == null || recordIds.isEmpty()) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "record_ids is required and cannot be empty"
                        ))
                    }
                    if (recordIds.size > 500) {
                        return@withContext ToolResult.success(mapOf(
                            "error" to "record_ids cannot exceed 500 items"
                        ))
                    }

                    Log.i(TAG, "batch_delete: app_token=$appToken, table_id=$tableId, record_ids_count=${recordIds.size}")

                    // JS source sends as { records: record_ids }
                    val body = mapOf("records" to recordIds)
                    val res = client.post("$basePath/batch_delete", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "batch_delete: deleted ${recordIds.size} records from table $tableId")
                    ToolResult.success(mapOf(
                        "success" to true
                    ))
                }

                // -----------------------------------------------------------------
                // LIST (P0) — 使用 search API（旧 list API 已废弃）
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // filter is STRUCTURED OBJECT; page_size/page_token as QUERY PARAMS
                // isEmpty/isNotEmpty auto-fix: add value=[]
                // user_id_type=open_id on all record actions
                // -----------------------------------------------------------------
                "list" -> {
                    @Suppress("UNCHECKED_CAST")
                    val viewId = args["view_id"] as? String
                    @Suppress("UNCHECKED_CAST")
                    val fieldNames = args["field_names"] as? List<String>
                    @Suppress("UNCHECKED_CAST")
                    var filter = args["filter"] as? Map<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val sort = args["sort"] as? List<Map<String, Any?>>
                    val automaticFields = args["automatic_fields"] as? Boolean
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String

                    Log.i(TAG, "list: app_token=$appToken, table_id=$tableId, view_id=${viewId ?: "none"}, " +
                        "field_names=${fieldNames?.size ?: 0}, filter=${if (filter != null) "yes" else "no"}")

                    // Build POST body (searchData)
                    val searchData = mutableMapOf<String, Any>()
                    if (viewId != null) searchData["view_id"] = viewId
                    if (fieldNames != null) searchData["field_names"] = fieldNames

                    // 特殊处理：isEmpty/isNotEmpty 必须带 value=[]（即使逻辑上不需要值）
                    if (filter != null) {
                        val filterCopy = filter!!.toMutableMap()
                        @Suppress("UNCHECKED_CAST")
                        val conditions = filterCopy["conditions"] as? List<Map<String, Any?>>
                        if (conditions != null) {
                            filterCopy["conditions"] = conditions.map { cond ->
                                val op = cond["operator"] as? String
                                if ((op == "isEmpty" || op == "isNotEmpty") && cond["value"] == null) {
                                    Log.w(TAG, "list: $op operator detected without value. Auto-adding value=[] to avoid API error.")
                                    cond.toMutableMap().apply { put("value", emptyList<String>()) }
                                } else {
                                    cond
                                }
                            }
                        }
                        searchData["filter"] = filterCopy
                    }

                    if (sort != null) searchData["sort"] = sort
                    if (automaticFields != null) searchData["automatic_fields"] = automaticFields

                    // page_size/page_token/user_id_type as query params
                    val query = buildQuery(
                        "user_id_type" to "open_id",
                        "page_size" to pageSize,
                        "page_token" to pageToken
                    )

                    val res = client.post("$basePath/search$query", searchData)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} records")
                    ToolResult.success(mapOf(
                        "records" to data?.getAsJsonArray("items"),
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString },
                        "total" to data?.get("total")?.let { if (it.isJsonNull) null else it.asInt }
                    ))
                }

                else -> ToolResult.success(mapOf(
                    "error" to "Unknown action: $action"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "操作Type",
                        enum = listOf("create", "list", "update", "delete", "batch_create", "batch_update", "batch_delete")
                    ),
                    "app_token" to PropertySchema("string", "Bitable token"),
                    "table_id" to PropertySchema("string", "数据表 ID"),
                    "record_id" to PropertySchema("string", "Record ID（update/delete Required）"),
                    "fields" to PropertySchema("object",
                        "RecordField（单条Record）。键为Field名，Value根据FieldType而定：\n" +
                        "- 文本：string\n" +
                        "- 数字：number\n" +
                        "- Single Select：string（Option名）\n" +
                        "- Multiple Select：string[]（Option名Array）\n" +
                        "- 日期：number（毫秒时间戳，如 1740441600000）\n" +
                        "- Checkbox：boolean\n" +
                        "- 人员：[{id: 'ou_xxx'}]\n" +
                        "- Attachment：[{file_token: 'xxx'}]\n" +
                        "Attention：create 只创建单条Record；批量创建Please使用 batch_create",
                        properties = emptyMap()),
                    "records" to PropertySchema(
                        "array",
                        "RecordArray（batch_create 为 [{fields: {...}}]，batch_update 为 [{record_id, fields: {...}}]）（最多 500 条）",
                        items = PropertySchema("object", "RecordObject")
                    ),
                    "record_ids" to PropertySchema(
                        "array",
                        "要Delete的Record ID List（batch_delete Required，最多 500 条）",
                        items = PropertySchema("string", "record_id 字符串")
                    ),
                    "view_id" to PropertySchema("string", "View ID（list Optional，Recommend指定以获得更好的性能）"),
                    "field_names" to PropertySchema(
                        "array", "要Back的Field名List（list Optional，不指定则Back所HasField）",
                        items = PropertySchema("string", "Field名")
                    ),
                    "filter" to PropertySchema(
                        "object",
                        "Filter条件（list Optional，MustYes结构化Object）。Example：{conjunction: 'and', conditions: [{field_name: '文本', operator: 'is', value: ['Test']}]}",
                        properties = mapOf(
                            "conjunction" to PropertySchema("string", "条件逻辑：and（All满足）or（任一满足）"),
                            "conditions" to PropertySchema("array", "Filter条件List", items = PropertySchema("object", "条件Object，含 field_name, operator, value"))
                        )
                    ),
                    "sort" to PropertySchema(
                        "array", "Sort规则（list Optional）",
                        items = PropertySchema("object", "SortObject，含 field_name, desc")
                    ),
                    "automatic_fields" to PropertySchema("boolean", "YesNoBackAutoField（created_time, last_modified_time, created_by, last_modified_by），Default false（list Optional）"),
                    "page_size" to PropertySchema("number", "每页Count，Default 50，Maximum 500（list Optional）"),
                    "page_token" to PropertySchema("string", "分页标记（list Optional）")
                ),
                required = listOf("action", "app_token", "table_id")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableRecordTool"
    }
}

// ═══════════════════════════════════════════════════════════════
// 5. FeishuBitableAppTableViewTool — 视图管理
//    Translated from: app-table-view.js
//    Actions: create, get, list, patch
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableViewTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_view"
    override val description =
        "【以User身份】FeishuBitableViewManagedTool。当User要求创建/查询/UpdateView、切换展示方式时使用。" +
        "Actions: create（创建View）, get（获取ViewDetails）, list（Column出所HasView）, patch（UpdateView）。"

    override fun isEnabled() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/views"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "create" -> {
                    val viewName = args["view_name"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: view_name")
                    val viewType = args["view_type"] as? String

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId, view_name=$viewName, view_type=${viewType ?: "grid"}")

                    val body = mutableMapOf<String, Any>(
                        "view_name" to viewName,
                        "view_type" to (viewType ?: "grid")
                    )

                    val res = client.post(basePath, body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created view ${json?.getAsJsonObject("data")?.getAsJsonObject("view")?.get("view_id")}")
                    ToolResult.success(mapOf(
                        "view" to json?.getAsJsonObject("data")?.getAsJsonObject("view")
                    ))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val viewId = args["view_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: view_id")

                    Log.i(TAG, "get: app_token=$appToken, table_id=$tableId, view_id=$viewId")

                    val res = client.get("$basePath/$viewId")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "get: returned view $viewId")
                    ToolResult.success(mapOf(
                        "view" to json?.getAsJsonObject("data")?.getAsJsonObject("view")
                    ))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Pass page_size/page_token as query params
                // -----------------------------------------------------------------
                "list" -> {
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String

                    Log.i(TAG, "list: app_token=$appToken, table_id=$tableId")

                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken
                    )
                    val res = client.get("$basePath$query")
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} views")
                    ToolResult.success(mapOf(
                        "views" to data?.getAsJsonArray("items"),
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString }
                    ))
                }

                // -----------------------------------------------------------------
                // PATCH
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // view_name is OPTIONAL
                // -----------------------------------------------------------------
                "patch" -> {
                    val viewId = args["view_id"] as? String
                        ?: return@withContext ToolResult.error("Missing required parameter: view_id")
                    val viewName = args["view_name"] as? String

                    Log.i(TAG, "patch: app_token=$appToken, table_id=$tableId, view_id=$viewId, view_name=$viewName")

                    val body = mutableMapOf<String, Any>()
                    if (viewName != null) body["view_name"] = viewName

                    val res = client.patch("$basePath/$viewId", body)
                    if (res.isFailure) return@withContext ToolResult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated view $viewId")
                    ToolResult.success(mapOf(
                        "view" to json?.getAsJsonObject("data")?.getAsJsonObject("view")
                    ))
                }

                else -> ToolResult.error("Unknown action: $action. Supported: create, get, list, patch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "操作Type",
                        enum = listOf("create", "get", "list", "patch")
                    ),
                    "app_token" to PropertySchema("string", "Bitable token"),
                    "table_id" to PropertySchema("string", "数据表 ID"),
                    "view_id" to PropertySchema("string", "View ID（get/patch Required）"),
                    "view_name" to PropertySchema("string", "ViewName（create Required，patch Optional）"),
                    "view_type" to PropertySchema("string", "ViewType（create Optional，Default grid）：grid=TableView, kanban=看板View, gallery=画册View, gantt=甘特图, form=FormView"),
                    "page_size" to PropertySchema("number", "每页Count，Default 50，Maximum 100（list Optional）"),
                    "page_token" to PropertySchema("string", "分页标记（list Optional）")
                ),
                required = listOf("action", "app_token", "table_id")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableViewTool"
    }
}
