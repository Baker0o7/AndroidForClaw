package com.xiaomo.feishu.tools.task

/**
 * Feishu Task tool set.
 * Line-by-line translation from @larksuite/openclaw-lark:
 *   - task/task.js
 *   - task/tasklist.js
 *   - task/subtask.js
 *   - task/comment.js
 *   - helpers.js (parseTimeToTimestamp, parseTimeToTimestampMs, unixTimestampToISO8601, pad2)
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

class FeishuTaskTools(config: FeishuConfig, client: FeishuClient) {
    private val taskTool = FeishuTaskTaskTool(config, client)
    private val tasklistTool = FeishuTaskTasklistTool(config, client)
    private val subtaskTool = FeishuTaskSubtaskTool(config, client)
    private val commentTool = FeishuTaskCommentTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(taskTool, tasklistTool, subtaskTool, commentTool)
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

// ---------------------------------------------------------------------------
// Shared helpers — line-by-line from helpers.js
// ---------------------------------------------------------------------------

private const val SHANGHAI_UTC_OFFSET_HOURS = 8
private const val SHANGHAI_OFFSET_SUFFIX = "+08:00"

/**
 * Pad a number to 2 digits.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun pad2(value: Int): String {
    return value.toString().padStart(2, '0')
}

/**
 * Parse time string to Unix timestamp (seconds).
 *
 * Supports:
 * 1. ISO 8601 / RFC 3339 (with timezone): "2024-01-01T00:00:00+08:00"
 * 2. Without timezone (defaults to Beijing UTC+8):
 *    - "2026-02-25 14:30"
 *    - "2026-02-25 14:30:00"
 *    - "2026-02-25T14:30:00"
 *
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToTimestamp(input: String): String? {
    return try {
        val trimmed = input.trim()

        // Check if timezone info is present (Z or +/- offset)
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)

        if (hasTimezone) {
            // Has timezone info, parse directly
            val date = parseISO8601(trimmed) ?: return null
            (date / 1000).toString()
        } else {
            // No timezone info, treat as Beijing time
            val normalized = trimmed.replace('T', ' ')
            val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$")
                .find(normalized)

            if (match == null) {
                // Try to parse directly (may be other ISO 8601 format)
                val date = parseISO8601(trimmed) ?: return null
                (date / 1000).toString()
            } else {
                val (year, month, day, hour, minute) = match.destructured
                val second = match.groupValues[6].ifEmpty { "0" }
                // Treat as Beijing time (UTC+8), convert to UTC
                val utcMs = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
                    set(year.toInt(), month.toInt() - 1, day.toInt(),
                        hour.toInt() - 8, minute.toInt(), second.toInt())
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                (utcMs / 1000).toString()
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse time string to Unix timestamp (milliseconds).
 *
 * Same formats as parseTimeToTimestamp, but returns milliseconds.
 *
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToTimestampMs(input: String): String? {
    return try {
        val trimmed = input.trim()

        // Check if timezone info is present (Z or +/- offset)
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)

        if (hasTimezone) {
            // Has timezone info, parse directly
            val date = parseISO8601(trimmed) ?: return null
            date.toString()
        } else {
            // No timezone info, treat as Beijing time
            val normalized = trimmed.replace('T', ' ')
            val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$")
                .find(normalized)

            if (match == null) {
                // Try to parse directly (may be other ISO 8601 format)
                val date = parseISO8601(trimmed) ?: return null
                date.toString()
            } else {
                val (year, month, day, hour, minute) = match.destructured
                val second = match.groupValues[6].ifEmpty { "0" }
                // Treat as Beijing time (UTC+8), convert to UTC
                val utcMs = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
                    set(year.toInt(), month.toInt() - 1, day.toInt(),
                        hour.toInt() - 8, minute.toInt(), second.toInt())
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                utcMs.toString()
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Convert a Unix timestamp (seconds or milliseconds) to ISO 8601 string
 * in the Asia/Shanghai timezone.
 *
 * Auto-detects seconds vs milliseconds based on magnitude.
 *
 * @returns e.g. "2026-02-25T14:30:00+08:00", or null on invalid input
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun unixTimestampToISO8601(raw: Any?): String? {
    if (raw == null) return null
    val text = when (raw) {
        is Number -> raw.toString()
        else -> raw.toString().trim()
    }
    if (!Regex("^-?\\d+$").matches(text)) return null
    val num = text.toLongOrNull() ?: return null

    val utcMs = if (kotlin.math.abs(num) >= 1_000_000_000_000L) num else num * 1000

    val beijingMs = utcMs + SHANGHAI_UTC_OFFSET_HOURS * 60 * 60 * 1000L
    val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = beijingMs
    }

    val year = cal.get(java.util.Calendar.YEAR)
    val month = pad2(cal.get(java.util.Calendar.MONTH) + 1)
    val day = pad2(cal.get(java.util.Calendar.DAY_OF_MONTH))
    val hour = pad2(cal.get(java.util.Calendar.HOUR_OF_DAY))
    val minute = pad2(cal.get(java.util.Calendar.MINUTE))
    val second = pad2(cal.get(java.util.Calendar.SECOND))

    return "$year-$month-${day}T$hour:$minute:$second$SHANGHAI_OFFSET_SUFFIX"
}

/**
 * Helper to parse ISO 8601 / RFC 3339 strings to epoch milliseconds.
 * Returns null on parse failure.
 */
private fun parseISO8601(input: String): Long? {
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                // Formats without timezone info: default to Beijing time
                if (!fmt.contains("X") && !fmt.endsWith("Z")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                }
                val date = sdf.parse(input) ?: continue
                return date.time
            } catch (_: Exception) {
                // try next
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

// ─────────────────────────────────────────────────────────────
// Helper: parse due/start object from args
// due/start are OBJECTS: {timestamp: string, is_all_day?: boolean}
// parse timestamp via parseTimeToTimestampMs
// @aligned openclaw-lark v2026.3.30 — line-by-line
// ─────────────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun parseDueStartObject(raw: Any?): Map<String, Any>? {
    if (raw == null) return null
    // If it's a structured object with timestamp field
    if (raw is Map<*, *>) {
        val tsRaw = raw["timestamp"] as? String ?: return null
        val tsMs = parseTimeToTimestampMs(tsRaw) ?: return null
        val isAllDay = raw["is_all_day"] as? Boolean ?: false
        return mapOf("timestamp" to tsMs, "is_all_day" to isAllDay)
    }
    // If it's a plain string (legacy format), convert to object
    if (raw is String) {
        val tsMs = parseTimeToTimestampMs(raw) ?: return null
        return mapOf("timestamp" to tsMs, "is_all_day" to false)
    }
    return null
}

// ─────────────────────────────────────────────────────────────
// 1. FeishuTaskTaskTool — feishu_task_task
// Translated from: task/task.js
// Actions: create, get, list, patch
// ─────────────────────────────────────────────────────────────

class FeishuTaskTaskTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_task_task"
    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override val description =
        "Feishu task management tool (as user). For creating, querying, and updating tasks." +
        "Actions: create (create task), get (get task details), list (query task list, returns only tasks I own), patch (update task)." +
        "Time parameters use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00'."

    override fun isEnabledd() = config.enableTaskTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            val basePath = "/open-apis/task/v2/tasks"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE TASK
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "create" -> {
                    val summary = args["summary"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: summary")
                    Log.i(TAG, "create: summary=$summary")

                    val taskData = mutableMapOf<String, Any>("summary" to summary)

                    val description = args["description"] as? String
                    if (description != null) taskData["description"] = description

                    // Handle due time conversion
                    // due is OBJECT: {timestamp: string, is_all_day?: boolean}
                    if (args["due"] != null) {
                        val dueObj = parseDueStartObject(args["due"])
                        if (dueObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "due time format error! Must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00', e.g. '2026-02-25 18:00'.",
                                "received" to args["due"]
                            ))
                        }
                        taskData["due"] = dueObj
                        Log.i(TAG, "create: due time converted")
                    }

                    // Handle start time conversion
                    if (args["start"] != null) {
                        val startObj = parseDueStartObject(args["start"])
                        if (startObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "start time format error! Must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00'.",
                                "received" to args["start"]
                            ))
                        }
                        taskData["start"] = startObj
                    }

                    @Suppress("UNCHECKED_CAST")
                    val members = args["members"] as? List<Map<String, Any?>>
                    if (members != null) taskData["members"] = members

                    val repeatRule = args["repeat_rule"] as? String
                    if (repeatRule != null) taskData["repeat_rule"] = repeatRule

                    @Suppress("UNCHECKED_CAST")
                    val tasklists = args["tasklists"] as? List<Map<String, Any?>>
                    if (tasklists != null) taskData["tasklists"] = tasklists

                    val userIdType = args["user_id_type"] as? String ?: "open_id"
                    val query = buildQuery("user_id_type" to userIdType)
                    val result = client.post("$basePath$query", taskData)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val data = result.getOrNull()
                    val taskGuid = data?.getAsJsonObject("data")?.getAsJsonObject("task")?.get("guid")?.asString
                    Log.i(TAG, "create: task created: task_guid=$taskGuid")
                    Toolresult.success(mapOf("task" to data?.getAsJsonObject("data")?.getAsJsonObject("task")))
                }

                // -----------------------------------------------------------------
                // GET TASK
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val taskGuid = args["task_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: task_guid")
                    Log.i(TAG, "get: task_guid=$taskGuid")

                    val userIdType = args["user_id_type"] as? String ?: "open_id"
                    val query = buildQuery("user_id_type" to userIdType)
                    val result = client.get("$basePath/$taskGuid$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val data = result.getOrNull()
                    Log.i(TAG, "get: retrieved task $taskGuid")
                    Toolresult.success(mapOf("task" to data?.getAsJsonObject("data")?.getAsJsonObject("task")))
                }

                // -----------------------------------------------------------------
                // LIST TASKS
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "list" -> {
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    val completed = args["completed"] as? Boolean
                    val userIdType = args["user_id_type"] as? String ?: "open_id"
                    Log.i(TAG, "list: page_size=${pageSize ?: 50}, completed=${completed ?: false}")

                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken,
                        "completed" to completed,
                        "user_id_type" to userIdType
                    )
                    val result = client.get("$basePath$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    val items = data?.getAsJsonArray("items")
                    Log.i(TAG, "list: returned ${items?.size() ?: 0} tasks")
                    Toolresult.success(mapOf(
                        "tasks" to items,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.asString
                    ))
                }

                // -----------------------------------------------------------------
                // PATCH TASK
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // CRITICAL: patch wraps body as {task: updateData, update_fields: [...]}
                // -----------------------------------------------------------------
                "patch" -> {
                    val taskGuid = args["task_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: task_guid")
                    Log.i(TAG, "patch: task_guid=$taskGuid")

                    val updateData = mutableMapOf<String, Any>()

                    val summary = args["summary"] as? String
                    if (summary != null) updateData["summary"] = summary

                    // description !== undefined check (JS uses !== undefined, not null check)
                    if (args.containsKey("description")) {
                        val desc = args["description"]
                        if (desc != null) updateData["description"] = desc
                    }

                    // Handle due time conversion
                    if (args["due"] != null) {
                        val dueObj = parseDueStartObject(args["due"])
                        if (dueObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "Due time format error! Must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00'. ",
                                "received" to args["due"]
                            ))
                        }
                        updateData["due"] = dueObj
                    }

                    // Handle start time conversion
                    if (args["start"] != null) {
                        val startObj = parseDueStartObject(args["start"])
                        if (startObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "start time format error! Must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00'.",
                                "received" to args["start"]
                            ))
                        }
                        updateData["start"] = startObj
                    }

                    // Handle completed_at conversion
                    val completedAt = args["completed_at"] as? String
                    if (completedAt != null) {
                        when {
                            // Special value: uncomplete (set to incomplete)
                            completedAt == "0" -> {
                                updateData["completed_at"] = "0"
                            }
                            // Numeric timestamp string (pass-through)
                            Regex("^\\d+$").matches(completedAt) -> {
                                updateData["completed_at"] = completedAt
                            }
                            // Time format string (need convert)
                            else -> {
                                val completedTs = parseTimeToTimestampMs(completedAt)
                                if (completedTs == null) {
                                    return@withContext Toolresult.success(mapOf(
                                        "error" to "completed_at format error! Support: 1) ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00'; 2) '0' (uncomplete); 3) Millisecond timestamp string. ",
                                        "received" to completedAt
                                    ))
                                }
                                updateData["completed_at"] = completedTs
                            }
                        }
                    }

                    @Suppress("UNCHECKED_CAST")
                    val members = args["members"] as? List<Map<String, Any?>>
                    if (members != null) updateData["members"] = members

                    val repeatRule = args["repeat_rule"] as? String
                    if (repeatRule != null) updateData["repeat_rule"] = repeatRule

                    // Build update_fields list (required by Task API)
                    val updateFields = updateData.keys.toList()

                    val userIdType = args["user_id_type"] as? String ?: "open_id"
                    val body = mapOf(
                        "task" to updateData,
                        "update_fields" to updateFields
                    )
                    val query = buildQuery("user_id_type" to userIdType)
                    val result = client.patch("$basePath/$taskGuid$query", body)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val data = result.getOrNull()
                    Log.i(TAG, "patch: task $taskGuid updated")
                    Toolresult.success(mapOf("task" to data?.getAsJsonObject("data")?.getAsJsonObject("task")))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, get, list, patch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_task_task failed", e)
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
                    "task_guid" to PropertySchema("string", "Task GUID (get/patch required)"),
                    "summary" to PropertySchema("string", "Task title (create required, patch optional)"),
                    "current_user_id" to PropertySchema("string", "Current user's open_id (strongly recommended, get from message context's SenderId). If not in members, the tool will auto-add as follower to ensure creator can edit task. "),
                    "description" to PropertySchema("string", "Task description (optional)"),
                    "due" to PropertySchema(
                        "object", "Due time object",
                        properties = mapOf(
                            "timestamp" to PropertySchema("string", "Due time (ISO 8601 / RFC 3339 format with timezone, e.g. '2024-01-01T00:00:00+08:00')"),
                            "is_all_day" to PropertySchema("boolean", "Whether all-day task")
                        )
                    ),
                    "start" to PropertySchema(
                        "object", "Start time object",
                        properties = mapOf(
                            "timestamp" to PropertySchema("string", "Start time (ISO 8601 / RFC 3339 format with timezone, e.g. '2024-01-01T00:00:00+08:00')"),
                            "is_all_day" to PropertySchema("boolean", "Whether all-day")
                        )
                    ),
                    "completed_at" to PropertySchema("string", "Completed time. Supports 3 formats: 1) ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00' (set to completed); 2) '0' (uncomplete, task becomes incomplete); 3) Millisecond timestamp string. "),
                    "completed" to PropertySchema("boolean", "Whether to filter completed tasks (list optional)"),
                    "members" to PropertySchema(
                        "array", "Task member list (assignee=assignee, follower=follower)",
                        items = PropertySchema("object", "Member object {id: open_id, role?: 'assignee'|'follower'}")
                    ),
                    "repeat_rule" to PropertySchema("string", "Repeat rule (RRULE format)"),
                    "tasklists" to PropertySchema(
                        "array", "Task's tasklist list",
                        items = PropertySchema("object", "Tasklist object {tasklist_guid, section_guid?}")
                    ),
                    "user_id_type" to PropertySchema("string", "User ID Type", enum = listOf("open_id", "union_id", "user_id")),
                    "page_size" to PropertySchema("number", "Page size(Default 50, Max 100)"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuTaskTask"
    }
}

// ─────────────────────────────────────────────────────────────
// 2. FeishuTaskTasklistTool — feishu_task_tasklist
// Translated from: task/tasklist.js
// Actions: create, get, list, tasks, patch, add_members
// ─────────────────────────────────────────────────────────────

class FeishuTaskTasklistTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_task_tasklist"
    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override val description =
        "【As user】Feishu task tasklist management tool. Use when user asks to create/query/manage tasklists, view tasks in a tasklist. " +
        "Actions: create (create tasklist), get (get tasklist details), list (list all readable tasklists, including ones I created and shared by others), " +
        "tasks (list tasks in tasklist), patch (update tasklist), add_members (add members). "

    override fun isEnabledd() = config.enableTaskTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            val basePath = "/open-apis/task/v2/tasklists"

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Transform members: add type='user', default role='editor'
                // -----------------------------------------------------------------
                "create" -> {
                    val name = args["name"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: name")
                    @Suppress("UNCHECKED_CAST")
                    val members = args["members"] as? List<Map<String, Any?>>
                    Log.i(TAG, "create: name=$name, members_count=${members?.size ?: 0}")

                    val data = mutableMapOf<String, Any>("name" to name)

                    // ConvertMember格式
                    if (members != null && members.isNotEmpty()) {
                        data["members"] = members.map { m ->
                            mapOf(
                                "id" to (m["id"] ?: ""),
                                "type" to "user",
                                "role" to (m["role"] ?: "editor")
                            )
                        }
                    }

                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.post("$basePath$query", data)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val tasklist = json?.getAsJsonObject("data")?.getAsJsonObject("tasklist")
                    Log.i(TAG, "create: created tasklist ${tasklist?.get("guid")?.asString}")
                    Toolresult.success(mapOf("tasklist" to tasklist))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val tasklistGuid = args["tasklist_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: tasklist_guid")
                    Log.i(TAG, "get: tasklist_guid=$tasklistGuid")

                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.get("$basePath/$tasklistGuid$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    Log.i(TAG, "get: returned tasklist $tasklistGuid")
                    Toolresult.success(mapOf("tasklist" to json?.getAsJsonObject("data")?.getAsJsonObject("tasklist")))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "list" -> {
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    Log.i(TAG, "list: page_size=${pageSize ?: 50}")

                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken,
                        "user_id_type" to "open_id"
                    )
                    val result = client.get("$basePath$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    val items = data?.getAsJsonArray("items")
                    Log.i(TAG, "list: returned ${items?.size() ?: 0} tasklists")
                    Toolresult.success(mapOf(
                        "tasklists" to items,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.asString
                    ))
                }

                // -----------------------------------------------------------------
                // TASKS - List tasks in tasklist
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // completed is boolean
                // -----------------------------------------------------------------
                "tasks" -> {
                    val tasklistGuid = args["tasklist_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: tasklist_guid")
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    val completedRaw = args["completed"]
                    val completed: Boolean? = when (completedRaw) {
                        is Boolean -> completedRaw
                        is String -> completedRaw.toBooleanStrictOrNull()
                        else -> null
                    }
                    Log.i(TAG, "tasks: tasklist_guid=$tasklistGuid, completed=${completed ?: "all"}")

                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken,
                        "completed" to completed,
                        "user_id_type" to "open_id"
                    )
                    val result = client.get("$basePath/$tasklistGuid/tasks$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    val items = data?.getAsJsonArray("items")
                    Log.i(TAG, "tasks: returned ${items?.size() ?: 0} tasks")
                    Toolresult.success(mapOf(
                        "tasks" to items,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.asString
                    ))
                }

                // -----------------------------------------------------------------
                // PATCH
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // wraps as {tasklist: {name}, update_fields: ['name']}
                // -----------------------------------------------------------------
                "patch" -> {
                    val tasklistGuid = args["tasklist_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: tasklist_guid")
                    val name = args["name"] as? String
                    Log.i(TAG, "patch: tasklist_guid=$tasklistGuid, name=$name")

                    // Feishu Task API requires special update format
                    val tasklistData = mutableMapOf<String, Any>()
                    val updateFields = mutableListOf<String>()

                    if (name != null) {
                        tasklistData["name"] = name
                        updateFields.add("name")
                    }

                    if (updateFields.isEmpty()) {
                        return@withContext Toolresult.success(mapOf("error" to "No fields to update"))
                    }

                    val body = mapOf(
                        "tasklist" to tasklistData,
                        "update_fields" to updateFields
                    )

                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.patch("$basePath/$tasklistGuid$query", body)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    Log.i(TAG, "patch: updated tasklist $tasklistGuid")
                    Toolresult.success(mapOf("tasklist" to json?.getAsJsonObject("data")?.getAsJsonObject("tasklist")))
                }

                // -----------------------------------------------------------------
                // ADD_MEMBERS
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // endpoint: /open-apis/task/v2/tasklists/:guid/add_members
                // Transform members: add type='user', default role='editor'
                // -----------------------------------------------------------------
                "add_members" -> {
                    @Suppress("UNCHECKED_CAST")
                    val members = args["members"] as? List<Map<String, Any?>>
                    if (members == null || members.isEmpty()) {
                        return@withContext Toolresult.success(mapOf("error" to "members is required and cannot be empty"))
                    }

                    val tasklistGuid = args["tasklist_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: tasklist_guid")
                    Log.i(TAG, "add_members: tasklist_guid=$tasklistGuid, members_count=${members.size}")

                    val memberData = members.map { m ->
                        mapOf(
                            "id" to (m["id"] ?: ""),
                            "type" to "user",
                            "role" to (m["role"] ?: "editor")
                        )
                    }

                    val body = mapOf("members" to memberData)
                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.post("$basePath/$tasklistGuid/add_members$query", body)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    Log.i(TAG, "add_members: added ${members.size} members to tasklist $tasklistGuid")
                    Toolresult.success(mapOf("tasklist" to json?.getAsJsonObject("data")?.getAsJsonObject("tasklist")))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, get, list, tasks, patch, add_members")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_task_tasklist failed", e)
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
                        enum = listOf("create", "get", "list", "tasks", "patch", "add_members")
                    ),
                    "tasklist_guid" to PropertySchema("string", "Task list GUID (get/tasks/patch/add_members required)"),
                    "name" to PropertySchema("string", "Task list name (create required, patch optional)"),
                    "members" to PropertySchema(
                        "array", "Task list member list (editor=can edit, viewer=can view). Note: Creator auto becomes owner",
                        items = PropertySchema("object", "Member object {id: open_id, role?: 'editor'|'viewer'}")
                    ),
                    "completed" to PropertySchema("boolean", "Whether to return only completed tasks (tasks optional, default return all)"),
                    "page_size" to PropertySchema("number", "Page size, Default 50, Max 100"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuTaskTasklist"
    }
}

// ─────────────────────────────────────────────────────────────
// 3. FeishuTaskSubtaskTool — feishu_task_subtask
// Translated from: task/subtask.js
// Actions: create, list
// ─────────────────────────────────────────────────────────────

class FeishuTaskSubtaskTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_task_subtask"
    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override val description =
        "【As user】Feishu task subtask management tool. Use when user asks to create subtask, query task's subtask list. " +
        "Actions: create (create subtask), list (list all subtasks of task). "

    override fun isEnabledd() = config.enableTaskTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // Members: add type='user', default role='assignee'
                // due/start same object structure as task.js
                // -----------------------------------------------------------------
                "create" -> {
                    val taskGuid = args["task_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: task_guid")
                    val summary = args["summary"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: summary")
                    Log.i(TAG, "create: task_guid=$taskGuid, summary=$summary")

                    val data = mutableMapOf<String, Any>("summary" to summary)

                    val description = args["description"] as? String
                    if (description != null) data["description"] = description

                    // ConvertDue time
                    if (args["due"] != null) {
                        val dueObj = parseDueStartObject(args["due"])
                        if (dueObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "Time format error! due.timestamp must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00', current value: ${(args["due"] as? Map<*, *>)?.get("timestamp")}"
                            ))
                        }
                        data["due"] = dueObj
                    }

                    // Convert start time
                    if (args["start"] != null) {
                        val startObj = parseDueStartObject(args["start"])
                        if (startObj == null) {
                            return@withContext Toolresult.success(mapOf(
                                "error" to "Time format error! start.timestamp must use ISO 8601 / RFC 3339 format (with timezone), e.g. '2024-01-01T00:00:00+08:00', current value: ${(args["start"] as? Map<*, *>)?.get("timestamp")}"
                            ))
                        }
                        data["start"] = startObj
                    }

                    // Convert member format: add type='user', default role='assignee'
                    @Suppress("UNCHECKED_CAST")
                    val members = args["members"] as? List<Map<String, Any?>>
                    if (members != null && members.isNotEmpty()) {
                        data["members"] = members.map { m ->
                            mapOf(
                                "id" to (m["id"] ?: ""),
                                "type" to "user",
                                "role" to (m["role"] ?: "assignee")
                            )
                        }
                    }

                    val basePath = "/open-apis/task/v2/tasks/$taskGuid/subtasks"
                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.post("$basePath$query", data)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val subtask = json?.getAsJsonObject("data")?.getAsJsonObject("subtask")
                    Log.i(TAG, "create: created subtask ${subtask?.get("guid")?.asString ?: "unknown"}")
                    Toolresult.success(mapOf("subtask" to subtask))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "list" -> {
                    val taskGuid = args["task_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: task_guid")
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    Log.i(TAG, "list: task_guid=$taskGuid, page_size=${pageSize ?: 50}")

                    val basePath = "/open-apis/task/v2/tasks/$taskGuid/subtasks"
                    val query = buildQuery(
                        "page_size" to pageSize,
                        "page_token" to pageToken,
                        "user_id_type" to "open_id"
                    )
                    val result = client.get("$basePath$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    val items = data?.getAsJsonArray("items")
                    Log.i(TAG, "list: returned ${items?.size() ?: 0} subtasks")
                    Toolresult.success(mapOf(
                        "subtasks" to items,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.asString
                    ))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_task_subtask failed", e)
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
                        enum = listOf("create", "list")
                    ),
                    "task_guid" to PropertySchema("string", "Parent task GUID"),
                    "summary" to PropertySchema("string", "Subtask title (create required)"),
                    "description" to PropertySchema("string", "Subtask description (create optional)"),
                    "due" to PropertySchema(
                        "object", "Due time object",
                        properties = mapOf(
                            "timestamp" to PropertySchema("string", "Due time (ISO 8601 / RFC 3339 format with timezone, e.g. '2024-01-01T00:00:00+08:00')"),
                            "is_all_day" to PropertySchema("boolean", "Whether all-day task")
                        )
                    ),
                    "start" to PropertySchema(
                        "object", "Start time object",
                        properties = mapOf(
                            "timestamp" to PropertySchema("string", "Start time (ISO 8601 / RFC 3339 format with timezone, e.g. '2024-01-01T00:00:00+08:00')"),
                            "is_all_day" to PropertySchema("boolean", "Whether all-day")
                        )
                    ),
                    "members" to PropertySchema(
                        "array", "Subtask member list (assignee=assignee, follower=follower)",
                        items = PropertySchema("object", "Member object {id: open_id, role?: 'assignee'|'follower'}")
                    ),
                    "page_size" to PropertySchema("number", "Page size, Default 50, Max 100"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action", "task_guid")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuTaskSubtask"
    }
}

// ─────────────────────────────────────────────────────────────
// 4. FeishuTaskCommentTool — feishu_task_comment
// Translated from: task/comment.js
// Actions: create, list, get
// ─────────────────────────────────────────────────────────────

class FeishuTaskCommentTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    override val name = "feishu_task_comment"
    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override val description =
        "【As user】Feishu task comment management tool. Use when user asks to add/query task comment, reply to comment. " +
        "Actions: create (add comment), list (list all task comments), get (get single comment details). "

    override fun isEnabledd() = config.enableTaskTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                // -----------------------------------------------------------------
                // CREATE
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // CRITICAL: POST /open-apis/task/v2/comments with resource_type='task', resource_id in body
                // -----------------------------------------------------------------
                "create" -> {
                    val taskGuid = args["task_guid"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: task_guid")
                    val content = args["content"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: content")
                    val replyToCommentId = args["reply_to_comment_id"] as? String
                    Log.i(TAG, "create: task_guid=$taskGuid, reply_to=${replyToCommentId ?: "none"}")

                    val data = mutableMapOf<String, Any>(
                        "content" to content,
                        "resource_type" to "task",
                        "resource_id" to taskGuid
                    )
                    if (replyToCommentId != null) {
                        data["reply_to_comment_id"] = replyToCommentId
                    }

                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.post("/open-apis/task/v2/comments$query", data)
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val comment = json?.getAsJsonObject("data")?.getAsJsonObject("comment")
                    Log.i(TAG, "create: created comment ${comment?.get("id")?.asString}")
                    Toolresult.success(mapOf("comment" to comment))
                }

                // -----------------------------------------------------------------
                // LIST
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // CRITICAL: GET /open-apis/task/v2/comments with resource_type=task&resource_id=... as query params
                // -----------------------------------------------------------------
                "list" -> {
                    val resourceId = args["resource_id"] as? String
                        ?: (args["task_guid"] as? String)
                        ?: return@withContext Toolresult.error("Missing required parameter: resource_id or task_guid")
                    val direction = args["direction"] as? String
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    Log.i(TAG, "list: resource_id=$resourceId, direction=${direction ?: "asc"}, page_size=${pageSize ?: 50}")

                    val query = buildQuery(
                        "resource_type" to "task",
                        "resource_id" to resourceId,
                        "direction" to direction,
                        "page_size" to pageSize,
                        "page_token" to pageToken,
                        "user_id_type" to "open_id"
                    )
                    val result = client.get("/open-apis/task/v2/comments$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    val data = json?.getAsJsonObject("data")
                    val items = data?.getAsJsonArray("items")
                    Log.i(TAG, "list: returned ${items?.size() ?: 0} comments")
                    Toolresult.success(mapOf(
                        "comments" to items,
                        "has_more" to (data?.get("has_more")?.asBoolean ?: false),
                        "page_token" to data?.get("page_token")?.asString
                    ))
                }

                // -----------------------------------------------------------------
                // GET
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                // -----------------------------------------------------------------
                "get" -> {
                    val commentId = args["comment_id"] as? String
                        ?: return@withContext Toolresult.error("Missing required parameter: comment_id")
                    Log.i(TAG, "get: comment_id=$commentId")

                    val query = buildQuery("user_id_type" to "open_id")
                    val result = client.get("/open-apis/task/v2/comments/$commentId$query")
                    if (result.isFailure) return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")

                    val json = result.getOrNull()
                    Log.i(TAG, "get: returned comment $commentId")
                    Toolresult.success(mapOf("comment" to json?.getAsJsonObject("data")?.getAsJsonObject("comment")))
                }

                else -> Toolresult.error("Unknown action: $action. Supported: create, list, get")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_task_comment failed", e)
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
                        enum = listOf("create", "list", "get")
                    ),
                    "task_guid" to PropertySchema("string", "Task GUID (create required)"),
                    "resource_id" to PropertySchema("string", "Resource ID for comment (Task GUID) (list required)"),
                    "comment_id" to PropertySchema("string", "Comment ID (get required)"),
                    "content" to PropertySchema("string", "Comment content (plain text, max 3000 characters) (create required)"),
                    "reply_to_comment_id" to PropertySchema("string", "Comment ID to reply to (for replying to comment) (create optional)"),
                    "direction" to PropertySchema("string", "Sort direction (asc=oldest first, desc=newest first, default asc)",
                        enum = listOf("asc", "desc")),
                    "page_size" to PropertySchema("number", "Page size, Default 50, Max 100"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action")
            )
        )
    )

    companion object {
        private const val TAG = "FeishuTaskComment"
    }
}
