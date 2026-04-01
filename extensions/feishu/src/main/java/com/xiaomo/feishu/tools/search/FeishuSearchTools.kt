package com.xiaomo.feishu.tools.search

/**
 * Feishu Search tool set.
 * Line-by-line translation from @larksuite/openclaw-lark JS source.
 * - feishu_search_doc_wiki: unified doc + wiki search
 */

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FeishuSearchTools"

// ---------------------------------------------------------------------------
// Time helpers — translated from helpers.js
// ---------------------------------------------------------------------------

private const val SHANGHAI_UTC_OFFSET_HOURS = 8
private const val SHANGHAI_OFFSET_SUFFIX = "+08:00"

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/**
 * Convert a Unix timestamp (seconds or milliseconds) to ISO 8601 string
 * in the Asia/Shanghai timezone.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun unixTimestampToISO8601(raw: Any?): String? {
    if (raw == null) return null
    val text = when (raw) {
        is Number -> raw.toLong().toString()
        is JsonPrimitive -> if (raw.isNumber) raw.asLong.toString() else raw.asString.trim()
        else -> raw.toString().trim()
    }
    if (!Regex("^-?\\d+$").matches(text)) return null
    val num = text.toLongOrNull() ?: return null

    val utcMs = if (kotlin.math.abs(num) >= 1_000_000_000_000L) num else num * 1000L

    val beijingMs = utcMs + SHANGHAI_UTC_OFFSET_HOURS * 60L * 60L * 1000L
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = beijingMs

    val year = cal.get(java.util.Calendar.YEAR)
    val month = pad2(cal.get(java.util.Calendar.MONTH) + 1)
    val day = pad2(cal.get(java.util.Calendar.DAY_OF_MONTH))
    val hour = pad2(cal.get(java.util.Calendar.HOUR_OF_DAY))
    val minute = pad2(cal.get(java.util.Calendar.MINUTE))
    val second = pad2(cal.get(java.util.Calendar.SECOND))

    return "$year-$month-${day}T$hour:$minute:$second$SHANGHAI_OFFSET_SUFFIX"
}

/**
 * Parse ISO 8601 time string to Unix timestamp (seconds as string).
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToTimestamp(input: String): String? {
    return try {
        val trimmed = input.trim()
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)
        if (hasTimezone) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return (date.time / 1000).toString()
        }
        val normalized = trimmed.replace('T', ' ')
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$").matchEntire(normalized)
        if (match == null) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return (date.time / 1000).toString()
        }
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = if (match.groupValues[6].isNotEmpty()) match.groupValues[6].toInt() else 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, hour - 8, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        (cal.timeInMillis / 1000).toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Convert time range object (for search API).
 * Converts ISO 8601 time strings to timestamps.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun convertTimeRange(timeRange: Map<*, *>?, unit: String = "s"): Map<String, Any>? {
    if (timeRange == null) return null
    val result = mutableMapOf<String, Any>()
    val parseFn: (String) -> String? = if (unit == "ms") { input ->
        parseTimeToTimestamp(input)?.toLongOrNull()?.let { (it * 1000).toString() }
    } else { input ->
        parseTimeToTimestamp(input)
    }

    (timeRange["start"] as? String)?.let { startStr ->
        val ts = parseFn(startStr)
            ?: throw IllegalArgumentException("Invalid time format for start. Must use ISO 8601 / RFC 3339 with timezone. Received: $startStr")
        result["start"] = ts.toLong()
    }
    (timeRange["end"] as? String)?.let { endStr ->
        val ts = parseFn(endStr)
            ?: throw IllegalArgumentException("Invalid time format for end. Must use ISO 8601 / RFC 3339 with timezone. Received: $endStr")
        result["end"] = ts.toLong()
    }
    return if (result.isNotEmpty()) result else null
}

// ---------------------------------------------------------------------------
// Normalize search result time fields — translated from doc-search.js
// ---------------------------------------------------------------------------

/**
 * Recursively normalize time fields in search results.
 * Any key ending with _time that is a numeric string gets converted to ISO 8601.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun normalizeSearchResultTimeFields(value: JsonElement?, counter: IntArray): JsonElement? {
    if (value == null || value.isJsonNull) return value

    if (value.isJsonArray) {
        val arr = JsonArray()
        for (item in value.asJsonArray) {
            arr.add(normalizeSearchResultTimeFields(item, counter))
        }
        return arr
    }

    if (!value.isJsonObject) return value

    val source = value.asJsonObject
    val normalized = JsonObject()
    for ((key, item) in source.entrySet()) {
        if (key.endsWith("_time") && item.isJsonPrimitive) {
            val iso = unixTimestampToISO8601(item.asString)
            if (iso != null) {
                normalized.addProperty(key, iso)
                counter[0] += 1
                continue
            }
        }
        normalized.add(key, normalizeSearchResultTimeFields(item, counter))
    }
    return normalized
}

// ─── feishu_search_doc_wiki ────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuSearchDocWikiTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_search_doc_wiki"
    override val description = "【以User身份】FeishuDocumentation与 Wiki 统一SearchTool。同时Search云Empty间Documentation和Knowledge Base Wiki。Actions: search。" +
        "【Important】query ParameterYesSearchOff键词（Optional），filter ParameterOptional。" +
        "【Important】filter 不传时，Search所HasDocumentation和 Wiki；传了则同时对Documentation和 Wiki 应用相同的过滤条件。" +
        "【Important】支持按DocumentationType、创建者、Created At、Open时间等多维度Filter。" +
        "【Important】Back结果包含标题和SummaryHigh亮（<h>Label包裹匹配Off键词）。"

    override fun isEnabled() = config.enableSearchTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String ?: "search"
            if (action != "search") {
                return@withContext ToolResult.error("Unknown action: $action. Only 'search' is supported.")
            }

            // query is optional, default to empty string
            val query = args["query"] as? String ?: ""
            Log.i(TAG, "search: query=\"$query\", has_filter=${args["filter"] != null}, page_size=${(args["page_size"] as? Number)?.toInt() ?: 15}")

            // Build request body
            val requestData = mutableMapOf<String, Any>(
                "query" to query
            )
            (args["page_size"] as? Number)?.let { requestData["page_size"] = it.toInt() }
            (args["page_token"] as? String)?.let { requestData["page_token"] = it }

            // Must pass doc_filter and wiki_filter (even if empty - API requires it)
            @Suppress("UNCHECKED_CAST")
            val filter = args["filter"] as? Map<String, Any?>
            if (filter != null) {
                val filterObj = mutableMapOf<String, Any?>()
                @Suppress("UNCHECKED_CAST")
                (filter["creator_ids"] as? List<String>)?.let { filterObj["creator_ids"] = it }
                @Suppress("UNCHECKED_CAST")
                (filter["doc_types"] as? List<String>)?.let { filterObj["doc_types"] = it }
                (filter["only_title"] as? Boolean)?.let { filterObj["only_title"] = it }
                (filter["sort_type"] as? String)?.let { filterObj["sort_type"] = it }

                // Convert time range fields
                @Suppress("UNCHECKED_CAST")
                (filter["open_time"] as? Map<*, *>)?.let {
                    filterObj["open_time"] = convertTimeRange(it)
                }
                @Suppress("UNCHECKED_CAST")
                (filter["create_time"] as? Map<*, *>)?.let {
                    filterObj["create_time"] = convertTimeRange(it)
                }

                // Same filter applied to both doc_filter and wiki_filter
                requestData["doc_filter"] = HashMap(filterObj.filterValues { it != null })
                requestData["wiki_filter"] = HashMap(filterObj.filterValues { it != null })
                Log.i(TAG, "search: applying filter to both doc and wiki: doc_types=${(filter["doc_types"] as? List<*>)?.joinToString(",") ?: "all"}, only_title=${filter["only_title"] ?: false}")
            } else {
                // API requires both filters even when empty
                requestData["doc_filter"] = emptyMap<String, Any>()
                requestData["wiki_filter"] = emptyMap<String, Any>()
                Log.i(TAG, "search: no filter provided, using empty filters (required by API)")
            }

            // POST /open-apis/search/v2/doc_wiki/search
            val result = client.post("/open-apis/search/v2/doc_wiki/search", requestData)
            if (result.isFailure) {
                return@withContext ToolResult.error(result.exceptionOrNull()?.message ?: "Failed to search documents")
            }

            val data = result.getOrNull()?.getAsJsonObject("data") ?: JsonObject()
            val resUnits = data.getAsJsonArray("res_units")
            Log.i(TAG, "search: found ${resUnits?.size() ?: 0} results, total=${data.get("total")?.asInt ?: 0}, has_more=${data.get("has_more")?.asBoolean ?: false}")

            // Normalize time fields in results
            val counter = intArrayOf(0)
            val normalizedResults = normalizeSearchResultTimeFields(resUnits, counter)
            Log.i(TAG, "search: normalized ${counter[0]} timestamp fields to ISO8601")

            val response = JsonObject().apply {
                data.get("total")?.let { add("total", it) }
                addProperty("has_more", data.get("has_more")?.asBoolean ?: false)
                add("results", normalizedResults)
                data.get("page_token")?.let { add("page_token", it) }
            }
            ToolResult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_search_doc_wiki failed", e)
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
                    "action" to PropertySchema("string", "操作Type（目前仅支持 search）", enum = listOf("search")),
                    "query" to PropertySchema("string", "SearchOff键词（Optional，最多 50 字符）。不传或传Empty字符串表示Empty搜，也Can支持Sort规则与Filter，Default根据最近浏览时间Back结果"),
                    "filter" to PropertySchema("object", "Search过滤条件（Optional）。不传则Search所HasDocumentation和 Wiki；传了则同时对Documentation和 Wiki 应用相同的过滤条件。", properties = mapOf(
                        "creator_ids" to PropertySchema("array", "创建者 OpenID List（最多 20 个）",
                            items = PropertySchema("string", "创建者 open_id")),
                        "doc_types" to PropertySchema("array", "DocumentationTypeList：DOC（Documentation）、SHEET（Table）、BITABLE（Bitable）、MINDNOTE（思维导图）、FILE（文件）、WIKI（维基）、DOCX（新版Documentation）、FOLDER（space文件夹）、CATALOG（wiki2.0文件夹）、SLIDES（新版幻灯片）、SHORTCUT（快捷方式）",
                            items = PropertySchema("string", "DocumentationType",
                                enum = listOf("DOC", "SHEET", "BITABLE", "MINDNOTE", "FILE", "WIKI", "DOCX", "FOLDER", "CATALOG", "SLIDES", "SHORTCUT"))),
                        "only_title" to PropertySchema("boolean", "仅Search标题（Default false，Search标题和正文）"),
                        "sort_type" to PropertySchema("string", "Sort方式。EDIT_TIME=Edit时间Descending（LatestDocumentation在前，Recommended），EDIT_TIME_ASC=Edit时间Ascending，CREATE_TIME=按DocumentationCreated AtSort，OPEN_TIME=Open时间，DEFAULT_TYPE=DefaultSort",
                            enum = listOf("DEFAULT_TYPE", "OPEN_TIME", "EDIT_TIME", "EDIT_TIME_ASC", "CREATE_TIME")),
                        "open_time" to PropertySchema("object", "Open时间范围 {start, end}（ISO 8601 Format）"),
                        "create_time" to PropertySchema("object", "Created At范围 {start, end}（ISO 8601 Format）")
                    )),
                    "page_token" to PropertySchema("string", "分页标记。首次Request不填；当Back结果Medium has_more 为 true 时，可传入Back的 page_token ContinueRequest下一页"),
                    "page_size" to PropertySchema("integer", "Page Size（Default 15，Maximum 20）")
                ),
                required = emptyList()
            )
        )
    )
}

// ─── Aggregator ────────────────────────────────────────────────────

class FeishuSearchTools(config: FeishuConfig, client: FeishuClient) {
    private val searchDocWikiTool = FeishuSearchDocWikiTool(config, client)

    fun getAllTools(): List<FeishuToolBase> = listOf(searchDocWikiTool)

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabled() }.map { it.getToolDefinition() }
    }
}
