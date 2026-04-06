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
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
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
// 1. FeishuBitableAppTool — Multi-dimensional table app management
//    Translated from: app.js
//    Actions: create, get, list, patch, copy
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app"
    override val description =
        "[As user] Feishu Multi-dimensional table app management tool. Use when user asks to create/query/manage multi-dimensional table apps. " +
        "Actions: create(Create app), get(Get app metadata), list(List apps), " +
        "patch(Update metadata), delete(Delete app), copy(Copy app). "

    override fun isEnabledd() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "create" -> {
                    val name = args["name"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: name")
                    val folderToken = args["folder_token"] as? String

                    Log.i(TAG, "create: name=$name, folder_token=${folderToken ?: "my_space"}")

                    val data = mutableMapOf<String, Any>("name" to name)
                    if (folderToken != null) {
                        data["folder_token"] = folderToken
                    }

                    val res = client.post("/open-apis/bitable/v1/apps", data)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created app ${json?.getAsJsonObject("data")?.getAsJsonObject("app")?.get("app_token")}")
                    Toolresult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val appToken = args["app_token"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: app_token")

                    Log.i(TAG, "get: app_token=$appToken")

                    val res = client.get("/open-apis/bitable/v1/apps/$appToken")
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "get: returned app $appToken")
                    Toolresult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // LIST — use Drive API to filter bitable type files
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")

                    // Filter out files with type === "bitable"
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
                    Toolresult.success(mapOf(
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
                        ?: return@withContext Toolresult.error("Missing required parameter: app_token")
                    val name = args["name"] as? String
                    val isAdvanced = args["is_advanced"] as? Boolean

                    Log.i(TAG, "patch: app_token=$appToken, name=$name, is_advanced=$isAdvanced")

                    val updateData = mutableMapOf<String, Any>()
                    if (name != null) updateData["name"] = name
                    if (isAdvanced != null) updateData["is_advanced"] = isAdvanced

                    val res = client.patch("/open-apis/bitable/v1/apps/$appToken", updateData)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated app $appToken")
                    Toolresult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                // -----------------------------------------------------------------
                // COPY (P1)
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "copy" -> {
                    val appToken = args["app_token"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: app_token")
                    val name = args["name"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: name")
                    val folderToken = args["folder_token"] as? String

                    Log.i(TAG, "copy: app_token=$appToken, name=$name, folder_token=${folderToken ?: "my_space"}")

                    val data = mutableMapOf<String, Any>("name" to name)
                    if (folderToken != null) {
                        data["folder_token"] = folderToken
                    }

                    val res = client.post("/open-apis/bitable/v1/apps/$appToken/copy", data)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "copy: created copy ${json?.getAsJsonObject("data")?.getAsJsonObject("app")?.get("app_token")}")
                    Toolresult.success(mapOf(
                        "app" to json?.getAsJsonObject("data")?.getAsJsonObject("app")
                    ))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, get, list, patch, copy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "Action type",
                        enum = listOf("create", "get", "list", "patch", "copy")
                    ),
                    "app_token" to PropertySchema("string", "App token, unique identifier for the multi-dimensional table (get/patch/copy required)"),
                    "name" to PropertySchema("string", "App name (create/copy required, patch optional)"),
                    "folder_token" to PropertySchema("string", "Folder token where the app is located (default: my space) (create/copy/list optional)"),
                    "is_advanced" to PropertySchema("boolean", "Whether to enable advanced permissions (patch optional)"),
                    "page_size" to PropertySchema("number", "Page size, default 50, max 200 (list optional)"),
                    "page_token" to PropertySchema("string", "Page token (list optional)")
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
// 2. FeishuBitableAppTableTool — Data table management
//    Translated from: app-table.js
//    Actions: create, list, patch, batch_create
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table"
    override val description =
        "[As user] Feishu Multi-dimensional table data table management tool. Use when user asks to create/query/manage data tables. " +
        "\n\nActions: create(Create data table, optionally define fields in the create request or add them later one by one), list(List all data tables), patch(Update data table), batch_create(Batch create). " +
        "\n\n[Field definition] Supports two schemas: 1) When requirements are clear, define all fields in create via table.fields (reduces API calls); 2) For exploratory scenarios, use default table + feishu_bitable_app_table_field to gradually modify fields (more stable, easier to adjust). "

    override fun isEnabledd() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: app_token")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Special handling: checkbox (type=7) and hyperlink (type=15) fields cannot have property parameter
                // -----------------------------------------------------------------
                "create" -> {
                    @Suppress("UNCHECKED_CAST")
                    val table = args["table"] as? Map<String, Any?>
                        ?: return@withContext Toolresult.error("Missing required parameter: table")

                    Log.i(TAG, "create: app_token=$appToken, table_name=${table["name"]}, fields_count=${(table["fields"] as? List<*>)?.size ?: 0}")

                    // Special handling: checkbox (type=7) and hyperlink (type=15) fields cannot have property parameter
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "create: created table ${data?.get("table_id")}")
                    Toolresult.success(mapOf(
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} tables")
                    Toolresult.success(mapOf(
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
                        ?: return@withContext Toolresult.error("Missing required parameter: table_id")
                    val tableName = args["name"] as? String

                    Log.i(TAG, "patch: app_token=$appToken, table_id=$tableId, name=$tableName")

                    val body = mutableMapOf<String, Any>()
                    if (tableName != null) body["name"] = tableName

                    val res = client.patch("$basePath/$tableId", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated table $tableId")
                    Toolresult.success(mapOf(
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
                        return@withContext Toolresult.success(mapOf(
                            "error" to "tables is required and cannot be empty"
                        ))
                    }

                    Log.i(TAG, "batch_create: app_token=$appToken, tables_count=${tables.size}")

                    val body = mapOf("tables" to tables)
                    val res = client.post("$basePath/batch_create", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_create: created ${tables.size} tables in app $appToken")
                    Toolresult.success(mapOf(
                        "table_ids" to json?.getAsJsonObject("data")?.getAsJsonArray("table_ids")
                    ))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, list, patch, batch_create")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "Action type",
                        enum = listOf("create", "list", "patch", "batch_create")
                    ),
                    "app_token" to PropertySchema("string", "Multi-dimensional table app token"),
                    "table_id" to PropertySchema("string", "Data table ID (patch required)"),
                    "name" to PropertySchema("string", "New table name (patch optional)"),
                    "table" to PropertySchema(
                        "object", "Data table definition (create required), includes name, default_view_name, fields",
                        properties = mapOf(
                            "name" to PropertySchema("string", "Data table name"),
                            "default_view_name" to PropertySchema("string", "Default view name"),
                            "fields" to PropertySchema("array", "Field list (optional, but strongly recommended to provide all fields when creating the table to avoid adding them later). If not provided, creates an empty table.",
                                items = PropertySchema("object", "Field definition, includes field_name, type, property"))
                        )
                    ),
                    "tables" to PropertySchema(
                        "array", "List of data tables to batch create (batch_create required)",
                        items = PropertySchema("object", "Data table definition, includes name")
                    ),
                    "page_size" to PropertySchema("number", "Page size, default 50, max 100 (list optional)"),
                    "page_token" to PropertySchema("string", "Page token (list optional)")
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
// 3. FeishuBitableAppTableFieldTool — Field (Column) Management
//    Translated from: app-table-field.js
//    Actions: create, list, update, delete
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableFieldTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_field"
    override val description =
        "[As user] Feishu Multi-dimensional table field (column) management tool. Use when user asks to create/query/update/delete fields, adjust table structure. " +
        "Actions: create(Create field), list(List all fields), update(Update field, supports renaming by providing field_name only), delete(Delete field). "

    override fun isEnabledd() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/fields"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Special handling: hyperlink field (type=15) and checkbox field (type=7) cannot have property parameter
                // -----------------------------------------------------------------
                "create" -> {
                    val fieldName = args["field_name"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: field_name")
                    val type = (args["type"] as? Number)?.toInt()
                        ?: return@withContext Toolresult.error("Missing required parameter: type")

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId, field_name=$fieldName, type=$type")

                    // Special handling: hyperlink field (type=15) and checkbox field (type=7) cannot have property parameter, even if null object will also cause error
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "create: created field ${data?.getAsJsonObject("field")?.get("field_id") ?: "unknown"}")
                    Toolresult.success(mapOf(
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} fields")
                    Toolresult.success(mapOf(
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
                        ?: return@withContext Toolresult.error("Missing required parameter: field_id")

                    Log.i(TAG, "update: app_token=$appToken, table_id=$tableId, field_id=$fieldId")

                    // If type or field_name is missing, auto-query current field info
                    var finalFieldName = args["field_name"] as? String
                    var finalType = (args["type"] as? Number)?.toInt()
                    var finalProperty: Any? = args["property"]

                    if (finalType == null || finalFieldName == null) {
                        Log.i(TAG, "update: missing type or field_name, auto-querying field info")
                        val listRes = client.get("$basePath?page_size=500")
                        if (listRes.isFailure) {
                            return@withContext Toolresult.error(
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
                            return@withContext Toolresult.success(mapOf(
                                "error" to "field $fieldId does not exist",
                                "hint" to "Please verify field_id is correct. Use list action to view all fields."
                            ))
                        }

                        // Merge: user provided values take priority, otherwise use queried values
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val resData = json?.getAsJsonObject("data")
                    Log.i(TAG, "update: updated field $fieldId")
                    Toolresult.success(mapOf(
                        "field" to (resData?.getAsJsonObject("field") ?: resData)
                    ))
                }

                // -----------------------------------------------------------------
                // DELETE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "delete" -> {
                    val fieldId = args["field_id"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: field_id")

                    Log.i(TAG, "delete: app_token=$appToken, table_id=$tableId, field_id=$fieldId")

                    val res = client.delete("$basePath/$fieldId")
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "delete: deleted field $fieldId")
                    Toolresult.success(mapOf(
                        "success" to true
                    ))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, list, update, delete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "Action type",
                        enum = listOf("create", "list", "update", "delete")
                    ),
                    "app_token" to PropertySchema("string", "Multi-dimensional table app token"),
                    "table_id" to PropertySchema("string", "Data table ID"),
                    "field_id" to PropertySchema("string", "Field ID (update/delete required)"),
                    "field_name" to PropertySchema("string", "Field name (create required, update optional - not provided means no change)"),
                    "type" to PropertySchema("number", "Field type (create required, update optional - not provided will auto-query): 1=Text, 2=Number, 3=Single Select, 4=Multi Select, 5=Date, 7=Checkbox, 11=Person, 13=Phone, 15=Hyperlink, 17=Attachment, 1001=Created Time, 1002=Modified Time, etc."),
                    "property" to PropertySchema("object",
                        "Field property configuration (varies by type, e.g., single/multi select need options, number needs formatter, etc.). " +
                        "Important: hyperlink field (type=15) MUST completely omit this parameter, passing null object {} will also cause error (URLFieldPropertyError). ",
                        properties = emptyMap()),
                    "view_id" to PropertySchema("string", "View ID (list optional)"),
                    "page_size" to PropertySchema("number", "Page size, default 50, max 100 (list optional)"),
                    "page_token" to PropertySchema("string", "Page token (list optional)")
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
// 4. FeishuBitableAppTableRecordTool — Record (Row) Management
//    Translated from: app-table-record.js
//    Actions: create, list, update, delete, batch_create, batch_update, batch_delete
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableRecordTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_record"
    override val description =
        "[As user] Feishu Multi-dimensional table record (row) management tool. Use when user asks to create/query/update/delete records, search data. \n\n" +
        "Actions:\n" +
        "- create(Create single record, use fields parameter)\n" +
        "- batch_create(Batch create records, use records array parameter)\n" +
        "- list(List/Search records)\n" +
        "- update(Update record)\n" +
        "- delete(Delete record)\n" +
        "- batch_update(Batch update)\n" +
        "- batch_delete(Batch delete)\n\n" +
        "\u26a0\ufe0f Note parameter difference: \n" +
        "- create uses 'fields' object (single record)\n" +
        "- batch_create uses 'records' array (batch)"

    override fun isEnabledd() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/records"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'records' misuse
                // -----------------------------------------------------------------
                "create" -> {
                    // Parameter validation: check if batch_create parameter format was mistakenly used
                    if (args.containsKey("records")) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "create action does not accept 'records' parameter",
                            "hint" to "Use 'fields' for single record creation. For batch creation, use action: 'batch_create' with 'records' parameter.",
                            "correct_format" to mapOf(
                                "action" to "create",
                                "fields" to mapOf("field_name" to "field_value")
                            ),
                            "batch_create_format" to mapOf(
                                "action" to "batch_create",
                                "records" to listOf(mapOf("fields" to mapOf("field_name" to "field_value")))
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val fields = args["fields"] as? Map<String, Any?>
                    if (fields == null || fields.isEmpty()) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "fields is required and cannot be empty",
                            "hint" to "create action requires 'fields' parameter, e.g. { 'field_name': 'value', ... }"
                        ))
                    }

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId")

                    val body = mapOf("fields" to fields)
                    val res = client.post("$basePath?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created record ${json?.getAsJsonObject("data")?.getAsJsonObject("record")?.get("record_id")}")
                    Toolresult.success(mapOf(
                        "record" to json?.getAsJsonObject("data")?.getAsJsonObject("record")
                    ))
                }

                // -----------------------------------------------------------------
                // UPDATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Cross-action validation: check for 'records' misuse
                // -----------------------------------------------------------------
                "update" -> {
                    // Parameter validation: check if batch_update parameter format was mistakenly used
                    if (args.containsKey("records")) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "update action does not accept 'records' parameter",
                            "hint" to "Use 'record_id' + 'fields' for single record update. For batch update, use action: 'batch_update' with 'records' parameter.",
                            "correct_format" to mapOf(
                                "action" to "update",
                                "record_id" to "recXXX",
                                "fields" to mapOf("field_name" to "field_value")
                            ),
                            "batch_update_format" to mapOf(
                                "action" to "batch_update",
                                "records" to listOf(mapOf("record_id" to "recXXX", "fields" to mapOf("field_name" to "field_value")))
                            )
                        ))
                    }

                    val recordId = args["record_id"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: record_id")
                    @Suppress("UNCHECKED_CAST")
                    val fields = args["fields"] as? Map<String, Any?>
                        ?: return@withContext Toolresult.error("Missing required parameter: fields")

                    Log.i(TAG, "update: app_token=$appToken, table_id=$tableId, record_id=$recordId")

                    val body = mapOf("fields" to fields)
                    val res = client.put("$basePath/$recordId?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "update: updated record $recordId")
                    Toolresult.success(mapOf(
                        "record" to json?.getAsJsonObject("data")?.getAsJsonObject("record")
                    ))
                }

                // -----------------------------------------------------------------
                // DELETE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "delete" -> {
                    val recordId = args["record_id"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: record_id")

                    Log.i(TAG, "delete: app_token=$appToken, table_id=$tableId, record_id=$recordId")

                    val res = client.delete("$basePath/$recordId")
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "delete: deleted record $recordId")
                    Toolresult.success(mapOf(
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
                    // Parameter validation: check if create parameter format was mistakenly used
                    if (args.containsKey("fields")) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "batch_create action does not accept 'fields' parameter",
                            "hint" to "Use 'records' array for batch creation. For single record, use action: 'create' with 'fields' parameter.",
                            "correct_format" to mapOf(
                                "action" to "batch_create",
                                "records" to listOf(mapOf("fields" to mapOf("field_name" to "field_value")))
                            ),
                            "single_create_format" to mapOf(
                                "action" to "create",
                                "fields" to mapOf("field_name" to "field_value")
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val records = args["records"] as? List<Map<String, Any?>>
                    if (records == null || records.isEmpty()) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "records is required and cannot be empty",
                            "hint" to "batch_create requires 'records' array, e.g. [{ fields: {...} }, ...]"
                        ))
                    }
                    if (records.size > 500) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "records count exceeds limit (maximum 500)",
                            "received_count" to records.size
                        ))
                    }

                    Log.i(TAG, "batch_create: app_token=$appToken, table_id=$tableId, records_count=${records.size}")

                    val body = mapOf("records" to records)
                    val res = client.post("$basePath/batch_create?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_create: created ${records.size} records in table $tableId")
                    Toolresult.success(mapOf(
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
                    // Parameter validation: check if update parameter format was mistakenly used
                    if (args.containsKey("record_id") || args.containsKey("fields")) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "batch_update action does not accept 'record_id' or 'fields' parameters",
                            "hint" to "Use 'records' array for batch update. For single record, use action: 'update' with 'record_id' + 'fields' parameters.",
                            "correct_format" to mapOf(
                                "action" to "batch_update",
                                "records" to listOf(mapOf("record_id" to "recXXX", "fields" to mapOf("field_name" to "field_value")))
                            ),
                            "single_update_format" to mapOf(
                                "action" to "update",
                                "record_id" to "recXXX",
                                "fields" to mapOf("field_name" to "field_value")
                            )
                        ))
                    }

                    @Suppress("UNCHECKED_CAST")
                    val records = args["records"] as? List<Map<String, Any?>>
                    if (records == null || records.isEmpty()) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "records is required and cannot be empty",
                            "hint" to "batch_update requires 'records' array, e.g. [{ record_id: 'recXXX', fields: {...} }, ...]"
                        ))
                    }
                    if (records.size > 500) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "records cannot exceed 500 items"
                        ))
                    }

                    Log.i(TAG, "batch_update: app_token=$appToken, table_id=$tableId, records_count=${records.size}")

                    val body = mapOf("records" to records)
                    val res = client.post("$basePath/batch_update?user_id_type=open_id", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "batch_update: updated ${records.size} records in table $tableId")
                    Toolresult.success(mapOf(
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
                        return@withContext Toolresult.success(mapOf(
                            "error" to "record_ids is required and cannot be empty"
                        ))
                    }
                    if (recordIds.size > 500) {
                        return@withContext Toolresult.success(mapOf(
                            "error" to "record_ids cannot exceed 500 items"
                        ))
                    }

                    Log.i(TAG, "batch_delete: app_token=$appToken, table_id=$tableId, record_ids_count=${recordIds.size}")

                    // JS source sends as { records: record_ids }
                    val body = mapOf("records" to recordIds)
                    val res = client.post("$basePath/batch_delete", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    Log.i(TAG, "batch_delete: deleted ${recordIds.size} records from table $tableId")
                    Toolresult.success(mapOf(
                        "success" to true
                    ))
                }

                // -----------------------------------------------------------------
                // LIST (P0) — use search API (old list API is deprecated)
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

                    // Special handling: isEmpty/isNotEmpty must have value=[] (even if logically not needed)
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} records")
                    Toolresult.success(mapOf(
                        "records" to data?.getAsJsonArray("items"),
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.let { if (it.isJsonNull) null else it.asString },
                        "total" to data?.get("total")?.let { if (it.isJsonNull) null else it.asInt }
                    ))
                }

                else -> Toolresult.success(mapOf(
                    "error" to "Unknown action: $action"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "Action type",
                        enum = listOf("create", "list", "update", "delete", "batch_create", "batch_update", "batch_delete")
                    ),
                    "app_token" to PropertySchema("string", "Multi-dimensional table app token"),
                    "table_id" to PropertySchema("string", "Data table ID"),
                    "record_id" to PropertySchema("string", "Record ID (update/delete required)"),
                    "fields" to PropertySchema("object",
                        "Record fields (single record). Key is field name, value depends on field type: \n" +
                        "- Text: string\n" +
                        "- Number: number\n" +
                        "- Single select: string (option name)\n" +
                        "- Multi select: string[] (option names array)\n" +
                        "- Date: number (millisecond timestamp, e.g., 1740441600000)\n" +
                        "- Checkbox: boolean\n" +
                        "- Person: [{id: 'ou_xxx'}]\n" +
                        "- Attachment: [{file_token: 'xxx'}]\n" +
                        "Note: create only creates single record; for batch create use batch_create",
                        properties = emptyMap()),
                    "records" to PropertySchema(
                        "array",
                        "Record array (batch_create is [{fields: {...}}], batch_update is [{record_id, fields: {...}}]) (max 500 records)",
                        items = PropertySchema("object", "Record object")
                    ),
                    "record_ids" to PropertySchema(
                        "array",
                        "List of record IDs to delete (batch_delete required, max 500 records)",
                        items = PropertySchema("string", "record_id string")
                    ),
                    "view_id" to PropertySchema("string", "View ID (list optional, recommended to specify for better performance)"),
                    "field_names" to PropertySchema(
                        "array", "List of field names to return (list optional, returns all fields if not specified)",
                        items = PropertySchema("string", "Field name")
                    ),
                    "filter" to PropertySchema(
                        "object",
                        "Filter condition (list optional, must be structured object). Example: {conjunction: 'and', conditions: [{field_name: 'Text', operator: 'is', value: ['Test']}]}",
                        properties = mapOf(
                            "conjunction" to PropertySchema("string", "Condition logic: and (all satisfied) or (any satisfy)"),
                            "conditions" to PropertySchema("array", "Filter condition list", items = PropertySchema("object", "Condition object, includes field_name, operator, value"))
                        )
                    ),
                    "sort" to PropertySchema(
                        "array", "Sort rules (list optional)",
                        items = PropertySchema("object", "Sort object, includes field_name, desc")
                    ),
                    "automatic_fields" to PropertySchema("boolean", "Whether to return auto fields (created_time, last_modified_time, created_by, last_modified_by), default false (list optional)"),
                    "page_size" to PropertySchema("number", "Page size, default 50, max 500 (list optional)"),
                    "page_token" to PropertySchema("string", "Page token (list optional)")
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
// 5. FeishuBitableAppTableViewTool — View Management
//    Translated from: app-table-view.js
//    Actions: create, get, list, patch
// ═══════════════════════════════════════════════════════════════

class FeishuBitableAppTableViewTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_bitable_app_table_view"
    override val description =
        "[As user] Feishu Multi-dimensional table view management tool. Use when user asks to create/query/update views, switch display mode. " +
        "Actions: create(Create view), get(Get view details), list(List all views), patch(Update view). "

    override fun isEnabledd() = config.enableBitableTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val appToken = args["app_token"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: app_token")
            val tableId = args["table_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: table_id")

            val basePath = "/open-apis/bitable/v1/apps/$appToken/tables/$tableId/views"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "create" -> {
                    val viewName = args["view_name"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: view_name")
                    val viewType = args["view_type"] as? String

                    Log.i(TAG, "create: app_token=$appToken, table_id=$tableId, view_name=$viewName, view_type=${viewType ?: "grid"}")

                    val body = mutableMapOf<String, Any>(
                        "view_name" to viewName,
                        "view_type" to (viewType ?: "grid")
                    )

                    val res = client.post(basePath, body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "create: created view ${json?.getAsJsonObject("data")?.getAsJsonObject("view")?.get("view_id")}")
                    Toolresult.success(mapOf(
                        "view" to json?.getAsJsonObject("data")?.getAsJsonObject("view")
                    ))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val viewId = args["view_id"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: view_id")

                    Log.i(TAG, "get: app_token=$appToken, table_id=$tableId, view_id=$viewId")

                    val res = client.get("$basePath/$viewId")
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "get: returned view $viewId")
                    Toolresult.success(mapOf(
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
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} views")
                    Toolresult.success(mapOf(
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
                        ?: return@withContext Toolresult.error("Missing required parameter: view_id")
                    val viewName = args["view_name"] as? String

                    Log.i(TAG, "patch: app_token=$appToken, table_id=$tableId, view_id=$viewId, view_name=$viewName")

                    val body = mutableMapOf<String, Any>()
                    if (viewName != null) body["view_name"] = viewName

                    val res = client.patch("$basePath/$viewId", body)
                    if (res.isFailure) return@withContext Toolresult.error(res.exceptionOrNull()?.message ?: "Failed")

                    val json = res.getOrNull()
                    Log.i(TAG, "patch: updated view $viewId")
                    Toolresult.success(mapOf(
                        "view" to json?.getAsJsonObject("data")?.getAsJsonObject("view")
                    ))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, get, list, patch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed", e)
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
                    "action" to PropertySchema(
                        "string", "Action type",
                        enum = listOf("create", "get", "list", "patch")
                    ),
                    "app_token" to PropertySchema("string", "Multi-dimensional table app token"),
                    "table_id" to PropertySchema("string", "Data table ID"),
                    "view_id" to PropertySchema("string", "View ID (get/patch required)"),
                    "view_name" to PropertySchema("string", "View name (create required, patch optional)"),
                    "view_type" to PropertySchema("string", "View type (create optional, default grid): grid=Table view, kanban=Kanban view, gallery=Album view, gantt=Gantt chart, form=Form view"),
                    "page_size" to PropertySchema("number", "Page size, default 50, max 100 (list optional)"),
                    "page_token" to PropertySchema("string", "Page token (list optional)")
                ),
                required = listOf("action", "app_token", "table_id")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuBitableViewTool"
    }
}