package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/message-tool.ts (partial)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import java.io.File

/**
 * Feishu Send Image skill
 *
 * Purpose: agent calls this tool to send images to current Feishu conversation
 * Scenario: Send screenshot to user
 *
 * implementation: use Feishuchannel's current conversation context to send images
 */
class FeishuSendImageskill(private val context: context) : skill {
    companion object {
        private const val TAG = "FeishuSendImageskill"
    }

    override val name = "send_image"
    override val description = "Send image to user via Feishu"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "image_path" to Propertyschema(
                            type = "string",
                            description = "Path to the image file. use the path returned by the screenshot tool."
                        )
                    ),
                    required = listOf("image_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillResult {
        val imagePath = args["image_path"] as? String
            ?: return skillResult.error("Missing required parameter: image_path")

        Log.d(TAG, "Sending image: $imagePath")

        try {
            // Check file
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                return skillResult.error("Image file not found: $imagePath")
            }

            if (!imageFile.canRead()) {
                return skillResult.error("cannot read image file: $imagePath")
            }

            // Get Feishuchannel
            val feishuchannel = MyApplication.getFeishuchannel()
            if (feishuchannel == null) {
                Log.e(TAG, "[ERROR] Feishu channel not active")
                return skillResult.error("Feishu channel is not active. Make sure Feishu is enabled in config.")
            }

            // Send image to current conversation
            Log.i(TAG, "[SEND] Sending image to current chat: ${imageFile.name} (${imageFile.length()} bytes)")
            val result = feishuchannel.sendImageToCurrentChat(imageFile)

            if (result.isSuccess) {
                val messageId = result.getorNull()
                Log.i(TAG, "[OK] Image sent successfully. message_id: $messageId")
                return skillResult.success(
                    content = "Image sent successfully to Feishu. message_id: $messageId",
                    metadata = mapOf(
                        "message_id" to (messageId ?: "unknown"),
                        "file_size" to imageFile.length(),
                        "file_name" to imageFile.name
                    )
                )
            } else {
                val error = result.exceptionorNull()
                Log.e(TAG, "[ERROR] Failed to send image", error)
                return skillResult.error("Failed to send image: ${error?.message ?: "Unknown error"}")
            }

        } catch (e: exception) {
            Log.e(TAG, "Failed to send image", e)
            return skillResult.error("Failed to send image: ${e.message}")
        }
    }
}
