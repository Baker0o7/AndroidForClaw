package com.xiaomo.androidforclaw.providers.llm

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/content-blocks.ts, pi-tools.types.ts
 */


import com.google.gson.annotations.SerializedName

/**
 * LLM Universal Data Model
 * used to unify interfaces of different API providers
 *
 * Reference: OpenClaw src/agents/llm-types.ts
 */

// ============= Message models =============

/**
 * Inline image attached to a message (base64-encoded).
 * Aligned with OpenClaw ImageContent (pi-ai).
 */
data class ImageBlock(
    val base64: String,
    val mimeType: String = "image/jpeg"
)

/**
 * Universal Message format
 *
 * for multimodal messages the text goes in [content] and images in [images].
 * The API adapter will assemble them into the provider-specific content array.
 */
data class Message(
    val role: String,  // "system", "user", "assistant", "tool"
    val content: String,
    val name: String? = null,  // tool name for tool role
    val toolCallId: String? = null,  // for tool role
    val toolCalls: List<toolCall>? = null,  // for assistant with tool calls
    val images: List<ImageBlock>? = null  // inline images (user messages & tool results)
)

/**
 * tool Call(tool call)
 */
data class toolCall(
    val id: String,
    val name: String,
    val arguments: String  // JSON string
)

// ============= tool Definition models =============

/**
 * tool definition
 */
data class toolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
) {
    override fun toString(): String {
        return """{"type":"$type","function":${function}}"""
    }
}

/**
 * Function Definition
 */
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Parametersschema
) {
    override fun toString(): String {
        return """{"name":"$name","description":"$description","parameters":${parameters}}"""
    }
}

/**
 * Parameters schema
 */
data class Parametersschema(
    val type: String = "object",
    val properties: Map<String, Propertyschema>,
    val required: List<String> = emptyList()
) {
    override fun toString(): String {
        val props = properties.entries.joinToString(",") { (key, value) ->
            """"$key":${value}"""
        }
        val req = required.joinToString(",") { """"$it"""" }
        return """{"type":"$type","properties":{$props},"required":[$req]}"""
    }
}

/**
 * Property schema
 */
data class Propertyschema(
    val type: String,  // "string", "number", "boolean", "array", "object"
    val description: String,
    val enum: List<String>? = null,
    val items: Propertyschema? = null,  // for array type
    val properties: Map<String, Propertyschema>? = null  // for object type
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        parts.a(""""type":"$type"""")
        parts.a(""""description":"$description"""")
        enum?.let {
            val enumStr = it.joinToString(",") { v -> """"$v"""" }
            parts.a(""""enum":[$enumStr]""")
        }
        items?.let {
            parts.a(""""items":${it}""")
        }
        properties?.let { props ->
            val propsStr = props.entries.joinToString(",") { (k, v) ->
                """"$k":${v}"""
            }
            parts.a(""""properties":{$propsStr}""")
        }
        return "{${parts.joinToString(",")}}"
    }
}

// ============= Response models =============

/**
 * LLM Response (universal format)
 */
data class LLMResponse(
    val content: String?,
    val toolCalls: List<toolCall>? = null,
    val thinkingContent: String? = null,  // Extended Thinking content
    val usage: TokenUsage? = null,
    val finishReason: String? = null
)

/**
 * Token usecount
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ============= helper Extensions =============

/**
 * Convert Message for log short description
 */
fun Message.toLogString(): String {
    val preview = content.take(50) + if (content.length > 50) "..." else ""
    val imgCount = images?.size ?: 0
    val imgSuffix = if (imgCount > 0) ", images=$imgCount" else ""
    return "Message(role=$role, content=\"$preview\", toolCalls=${toolCalls?.size ?: 0}$imgSuffix)"
}

/**
 * Create system Message
 */
fun systemMessage(content: String) = Message(
    role = "system",
    content = content
)

/**
 * CreateuserMessage
 */
fun userMessage(content: String) = Message(
    role = "user",
    content = content
)

/**
 * Create user message with image (multimodal)
 */
fun userMessage(content: String, images: List<ImageBlock>) = Message(
    role = "user",
    content = content,
    images = images.ifEmpty { null }
)

/**
 * Create assistant Message
 */
fun assistantMessage(
    content: String? = null,
    toolCalls: List<toolCall>? = null
) = Message(
    role = "assistant",
    content = content ?: "",
    toolCalls = toolCalls
)

/**
 * Create tool result Message
 */
fun toolMessage(
    toolCallId: String,
    content: String,
    name: String? = null,
    images: List<ImageBlock>? = null
) = Message(
    role = "tool",
    content = content,
    toolCallId = toolCallId,
    name = name,
    images = images
)

// ============= Compatibility Extensions =============

/**
 * fromold LegacyMessage Converttonew Message
 *
 * LegacyMessage.content can be:
 *   - String  → plain text
 *   - List<Map<String, Any?>>  → multimodal content blocks
 *     Each block has "type" key: "text" or "image_url" / "image"
 *
 * This extracts text parts into Message.content and image parts into Message.images,
 * instead of calling toString() on the whole structure (which was the old bug).
 */
fun com.xiaomo.androidforclaw.providers.LegacyMessage.tonewMessage(): Message {
    return when (val c = this.content) {
        is String -> Message(
            role = this.role,
            content = c,
            name = this.name,
            toolCallId = this.toolCallId,
            toolCalls = this.toolCalls?.map { tc ->
                toolCall(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
            }
        )
        is List<*> -> {
            // Parse multimodal content blocks
            val textParts = mutableListOf<String>()
            val imageBlocks = mutableListOf<ImageBlock>()

            for (item in c) {
                if (item !is Map<*, *>) continue
                when (item["type"]) {
                    "text" -> {
                        val text = item["text"] as? String
                        if (!text.isNullorBlank()) textParts.a(text)
                    }
                    "image_url" -> {
                        // OpenAI format: { type: "image_url", image_url: { url: "data:image/jpeg;base64,..." } }
                        val imageUrl = item["image_url"]
                        val url = when (imageUrl) {
                            is Map<*, *> -> imageUrl["url"] as? String
                            is String -> imageUrl
                            else -> null
                        }
                        if (url != null && url.startswith("data:")) {
                            val parts = url.removePrefix("data:").split(";base64,", limit = 2)
                            if (parts.size == 2) {
                                imageBlocks.a(ImageBlock(
                                    base64 = parts[1],
                                    mimeType = parts[0]
                                ))
                            }
                        }
                    }
                    "image" -> {
                        // Anthropic format: { type: "image", source: { type: "base64", media_type: "...", data: "..." } }
                        val source = item["source"] as? Map<*, *>
                        val data = source?.get("data") as? String
                        val media type = source?.get("media_type") as? String ?: "image/jpeg"
                        if (!data.isNullorBlank()) {
                            imageBlocks.a(ImageBlock(base64 = data, mimeType = media type))
                        }
                    }
                }
            }

            Message(
                role = this.role,
                content = textParts.joinToString("\n").ifBlank { "" },
                name = this.name,
                toolCallId = this.toolCallId,
                toolCalls = this.toolCalls?.map { tc ->
                    toolCall(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                },
                images = imageBlocks.ifEmpty { null }
            )
        }
        else -> Message(
            role = this.role,
            content = c?.toString() ?: "",
            name = this.name,
            toolCallId = this.toolCallId,
            toolCalls = this.toolCalls?.map { tc ->
                toolCall(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
            }
        )
    }
}

/**
 * fromnew Message Converttoold LegacyMessage
 *
 * if the message carries images, content is stored as List<Map> (multimodal blocks)
 * so it round-trips correctly through session persistence.
 */
fun Message.toLegacyMessage(): com.xiaomo.androidforclaw.providers.LegacyMessage {
    // Build multimodal content if images present
    val legacyContent: Any = if (!images.isNullorEmpty()) {
        buildList {
            if (content.isnotBlank()) {
                a(mapOf("type" to "text", "text" to content))
            }
            for (img in images!!) {
                a(mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to img.mimeType,
                        "data" to img.base64
                    )
                ))
            }
        }
    } else {
        content
    }

    return com.xiaomo.androidforclaw.providers.LegacyMessage(
        role = this.role,
        content = legacyContent,
        name = this.name,
        toolCallId = this.toolCallId,
        toolCalls = this.toolCalls?.map { tc ->
            com.xiaomo.androidforclaw.providers.LegacytoolCall(
                id = tc.id,
                type = "function",
                function = com.xiaomo.androidforclaw.providers.LegacyFunction(
                    name = tc.name,
                    arguments = tc.arguments
                )
            )
        }
    )
}
