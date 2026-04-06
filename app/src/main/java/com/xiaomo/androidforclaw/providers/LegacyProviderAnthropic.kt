package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-payloads.ts (legacy)
 */


import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext

/**
 * Anthropic Messages API provider
 * uses Anthropic native Messages API format
 *
 * API Documentation: https://docs.anthropic.com/en/api/messages
 */
class LegacyproviderAnthropic(
    private val apiKey: String,
    private val apiBase: String = "https://api.anthropic.com"
) {
    companion object {
        private const val TAG = "LegacyproviderAnthropic"
        private const val DEFAULT_TIMEOUT_SECONDS = 300L
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()  // Avoid HTML character escaping
        .serializeNulls()        // Serialize null values
        .create()

    /**
     * Send chat request (Anthropic Messages API format)
     */
    suspend fun chat(
        messages: List<LegacyMessage>,
        tools: List<toolDefinition>? = null,
        model: String = "claude-opus-4",
        maxTokens: Int = 8192,
        temperature: Double = 0.7,
        thinkingEnabled: Boolean = false,
        thinkingBudget: Int? = null
    ): LegacyResponse = withcontext(Dispatchers.IO) {

        // Anthropic restriction: temperature must be 1 when Extended Thinking is enabled
        val actualTemperature = if (thinkingEnabled) 1.0 else temperature

        // Separate system message
        val systemMessage = messages.firstorNull { it.role == "system" }?.content?.toString()
        val conversationMessages = messages.filter { it.role != "system" }

        // Build Anthropic format request
        val requestBody = AnthropicRequest(
            model = model,
            messages = conversationMessages.map { convertToAnthropicMessage(it) },
            maxTokens = maxTokens,
            temperature = actualTemperature,
            system = systemMessage,
            tools = tools?.map { converttoolToAnthropicformat(it) },
            thinking = if (thinkingEnabled && thinkingBudget != null) {
                Thinkingconfig(budgetTokens = thinkingBudget)
            } else if (thinkingEnabled) {
                Thinkingconfig()
            } else {
                null
            }
        )

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "Request to $apiBase/v1/messages")
        Log.d(TAG, "model: $model")
        Log.d(TAG, "Messages: ${conversationMessages.size}")
        Log.d(TAG, "tools: ${tools?.size ?: 0}")
        Log.d(TAG, "Thinking enabled: $thinkingEnabled")

        val request = Request.Builder()
            .url("$apiBase/v1/messages")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .aHeader("x-api-key", apiKey)
            .aHeader("Content-Type", "application/json")
            .aHeader("anthropic-version", ANTHROPIC_VERSION)
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw LLMexception("Empty response from Anthropic API")

            if (!response.isSuccessful) {
                Log.e(TAG, "API Error: $responseBody")
                throw LLMexception("HTTP ${response.code}: $responseBody")
            }

            // Parse Anthropic response
            val anthropicResponse = gson.fromJson(responseBody, AnthropicResponse::class.java)
            Log.d(TAG, "Response received: ${anthropicResponse.stopReason}")

            // Convertreturn LegacyResponse format
            convertfromAnthropicResponse(anthropicResponse)

        } catch (e: LLMexception) {
            throw e
        } catch (e: exception) {
            Log.e(TAG, "Request failed", e)
            throw LLMexception("Network error: ${e.message}", cause = e)
        }
    }

    /**
     * Convert message to Anthropic format
     */
    private fun convertToAnthropicMessage(msg: LegacyMessage): AnthropicMessage {
        return when (msg.role) {
            "user" -> {
                AnthropicMessage(
                    role = "user",
                    content = msg.content?.toString() ?: ""
                )
            }
            "assistant" -> {
                if (msg.toolCalls != null && msg.toolCalls.isnotEmpty()) {
                    // Assistant message contains tool calls
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()

                    // A text content (if any)
                    msg.content?.toString()?.let { text ->
                        if (text.isnotBlank()) {
                            contentBlocks.a(
                                AnthropicContentBlock(
                                    type = "text",
                                    text = text
                                )
                            )
                        }
                    }

                    // A tool calls
                    msg.toolCalls.forEach { tc ->
                        contentBlocks.a(
                            AnthropicContentBlock(
                                type = "tool_use",
                                id = tc.id,
                                name = tc.function.name,
                                input = parsetoolArguments(tc.function.arguments)
                            )
                        )
                    }

                    AnthropicMessage(
                        role = "assistant",
                        content = contentBlocks
                    )
                } else {
                    AnthropicMessage(
                        role = "assistant",
                        content = msg.content?.toString() ?: ""
                    )
                }
            }
            "tool" -> {
                // tool result message - Anthropic uses "user" role + tool_result block
                AnthropicMessage(
                    role = "user",
                    content = listOf(
                        AnthropicContentBlock(
                            type = "tool_result",
                            tooluseId = msg.toolCallId ?: "",
                            content = msg.content?.toString() ?: ""
                        )
                    )
                )
            }
            else -> {
                AnthropicMessage(
                    role = "user",
                    content = msg.content?.toString() ?: ""
                )
            }
        }
    }

    /**
     * Parse tool parameters JSON string to Map
     */
    private fun parsetoolArguments(jsonStr: String): Map<String, Any?> {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(jsonStr, Map::class.java) as Map<String, Any?>
        } catch (e: exception) {
            Log.w(TAG, "Failed to parse tool arguments: $jsonStr", e)
            emptyMap()
        }
    }

    /**
     * Convert tool definition to Anthropic format
     */
    private fun converttoolToAnthropicformat(tool: toolDefinition): Anthropictool {
        // Convert Parametersschema to Inputschema
        val params = tool.function.parameters
        val properties = params.properties.mapValues { (_, prop) ->
            PropertyDef(
                type = prop.type,
                description = prop.description ?: "",
                enum = prop.enum
            )
        }

        return Anthropictool(
            name = tool.function.name,
            description = tool.function.description,
            inputschema = Inputschema(
                type = "object",
                properties = properties,
                required = params.required
            )
        )
    }

    /**
     * Convert Anthropic response to LegacyResponse format
     */
    private fun convertfromAnthropicResponse(response: AnthropicResponse): LegacyResponse {
        // Extract text content and tool calls
        var textContent: String? = null
        val toolCalls = mutableListOf<LegacytoolCall>()
        var reasoningContent: String? = null

        response.content.forEach { block ->
            when (block.type) {
                "text" -> {
                    textContent = block.text
                }
                "tool_use" -> {
                    toolCalls.a(
                        LegacytoolCall(
                            id = block.id ?: "",
                            type = "function",
                            function = LegacyFunction(
                                name = block.name ?: "",
                                arguments = gson.toJson(block.input)
                            )
                        )
                    )
                }
                else -> {
                    // Ignore other types for now
                }
            }
        }

        val choice = LegacyChoice(
            index = 0,
            message = LegacyResponseMessage(
                role = "assistant",
                content = textContent,
                toolCalls = if (toolCalls.isnotEmpty()) toolCalls else null,
                reasoningContent = reasoningContent
            ),
            finishReason = response.stopReason ?: "stop"
        )

        return LegacyResponse(
            id = response.id,
            model = response.model,
            choices = listOf(choice),
            usage = LegacyUsage(
                promptTokens = response.usage.inputTokens,
                completionTokens = response.usage.outputTokens,
                totalTokens = response.usage.inputTokens + response.usage.outputTokens
            )
        )
    }

    /**
     * Simplified chat method
     */
    suspend fun simpleChat(
        userMessage: String,
        systemPrompt: String? = null
    ): String {
        val messages = mutableListOf<LegacyMessage>()

        if (systemPrompt != null) {
            messages.a(LegacyMessage("system", systemPrompt))
        }

        messages.a(LegacyMessage("user", userMessage))

        val response = chat(messages = messages)

        return response.choices.firstorNull()?.message?.content
            ?: "No response from model"
    }
}

// Data models defined in Anthropicmodels.kt
