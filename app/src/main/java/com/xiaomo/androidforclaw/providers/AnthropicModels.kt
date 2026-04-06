package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import com.google.gson.annotations.SerializedName

/**
 * Anthropic Messages API Data模型
 * for Legacy Anthropic API compatible interface
 */

// ============= Request models (Anthropic format) =============

data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096,
    val temperature: Double = 0.1,
    val tools: List<Anthropictool>? = null,
    val system: String? = null,  // Anthropic system is a separate field
    // Extended Thinking support
    val thinking: Thinkingconfig? = null
)

data class Thinkingconfig(
    val type: String = "enabled",
    @SerializedName("budget_tokens")
    val budgetTokens: Int = 10000
)

data class AnthropicMessage(
    val role: String,  // "user" or "assistant"
    val content: Any  // String or List<ContentBlock>
)

data class AnthropicContentBlock(
    val type: String,  // "text", "image", "tool_use", "tool_result"
    val text: String? = null,
    // for tool_use
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null,
    // for tool_result
    @SerializedName("tool_use_id")
    val tooluseId: String? = null,
    val content: Any? = null,  // String or List
    // for image
    val source: ImageSource? = null
)

data class ImageSource(
    val type: String = "base64",
    @SerializedName("media_type")
    val media type: String,  // "image/jpeg", "image/png", etc.
    val data: String  // base64 encoded
)

data class Anthropictool(
    val name: String,
    val description: String,
    @SerializedName("input_schema")
    val inputschema: Inputschema
)

data class Inputschema(
    val type: String = "object",
    val properties: Map<String, PropertyDef>,
    val required: List<String>? = null
)

data class PropertyDef(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

// ============= Response models (Anthropic format) =============

data class AnthropicResponse(
    val id: String,
    val type: String = "message",
    val role: String = "assistant",
    val content: List<AnthropicResponseContent>,
    val model: String,
    @SerializedName("stop_reason")
    val stopReason: String?,  // "end_turn", "tool_use", "max_tokens"
    @SerializedName("stop_sequence")
    val stopSequence: String? = null,
    val usage: AnthropicUsage
)

data class AnthropicResponseContent(
    val type: String,  // "text" or "tool_use"
    // for text
    val text: String? = null,
    // for tool_use
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null
)

data class AnthropicUsage(
    @SerializedName("input_tokens")
    val inputTokens: Int,
    @SerializedName("output_tokens")
    val outputTokens: Int,
    @SerializedName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int? = null,
    @SerializedName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null
)

// ============= Error models =============

data class AnthropicError(
    val type: String = "error",
    val error: AnthropicErrorDetail
)

data class AnthropicErrorDetail(
    val type: String,
    val message: String
)
