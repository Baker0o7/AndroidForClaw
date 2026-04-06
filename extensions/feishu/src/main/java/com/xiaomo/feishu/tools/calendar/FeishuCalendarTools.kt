package com.xiaomo.feishu.tools.calendar

/**
 * Feishu Calendar tool set.
 * Line-by-line translation from @larksuite/openclaw-lark JS source.
 * - feishu_calendar_calendar: calendar management (list, get, primary)
 * - feishu_calendar_event: event management (create, list, get, patch, delete, search, reply, instances, instance_view)
 * - feishu_calendar_event_attendee: attendee management (create, list)
 * - feishu_calendar_freebusy: free/busy query
 */

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FeishuCalendarTools"

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
 * Auto-detects seconds vs milliseconds based on magnitude.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun unixTimestampToISO8601(raw: Any?): String? {
    if (raw == null) return null
    val text = when (raw) {
        is Number -> raw.toLong().toString()
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
 * Parse ISO 8601 / bare datetime -> Unix seconds string.
 * Supports: '2024-01-01T00:00:00+08:00', '2026-02-25 14:30', '2026-02-25T14:30:00'
 * No timezone => treat as Beijing time (UTC+8).
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToTimestamp(input: String): String? {
    return try {
        val trimmed = input.trim()
        // Check if has timezone info (Z or +/- offset)
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)
        if (hasTimezone) {
            // Has timezone, parse directly
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return (date.time / 1000).toString()
        }
        // No timezone, treat as Beijing time
        // Support: YYYY-MM-DD HH:mm or YYYY-MM-DD HH:mm:ss or YYYY-MM-DDTHH:mm:ss
        val normalized = trimmed.replace('T', ' ')
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$").matchEntire(normalized)
        if (match == null) {
            // Try direct parse (other ISO 8601 formats)
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
        // Treat as Beijing time (UTC+8), convert to UTC
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, hour - 8, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        (cal.timeInMillis / 1000).toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse ISO 8601 / bare datetime -> Unix milliseconds string.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToTimestampMs(input: String): String? {
    return try {
        val trimmed = input.trim()
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)
        if (hasTimezone) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return date.time.toString()
        }
        val normalized = trimmed.replace('T', ' ')
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$").matchEntire(normalized)
        if (match == null) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return date.time.toString()
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
        cal.timeInMillis.toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Parse ISO 8601 / bare datetime -> RFC 3339 string (for freebusy API).
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeToRFC3339(input: String): String? {
    return try {
        val trimmed = input.trim()
        val hasTimezone = Regex("[Zz]$|[+-]\\d{2}:\\d{2}$").containsMatchIn(trimmed)
        if (hasTimezone) {
            // Has timezone, validate then return as-is
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return trimmed
        }
        // No timezone, treat as Beijing time, construct RFC 3339
        val normalized = trimmed.replace('T', ' ')
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::(\\d{2}))?$").matchEntire(normalized)
        if (match == null) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            val date = try { sdf.parse(trimmed) } catch (_: Exception) { null }
            if (date == null) return null
            return if (trimmed.contains('T')) "${trimmed}+08:00" else trimmed
        }
        val year = match.groupValues[1]
        val month = match.groupValues[2]
        val day = match.groupValues[3]
        val hour = match.groupValues[4]
        val minute = match.groupValues[5]
        val sec = if (match.groupValues[6].isNotEmpty()) match.groupValues[6] else "00"
        "$year-$month-${day}T$hour:$minute:$sec+08:00"
    } catch (_: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// Time normalization helpers — translated from event.js
// ---------------------------------------------------------------------------

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun normalizeCalendarTimeValue(value: Any?): String? {
    if (value == null) return null
    if (value is String) {
        val iso = unixTimestampToISO8601(value)
        return iso ?: value
    }
    if (value !is Map<*, *>) return null
    val timeObj = value
    val fromTimestamp = unixTimestampToISO8601(timeObj["timestamp"])
    if (fromTimestamp != null) return fromTimestamp
    val dateVal = timeObj["date"]
    if (dateVal is String) return dateVal
    return null
}

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun normalizeEventTimeFields(event: Map<String, Any?>?): Map<String, Any?>? {
    if (event == null) return null
    val normalized = event.toMutableMap()
    val startTime = normalizeCalendarTimeValue(event["start_time"])
    if (startTime != null) normalized["start_time"] = startTime
    val endTime = normalizeCalendarTimeValue(event["end_time"])
    if (endTime != null) normalized["end_time"] = endTime
    val createTime = unixTimestampToISO8601(event["create_time"])
    if (createTime != null) normalized["create_time"] = createTime
    return normalized
}

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun normalizeEventTimeFieldsJson(event: JsonObject?): JsonObject? {
    if (event == null) return null
    val normalized = event.deepCopy()
    // start_time
    val startTime = event.get("start_time")
    if (startTime != null) {
        val iso = if (startTime.isJsonPrimitive) {
            unixTimestampToISO8601(startTime.asString)
        } else if (startTime.isJsonObject) {
            val ts = startTime.asJsonObject.get("timestamp")?.asString
            if (ts != null) unixTimestampToISO8601(ts)
            else startTime.asJsonObject.get("date")?.asString
        } else null
        if (iso != null) normalized.addProperty("start_time", iso)
    }
    // end_time
    val endTime = event.get("end_time")
    if (endTime != null) {
        val iso = if (endTime.isJsonPrimitive) {
            unixTimestampToISO8601(endTime.asString)
        } else if (endTime.isJsonObject) {
            val ts = endTime.asJsonObject.get("timestamp")?.asString
            if (ts != null) unixTimestampToISO8601(ts)
            else endTime.asJsonObject.get("date")?.asString
        } else null
        if (iso != null) normalized.addProperty("end_time", iso)
    }
    // create_time
    val createTime = event.get("create_time")?.asString
    if (createTime != null) {
        val iso = unixTimestampToISO8601(createTime)
        if (iso != null) normalized.addProperty("create_time", iso)
    }
    return normalized
}

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun normalizeEventListTimeFieldsJson(items: JsonArray?): JsonArray? {
    if (items == null) return null
    val result = JsonArray()
    for (item in items) {
        if (item.isJsonObject) {
            result.add(normalizeEventTimeFieldsJson(item.asJsonObject) ?: item)
        } else {
            result.add(item)
        }
    }
    return result
}

// ─── Helper: resolve primary calendar_id ──────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
private suspend fun resolveCalendarId(calendarId: String?, client: FeishuClient): String {
    if (!calendarId.isNullOrBlank()) return calendarId
    val result = client.post("/open-apis/calendar/v4/calendars/primary", emptyMap<String, Any>())
    if (result.isFailure) {
        throw IllegalStateException("Could not determine primary calendar: ${result.exceptionOrNull()?.message}")
    }
    val data = result.getOrNull()?.getAsJsonObject("data")
    val calendars = data?.getAsJsonArray("calendars")
    if (calendars != null && calendars.size() > 0) {
        val cid = calendars[0].asJsonObject
            ?.getAsJsonObject("calendar")
            ?.get("calendar_id")?.asString
        if (cid != null) {
            Log.i(TAG, "resolveCalendarId: primary() returned calendar_id=$cid")
            return cid
        }
    }
    throw IllegalStateException("Could not determine primary calendar")
}

// ─── feishu_calendar_calendar ──────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuCalendarCalendarTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_calendar_calendar"
    override val description = "【As user】飞书日历Manage工具. 用于Query日历List、Get日历Info、Query主日历. " +
        "Actions: list(Query日历List), get(Query指定日历Info), primary(Query主日历Info). "

    override fun isEnabledd() = config.enableCalendarTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "list" -> {
                    val pageSize = (args["page_size"] as? Number)?.toInt()
                    val pageToken = args["page_token"] as? String
                    Log.i(TAG, "list: page_size=${pageSize ?: 50}, page_token=${pageToken ?: "none"}")

                    val params = mutableListOf<String>()
                    if (pageSize != null) params.add("page_size=$pageSize")
                    if (pageToken != null) params.add("page_token=$pageToken")
                    val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""

                    val result = client.get("/open-apis/calendar/v4/calendars$query")
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to list calendars")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    val calendars = data?.getAsJsonArray("calendar_list") ?: JsonArray()
                    Log.i(TAG, "list: returned ${calendars.size()} calendars")
                    val response = JsonObject().apply {
                        add("calendars", calendars)
                        addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
                        data?.get("page_token")?.let { add("page_token", it) }
                    }
                    Toolresult.success(response)
                }
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "get" -> {
                    val calendarId = args["calendar_id"] as? String
                    if (calendarId.isNullOrBlank()) {
                        return@withContext Toolresult.error("calendar_id is required for 'get' action")
                    }
                    Log.i(TAG, "get: calendar_id=$calendarId")
                    val result = client.get("/open-apis/calendar/v4/calendars/$calendarId")
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get calendar")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    Log.i(TAG, "get: retrieved calendar $calendarId")
                    val response = JsonObject().apply {
                        add("calendar", data?.getAsJsonObject("calendar") ?: data)
                    }
                    Toolresult.success(response)
                }
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "primary" -> {
                    Log.i(TAG, "primary: querying primary calendar")
                    val result = client.post("/open-apis/calendar/v4/calendars/primary", emptyMap<String, Any>())
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get primary calendar")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    val calendars = data?.getAsJsonArray("calendars") ?: JsonArray()
                    Log.i(TAG, "primary: returned ${calendars.size()} primary calendars")
                    val response = JsonObject().apply {
                        add("calendars", calendars)
                    }
                    Toolresult.success(response)
                }
                else -> Toolresult.error("Unknown action: $action. Must be one of: list, get, primary")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_calendar_calendar failed", e)
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
                    "action" to PropertySchema("string", "Action type", enum = listOf("list", "get", "primary")),
                    "calendar_id" to PropertySchema("string", "Calendar ID"),
                    "page_size" to PropertySchema("integer", "Number of calendars to return per page (default: 50, max: 1000)"),
                    "page_token" to PropertySchema("string", "Pagination token for next page")
                ),
                required = listOf("action")
            )
        )
    )
}

// ─── feishu_calendar_event ─────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuCalendarEventTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_calendar_event"
    override val description = "【As user】飞书日程Manage工具. 当User要求View日程、Create会议、约会议、Modify日程、" +
        "Delete日程、Search日程、回复日程邀请时use. Actions: create(Create日历Event), list(QueryTimeRangeInside的日程, Auto展开Duplicate日程), " +
        "get(Get日程Details), patch(Update日程), delete(Delete日程), search(Search日程), reply(回复日程邀请), " +
        "instances(GetDuplicate日程的InstanceList, 仅对Duplicate日程Valid), instance_view(View展开Back的日程List). " +
        "[Important]create 时Must传 user_open_id Parameters, Value为MessageUpDown文中的 SenderId(格式 ou_xxx), No则日程只在apply日历Up, Usercompletely看不到. " +
        "list Actionuse instance_view Interface, 会Auto展开Duplicate日程为MultipleInstance, Time区间cannot超过40天, ReturnInstance数量Up限1000. " +
        "Time parametersuseISO 8601 / RFC 3339 格式(with timezone), e.g. '2024-01-01T00:00:00+08:00'. "

    override fun isEnabledd() = config.enableCalendarTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val calendarIdArg = args["calendar_id"] as? String

            when (action) {
                "create" -> doCreate(calendarIdArg, args)
                "list" -> doList(calendarIdArg, args)
                "get" -> doGet(calendarIdArg, args)
                "patch" -> doPatch(calendarIdArg, args)
                "delete" -> doDelete(calendarIdArg, args)
                "search" -> doSearch(calendarIdArg, args)
                "reply" -> doReply(calendarIdArg, args)
                "instances" -> doInstances(calendarIdArg, args)
                "instance_view" -> doInstanceView(calendarIdArg, args)
                else -> Toolresult.error(
                    "Unknown action: $action. Must be one of: create, list, get, patch, delete, search, reply, instances, instance_view"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_calendar_event failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doCreate(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val summary = args["summary"] as? String
        if (summary.isNullOrBlank()) return Toolresult.error("summary is required")
        val startTimeStr = args["start_time"] as? String
            ?: return Toolresult.error("start_time is required")
        val endTimeStr = args["end_time"] as? String
            ?: return Toolresult.error("end_time is required")
        val userOpenId = args["user_open_id"] as? String

        val startTs = parseTimeToTimestamp(startTimeStr)
        val endTs = parseTimeToTimestamp(endTimeStr)
        if (startTs == null || endTs == null) {
            return Toolresult.error(mapOf(
                "error" to "Invalid time format. Must use ISO 8601 / RFC 3339 with timezone, e.g. '2024-01-01T00:00:00+08:00' or '2026-02-25 14:00:00'. Do not pass Unix timestamp numbers.",
                "received_start" to startTimeStr,
                "received_end" to endTimeStr
            ).toString())
        }

        Log.i(TAG, "create: summary=$summary, start_time=$startTimeStr -> ts=$startTs, end_time=$endTimeStr -> ts=$endTs, user_open_id=${userOpenId ?: "MISSING"}")

        // Resolve bot's calendar
        val calendarId = resolveCalendarId(calendarIdArg, client)

        val eventData = mutableMapOf<String, Any?>(
            "summary" to summary,
            "start_time" to mapOf("timestamp" to startTs),
            "end_time" to mapOf("timestamp" to endTs),
            "need_notification" to true,
            "attendee_ability" to (args["attendee_ability"] as? String ?: "can_modify_event")
        )

        (args["description"] as? String)?.let { eventData["description"] = it }

        // vchat
        @Suppress("UNCHECKED_CAST")
        (args["vchat"] as? Map<String, Any?>)?.let { vchat ->
            val vchatData = mutableMapOf<String, Any?>()
            (vchat["vc_type"] as? String)?.let { vchatData["vc_type"] = it }
            (vchat["icon_type"] as? String)?.let { vchatData["icon_type"] = it }
            (vchat["description"] as? String)?.let { vchatData["description"] = it }
            (vchat["meeting_url"] as? String)?.let { vchatData["meeting_url"] = it }
            if (vchatData.isNotEmpty()) eventData["vchat"] = vchatData
        }

        // visibility
        (args["visibility"] as? String)?.let { eventData["visibility"] = it }

        // free_busy_status
        (args["free_busy_status"] as? String)?.let { eventData["free_busy_status"] = it }

        // location (OBJECT with name, address, latitude, longitude)
        @Suppress("UNCHECKED_CAST")
        (args["location"] as? Map<String, Any?>)?.let { loc ->
            val locationData = mutableMapOf<String, Any?>()
            (loc["name"] as? String)?.let { locationData["name"] = it }
            (loc["address"] as? String)?.let { locationData["address"] = it }
            (loc["latitude"] as? Number)?.let { locationData["latitude"] = it }
            (loc["longitude"] as? Number)?.let { locationData["longitude"] = it }
            if (locationData.isNotEmpty()) eventData["location"] = locationData
        }

        // reminders
        @Suppress("UNCHECKED_CAST")
        (args["reminders"] as? List<Map<String, Any?>>)?.let { reminders ->
            eventData["reminders"] = reminders.map { r -> mapOf("minutes" to r["minutes"]) }
        }

        // recurrence
        (args["recurrence"] as? String)?.let { eventData["recurrence"] = it }

        val result = client.post("/open-apis/calendar/v4/calendars/$calendarId/events", eventData)
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to create event")
        }
        val eventresult = result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("event")
        val eventId = eventresult?.get("event_id")?.asString
        Log.i(TAG, "event created: event_id=$eventId")

        // Build attendee list: merge explicit attendees + user_open_id
        @Suppress("UNCHECKED_CAST")
        val allAttendees = (args["attendees"] as? List<Map<String, Any?>>)?.toMutableList() ?: mutableListOf()
        if (userOpenId != null) {
            val alreadyIncluded = allAttendees.any { it["type"] == "user" && it["id"] == userOpenId }
            if (!alreadyIncluded) {
                allAttendees.add(mapOf("type" to "user", "id" to userOpenId))
            }
        }
        Log.i(TAG, "allAttendees=$allAttendees")

        var attendeeError: String? = null
        val operateId = userOpenId ?: allAttendees.firstOrNull { it["type"] == "user" }?.get("id") as? String

        if (allAttendees.isNotEmpty() && eventId != null) {
            val attendeeData = allAttendees.map { a ->
                val entry = mutableMapOf<String, Any?>(
                    "type" to a["type"],
                    "user_id" to if (a["type"] == "user") a["id"] else null,
                    "chat_id" to if (a["type"] == "chat") a["id"] else null,
                    "room_id" to if (a["type"] == "resource") a["id"] else null,
                    "third_party_email" to if (a["type"] == "third_party") a["id"] else null,
                    "operate_id" to operateId
                ).filterValues { it != null }
                entry
            }
            try {
                val attendeeresult = client.post(
                    "/open-apis/calendar/v4/calendars/$calendarId/events/$eventId/attendees?user_id_type=open_id",
                    mapOf("attendees" to attendeeData, "need_notification" to true)
                )
                if (attendeeresult.isFailure) {
                    attendeeError = attendeeresult.exceptionOrNull()?.message ?: "Failed to add attendees"
                    Log.i(TAG, "attendee add FAILED: $attendeeError")
                } else {
                    Log.i(TAG, "attendee API response: ${attendeeresult.getOrNull()?.getAsJsonObject("data")}")
                }
            } catch (e: Exception) {
                attendeeError = e.message
                Log.i(TAG, "attendee add FAILED: $attendeeError")
            }
        }

        // Build response
        val appLink = eventresult?.get("app_link")?.asString
        val safeEvent = if (eventresult != null) mapOf(
            "event_id" to eventId,
            "summary" to summary,
            "app_link" to appLink,
            "start_time" to (unixTimestampToISO8601(startTs) ?: startTimeStr),
            "end_time" to (unixTimestampToISO8601(endTs) ?: endTimeStr)
        ) else null

        val response = mutableMapOf<String, Any?>(
            "event" to safeEvent,
            "attendees" to allAttendees.map { mapOf("type" to it["type"], "id" to it["id"]) },
            "_debug" to mapOf(
                "calendar_id" to calendarId,
                "operate_id" to operateId,
                "start_input" to startTimeStr,
                "start_iso8601" to (unixTimestampToISO8601(startTs) ?: startTimeStr),
                "end_input" to endTimeStr,
                "end_iso8601" to (unixTimestampToISO8601(endTs) ?: endTimeStr),
                "attendees_count" to allAttendees.size
            )
        )

        if (attendeeError != null) {
            response["warning"] = "日程已Create, 但Add参会人Failed: $attendeeError"
        } else if (allAttendees.isEmpty()) {
            response["error"] = "日程已Create在apply日历Up, 但未Add任何参会人, User看不到此日程. 请重Newcall时传入 user_open_id Parameters. "
        } else {
            response["note"] = "已SuccessAdd ${allAttendees.size} 位参会人, 日程应出now参会人的飞书日历中. "
        }

        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doList(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val startTimeStr = args["start_time"] as? String
            ?: return Toolresult.error("start_time is required")
        val endTimeStr = args["end_time"] as? String
            ?: return Toolresult.error("end_time is required")

        val startTs = parseTimeToTimestamp(startTimeStr)
        val endTs = parseTimeToTimestamp(endTimeStr)
        if (startTs == null || endTs == null) {
            return Toolresult.error(mapOf(
                "error" to "Invalid time format. Must use ISO 8601 / RFC 3339 with timezone, e.g. '2024-01-01T00:00:00+08:00' or '2026-02-25 14:00:00'. Do not pass Unix timestamps.",
                "received_start" to startTimeStr,
                "received_end" to endTimeStr
            ).toString())
        }

        val cid = resolveCalendarId(calendarIdArg, client)
        Log.i(TAG, "list: calendar_id=$cid, start_time=$startTs, end_time=$endTs (using instance_view)")

        // Use instance_view endpoint (NOT /events)
        val result = client.get(
            "/open-apis/calendar/v4/calendars/$cid/events/instance_view?start_time=$startTs&end_time=$endTs&user_id_type=open_id"
        )
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to list events")
        }
        val data = result.getOrNull()?.getAsJsonObject("data")
        val items = data?.getAsJsonArray("items")
        Log.i(TAG, "list: returned ${items?.size() ?: 0} event instances")

        val response = JsonObject().apply {
            add("events", normalizeEventListTimeFieldsJson(items))
            addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
            data?.get("page_token")?.let { add("page_token", it) }
        }
        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doGet(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val eventId = args["event_id"] as? String
            ?: return Toolresult.error("event_id is required")
        val cid = resolveCalendarId(calendarIdArg, client)
        Log.i(TAG, "get: calendar_id=$cid, event_id=$eventId")

        val result = client.get("/open-apis/calendar/v4/calendars/$cid/events/$eventId")
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get event")
        }
        Log.i(TAG, "get: retrieved event $eventId")
        val event = result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("event")
        val response = JsonObject().apply {
            add("event", normalizeEventTimeFieldsJson(event))
        }
        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doPatch(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val eventId = args["event_id"] as? String
            ?: return Toolresult.error("event_id is required")
        val cid = resolveCalendarId(calendarIdArg, client)

        val updateData = mutableMapOf<String, Any?>()

        // Handle time conversion if provided
        (args["start_time"] as? String)?.let { timeStr ->
            val startTs = parseTimeToTimestamp(timeStr)
                ?: return Toolresult.error("start_time Format error!Must useISO 8601 / RFC 3339 格式(with timezone), e.g. '2024-01-01T00:00:00+08:00'")
            updateData["start_time"] = mapOf("timestamp" to startTs)
        }
        (args["end_time"] as? String)?.let { timeStr ->
            val endTs = parseTimeToTimestamp(timeStr)
                ?: return Toolresult.error("end_time Format error!Must useISO 8601 / RFC 3339 格式(with timezone), e.g. '2024-01-01T00:00:00+08:00'")
            updateData["end_time"] = mapOf("timestamp" to endTs)
        }

        (args["summary"] as? String)?.let { updateData["summary"] = it }
        (args["description"] as? String)?.let { updateData["description"] = it }
        // Location in patch is STRING, wrapped as {name: string}
        (args["location"] as? String)?.let { updateData["location"] = mapOf("name" to it) }

        Log.i(TAG, "patch: calendar_id=$cid, event_id=$eventId, fields=${updateData.keys.joinToString(",")}")
        val result = client.patch("/open-apis/calendar/v4/calendars/$cid/events/$eventId", updateData)
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to patch event")
        }
        Log.i(TAG, "patch: updated event $eventId")
        val event = result.getOrNull()?.getAsJsonObject("data")?.getAsJsonObject("event")
        val response = JsonObject().apply {
            add("event", normalizeEventTimeFieldsJson(event))
        }
        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doDelete(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val eventId = args["event_id"] as? String
            ?: return Toolresult.error("event_id is required")
        val cid = resolveCalendarId(calendarIdArg, client)
        val needNotification = args["need_notification"] as? Boolean ?: true
        Log.i(TAG, "delete: calendar_id=$cid, event_id=$eventId, notify=$needNotification")

        val result = client.delete("/open-apis/calendar/v4/calendars/$cid/events/$eventId?need_notification=$needNotification")
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to delete event")
        }
        Log.i(TAG, "delete: deleted event $eventId")
        return Toolresult.success(mapOf("success" to true, "event_id" to eventId))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doSearch(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val query = args["query"] as? String
            ?: return Toolresult.error("query is required")
        val cid = resolveCalendarId(calendarIdArg, client)

        val params = mutableListOf<String>()
        (args["page_size"] as? Number)?.let { params.add("page_size=${it.toInt()}") }
        (args["page_token"] as? String)?.let { params.add("page_token=$it") }
        val queryStr = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""

        Log.i(TAG, "search: calendar_id=$cid, query=$query, page_size=${(args["page_size"] as? Number)?.toInt() ?: 50}")
        val result = client.post("/open-apis/calendar/v4/calendars/$cid/events/search$queryStr", mapOf("query" to query))
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to search events")
        }
        val data = result.getOrNull()?.getAsJsonObject("data")
        val items = data?.getAsJsonArray("items")
        Log.i(TAG, "search: found ${items?.size() ?: 0} events")

        val response = JsonObject().apply {
            add("events", normalizeEventListTimeFieldsJson(items))
            addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
            data?.get("page_token")?.let { add("page_token", it) }
        }
        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doReply(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val eventId = args["event_id"] as? String
            ?: return Toolresult.error("event_id is required")
        val rsvpStatus = args["rsvp_status"] as? String
            ?: return Toolresult.error("rsvp_status is required")
        val cid = resolveCalendarId(calendarIdArg, client)
        Log.i(TAG, "reply: calendar_id=$cid, event_id=$eventId, rsvp=$rsvpStatus")

        val result = client.post(
            "/open-apis/calendar/v4/calendars/$cid/events/$eventId/reply",
            mapOf("rsvp_status" to rsvpStatus)
        )
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to reply event")
        }
        Log.i(TAG, "reply: replied to event $eventId with $rsvpStatus")
        return Toolresult.success(mapOf("success" to true, "event_id" to eventId, "rsvp_status" to rsvpStatus))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doInstances(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val eventId = args["event_id"] as? String
            ?: return Toolresult.error("event_id is required")
        val startTimeStr = args["start_time"] as? String
            ?: return Toolresult.error("start_time is required")
        val endTimeStr = args["end_time"] as? String
            ?: return Toolresult.error("end_time is required")

        val startTs = parseTimeToTimestamp(startTimeStr)
        val endTs = parseTimeToTimestamp(endTimeStr)
        if (startTs == null || endTs == null) {
            return Toolresult.error(mapOf(
                "error" to "Invalid time format. Must use ISO 8601 / RFC 3339 with timezone, e.g. '2024-01-01T00:00:00+08:00'",
                "received_start" to startTimeStr,
                "received_end" to endTimeStr
            ).toString())
        }

        val cid = resolveCalendarId(calendarIdArg, client)
        Log.i(TAG, "instances: calendar_id=$cid, event_id=$eventId, start=$startTs, end=$endTs")

        val params = mutableListOf("start_time=$startTs", "end_time=$endTs")
        (args["page_size"] as? Number)?.let { params.add("page_size=${it.toInt()}") }
        (args["page_token"] as? String)?.let { params.add("page_token=$it") }
        val queryStr = params.joinToString("&")

        val result = client.get("/open-apis/calendar/v4/calendars/$cid/events/$eventId/instances?$queryStr")
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get instances")
        }
        val data = result.getOrNull()?.getAsJsonObject("data")
        val items = data?.getAsJsonArray("items")
        Log.i(TAG, "instances: returned ${items?.size() ?: 0} instances")

        val response = JsonObject().apply {
            add("instances", normalizeEventListTimeFieldsJson(items))
            addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
            data?.get("page_token")?.let { add("page_token", it) }
        }
        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun doInstanceView(calendarIdArg: String?, args: Map<String, Any?>): Toolresult {
        val startTimeStr = args["start_time"] as? String
            ?: return Toolresult.error("start_time is required")
        val endTimeStr = args["end_time"] as? String
            ?: return Toolresult.error("end_time is required")

        val startTs = parseTimeToTimestamp(startTimeStr)
        val endTs = parseTimeToTimestamp(endTimeStr)
        if (startTs == null || endTs == null) {
            return Toolresult.error(mapOf(
                "error" to "Invalid time format. Must use ISO 8601 / RFC 3339 with timezone, e.g. '2024-01-01T00:00:00+08:00'",
                "received_start" to startTimeStr,
                "received_end" to endTimeStr
            ).toString())
        }

        val cid = resolveCalendarId(calendarIdArg, client)
        Log.i(TAG, "instance_view: calendar_id=$cid, start=$startTs, end=$endTs")

        val params = mutableListOf("start_time=$startTs", "end_time=$endTs", "user_id_type=open_id")
        (args["page_size"] as? Number)?.let { params.add("page_size=${it.toInt()}") }
        (args["page_token"] as? String)?.let { params.add("page_token=$it") }
        val queryStr = params.joinToString("&")

        val result = client.get("/open-apis/calendar/v4/calendars/$cid/events/instance_view?$queryStr")
        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get instance view")
        }
        val data = result.getOrNull()?.getAsJsonObject("data")
        val items = data?.getAsJsonArray("items")
        Log.i(TAG, "instance_view: returned ${items?.size() ?: 0} events")

        val response = JsonObject().apply {
            add("events", normalizeEventListTimeFieldsJson(items))
            addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
            data?.get("page_token")?.let { add("page_token", it) }
        }
        return Toolresult.success(response)
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
                        enum = listOf("create", "list", "get", "patch", "delete", "search", "reply", "instances", "instance_view")
                    ),
                    "calendar_id" to PropertySchema("string", "Calendar ID (optional; primary calendar used if omitted)"),
                    "event_id" to PropertySchema("string", "Event ID"),
                    "summary" to PropertySchema("string", "日程Title(Optional, 但强烈suggest提供)"),
                    "description" to PropertySchema("string", "日程Description"),
                    "start_time" to PropertySchema("string", "Start time(Required). ISO 8601 / RFC 3339 格式(with timezone), e.g. '2024-01-01T00:00:00+08:00'"),
                    "end_time" to PropertySchema("string", "EndTime(Required). 格式同 start_time. ifUser未指定时长, Default为Start timeBack1小时. "),
                    "user_open_id" to PropertySchema("string", "当FrontRequestUser的 open_id(Optional, 但强烈suggest提供). 从MessageUpDown文的 SenderId FieldGet, 格式为 ou_xxx. "),
                    "attendees" to PropertySchema("array", "参会人List. type='user' 时 id 填 open_id, type='third_party' 时 id 填邮箱. ",
                        items = PropertySchema("object", "参会人 {type, id}")),
                    "vchat" to PropertySchema("object", "视频会议Info"),
                    "visibility" to PropertySchema("string", "日程公开Range", enum = listOf("default", "public", "private")),
                    "attendee_ability" to PropertySchema("string", "参与人Permission(Default can_modify_event)",
                        enum = listOf("none", "can_see_others", "can_invite_others", "can_modify_event")),
                    "free_busy_status" to PropertySchema("string", "忙闲Status", enum = listOf("busy", "free")),
                    "location" to PropertySchema("object", "日程地点Info {name, address, latitude, longitude}"),
                    "reminders" to PropertySchema("array", "日程NotifyList"),
                    "recurrence" to PropertySchema("string", "Duplicate日程的Duplicate性Rule(RFC5545 RRULE 格式)"),
                    "need_notification" to PropertySchema("boolean", "YesNoNotification参会人(delete 时use, Default true)"),
                    "query" to PropertySchema("string", "Search关Key词(search 时Required)"),
                    "rsvp_status" to PropertySchema("string", "回复Status(reply 时Required)", enum = listOf("accept", "decline", "tentative")),
                    "page_size" to PropertySchema("integer", "Page size"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action")
            )
        )
    )
}

// ─── feishu_calendar_event_attendee ────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuCalendarEventAttendeeTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_calendar_event_attendee"
    override val description = "飞书日程参会人Manage工具. 当User要求邀请/Add参会人、View参会人List时use. " +
        "Actions: create(Add参会人), list(Query参会人List). "

    override fun isEnabledd() = config.enableCalendarTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")
            val calendarId = args["calendar_id"] as? String
                ?: return@withContext Toolresult.error("Missing calendar_id")
            val eventId = args["event_id"] as? String
                ?: return@withContext Toolresult.error("Missing event_id")

            val basePath = "/open-apis/calendar/v4/calendars/$calendarId/events/$eventId/attendees"

            when (action) {
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "create" -> {
                    @Suppress("UNCHECKED_CAST")
                    val attendees = args["attendees"] as? List<Map<String, Any?>>
                    if (attendees.isNullOrEmpty()) {
                        return@withContext Toolresult.error("attendees is required and cannot be empty")
                    }
                    Log.i(TAG, "create: calendar_id=$calendarId, event_id=$eventId, attendees_count=${attendees.size}")

                    // Map attendee_id to type-specific field
                    val attendeeData = attendees.map { a ->
                        val base = mutableMapOf<String, Any?>(
                            "type" to a["type"],
                            "is_optional" to false
                        )
                        when (a["type"]) {
                            "user" -> base["user_id"] = a["attendee_id"]
                            "chat" -> base["chat_id"] = a["attendee_id"]
                            "resource" -> base["room_id"] = a["attendee_id"]
                            "third_party" -> base["third_party_email"] = a["attendee_id"]
                        }
                        base
                    }

                    val needNotification = args["need_notification"] as? Boolean ?: true
                    val body = mapOf(
                        "attendees" to attendeeData,
                        "need_notification" to needNotification
                    )

                    val result = client.post("$basePath?user_id_type=open_id", body)
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to add attendees")
                    }
                    Log.i(TAG, "create: added ${attendees.size} attendees to event $eventId")
                    val response = JsonObject().apply {
                        add("attendees", result.getOrNull()?.getAsJsonObject("data")?.getAsJsonArray("attendees"))
                    }
                    Toolresult.success(response)
                }
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "list" -> {
                    Log.i(TAG, "list: calendar_id=$calendarId, event_id=$eventId, page_size=${(args["page_size"] as? Number)?.toInt() ?: 50}")

                    val params = mutableListOf<String>()
                    val userIdType = args["user_id_type"] as? String ?: "open_id"
                    params.add("user_id_type=$userIdType")
                    (args["page_size"] as? Number)?.let { params.add("page_size=${it.toInt()}") }
                    (args["page_token"] as? String)?.let { params.add("page_token=$it") }
                    val query = params.joinToString("&")

                    val result = client.get("$basePath?$query")
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to list attendees")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    Log.i(TAG, "list: returned ${data?.getAsJsonArray("items")?.size() ?: 0} attendees")
                    val response = JsonObject().apply {
                        add("attendees", data?.getAsJsonArray("items"))
                        addProperty("has_more", data?.get("has_more")?.asBoolean ?: false)
                        data?.get("page_token")?.let { add("page_token", it) }
                    }
                    Toolresult.success(response)
                }
                else -> Toolresult.error("Unknown action: $action. Must be one of: create, list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_calendar_event_attendee failed", e)
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
                    "action" to PropertySchema("string", "Action type", enum = listOf("create", "list")),
                    "calendar_id" to PropertySchema("string", "日历 ID"),
                    "event_id" to PropertySchema("string", "日程 ID"),
                    "attendees" to PropertySchema("array", "参会人List(create 时Required). type=user 时 attendee_id 为 open_id, type=chat 时为 chat_id, type=resource 时为会议室 ID, type=third_party 时为邮箱地址",
                        items = PropertySchema("object", "参会人 {type, attendee_id}")),
                    "need_notification" to PropertySchema("boolean", "YesNoSend to attendeeNotification(Default true)"),
                    "attendee_ability" to PropertySchema("string", "参与人Permission",
                        enum = listOf("none", "can_see_others", "can_invite_others", "can_modify_event")),
                    "user_id_type" to PropertySchema("string", "User ID Type(list 时use, Default open_id)",
                        enum = listOf("open_id", "union_id", "user_id")),
                    "page_size" to PropertySchema("integer", "Page size(Default 50, Max 500)"),
                    "page_token" to PropertySchema("string", "Page token")
                ),
                required = listOf("action", "calendar_id", "event_id")
            )
        )
    )
}

// ─── feishu_calendar_freebusy ──────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuCalendarFreebusyTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_calendar_freebusy"
    override val description = "【As user】飞书日历忙闲Query工具. 当User要求Query某Time段Inside某人YesNoNull闲、" +
        "View忙闲Status时use. SupportBatchQuery 1-10 个User的主日历忙闲Info, 用于安排会议Time. "

    override fun isEnabledd() = config.enableCalendarTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String ?: "list"
            Log.i(TAG, "[FREEBUSY] Execute called with params: $args")

            if (action != "list") {
                Log.w(TAG, "[FREEBUSY] Unknown action: $action")
                return@withContext Toolresult.error("Unknown action: $action")
            }

            // Validate user_ids
            @Suppress("UNCHECKED_CAST")
            val userIds = args["user_ids"] as? List<String>
            if (userIds.isNullOrEmpty()) {
                Log.w(TAG, "[FREEBUSY] user_ids is empty")
                return@withContext Toolresult.error("user_ids is required (1-10 user IDs)")
            }
            if (userIds.size > 10) {
                Log.w(TAG, "[FREEBUSY] user_ids exceeds limit: ${userIds.size}")
                return@withContext Toolresult.error("user_ids count exceeds limit, maximum 10 users (current: ${userIds.size})")
            }
            Log.i(TAG, "[FREEBUSY] Validation passed, user_ids count: ${userIds.size}")

            val timeMinStr = args["time_min"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: time_min")
            val timeMaxStr = args["time_max"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: time_max")

            // Convert time strings to RFC 3339 format (required by freebusy API)
            val timeMin = parseTimeToRFC3339(timeMinStr)
            val timeMax = parseTimeToRFC3339(timeMaxStr)
            if (timeMin == null || timeMax == null) {
                Log.w(TAG, "[FREEBUSY] Time format error: time_min=$timeMinStr, time_max=$timeMaxStr")
                return@withContext Toolresult.error(mapOf(
                    "error" to "Invalid time format. Must use ISO 8601 / RFC 3339 with timezone, e.g. '2024-01-01T00:00:00+08:00' or '2026-02-25 14:00:00'.",
                    "received_time_min" to timeMinStr,
                    "received_time_max" to timeMaxStr
                ).toString())
            }

            Log.i(TAG, "[FREEBUSY] Calling batch API: time_min=$timeMinStr -> $timeMin, time_max=$timeMaxStr -> $timeMax, user_ids=${userIds.size}")

            val body = mapOf(
                "time_min" to timeMin,
                "time_max" to timeMax,
                "user_ids" to userIds,
                "include_external_calendar" to true,
                "only_busy" to true
            )

            val result = client.post("/open-apis/calendar/v4/freebusy/batch", body)
            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to query freebusy")
            }
            val data = result.getOrNull()?.getAsJsonObject("data")
            val freebusyLists = data?.getAsJsonArray("freebusy_lists") ?: JsonArray()
            Log.i(TAG, "[FREEBUSY] Success: returned ${freebusyLists.size()} user(s) freebusy data")

            val response = JsonObject().apply {
                add("freebusy_lists", freebusyLists)
                val debug = JsonObject().apply {
                    addProperty("time_min_input", timeMinStr)
                    addProperty("time_min_rfc3339", timeMin)
                    addProperty("time_max_input", timeMaxStr)
                    addProperty("time_max_rfc3339", timeMax)
                    addProperty("user_count", userIds.size)
                }
                add("_debug", debug)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_calendar_freebusy failed", e)
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
                    "action" to PropertySchema("string", "Action type", enum = listOf("list")),
                    "time_min" to PropertySchema("string", "Query起始Time(ISO 8601 / RFC 3339 格式(with timezone), e.g. '2024-01-01T00:00:00+08:00')"),
                    "time_max" to PropertySchema("string", "QueryEndTime(ISO 8601 / RFC 3339 格式)"),
                    "user_ids" to PropertySchema("array", "要QueryBusy/FreeUser ID List(1-10 个User)",
                        items = PropertySchema("string", "User open_id"))
                ),
                required = listOf("action", "time_min", "time_max", "user_ids")
            )
        )
    )
}

// ─── Aggregator ────────────────────────────────────────────────────

class FeishuCalendarTools(config: FeishuConfig, client: FeishuClient) {
    private val calendarTool = FeishuCalendarCalendarTool(config, client)
    private val eventTool = FeishuCalendarEventTool(config, client)
    private val attendeeTool = FeishuCalendarEventAttendeeTool(config, client)
    private val freebusyTool = FeishuCalendarFreebusyTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(calendarTool, eventTool, attendeeTool, freebusyTool)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
    }
}
