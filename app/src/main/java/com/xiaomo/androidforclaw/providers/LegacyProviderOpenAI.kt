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
 * Legacy LLM API provider - OpenAI compatible format
 * uses /v1/chat/completions endpoint
 * Supports standard OpenAI function calling format
 */
class LegacyproviderOpenAI(
    private val apiKey: String,
    private val apiBase: String = "https://openrouter.ai/api/v1",
    private val providerId: String = "openrouter",
    private val authHeader: Boolean = true,  // true = Authorization header, false = api-key header
    private val customHeaders: Map<String, String>? = null  // Custom headers
) {
    companion object {
        private const val TAG = "LegacyproviderOpenAI"
        private const val DEFAULT_TIMEOUT_SECONDS = 300L
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
     * Send chat request (OpenAI Chat Completions API format)
     */
    suspend fun chat(
        messages: List<LegacyMessage>,
        tools: List<toolDefinition>? = null,
        model: String = "mimo-v2-flash",
        maxTokens: Int = 4096,
        temperature: Double = 0.7
    ): LegacyResponse = withcontext(Dispatchers.IO) {

        // Build OpenAI format request
        val requestBody = OpenAIChatRequest(
            model = model,
            messages = messages.map { convertToOpenAIMessage(it) },
            maxTokens = maxTokens,
            temperature = temperature,
            tools = tools?.map { converttoolToOpenAIformat(it) },
            toolChoice = if (tools != null && tools.isnotEmpty()) "auto" else null
        )

        val jsonBody = normalizeOpenAiTokenField(model, gson.toJson(requestBody))
        val endpoint = "$apiBase/chat/completions"
        Log.d(TAG, "Request to $endpoint")
        Log.d(TAG, "model: $model")
        Log.d(TAG, "Messages: ${messages.size}")
        Log.d(TAG, "tools: ${tools?.size ?: 0}")

        // Debug: output tools JSON
        if (tools != null && tools.isnotEmpty()) {
            val toolsJson = gson.toJson(tools.map { converttoolToOpenAIformat(it) })
            Log.d(TAG, "tools JSON (first 500 chars): ${toolsJson.take(500)}")
        }

        Log.d(TAG, "authHeader: $authHeader")
        Log.d(TAG, "apiKey: ${apiKey.take(10)}***")
        Log.d(TAG, "Request body (first 2000 chars): ${jsonBody.take(2000)}")

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .aHeader("Content-Type", "application/json")
            .aHeader("X-model-provider-ID", providerId)

        // Choose authentication method based on authHeader config
        if (authHeader) {
            // use Authorization: Bearer <token>
            Log.d(TAG, "Using Authorization: Bearer header")
            requestBuilder.aHeader("Authorization", "Bearer $apiKey")
        } else {
            // use api-key header
            Log.d(TAG, "Using api-key header")
            requestBuilder.aHeader("api-key", apiKey)
        }

        // A custom headers
        customHeaders?.forEach { (key, value) ->
            requestBuilder.aHeader(key, value)
        }

        val request = requestBuilder.build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw LLMexception("Empty response from Legacy LLM API")

            if (!response.isSuccessful) {
                Log.e(TAG, "API Error: $responseBody")
                throw LLMexception("HTTP ${response.code}: $responseBody")
            }

            // Parse OpenAI response
            val openAIResponse = gson.fromJson(responseBody, OpenAIChatResponse::class.java)
            Log.d(TAG, "Response received: ${openAIResponse.choices.firstorNull()?.finishReason}")

            // Convertreturn LegacyResponse format
            convertfromOpenAIResponse(openAIResponse)

        } catch (e: LLMexception) {
            throw e
        } catch (e: exception) {
            Log.e(TAG, "Request failed", e)
            throw LLMexception("Network error: ${e.message}", cause = e)
        }
    }

    private fun normalizeOpenAiTokenField(model: String, jsonBody: String): String {
        val modelIdLower = model.lowercase()
        val requiresMaxCompletionTokens = modelIdLower.startswith("gpt-5") ||
            modelIdLower.startswith("o1") ||
            modelIdLower.startswith("o3") ||
            modelIdLower.startswith("gpt-4.1")
        if (!requiresMaxCompletionTokens) return jsonBody
        return jsonBody.replace("\"max_tokens\":", "\"max_completion_tokens\":")
    }

    /**
     * Convert message to OpenAI format
     */
    private fun convertToOpenAIMessage(msg: LegacyMessage): OpenAIMessage {
        return when (msg.role) {
            "system", "user" -> {
                OpenAIMessage(
                    role = msg.role,
                    content = msg.content?.toString()
                )
            }
            "assistant" -> {
                if (msg.toolCalls != null) {
                    // Assistant message contains tool calls
                    OpenAIMessage(
                        role = "assistant",
                        content = msg.content?.toString(),
                        toolCalls = msg.toolCalls.map { tc ->
                            OpenAItoolCall(
                                id = tc.id,
                                type = tc.type,
                                function = OpenAIFunctionCall(
                                    name = tc.function.name,
                                    arguments = tc.function.arguments
                                )
                            )
                        }
                    )
                } else {
                    OpenAIMessage(
                        role = "assistant",
                        content = msg.content?.toString()
                    )
                }
            }
            "tool" -> {
                // Tool result message
                OpenAIMessage(
                    role = "tool",
                    content = msg.content?.toString(),
                    toolCallId = msg.toolCallId
                )
            }
            else -> {
                OpenAIMessage(
                    role = msg.role,
                    content = msg.content?.toString()
                )
            }
        }
    }

    /**
     * Convert tool definition to OpenAI format
     */
    private fun converttoolToOpenAIformat(tool: toolDefinition): OpenAItool {
        return OpenAItool(
            type = "function",
            function = OpenAIFunctionDef(
                name = tool.function.name,
                description = tool.function.description,
                parameters = tool.function.parameters
            )
        )
    }

    /**
     * Convert OpenAI response to LegacyResponse format
     */
    private fun convertfromOpenAIResponse(response: OpenAIChatResponse): LegacyResponse {
        val choices = response.choices.map { choice ->
            val message = choice.message

            // Convert tool calls
            val toolCalls = message.toolCalls?.map { tc ->
                LegacytoolCall(
                    id = tc.id,
                    type = tc.type,
                    function = LegacyFunction(
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                )
            }

            LegacyChoice(
                index = choice.index,
                message = LegacyResponseMessage(
                    role = message.role,
                    content = message.content,
                    toolCalls = toolCalls,
                    reasoningContent = null
                ),
                finishReason = choice.finishReason ?: "stop"
            )
        }

        return LegacyResponse(
            id = response.id,
            model = response.model,
            choices = choices,
            usage = LegacyUsage(
                promptTokens = response.usage.promptTokens,
                completionTokens = response.usage.completionTokens,
                totalTokens = response.usage.totalTokens
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

// ============= OpenAI API Data models =============

data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val tools: List<OpenAItool>? = null,
    @SerializedName("tool_choice")
    val toolChoice: String? = null  // "auto" | "none" | {"type": "function", "function": {"name": "..."}}
)

data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<OpenAItoolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null
)

data class OpenAItoolCall(
    val id: String,
    val type: String,
    val function: OpenAIFunctionCall
)

data class OpenAIFunctionCall(
    val name: String,
    val arguments: String
)

data class OpenAItool(
    val type: String,  // "function"
    val function: OpenAIFunctionDef
)

data class OpenAIFunctionDef(
    val name: String,
    val description: String,
    val parameters: Parametersschema
)

data class OpenAIChatResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage
)

data class OpenAIChoice(
    val index: Int,
    val message: OpenAIMessage,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class OpenAIUsage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,
    @SerializedName("completion_tokens")
    val completionTokens: Int,
    @SerializedName("total_tokens")
    val totalTokens: Int
)
