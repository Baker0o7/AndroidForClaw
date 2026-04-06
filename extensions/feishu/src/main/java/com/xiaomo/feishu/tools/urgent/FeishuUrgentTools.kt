package com.xiaomo.feishu.tools.urgent

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel tool definitions.
 */


import android.util.Log
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feishu Urgent Tools
 * Aligned with OpenClaw src/urgent-tools
 */
class FeishuUrgentTools(config: FeishuConfig, client: FeishuClient) {
    private val urgentSendTool = UrgentSendTool(config, client)
    private val urgentAppTool = UrgentAppTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(urgentSendTool, urgentAppTool)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabled() }.map { it.getToolDefinition() }
    }
}

/**
 * Urgent message sender tool
 */
class UrgentSendTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_urgent_send"
    override val description = "Send Feishu urgent message (will send phone reminder to user)"

    override fun isEnabled() = config.enableUrgentTools

    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val messageId = args["message_id"] as? String ?: return@withContext ToolResult.error("Missing message_id")
            @Suppress("UNCHECKED_CAST")
            val userIds = args["user_ids"] as? List<String> ?: return@withContext ToolResult.error("Missing user_ids")

            val body = mapOf(
                "user_id_list" to userIds
            )

            val result = client.post("/open-apis/im/v1/messages/$messageId/urgent_app", body)

            if (result.isFailure) {
                return@withContext ToolResult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            Log.d("UrgentSendTool", "Urgent message sent: $messageId to ${userIds.size} users")
            ToolResult.success(mapOf(
                "message_id" to messageId,
                "user_count" to userIds.size
            ))

        } catch (e: Exception) {
            Log.e("UrgentSendTool", "Failed", e)
            ToolResult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "message_id" to PropertySchema("string", "Message ID to send urgent"),
                    "user_ids" to PropertySchema("array", "User ID list to notify", items = PropertySchema("string", "User ID"))
                ),
                required = listOf("message_id", "user_ids")
            )
        )
    )
}

/**
 * Urgent app message tool
 */
class UrgentAppTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_urgent_app"
    override val description = "Urgent Feishu app message (phone/SMS reminder)"

    override fun isEnabled() = config.enableUrgentTools

    override suspend fun execute(args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val messageId = args["message_id"] as? String ?: return@withContext ToolResult.error("Missing message_id")
            @Suppress("UNCHECKED_CAST")
            val userIds = args["user_ids"] as? List<String> ?: return@withContext ToolResult.error("Missing user_ids")
            val urgentType = args["urgent_type"] as? String ?: "app" // app, sms, phone

            val body = mapOf(
                "user_id_list" to userIds,
                "urgent_type" to urgentType
            )

            val result = client.post("/open-apis/im/v1/messages/$messageId/urgent_app", body)

            if (result.isFailure) {
                return@withContext ToolResult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            Log.d("UrgentAppTool", "Urgent app message sent: $messageId type=$urgentType")
            ToolResult.success(mapOf(
                "message_id" to messageId,
                "urgent_type" to urgentType,
                "user_count" to userIds.size
            ))

        } catch (e: Exception) {
            Log.e("UrgentAppTool", "Failed", e)
            ToolResult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "message_id" to PropertySchema("string", "Message ID to send urgent"),
                    "user_ids" to PropertySchema("array", "User ID list to notify", items = PropertySchema("string", "User ID")),
                    "urgent_type" to PropertySchema(
                        "string",
                        "Urgent type (app/sms/phone, default app)",
                        enum = listOf("app", "sms", "phone")
                    )
                ),
                required = listOf("message_id", "user_ids")
            )
        )
    )
}
