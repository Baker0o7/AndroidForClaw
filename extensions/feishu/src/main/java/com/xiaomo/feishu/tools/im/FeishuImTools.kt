package com.xiaomo.feishu.tools.im

/**
 * Feishu IM (Instant Messaging) tool set.
 * Line-by-line translation from @larksuite/openclaw-lark JS source.
 * - feishu_im_user_message: send/reply messages as user
 * - feishu_im_user_get_messages: get chat history
 * - feishu_im_user_get_thread_messages: get thread messages
 * - feishu_im_user_search_messages: cross-chat message search
 * - feishu_im_user_fetch_resource: download message resources (user token)
 * - feishu_im_bot_image: download message resources (bot token)
 */

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

private const val TAG = "FeishuImTools"

// ---------------------------------------------------------------------------
// Time helpers — translated from time-utils.js
// ---------------------------------------------------------------------------

private const val BJ_OFFSET_MS = 8L * 60 * 60 * 1000
private const val SHANGHAI_OFFSET_SUFFIX = "+08:00"

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun formatBeijingISO(utcMs: Long): String {
    val bj = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    bj.timeInMillis = utcMs + BJ_OFFSET_MS
    val y = bj.get(java.util.Calendar.YEAR)
    val mo = bj.get(java.util.Calendar.MONTH) + 1
    val da = bj.get(java.util.Calendar.DAY_OF_MONTH)
    val h = bj.get(java.util.Calendar.HOUR_OF_DAY)
    val mi = bj.get(java.util.Calendar.MINUTE)
    val s = bj.get(java.util.Calendar.SECOND)
    return "$y-${mo.toString().padStart(2, '0')}-${da.toString().padStart(2, '0')}T${h.toString().padStart(2, '0')}:${mi.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}$SHANGHAI_OFFSET_SUFFIX"
}

/** Unix seconds (number) -> ISO 8601 Beijing time */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun secondsToDateTime(seconds: Long): String = formatBeijingISO(seconds * 1000L)

/** Unix seconds (string) -> ISO 8601 Beijing time */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun secondsStringToDateTime(seconds: String): String = secondsToDateTime(seconds.toLong())

/** Unix millis (string) -> ISO 8601 Beijing time */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun millisStringToDateTime(millis: String): String = formatBeijingISO(millis.toLong())

/** ISO 8601 -> Unix seconds (number) */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun dateTimeToSeconds(datetime: String): Long {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
    val date = sdf.parse(datetime) ?: throw IllegalArgumentException("Cannot parse ISO 8601 time: \"$datetime\"")
    return date.time / 1000
}

/** ISO 8601 -> Unix seconds (string) */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun dateTimeToSecondsString(datetime: String): String = dateTimeToSeconds(datetime).toString()

// ---------------------------------------------------------------------------
// parseTimeRange — translated from time-utils.js
// ---------------------------------------------------------------------------

/**
 * Parse relative time range identifier, return ISO 8601 string pair.
 * Supports: today, yesterday, day_before_yesterday, this_week, last_week,
 *           this_month, last_month, last_{N}_{unit} (unit: minutes/hours/days)
 * All calculations based on Beijing time (UTC+8).
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeRange(input: String): Pair<String, String> {
    val now = System.currentTimeMillis()
    val bjNow = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = now + BJ_OFFSET_MS
    }

    fun beijingStartOfDay(bjCal: java.util.Calendar): Long {
        val utcStart = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utcStart.clear()
        utcStart.set(bjCal.get(java.util.Calendar.YEAR), bjCal.get(java.util.Calendar.MONTH), bjCal.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        return utcStart.timeInMillis - BJ_OFFSET_MS
    }

    fun beijingEndOfDay(bjCal: java.util.Calendar): Long {
        val utcEnd = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utcEnd.clear()
        utcEnd.set(bjCal.get(java.util.Calendar.YEAR), bjCal.get(java.util.Calendar.MONTH), bjCal.get(java.util.Calendar.DAY_OF_MONTH), 23, 59, 59)
        return utcEnd.timeInMillis - BJ_OFFSET_MS
    }

    val startMs: Long
    val endMs: Long

    when (input) {
        "today" -> {
            startMs = beijingStartOfDay(bjNow)
            endMs = now
        }
        "yesterday" -> {
            val d = bjNow.clone() as java.util.Calendar
            d.add(java.util.Calendar.DAY_OF_MONTH, -1)
            startMs = beijingStartOfDay(d)
            endMs = beijingEndOfDay(d)
        }
        "day_before_yesterday" -> {
            val d = bjNow.clone() as java.util.Calendar
            d.add(java.util.Calendar.DAY_OF_MONTH, -2)
            startMs = beijingStartOfDay(d)
            endMs = beijingEndOfDay(d)
        }
        "this_week" -> {
            val day = bjNow.get(java.util.Calendar.DAY_OF_WEEK) // 1=Sun .. 7=Sat
            val diffToMon = if (day == java.util.Calendar.SUNDAY) 6 else day - java.util.Calendar.MONDAY
            val monday = bjNow.clone() as java.util.Calendar
            monday.add(java.util.Calendar.DAY_OF_MONTH, -diffToMon)
            startMs = beijingStartOfDay(monday)
            endMs = now
        }
        "last_week" -> {
            val day = bjNow.get(java.util.Calendar.DAY_OF_WEEK)
            val diffToMon = if (day == java.util.Calendar.SUNDAY) 6 else day - java.util.Calendar.MONDAY
            val thisMonday = bjNow.clone() as java.util.Calendar
            thisMonday.add(java.util.Calendar.DAY_OF_MONTH, -diffToMon)
            val lastMonday = thisMonday.clone() as java.util.Calendar
            lastMonday.add(java.util.Calendar.DAY_OF_MONTH, -7)
            val lastSunday = thisMonday.clone() as java.util.Calendar
            lastSunday.add(java.util.Calendar.DAY_OF_MONTH, -1)
            startMs = beijingStartOfDay(lastMonday)
            endMs = beijingEndOfDay(lastSunday)
        }
        "this_month" -> {
            val firstDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            firstDay.clear()
            firstDay.set(bjNow.get(java.util.Calendar.YEAR), bjNow.get(java.util.Calendar.MONTH), 1)
            startMs = beijingStartOfDay(firstDay)
            endMs = now
        }
        "last_month" -> {
            val firstDayThisMonth = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            firstDayThisMonth.clear()
            firstDayThisMonth.set(bjNow.get(java.util.Calendar.YEAR), bjNow.get(java.util.Calendar.MONTH), 1)
            val lastDayPrevMonth = firstDayThisMonth.clone() as java.util.Calendar
            lastDayPrevMonth.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val firstDayPrevMonth = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            firstDayPrevMonth.clear()
            firstDayPrevMonth.set(lastDayPrevMonth.get(java.util.Calendar.YEAR), lastDayPrevMonth.get(java.util.Calendar.MONTH), 1)
            startMs = beijingStartOfDay(firstDayPrevMonth)
            endMs = beijingEndOfDay(lastDayPrevMonth)
        }
        else -> {
            // last_{N}_{unit}
            val match = Regex("^last_(\\d+)_(minutes?|hours?|days?)$").matchEntire(input)
                ?: throw IllegalArgumentException("Unsupported relative_time format: \"$input\". " +
                    "Supported: today, yesterday, day_before_yesterday, this_week, last_week, this_month, last_month, last_{N}_{unit} (unit: minutes/hours/days)")
            val n = match.groupValues[1].toInt()
            val unit = match.groupValues[2].removeSuffix("s")
            val d = java.util.Calendar.getInstance()
            d.timeInMillis = now
            when (unit) {
                "minute" -> d.add(java.util.Calendar.MINUTE, -n)
                "hour" -> d.add(java.util.Calendar.HOUR_OF_DAY, -n)
                "day" -> d.add(java.util.Calendar.DAY_OF_MONTH, -n)
                else -> throw IllegalArgumentException("Unsupported time unit: $unit")
            }
            startMs = d.timeInMillis
            endMs = now
        }
    }

    return formatBeijingISO(startMs) to formatBeijingISO(endMs)
}

/**
 * Parse relative time range, return Unix seconds string pair.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun parseTimeRangeToSeconds(input: String): Pair<String, String> {
    val range = parseTimeRange(input)
    return dateTimeToSecondsString(range.first) to dateTimeToSecondsString(range.second)
}

// ---------------------------------------------------------------------------
// Shared IM helpers — translated from message-read.js
// ---------------------------------------------------------------------------

// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun sortRuleToSortType(sortRule: String?): String {
    return if (sortRule == "create_time_asc") "ByCreateTimeAsc" else "ByCreateTimeDesc"
}

/** Resolve time range from params, returning seconds-level timestamps. */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private fun resolveTimeRange(
    relativeTime: String?,
    startTime: String?,
    endTime: String?,
    logInfo: (String) -> Unit
): Pair<String?, String?> {
    if (relativeTime != null) {
        val range = parseTimeRangeToSeconds(relativeTime)
        logInfo("relative_time=\"$relativeTime\" -> start=${range.first}, end=${range.second}")
        return range.first to range.second
    }
    return (startTime?.let { dateTimeToSecondsString(it) }) to (endTime?.let { dateTimeToSecondsString(it) })
}

/** open_id -> chat_id (P2P single chat) */
// @aligned openclaw-lark v2026.3.30 — line-by-line
private suspend fun resolveP2PChatId(client: FeishuClient, openId: String): String {
    val batchBody = mapOf("chatter_ids" to listOf(openId))
    val batchresult = client.post(
        "/open-apis/im/v1/chat_p2p/batch_query?user_id_type=open_id",
        batchBody
    )
    if (batchresult.isFailure) {
        throw IllegalStateException("Failed to resolve P2P chat for open_id=$openId: ${batchresult.exceptionOrNull()?.message}")
    }
    val batchData = batchresult.getOrNull()?.getAsJsonObject("data")
    val p2pChats = batchData?.getAsJsonArray("p2p_chats")
    if (p2pChats == null || p2pChats.size() == 0) {
        throw IllegalStateException("no 1-on-1 chat found with open_id=$openId. You may not have chat history with this user.")
    }
    val chatId = p2pChats[0].asJsonObject.get("chat_id")?.asString
        ?: throw IllegalStateException("P2P chat resolved but chat_id is null")
    Log.i(TAG, "batch_query: resolved chat_id=$chatId")
    return chatId
}

// ---------------------------------------------------------------------------
// Format messages — translated from format-messages.js
// ---------------------------------------------------------------------------

/**
 * Format a single message item for AI-readable output.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun formatMessageItem(item: JsonObject): JsonObject {
    val messageId = item.get("message_id")?.asString ?: ""
    val msgType = item.get("msg_type")?.asString ?: "unknown"

    // Extract content from body.content
    val content = try {
        item.getAsJsonObject("body")?.get("content")?.asString ?: ""
    } catch (_: Exception) { "" }

    // Build sender
    val senderObj = item.getAsJsonObject("sender")
    val senderId = senderObj?.get("id")?.asString ?: ""
    val senderType = senderObj?.get("sender_type")?.asString ?: "unknown"
    val sender = JsonObject().apply {
        addProperty("id", senderId)
        addProperty("sender_type", senderType)
    }

    // Build mentions
    val mentionsArr = item.getAsJsonArray("mentions")
    val mentions = if (mentionsArr != null && mentionsArr.size() > 0) {
        val arr = JsonArray()
        for (m in mentionsArr) {
            if (m.isJsonObject) {
                val mention = JsonObject().apply {
                    addProperty("key", m.asJsonObject.get("key")?.asString ?: "")
                    addProperty("id", m.asJsonObject.get("id")?.asString ?: "")
                    addProperty("name", m.asJsonObject.get("name")?.asString ?: "")
                }
                arr.add(mention)
            }
        }
        arr
    } else null

    // Convert create_time (millis string -> ISO 8601 +08:00)
    val createTimeRaw = item.get("create_time")?.asString
    val createTime = if (createTimeRaw != null) {
        try { millisStringToDateTime(createTimeRaw) } catch (_: Exception) { createTimeRaw }
    } else ""

    val formatted = JsonObject().apply {
        addProperty("message_id", messageId)
        addProperty("msg_type", msgType)
        addProperty("content", content)
        add("sender", sender)
        addProperty("create_time", createTime)
        addProperty("deleted", item.get("deleted")?.asBoolean ?: false)
        addProperty("updated", item.get("updated")?.asBoolean ?: false)
    }

    // Optional fields: thread_id / reply_to
    val threadId = item.get("thread_id")?.asString
    val parentId = item.get("parent_id")?.asString
    if (threadId != null) {
        formatted.addProperty("thread_id", threadId)
    } else if (parentId != null) {
        formatted.addProperty("reply_to", parentId)
    }

    if (mentions != null) {
        formatted.add("mentions", mentions)
    }

    return formatted
}

/**
 * Format a list of message items.
 * @aligned openclaw-lark v2026.3.30 — line-by-line
 */
private fun formatMessageList(items: JsonArray): JsonArray {
    val result = JsonArray()
    for (item in items) {
        if (item.isJsonObject) {
            result.add(formatMessageItem(item.asJsonObject))
        }
    }
    return result
}

// ─── feishu_im_user_message ────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImUserMessageTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_user_message"
    override val description = "飞书User身份 IM Message工具. **Has且仅当User明确要求以自己身份发Message、回复Message时use, 当None明确要求时优先usemessage系统工具**. " +
        "\n\nActions:" +
        "\n- send(sendMessage): sendMessage到私聊或群聊. 私聊用 receive_id_type=open_id, 群聊用 receive_id_type=chat_id" +
        "\n- reply(回复Message): 回复指定 message_id 的Message, Support话题回复(reply_in_thread=true)" +
        "\n\n[Important]content MustYes合法 JSON String, 格式depend on msg_type. " +
        "most常用: text Type content 为 '{\"text\":\"MessageInside容\"}'. " +
        "\n\n【SecureConstraint】此工具As usersendMessage, 发出Back对方看到的send者YesUser本人. " +
        "callFrontMust先向UserConfirm: 1) sendObject(哪个人或哪个群)2) MessageInside容. " +
        "禁止在User未明确agree的情况Down自RowsendMessage. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "send" -> {
                    val receiveIdType = args["receive_id_type"] as? String
                        ?: return@withContext Toolresult.error("Missing receive_id_type")
                    val receiveId = args["receive_id"] as? String
                        ?: return@withContext Toolresult.error("Missing receive_id")
                    val msgType = args["msg_type"] as? String
                        ?: return@withContext Toolresult.error("Missing msg_type")
                    val content = args["content"] as? String
                        ?: return@withContext Toolresult.error("Missing content")

                    Log.i(TAG, "send: receive_id_type=$receiveIdType, receive_id=$receiveId, msg_type=$msgType")

                    val body = mutableMapOf<String, Any>(
                        "receive_id" to receiveId,
                        "msg_type" to msgType,
                        "content" to content
                    )
                    (args["uuid"] as? String)?.let { body["uuid"] = it }

                    val result = client.post(
                        "/open-apis/im/v1/messages?receive_id_type=$receiveIdType",
                        body
                    )
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to send message")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    Log.i(TAG, "send: message sent, message_id=${data?.get("message_id")?.asString}")
                    val response = JsonObject().apply {
                        data?.get("message_id")?.let { addProperty("message_id", it.asString) }
                        data?.get("chat_id")?.let { addProperty("chat_id", it.asString) }
                        data?.get("create_time")?.let { addProperty("create_time", it.asString) }
                    }
                    Toolresult.success(response)
                }
                // @aligned openclaw-lark v2026.3.30 — line-by-line
                "reply" -> {
                    val messageId = args["message_id"] as? String
                        ?: return@withContext Toolresult.error("Missing message_id")
                    val msgType = args["msg_type"] as? String
                        ?: return@withContext Toolresult.error("Missing msg_type")
                    val content = args["content"] as? String
                        ?: return@withContext Toolresult.error("Missing content")
                    val replyInThread = args["reply_in_thread"] as? Boolean

                    Log.i(TAG, "reply: message_id=$messageId, msg_type=$msgType, reply_in_thread=${replyInThread ?: false}")

                    val body = mutableMapOf<String, Any>(
                        "msg_type" to msgType,
                        "content" to content
                    )
                    if (replyInThread != null) body["reply_in_thread"] = replyInThread
                    (args["uuid"] as? String)?.let { body["uuid"] = it }

                    val result = client.post("/open-apis/im/v1/messages/$messageId/reply", body)
                    if (result.isFailure) {
                        return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to reply message")
                    }
                    val data = result.getOrNull()?.getAsJsonObject("data")
                    Log.i(TAG, "reply: message sent, message_id=${data?.get("message_id")?.asString}")
                    val response = JsonObject().apply {
                        data?.get("message_id")?.let { addProperty("message_id", it.asString) }
                        data?.get("chat_id")?.let { addProperty("chat_id", it.asString) }
                        data?.get("create_time")?.let { addProperty("create_time", it.asString) }
                    }
                    Toolresult.success(response)
                }
                else -> Toolresult.error("Unknown action: $action. Must be one of: send, reply")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_user_message failed", e)
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
                    "action" to PropertySchema("string", "Action type", enum = listOf("send", "reply")),
                    "receive_id_type" to PropertySchema("string", "receive者 ID Type: open_id(私聊, ou_xxx)、chat_id(群聊, oc_xxx)",
                        enum = listOf("open_id", "chat_id")),
                    "receive_id" to PropertySchema("string", "receive者 ID, 与 receive_id_type 对应. open_id 填 'ou_xxx', chat_id 填 'oc_xxx'"),
                    "message_id" to PropertySchema("string", "被回复Message的 ID(om_xxx 格式)"),
                    "msg_type" to PropertySchema("string", "MessageType",
                        enum = listOf("text", "post", "image", "file", "audio", "media", "interactive", "share_chat", "share_user")),
                    "content" to PropertySchema("string", "MessageInside容(JSON String), 格式depend on msg_type. 示例: text -> '{\"text\":\"hello\"}'"),
                    "reply_in_thread" to PropertySchema("boolean", "YesNo以话题形式回复(reply 时use)"),
                    "uuid" to PropertySchema("string", "幂等Unique标识. 同一 uuid 在 1 小时Inside只会send一条Message")
                ),
                required = listOf("action")
            )
        )
    )
}

// ─── feishu_im_user_get_messages ───────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImUserGetMessagesTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_user_get_messages"
    override val description = "【As user】Get群聊或单聊的历史Message. " +
        "\n\n用法: " +
        "\n- 通过 chat_id Get群聊/单聊Message" +
        "\n- 通过 open_id Get与指定User的单聊Message(AutoParse chat_id)" +
        "\n- SupportTimeRangeFilter: relative_time(such as today、last_3_days)或 start_time/end_time(ISO 8601 格式)" +
        "\n- SupportPaginate: page_size + page_token" +
        "\n\n【ParametersConstraint】" +
        "\n- open_id 和 chat_id MustChoose one, cannotat the same time提供" +
        "\n- relative_time 和 start_time/end_time cannotat the same timeuse" +
        "\n- page_size Range 1-50, Default 50" +
        "\n\nReturnMessageList, 每条MessageContains message_id、msg_type、content(AI 可读Text)、sender、create_time 等Field. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val chatId = args["chat_id"] as? String
            val openId = args["open_id"] as? String
            val relativeTime = args["relative_time"] as? String
            val startTime = args["start_time"] as? String
            val endTime = args["end_time"] as? String

            if (openId != null && chatId != null) {
                return@withContext Toolresult.error("cannot provide both open_id and chat_id, please provide only one")
            }
            if (openId == null && chatId == null) {
                return@withContext Toolresult.error("either open_id or chat_id is required")
            }
            if (relativeTime != null && (startTime != null || endTime != null)) {
                return@withContext Toolresult.error("cannot use both relative_time and start_time/end_time")
            }

            // Resolve chat_id
            val resolvedChatId = if (openId != null) {
                Log.i(TAG, "resolving P2P chat for open_id=$openId")
                resolveP2PChatId(client, openId)
            } else {
                chatId!!
            }

            val time = resolveTimeRange(relativeTime, startTime, endTime) { Log.i(TAG, it) }
            val sortRule = args["sort_rule"] as? String

            Log.i(TAG, "list: chat_id=$resolvedChatId, sort=${sortRule ?: "create_time_desc"}, page_size=${(args["page_size"] as? Number)?.toInt() ?: 50}")

            val params = mutableListOf(
                "container_id_type=chat",
                "container_id=$resolvedChatId",
                "card_msg_content_type=raw_card_content",
                "sort_type=${sortRuleToSortType(sortRule)}"
            )
            val pageSize = (args["page_size"] as? Number)?.toInt() ?: 50
            params.add("page_size=$pageSize")
            (args["page_token"] as? String)?.let { params.add("page_token=$it") }
            time.first?.let { params.add("start_time=$it") }
            time.second?.let { params.add("end_time=$it") }

            val query = params.joinToString("&")
            val result = client.get("/open-apis/im/v1/messages?$query")
            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get messages")
            }
            val data = result.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items") ?: JsonArray()
            val messages = formatMessageList(items)
            val hasMore = data?.get("has_more")?.asBoolean ?: false
            val pageToken = data?.get("page_token")?.asString
            Log.i(TAG, "list: returned ${messages.size()} messages, has_more=$hasMore")

            val response = JsonObject().apply {
                add("messages", messages)
                addProperty("has_more", hasMore)
                if (pageToken != null) addProperty("page_token", pageToken)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_user_get_messages failed", e)
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
                    "open_id" to PropertySchema("string", "User open_id(ou_xxx), Get与该User的单聊Message. 与 chat_id Mutex"),
                    "chat_id" to PropertySchema("string", "Session ID(oc_xxx), Support单聊和群聊. 与 open_id Mutex"),
                    "sort_rule" to PropertySchema("string", "Sort方式, Default create_time_desc(mostNewMessage在Front)",
                        enum = listOf("create_time_asc", "create_time_desc")),
                    "page_size" to PropertySchema("integer", "每页Message数(1-50), Default 50"),
                    "page_token" to PropertySchema("string", "Page token, 用于GetDown一页"),
                    "relative_time" to PropertySchema("string",
                        "relativelyTimeRange: today / yesterday / day_before_yesterday / this_week / last_week / this_month / last_month / last_{N}_{unit}(unit: minutes/hours/days). 与 start_time/end_time Mutex"),
                    "start_time" to PropertySchema("string", "起始Time(ISO 8601 格式, such as 2026-02-27T00:00:00+08:00). 与 relative_time Mutex"),
                    "end_time" to PropertySchema("string", "EndTime(ISO 8601 格式, such as 2026-02-27T23:59:59+08:00). 与 relative_time Mutex")
                ),
                required = emptyList()
            )
        )
    )
}

// ─── feishu_im_user_get_thread_messages ────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImUserGetThreadMessagesTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_user_get_thread_messages"
    override val description = "【As user】Get话题(thread)Inside的MessageList. " +
        "\n\n用法: " +
        "\n- 通过 thread_id(omt_xxx)Get话题Inside的AllMessage" +
        "\n- SupportPaginate: page_size + page_token" +
        "\n\n【注意】话题Message不SupportTimeRangeFilter(飞书 API Limit)" +
        "\n\nReturnMessageList, 格式同 feishu_im_user_get_messages. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val threadId = args["thread_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: thread_id")

            val sortRule = args["sort_rule"] as? String
            Log.i(TAG, "list: thread_id=$threadId, sort=${sortRule ?: "create_time_desc"}, page_size=${(args["page_size"] as? Number)?.toInt() ?: 50}")

            val params = mutableListOf(
                "container_id_type=thread",
                "container_id=$threadId",
                "card_msg_content_type=raw_card_content",
                "sort_type=${sortRuleToSortType(sortRule)}"
            )
            val pageSize = (args["page_size"] as? Number)?.toInt() ?: 50
            params.add("page_size=$pageSize")
            (args["page_token"] as? String)?.let { params.add("page_token=$it") }

            val query = params.joinToString("&")
            val result = client.get("/open-apis/im/v1/messages?$query")
            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get thread messages")
            }
            val data = result.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items") ?: JsonArray()
            val messages = formatMessageList(items)
            val hasMore = data?.get("has_more")?.asBoolean ?: false
            val pageToken = data?.get("page_token")?.asString
            Log.i(TAG, "list: returned ${messages.size()} messages, has_more=$hasMore")

            val response = JsonObject().apply {
                add("messages", messages)
                addProperty("has_more", hasMore)
                if (pageToken != null) addProperty("page_token", pageToken)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_user_get_thread_messages failed", e)
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
                    "thread_id" to PropertySchema("string", "话题 ID(omt_xxx 格式)"),
                    "sort_rule" to PropertySchema("string", "Sort方式, Default create_time_desc(mostNewMessage在Front)",
                        enum = listOf("create_time_asc", "create_time_desc")),
                    "page_size" to PropertySchema("integer", "每页Message数(1-50), Default 50"),
                    "page_token" to PropertySchema("string", "Page token, 用于GetDown一页")
                ),
                required = listOf("thread_id")
            )
        )
    )
}

// ─── feishu_im_user_search_messages ────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImUserSearchMessagesTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_user_search_messages"
    override val description = "【As user】跨SessionSearch飞书Message. " +
        "\n\n用法: " +
        "\n- 按关Key词SearchMessageInside容" +
        "\n- 按send者、被@User、MessageTypeFilter" +
        "\n- Filter by time range: relative_time 或 start_time/end_time" +
        "\n- 限定在某个SessionInsideSearch(chat_id)" +
        "\n- SupportPaginate: page_size + page_token" +
        "\n\n【ParametersConstraint】" +
        "\n- AllParameters均Optional, 但至少应提供一个FilterCondition" +
        "\n- relative_time 和 start_time/end_time cannotat the same timeuse" +
        "\n- page_size Range 1-50, Default 50" +
        "\n\nReturnMessageList, 每条MessageContains message_id、msg_type、content、sender、create_time 等Field. " +
        "\n每条Message还Contains chat_id、chat_type(p2p/group)、chat_name(群名或单聊对方名字). " +
        "\n单聊Message额OutsideContains chat_partner(对方 open_id 和名字). " +
        "\nSearchresult中的 chat_id 和 thread_id 可配合 feishu_im_user_get_messages / feishu_im_user_get_thread_messages ViewUpDown文. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private fun buildSearchData(
        args: Map<String, Any?>,
        timeStart: String,
        timeEnd: String
    ): MutableMap<String, Any> {
        val data = mutableMapOf<String, Any>(
            "query" to (args["query"] as? String ?: ""),
            "start_time" to timeStart,
            "end_time" to timeEnd
        )
        @Suppress("UNCHECKED_CAST")
        (args["sender_ids"] as? List<String>)?.takeIf { it.isNotEmpty() }?.let { data["from_ids"] = it }
        (args["chat_id"] as? String)?.let { data["chat_ids"] = listOf(it) }
        @Suppress("UNCHECKED_CAST")
        (args["mention_ids"] as? List<String>)?.takeIf { it.isNotEmpty() }?.let { data["at_chatter_ids"] = it }
        (args["message_type"] as? String)?.let { data["message_type"] = it }
        (args["sender_type"] as? String)?.let {
            if (it != "all") data["from_type"] = it
        }
        (args["chat_type"] as? String)?.let {
            data["chat_type"] = when (it) {
                "group" -> "group_chat"
                "p2p" -> "p2p_chat"
                else -> it
            }
        }
        return data
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private suspend fun fetchChatContexts(
        client: FeishuClient,
        chatIds: List<String>
    ): Map<String, JsonObject> {
        val map = mutableMapOf<String, JsonObject>()
        if (chatIds.isEmpty()) return map
        try {
            Log.i(TAG, "batch_query: requesting ${chatIds.size} chat_ids: ${chatIds.joinToString(", ")}")
            val res = client.post(
                "/open-apis/im/v1/chats/batch_query?user_id_type=open_id",
                mapOf("chat_ids" to chatIds)
            )
            if (res.isFailure) {
                Log.w(TAG, "batch_query chats failed: ${res.exceptionOrNull()?.message}")
                return map
            }
            val data = res.getOrNull()?.getAsJsonObject("data")
            val items = data?.getAsJsonArray("items") ?: JsonArray()
            Log.i(TAG, "batch_query: response items=${items.size()}")
            for (c in items) {
                if (c.isJsonObject) {
                    val chatId = c.asJsonObject.get("chat_id")?.asString
                    if (chatId != null) {
                        map[chatId] = c.asJsonObject
                    }
                }
            }
        } catch (err: Exception) {
            Log.i(TAG, "batch_query chats failed, skipping: $err")
        }
        return map
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    private fun enrichMessages(
        messages: JsonArray,
        items: JsonArray,
        chatMap: Map<String, JsonObject>
    ): JsonArray {
        val result = JsonArray()
        for (i in 0 until messages.size()) {
            val msg = messages[i].asJsonObject.deepCopy()
            val chatId = if (i < items.size()) items[i].asJsonObject.get("chat_id")?.asString else null
            if (chatId != null) {
                msg.addProperty("chat_id", chatId)
                val ctx = chatMap[chatId]
                if (ctx != null) {
                    val chatMode = ctx.get("chat_mode")?.asString ?: ""
                    val p2pTargetId = ctx.get("p2p_target_id")?.asString
                    if (chatMode == "p2p" && p2pTargetId != null) {
                        msg.addProperty("chat_type", "p2p")
                        msg.add("chat_partner", JsonObject().apply {
                            addProperty("open_id", p2pTargetId)
                        })
                    } else {
                        msg.addProperty("chat_type", chatMode)
                        val chatName = ctx.get("name")?.asString
                        if (!chatName.isNullOrBlank()) msg.addProperty("chat_name", chatName)
                    }
                }
            }
            result.add(msg)
        }
        return result
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val relativeTime = args["relative_time"] as? String
            val startTime = args["start_time"] as? String
            val endTime = args["end_time"] as? String

            if (relativeTime != null && (startTime != null || endTime != null)) {
                return@withContext Toolresult.error("cannot use both relative_time and start_time/end_time")
            }

            // 1. Resolve time range
            val time = resolveTimeRange(relativeTime, startTime, endTime) { Log.i(TAG, it) }
            val searchData = buildSearchData(
                args,
                time.first ?: "978307200",
                time.second ?: (System.currentTimeMillis() / 1000).toString()
            )

            val pageSize = (args["page_size"] as? Number)?.toInt() ?: 50
            val pageToken = args["page_token"] as? String

            Log.i(TAG, "search: query=\"${args["query"] ?: ""}\", page_size=$pageSize")

            // 2. Search message IDs: POST /open-apis/search/v2/message
            val queryParams = mutableListOf(
                "user_id_type=open_id",
                "page_size=$pageSize"
            )
            pageToken?.let { queryParams.add("page_token=$it") }
            val queryString = queryParams.joinToString("&")

            val searchRes = client.post("/open-apis/search/v2/message?$queryString", searchData)
            if (searchRes.isFailure) {
                return@withContext Toolresult.error(searchRes.exceptionOrNull()?.message ?: "Failed to search messages")
            }
            val searchData2 = searchRes.getOrNull()?.getAsJsonObject("data")
            val messageIds = searchData2?.getAsJsonArray("items") ?: JsonArray()
            val hasMore = searchData2?.get("has_more")?.asBoolean ?: false
            val searchPageToken = searchData2?.get("page_token")?.asString
            Log.i(TAG, "search: found ${messageIds.size()} IDs, has_more=$hasMore")

            if (messageIds.size() == 0) {
                val response = JsonObject().apply {
                    add("messages", JsonArray())
                    addProperty("has_more", hasMore)
                    if (searchPageToken != null) addProperty("page_token", searchPageToken)
                }
                return@withContext Toolresult.success(response)
            }

            // 3. Batch fetch message details: GET /open-apis/im/v1/messages/mget
            val idList = mutableListOf<String>()
            for (idElem in messageIds) {
                val id = if (idElem.isJsonPrimitive) idElem.asString else idElem.toString()
                idList.add(id)
            }
            val mgetQueryStr = idList.joinToString("&") { "message_ids=${URLEncoder.encode(it, "UTF-8")}" }
            val mgetRes = client.get("/open-apis/im/v1/messages/mget?$mgetQueryStr&user_id_type=open_id&card_msg_content_type=raw_card_content")
            val mgetItems = if (mgetRes.isSuccess) {
                mgetRes.getOrNull()?.getAsJsonObject("data")?.getAsJsonArray("items") ?: JsonArray()
            } else JsonArray()
            Log.i(TAG, "mget: ${mgetItems.size()} details")

            // 4. Batch fetch chat contexts
            val chatIdSet = mutableSetOf<String>()
            for (item in mgetItems) {
                if (item.isJsonObject) {
                    item.asJsonObject.get("chat_id")?.asString?.let { chatIdSet.add(it) }
                }
            }
            val chatMap = fetchChatContexts(client, chatIdSet.toList())
            Log.i(TAG, "chats: ${chatMap.size}/${chatIdSet.size} resolved")

            // 5. Format messages
            val messages = formatMessageList(mgetItems)

            // 6. Enrich with chat context
            val enriched = enrichMessages(messages, mgetItems, chatMap)
            Log.i(TAG, "result: ${enriched.size()} messages, has_more=$hasMore")

            val response = JsonObject().apply {
                add("messages", enriched)
                addProperty("has_more", hasMore)
                if (searchPageToken != null) addProperty("page_token", searchPageToken)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_user_search_messages failed", e)
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
                    "query" to PropertySchema("string", "Search关Key词, matchMessageInside容. 可为NullStringTable示不按Inside容Filter"),
                    "sender_ids" to PropertySchema("array", "send者 open_id List. such as需according toUser名Find open_id, 请先use search_user 工具",
                        items = PropertySchema("string", "send者的 open_id(ou_xxx)")),
                    "chat_id" to PropertySchema("string", "限定SearchRange的Session ID(oc_xxx)"),
                    "mention_ids" to PropertySchema("array", "被@User的 open_id List",
                        items = PropertySchema("string", "被@User的 open_id(ou_xxx)")),
                    "message_type" to PropertySchema("string", "MessageTypeFilter: file / image / media. 为Null则SearchAllType",
                        enum = listOf("file", "image", "media")),
                    "sender_type" to PropertySchema("string", "send者Type: user / bot / all. Default user",
                        enum = listOf("user", "bot", "all")),
                    "chat_type" to PropertySchema("string", "SessionType: group(群聊)/ p2p(单聊)",
                        enum = listOf("group", "p2p")),
                    "relative_time" to PropertySchema("string",
                        "relativelyTimeRange: today / yesterday / day_before_yesterday / this_week / last_week / this_month / last_month / last_{N}_{unit}(unit: minutes/hours/days). 与 start_time/end_time Mutex"),
                    "start_time" to PropertySchema("string", "起始Time(ISO 8601 格式, such as 2026-02-27T00:00:00+08:00). 与 relative_time Mutex"),
                    "end_time" to PropertySchema("string", "EndTime(ISO 8601 格式, such as 2026-02-27T23:59:59+08:00). 与 relative_time Mutex"),
                    "page_size" to PropertySchema("integer", "每页Message数(1-50), Default 50"),
                    "page_token" to PropertySchema("string", "Page token, 用于GetDown一页")
                ),
                required = emptyList()
            )
        )
    )
}

// ─── feishu_im_user_fetch_resource ─────────────────────────────────

// MIME type -> extension mapping (shared between user fetch and bot image)
// @aligned openclaw-lark v2026.3.30 — line-by-line
private val MIME_TO_EXT = mapOf(
    "image/png" to ".png",
    "image/jpeg" to ".jpg",
    "image/jpg" to ".jpg",
    "image/gif" to ".gif",
    "image/webp" to ".webp",
    "image/svg+xml" to ".svg",
    "image/bmp" to ".bmp",
    "image/tiff" to ".tiff",
    "video/mp4" to ".mp4",
    "video/mpeg" to ".mpeg",
    "video/quicktime" to ".mov",
    "video/x-msvideo" to ".avi",
    "video/webm" to ".webm",
    "audio/mpeg" to ".mp3",
    "audio/wav" to ".wav",
    "audio/ogg" to ".ogg",
    "audio/mp4" to ".m4a",
    "application/pdf" to ".pdf",
    "application/msword" to ".doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx",
    "application/vnd.ms-excel" to ".xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
    "application/vnd.ms-powerpoint" to ".ppt",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
    "application/zip" to ".zip",
    "application/x-rar-compressed" to ".rar",
    "text/plain" to ".txt",
    "application/json" to ".json"
)

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImUserFetchResourceTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_user_fetch_resource"
    override val description = "【As user】Download飞书 IM Message中的文件或Graph片Resource到本地文件. NeedUser OAuth Authorize. " +
        "\n\n适用场景: 当你As usercall了MessageList/Search等 API Get到 message_id 和 file_key 时, " +
        "应use本工具以同样的User身份DownloadResource. " +
        "\n注意: if message_id from当FrontConversationUpDown文(User发给机器人的Message、引用的Message), " +
        "请use feishu_im_bot_image 工具以机器人身份Download, None需UserAuthorize. " +
        "\n\nParametersillustrate: " +
        "\n- message_id: Message ID(om_xxx), 从MessageEvent或MessageList中Get" +
        "\n- file_key: Resource Key, 从Message体中Get. Graph片用 image_key(img_xxx), 文件用 file_key(file_xxx)" +
        "\n- type: Graph片用 image, 文件/音频/视频用 file" +
        "\n\n文件AutoSave到Temporary目录Down, ReturnValue中的 saved_path 为实际SavePath. " +
        "\nLimit: 文件Size不超过 100MB. 不SupportDownloadTable情Package、Merge转发Message、卡片中的Resource. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val messageId = args["message_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: message_id")
            val fileKey = args["file_key"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: file_key")
            val type = args["type"] as? String ?: "file"

            Log.i(TAG, "fetch_resource: message_id=\"$messageId\", file_key=\"$fileKey\", type=\"$type\"")

            val path = "/open-apis/im/v1/messages/$messageId/resources/$fileKey?type=$type"
            val result = client.downloadRawWithHeaders(path)
            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to download resource")
            }

            val (bytes, headers) = result.getOrNull()!!
            val contentType = headers["Content-Type"] ?: headers["content-type"] ?: ""
            Log.i(TAG, "fetch_resource: downloaded ${bytes.size} bytes, content-type=$contentType")

            // Determine file extension from Content-Type
            val mimeType = if (contentType.isNotBlank()) contentType.split(";")[0].trim() else ""
            val mimeExt = MIME_TO_EXT[mimeType] ?: if (type == "image") ".png" else ".bin"

            val tmpFile = File.createTempFile("im-resource-", mimeExt)
            tmpFile.writeBytes(bytes)
            Log.i(TAG, "fetch_resource: saved to ${tmpFile.absolutePath}")

            val response = JsonObject().apply {
                addProperty("message_id", messageId)
                addProperty("file_key", fileKey)
                addProperty("type", type)
                addProperty("size_bytes", bytes.size.toLong())
                addProperty("content_type", contentType)
                addProperty("saved_path", tmpFile.absolutePath)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_user_fetch_resource failed", e)
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
                    "message_id" to PropertySchema("string", "Message ID(om_xxx 格式), 从MessageEvent或MessageList中Get"),
                    "file_key" to PropertySchema("string", "Resource Key, 从Message体中Get. Graph片Message的 image_key(img_xxx)或文件Message的 file_key(file_xxx)"),
                    "type" to PropertySchema("string", "ResourceType: image(Graph片Message中的Graph片)、file(文件/音频/视频Message中的文件)",
                        enum = listOf("image", "file"))
                ),
                required = listOf("message_id", "file_key")
            )
        )
    )
}

// ─── feishu_im_bot_image ───────────────────────────────────────────

// @aligned openclaw-lark v2026.3.30 — line-by-line
class FeishuImBotImageTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_im_bot_image"
    override val description = "【以机器人身份】Download飞书 IM Image or file resources in message to local. " +
        "\n\n适用场景: User直接send给机器人的Message、User引用的Message、机器人收到的群聊Message中的Graph片/文件. " +
        "that is当FrontConversationUpDown文中出现的 message_id 和 image_key/file_key, 应use本工具Download. " +
        "\n引用Message的 message_id 可从UpDown文中的 [message_id=om_xxx] 提取, None需向User询问. " +
        "\n\n文件AutoSave到Temporary目录Down, ReturnValue中的 saved_path 为实际SavePath. "

    override fun isEnabledd() = config.enableImTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val messageId = args["message_id"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: message_id")
            val fileKey = args["file_key"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: file_key")
            val type = args["type"] as? String ?: "image"

            Log.i(TAG, "download: message_id=\"$messageId\", file_key=\"$fileKey\", type=\"$type\"")

            val path = "/open-apis/im/v1/messages/$messageId/resources/$fileKey?type=$type"
            val result = client.downloadRawWithHeaders(path)
            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to download resource")
            }

            val (bytes, headers) = result.getOrNull()!!
            val contentType = headers["Content-Type"] ?: headers["content-type"] ?: ""
            Log.i(TAG, "download: ${bytes.size} bytes, content-type=$contentType")

            val mimeType = if (contentType.isNotBlank()) contentType.split(";")[0].trim() else ""
            val mimeExt = MIME_TO_EXT[mimeType] ?: if (type == "image") ".png" else ".bin"

            val tmpFile = File.createTempFile("bot-resource-", mimeExt)
            tmpFile.writeBytes(bytes)
            Log.i(TAG, "download: saved to ${tmpFile.absolutePath}")

            val response = JsonObject().apply {
                addProperty("message_id", messageId)
                addProperty("file_key", fileKey)
                addProperty("type", type)
                addProperty("size_bytes", bytes.size.toLong())
                addProperty("content_type", contentType)
                addProperty("saved_path", tmpFile.absolutePath)
            }
            Toolresult.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "feishu_im_bot_image failed", e)
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
                    "message_id" to PropertySchema("string", "Message ID(om_xxx 格式), 引用Message可从UpDown文中的 [message_id=om_xxx] 提取"),
                    "file_key" to PropertySchema("string", "Resource Key, Graph片Message的 image_key(img_xxx)或文件Message的 file_key(file_xxx)"),
                    "type" to PropertySchema("string", "ResourceType: image(Graph片Message中的Graph片)、file(文件/音频/视频Message中的文件)",
                        enum = listOf("image", "file"))
                ),
                required = listOf("message_id", "file_key")
            )
        )
    )
}

// ─── Aggregator ────────────────────────────────────────────────────

class FeishuImTools(config: FeishuConfig, client: FeishuClient) {
    private val userMessageTool = FeishuImUserMessageTool(config, client)
    private val getMessagesTool = FeishuImUserGetMessagesTool(config, client)
    private val getThreadMessagesTool = FeishuImUserGetThreadMessagesTool(config, client)
    private val searchMessagesTool = FeishuImUserSearchMessagesTool(config, client)
    private val fetchResourceTool = FeishuImUserFetchResourceTool(config, client)
    private val botImageTool = FeishuImBotImageTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(userMessageTool, getMessagesTool, getThreadMessagesTool, searchMessagesTool, fetchResourceTool, botImageTool)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
    }
}
