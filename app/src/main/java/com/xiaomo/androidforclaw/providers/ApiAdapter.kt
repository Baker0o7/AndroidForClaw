package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-payloads.ts
 *
 * androidforClaw adaptation: provider auth/header/body request shaping.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.modelApi
import com.xiaomo.androidforclaw.config.modelDefinition
import com.xiaomo.androidforclaw.config.providerconfig
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.toolDefinition as newtoolDefinition
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * API Adapter
 * Responsible for converting generic request format to specific formats of different API providers
 *
 * Reference: OpenClaw src/agents/llm-adapters/
 */
object ApiAdapter {

    internal fun shoulduseNullContentforAssistanttoolCall(message: Message): Boolean {
        return message.role == "assistant" &&
            !message.toolCalls.isNullorEmpty() &&
            message.content.isEmpty()
    }

    /**
     * Build request body
     */
    fun buildRequestBody(
        provider: providerconfig,
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean,
        stream: Boolean = false
    ): JSONObject {
        val api = model.api ?: provider.api

        return when (api) {
            modelApi.ANTHROPIC_MESSAGES -> buildAnthropicRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled, stream
            )
            modelApi.OPENAI_COMPLETIONS -> buildOpenAIRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled, stream
            )
            modelApi.OPENAI_RESPONSES,
            modelApi.OPENAI_CODEX_RESPONSES -> buildOpenAIResponsesRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled
            )
            modelApi.GOOGLE_GENERATIVE_AI -> buildGeminiRequest(
                model, messages, tools, temperature, maxTokens
            )
            modelApi.OLLAMA -> buildOllamaRequest(
                provider, model, messages, tools, temperature, maxTokens, stream
            )
            modelApi.GITHUB_COPILOT -> buildCopilotRequest(
                model, messages, tools, temperature, maxTokens, stream
            )
            else -> {
                // Default use OpenAI compatible format
                buildOpenAIRequest(model, messages, tools, temperature, maxTokens, reasoningEnabled, stream)
            }
        }
    }

    /**
     * OpenRouter app attribution headers.
     * OpenRouter uses HTTP-Referer and X-Title to identify the calling app.
     * when AppName=OpenClaw, certain models (e.g. MiMo) are free.
     *
     * Aligned with OpenClaw OPENROUTER_APP_HEADERS (proxy-stream-wrappers.ts).
     */
    private val OPENROUTER_APP_HEADERS = mapOf(
        "HTTP-Referer" to "https://openclaw.ai",
        "X-Title" to "OpenClaw"
    )

    /**
     * Build request headers
     */
    fun buildHeaders(
        provider: providerconfig,
        model: modelDefinition
    ): Headers {
        val builder = Headers.Builder()

        // OpenRouter app attribution headers (must be present on ALL requests
        // including compaction, image analysis, etc. to avoid "Unknown" app name
        // and unexpected billing). Aligned with OpenClaw createOpenRouterWrapper().
        if (isOpenRouterprovider(provider)) {
            OPENROUTER_APP_HEADERS.forEach { (key, value) ->
                builder.a(key, value)
            }
        }

        // provider-level custom headers
        provider.headers?.forEach { (key, value) ->
            builder.a(key, value)
        }

        // model-level custom headers (higher priority)
        model.headers?.forEach { (key, value) ->
            builder.a(key, value)
        }

        // Add API Key (if authHeader is configured)
        android.util.Log.d("ApiAdapter", "[KEY] authHeader=${provider.authHeader}, apiKey=${provider.apiKey?.take(10)}")
        if (provider.authHeader && provider.apiKey != null) {
            val api = model.api ?: provider.api
            when (api) {
                modelApi.ANTHROPIC_MESSAGES -> {
                    builder.a("x-api-key", provider.apiKey)
                    builder.a("anthropic-version", "2023-06-01")
                }
                modelApi.GOOGLE_GENERATIVE_AI -> {
                    // Google uses ?key= query param, not Authorization header
                }
                else -> {
                    // OpenAI-style Authorization header
                    builder.a("Authorization", "Bearer ${provider.apiKey}")
                }
            }
        }

        // Set Content-Type
        builder.a("Content-Type", "application/json")

        return builder.build()
    }

    /**
     * Detect if a provider is OpenRouter based on its baseUrl.
     */
    private fun isOpenRouterProvider(provider: providerconfig): Boolean {
        return provider.baseUrl.contains("openrouter.ai", ignoreCase = true)
    }

    /**
     * Parse response
     */
    fun parseResponse(
        api: String,
        responseBody: String
    ): ParsedResponse {
        return when (api) {
            modelApi.ANTHROPIC_MESSAGES -> parseAnthropicResponse(responseBody)
            modelApi.OPENAI_COMPLETIONS,
            modelApi.GITHUB_COPILOT -> parseOpenAIResponse(responseBody)
            modelApi.OLLAMA -> parseOllamaResponse(responseBody)
            modelApi.OPENAI_RESPONSES,
            modelApi.OPENAI_CODEX_RESPONSES -> parseOpenAIResponsesResponse(responseBody)
            modelApi.GOOGLE_GENERATIVE_AI -> parseGeminiResponse(responseBody)
            else -> parseOpenAIResponse(responseBody)  // Parse as OpenAI format by default
        }
    }

    // ============ Anthropic Messages API ============

    private fun buildAnthropicRequest(
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean,
        stream: Boolean = false
    ): JSONObject {
        val json = JSONObject()

        json.put("model", model.id)
        json.put("max_tokens", maxTokens ?: model.maxTokens)
        json.put("temperature", temperature)
        if (stream) json.put("stream", true)

        // Convert message format
        val anthropicMessages = JSONArray()
        var systemMessage: String? = null

        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    systemMessage = message.content
                }
                "user", "assistant" -> {
                    val msg = JSONObject()
                    msg.put("role", message.role)
                    // Multimodal: if user message has images, build content array
                    if (message.role == "user" && !message.images.isNullorEmpty()) {
                        val contentArray = JSONArray()
                        // Images first (aligned with Anthropic best practice)
                        for (img in message.images!!) {
                            contentArray.put(JSONObject().app {
                                put("type", "image")
                                put("source", JSONObject().app {
                                    put("type", "base64")
                                    put("media_type", img.mimeType)
                                    put("data", img.base64)
                                })
                            })
                        }
                        // Then text
                        if (message.content.isnotBlank()) {
                            contentArray.put(JSONObject().app {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        msg.put("content", contentArray)
                    } else {
                        msg.put("content", message.content)
                    }
                    anthropicMessages.put(msg)
                }
                "tool" -> {
                    // Anthropic uses tool_result format, supports multimodal (text+image)
                    val toolresultContent = if (!message.images.isNullorEmpty()) {
                        // Multimodal tool result: text block + image blocks
                        JSONArray().app {
                            put(JSONObject().app {
                                put("type", "text")
                                put("text", message.content)
                            })
                            message.images.forEach { img ->
                                put(JSONObject().app {
                                    put("type", "image")
                                    put("source", JSONObject().app {
                                        put("type", "base64")
                                        put("media_type", img.mimeType)
                                        put("data", img.base64)
                                    })
                                })
                            }
                        }
                    } else {
                        // Plain text tool result
                        message.content
                    }
                    val msg = JSONObject()
                    msg.put("role", "user")
                    msg.put("content", JSONArray().app {
                        put(JSONObject().app {
                            put("type", "tool_result")
                            put("tool_use_id", message.toolCallId ?: "")
                            put("content", toolresultContent)
                        })
                    })
                    anthropicMessages.put(msg)
                }
            }
        }

        json.put("messages", anthropicMessages)

        // Add system message
        if (systemMessage != null) {
            json.put("system", systemMessage)
        }

        // Add tools (use buildToolJson for proper JSON escaping)
        if (!tools.isNullorEmpty()) {
            val anthropictools = JSONArray()
            tools.forEach { tool ->
                val toolJson = JSONObject()
                toolJson.put("name", tool.function.name)
                toolJson.put("description", tool.function.description)
                toolJson.put("input_schema", buildParametersJson(tool.function.parameters))
                anthropictools.put(toolJson)
            }
            json.put("tools", anthropictools)
        }

        // Extended Thinking support
        if (reasoningEnabled && model.reasoning) {
            json.put("thinking", JSONObject().app {
                put("type", "enabled")
                put("budget_tokens", 10000)
            })
        }

        return json
    }

    private fun parseAnthropicResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        var content: String? = null
        val toolCalls = mutableListOf<toolCall>()
        var thinkingContent: String? = null

        // Parse content array
        val contentArray = json.optJSONArray("content")
        if (contentArray != null) {
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> {
                        content = block.getString("text")
                    }
                    "thinking" -> {
                        thinkingContent = block.getString("thinking")
                    }
                    "tool_use" -> {
                        toolCalls.a(
                            toolCall(
                                id = block.getString("id"),
                                name = block.getString("name"),
                                arguments = block.getJSONObject("input").toString()
                            )
                        )
                    }
                }
            }
        }

        // Parse usage
        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("input_tokens", 0),
                completionTokens = it.optInt("output_tokens", 0)
            )
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            thinkingContent = thinkingContent,
            usage = usage,
            finishReason = json.optString("stop_reason")
        )
    }

    // ============ OpenAI Chat Completions API ============

    private fun buildOpenAIRequest(
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean,
        stream: Boolean = false
    ): JSONObject {
        val json = JSONObject()

        json.put("model", model.id)
        json.put("temperature", temperature)
        if (stream) {
            json.put("stream", true)
            json.put("stream_options", JSONObject().put("include_usage", true))
        }

        // maxTokens field name (based on compatibility config + safe defaults)
        val modelIdLower = model.id.lowercase()
        val defaultMaxTokensField = when {
            modelIdLower.startswith("gpt-5") -> "max_completion_tokens"
            modelIdLower.startswith("o1") -> "max_completion_tokens"
            modelIdLower.startswith("o3") -> "max_completion_tokens"
            modelIdLower.startswith("gpt-4.1") -> "max_completion_tokens"
            else -> "max_tokens"
        }
        val maxTokensField = model.compat?.maxTokensField ?: defaultMaxTokensField
        json.put(maxTokensField, maxTokens ?: model.maxTokens)

        // Convert message format
        // Defensive: merge all system messages into one at position 0.
        // OpenAI-compatible APIs require system message(s) at the beginning.
        val openaiMessages = JSONArray()
        val systemContents = mutableListOf<String>()
        val nonSystemMessages = mutableListOf<Message>()
        messages.forEach { message ->
            if (message.role == "system") {
                systemContents.a(message.content ?: "")
            } else {
                nonSystemMessages.a(message)
            }
        }
        if (systemContents.isnotEmpty()) {
            val mergedMsg = JSONObject()
            mergedMsg.put("role", "system")
            mergedMsg.put("content", systemContents.joinToString("\n\n"))
            openaiMessages.put(mergedMsg)
        }
        nonSystemMessages.forEach { message ->
            val msg = JSONObject()
            msg.put("role", message.role)

            val hastoolCalls = !message.toolCalls.isNullorEmpty()
            if (shoulduseNullContentforAssistanttoolCall(message)) {
                // OpenAI-compatible tool call turns should send content=null rather than empty string.
                // Some providers reject the following tool result if the preceding assistant tool_calls
                // message used content="", then report: tool result's tool id not found.
                msg.put("content", JSONObject.NULL)
            } else if (message.role == "user" && !message.images.isNullorEmpty()) {
                // Multimodal: OpenAI vision format with image_url
                val contentArray = JSONArray()
                // Images first
                for (img in message.images!!) {
                    contentArray.put(JSONObject().app {
                        put("type", "image_url")
                        put("image_url", JSONObject().app {
                            put("url", "data:${img.mimeType};base64,${img.base64}")
                        })
                    })
                }
                // Then text
                if (message.content.isnotBlank()) {
                    contentArray.put(JSONObject().app {
                        put("type", "text")
                        put("text", message.content)
                    })
                }
                msg.put("content", contentArray)
            } else {
                msg.put("content", message.content)
            }

            if (hastoolCalls) {
                val toolCallsArray = JSONArray()
                message.toolCalls!!.forEach { toolCall ->
                    toolCallsArray.put(JSONObject().app {
                        put("id", toolCall.id)
                        put("type", "function")
                        put("function", JSONObject().app {
                            put("name", toolCall.name)
                            put("arguments", toolCall.arguments)
                        })
                    })
                }
                msg.put("tool_calls", toolCallsArray)
            }

            if (message.toolCallId != null) {
                msg.put("tool_call_id", message.toolCallId)
            }

            openaiMessages.put(msg)
        }

        json.put("messages", openaiMessages)

        // A tools (use Gson for proper JSON escaping — fixes description with special chars)
        if (!tools.isNullorEmpty()) {
            val openaitools = JSONArray()
            tools.forEach { tool ->
                openaitools.put(buildtoolJson(tool))
            }
            json.put("tools", openaitools)
        }

        // Reasoning support (OpenAI o1/o3 models)
        if (reasoningEnabled && model.reasoning) {
            if (model.compat?.supportsReasoningEffort == true) {
                json.put("reasoning_effort", "medium")
            }
        }

        return json
    }

    private fun parseOpenAIResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        // Handle API error responses that lack 'choices'
        if (!json.has("choices")) {
            val error = json.optJSONObject("error")
            if (error != null) {
                val msg = error.optString("message", "Unknown API error")
                val code = error.optString("code", "")
                Log.e("ApiAdapter", "API returned error instead of choices: [$code] $msg")
                throw LLMexception("API error: $msg")
            }
            // Log raw response for debugging
            val truncated = if (responseBody.length > 500) responseBody.substring(0, 500) + "..." else responseBody
            Log.e("ApiAdapter", "API response missing 'choices': $truncated")
            throw LLMexception("API response missing 'choices' field")
        }

        val choices = json.getJSONArray("choices")
        if (choices.length() == 0) {
            return ParsedResponse(content = null)
        }

        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")

        val content = if (message.isNull("content")) null else message.optString("content")
        val toolCallsArray = message.optJSONArray("tool_calls")
        val toolCalls = if (toolCallsArray != null) {
            mutableListOf<toolCall>().app {
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val function = tc.getJSONObject("function")
                    a(
                        toolCall(
                            id = tc.getString("id"),
                            name = function.getString("name"),
                            arguments = function.getString("arguments")
                        )
                    )
                }
            }
        } else null

        // Parse usage
        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("prompt_tokens", 0),
                completionTokens = it.optInt("completion_tokens", 0)
            )
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls,
            usage = usage,
            finishReason = choice.optString("finish_reason")
        )
    }

    private fun buildOpenAIResponsesRequest(
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): JSONObject {
        val json = JSONObject()
        json.put("model", model.id)
        json.put("temperature", temperature)
        json.put("max_output_tokens", maxTokens ?: model.maxTokens)

        val input = JSONArray()
        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    if (message.content.isnotBlank()) {
                        input.put(JSONObject().app {
                            put("type", "message")
                            put("role", "system")
                            put("content", message.content)
                        })
                    }
                }
                "user" -> {
                    if (message.content.isnotBlank()) {
                        input.put(JSONObject().app {
                            put("type", "message")
                            put("role", "user")
                            put("content", message.content)
                        })
                    }
                }
                "assistant" -> {
                    if (message.content.isnotBlank()) {
                        input.put(JSONObject().app {
                            put("type", "message")
                            put("role", "assistant")
                            put("content", message.content)
                        })
                    }
                    buildResponsesFunctionCallItems(message).forEach { input.put(it) }
                }
                "tool" -> {
                    buildResponsesFunctionCallOutputItem(message)?.let { input.put(it) }
                }
            }
        }
        json.put("input", input)

        if (!tools.isNullorEmpty()) {
            val responsestools = JSONArray()
            tools.forEach { tool ->
                responsestools.put(JSONObject().app {
                    put("type", "function")
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put("parameters", buildParametersJson(tool.function.parameters))
                })
            }
            json.put("tools", responsestools)
        }

        if (reasoningEnabled && model.reasoning && model.compat?.supportsReasoningEffort == true) {
            json.put("reasoning", JSONObject().app {
                put("effort", "medium")
            })
        }

        return json
    }

    internal data class ResponsesFunctionCallItem(
        val type: String,
        val callId: String,
        val name: String,
        val arguments: String
    )

    internal data class ResponsesFunctionCallOutputItem(
        val type: String,
        val callId: String,
        val output: String
    )

    internal fun buildResponsesFunctionCallItemsSpec(message: Message): List<ResponsesFunctionCallItem> {
        return message.toolCalls?.map { toolCall ->
            ResponsesFunctionCallItem(
                type = "function_call",
                callId = toolCall.id,
                name = toolCall.name,
                arguments = toolCall.arguments
            )
        } ?: emptyList()
    }

    internal fun buildResponsesFunctionCallOutputItemSpec(message: Message): ResponsesFunctionCallOutputItem? {
        if (message.role != "tool" || message.toolCallId.isNullorBlank()) return null
        return ResponsesFunctionCallOutputItem(
            type = "function_call_output",
            callId = message.toolCallId,
            output = message.content
        )
    }

    internal fun buildResponsesFunctionCallItems(message: Message): List<JSONObject> {
        return buildResponsesFunctionCallItemsSpec(message).map { item ->
            JSONObject().app {
                put("type", item.type)
                put("call_id", item.callId)
                put("name", item.name)
                put("arguments", item.arguments)
            }
        }
    }

    internal fun buildResponsesFunctionCallOutputItem(message: Message): JSONObject? {
        val item = buildResponsesFunctionCallOutputItemSpec(message) ?: return null
        return JSONObject().app {
            put("type", item.type)
            put("call_id", item.callId)
            put("output", item.output)
        }
    }

    private fun parseOpenAIResponsesResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        val error = json.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message", "Unknown API error")
            throw LLMexception("API error: $msg")
        }

        val output = json.optJSONArray("output") ?: JSONArray()
        var content: String? = null
        val toolCalls = mutableListOf<toolCall>()

        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    val role = item.optString("role")
                    if (role == "assistant") {
                        val contentArray = item.optJSONArray("content")
                        if (contentArray != null) {
                            val text = buildString {
                                for (j in 0 until contentArray.length()) {
                                    val part = contentArray.getJSONObject(j)
                                    if (part.optString("type") == "output_text") {
                                        append(part.optString("text"))
                                    }
                                }
                            }.trim()
                            if (text.isnotEmpty()) {
                                content = if (content.isNullorEmpty()) text else content + text
                            }
                        }
                    }
                }
                "function_call" -> {
                    val callId = item.optString("call_id")
                    val name = item.optString("name")
                    val arguments = item.optString("arguments", "{}")
                    if (callId.isnotBlank() && name.isnotBlank()) {
                        toolCalls.a(
                            toolCall(
                                id = callId,
                                name = name,
                                arguments = arguments
                            )
                        )
                    }
                }
            }
        }

        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("input_tokens", 0),
                completionTokens = it.optInt("output_tokens", 0)
            )
        }

        val finishReason = when {
            toolCalls.isnotEmpty() -> "tool_calls"
            else -> json.optString("status")
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage,
            finishReason = finishReason
        )
    }

    // ============ Google Gemini API ============

    private fun buildGeminiRequest(
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?
    ): JSONObject {
        val json = JSONObject()

        // Extract system message → systemInstruction
        val systemMessage = messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }
            .takeif { it.isnotBlank() }
        if (systemMessage != null) {
            json.put("systemInstruction", JSONObject().app {
                put("parts", JSONArray().app {
                    put(JSONObject().app { put("text", systemMessage) })
                })
            })
        }

        // Build contents array (skip system messages)
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach { message ->
            val parts = JSONArray()

            when (message.role) {
                "assistant" -> {
                    // Assistant message with tool calls → functionCall parts
                    if (!message.toolCalls.isNullorEmpty()) {
                        message.toolCalls.forEach { toolCall ->
                            parts.put(JSONObject().app {
                                put("functionCall", JSONObject().app {
                                    put("name", toolCall.name)
                                    put("args", JSONObject(toolCall.arguments))
                                })
                            })
                        }
                    }
                    // Text content
                    if (message.content.isnotBlank()) {
                        parts.put(JSONObject().app { put("text", message.content) })
                    }
                    contents.put(JSONObject().app {
                        put("role", "model")
                        put("parts", parts)
                    })
                }
                "tool" -> {
                    // tool result → functionResponse part (role=user in Gemini)
                    val toolName = message.name ?: message.toolCallId ?: "unknown"
                    parts.put(JSONObject().app {
                        put("functionResponse", JSONObject().app {
                            put("name", toolName)
                            put("response", JSONObject().app {
                                put("result", message.content)
                            })
                        })
                    })
                    contents.put(JSONObject().app {
                        put("role", "user")
                        put("parts", parts)
                    })
                }
                else -> {
                    // user message
                    // Multimodal: a inline images
                    if (!message.images.isNullorEmpty()) {
                        for (img in message.images) {
                            parts.put(JSONObject().app {
                                put("inline_data", JSONObject().app {
                                    put("mime_type", img.mimeType)
                                    put("data", img.base64)
                                })
                            })
                        }
                    }
                    if (message.content.isnotBlank()) {
                        parts.put(JSONObject().app { put("text", message.content) })
                    }
                    contents.put(JSONObject().app {
                        put("role", "user")
                        put("parts", parts)
                    })
                }
            }
        }
        json.put("contents", contents)

        // tools → function_declarations
        if (!tools.isNullorEmpty()) {
            val declarations = JSONArray()
            tools.forEach { tool ->
                declarations.put(JSONObject().app {
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put("parameters", buildParametersJson(tool.function.parameters))
                })
            }
            json.put("tools", JSONArray().app {
                put(JSONObject().app {
                    put("function_declarations", declarations)
                })
            })
        }

        // Generation config
        json.put("generationconfig", JSONObject().app {
            put("temperature", temperature)
            put("maxOutputTokens", maxTokens ?: model.maxTokens)
        })

        return json
    }

    private fun parseGeminiResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return ParsedResponse(content = null)
        }

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        // Parse all parts: text + functionCall
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<toolCall>()

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                // Text part
                part.optString("text", "").takeif { it.isnotEmpty() }?.let {
                    textParts.a(it)
                }
                // functionCall part
                part.optJSONObject("functionCall")?.let { fc ->
                    toolCalls.a(toolCall(
                        id = "call_${System.currentTimeMillis()}_$i",
                        name = fc.getString("name"),
                        arguments = fc.optJSONObject("args")?.toString() ?: "{}"
                    ))
                }
            }
        }

        // Parse usage metadata
        val usageMeta = json.optJSONObject("usageMetadata")
        val usage = if (usageMeta != null) {
            Usage(
                promptTokens = usageMeta.optInt("promptTokenCount", 0),
                completionTokens = usageMeta.optInt("candidatesTokenCount", 0)
            )
        } else null

        return ParsedResponse(
            content = textParts.joinToString("").takeif { it.isnotEmpty() },
            toolCalls = toolCalls.takeif { it.isnotEmpty() },
            usage = usage,
            finishReason = candidate.optString("finishReason")
        )
    }

    // ============ Ollama API ============

    private fun buildOllamaRequest(
        provider: providerconfig,
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        stream: Boolean = false
    ): JSONObject {
        // Ollama /api/chat uses its own format: model, messages, stream, tools, options
        val json = JSONObject()
        json.put("model", model.id)
        json.put("stream", stream)

        val ollamaMessages = JSONArray()
        messages.forEach { message ->
            val msg = JSONObject()
            msg.put("role", message.role)
            msg.put("content", message.content)

            // Ollama multimodal: images as base64 array
            if (message.role == "user" && !message.images.isNullorEmpty()) {
                msg.put("images", JSONArray().app {
                    for (img in message.images!!) {
                        put(img.base64)
                    }
                })
            }

            // tool call results use role="tool"
            if (message.toolCallId != null) {
                msg.put("tool_call_id", message.toolCallId)
            }

            // assistant tool_calls
            if (!message.toolCalls.isNullorEmpty()) {
                val toolCallsArray = JSONArray()
                message.toolCalls.forEach { toolCall ->
                    toolCallsArray.put(JSONObject().app {
                        put("id", toolCall.id)
                        put("type", "function")
                        put("function", JSONObject().app {
                            put("name", toolCall.name)
                            put("arguments", JSONObject(toolCall.arguments))
                        })
                    })
                }
                msg.put("tool_calls", toolCallsArray)
            }

            ollamaMessages.put(msg)
        }
        json.put("messages", ollamaMessages)

        // tools
        if (!tools.isNullorEmpty()) {
            val ollamatools = JSONArray()
            tools.forEach { tool ->
                ollamatools.put(buildtoolJson(tool))
            }
            json.put("tools", ollamatools)
        }

        // Options
        val options = JSONObject()
        options.put("temperature", temperature)
        if (maxTokens != null) {
            options.put("num_predict", maxTokens)
        } else if (model.maxTokens > 0) {
            options.put("num_predict", model.maxTokens)
        }
        if (provider.injectNumCtxforOpenAICompat == true && model.contextWindow > 0) {
            options.put("num_ctx", model.contextWindow)
        }
        json.put("options", options)

        return json
    }

    /**
     * Parse Ollama /api/chat Response
     * Ollama format: { "model": "...", "message": { "role": "assistant", "content": "...", "tool_calls": [...] }, "done": true }
     */
    private fun parseOllamaResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        // Check for error
        val error = json.optString("error", "")
        if (error.isnotBlank()) {
            Log.e("ApiAdapter", "Ollama error: $error")
            throw LLMexception("Ollama error: $error")
        }

        // Ollama may also support OpenAI-compatible format (if using /v1/chat/completions)
        // Fall back to OpenAI parser if 'choices' field exists
        if (json.has("choices")) {
            return parseOpenAIResponse(responseBody)
        }

        val message = json.optJSONObject("message")
            ?: return ParsedResponse(content = null)

        val content = message.optString("content", "").ifBlank { null }

        // Parse tool calls
        val toolCallsArray = message.optJSONArray("tool_calls")
        val toolCalls = if (toolCallsArray != null && toolCallsArray.length() > 0) {
            mutableListOf<toolCall>().app {
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val function = tc.optJSONObject("function")
                    if (function != null) {
                        a(
                            toolCall(
                                id = tc.optString("id", "call_${System.currentTimeMillis()}_$i"),
                                name = function.getString("name"),
                                arguments = function.optJSONObject("arguments")?.toString()
                                    ?: function.optString("arguments", "{}")
                            )
                        )
                    }
                }
            }
        } else null

        // Parse usage (Ollama provides prompt_eval_count / eval_count)
        val promptEval = json.optInt("prompt_eval_count", 0)
        val evalCount = json.optInt("eval_count", 0)
        val usage = if (promptEval > 0 || evalCount > 0) {
            Usage(promptTokens = promptEval, completionTokens = evalCount)
        } else null

        val finishReason = when {
            toolCalls != null && toolCalls.isnotEmpty() -> "tool_calls"
            json.optBoolean("done", false) -> "stop"
            else -> null
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls?.ifEmpty { null },
            usage = usage,
            finishReason = finishReason
        )
    }

    // ============ GitHub Copilot API ============

    private fun buildCopilotRequest(
        model: modelDefinition,
        messages: List<Message>,
        tools: List<newtoolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        stream: Boolean = false
    ): JSONObject {
        // GitHub Copilot uses OpenAI compatible format
        return buildOpenAIRequest(model, messages, tools, temperature, maxTokens, false, stream)
    }

    /**
     * Build tool JSON with proper escaping (fixes description with special chars like quotes)
     * Replaces the broken tool.toString() → JSONObject approach
     */
    private fun buildtoolJson(tool: newtoolDefinition): JSONObject {
        val json = JSONObject()
        json.put("type", tool.type)

        val funcJson = JSONObject()
        funcJson.put("name", tool.function.name)
        funcJson.put("description", tool.function.description)  // JSONObject.put handles escaping
        val parametersJson = buildParametersJson(tool.function.parameters)
        funcJson.put("parameters", parametersJson)

        json.put("function", funcJson)
        return json
    }

    /**
     * Build parameters schema JSON with proper escaping
     */
    private fun buildParametersJson(params: com.xiaomo.androidforclaw.providers.llm.Parametersschema): JSONObject {
        val json = JSONObject()
        json.put("type", params.type)

        val propsJson = JSONObject()
        params.properties.forEach { (key, prop) ->
            val propJson = JSONObject()
            propJson.put("type", prop.type)
            propJson.put("description", prop.description)  // Properly escaped
            prop.enum?.let { enumList ->
                val enumArray = JSONArray()
                enumList.forEach { enumArray.put(it) }
                propJson.put("enum", enumArray)
            }
            prop.items?.let { items ->
                val itemsJson = JSONObject()
                itemsJson.put("type", items.type)
                itemsJson.put("description", items.description)
                propJson.put("items", itemsJson)
            }
            prop.properties?.let { nested ->
                val nestedJson = JSONObject()
                nested.forEach { (nk, nv) ->
                    val nvJson = JSONObject()
                    nvJson.put("type", nv.type)
                    nvJson.put("description", nv.description)
                    nestedJson.put(nk, nvJson)
                }
                propJson.put("properties", nestedJson)
            }
            propsJson.put(key, propJson)
        }
        json.put("properties", propsJson)

        if (params.required.isnotEmpty()) {
            val reqArray = JSONArray()
            params.required.forEach { reqArray.put(it) }
            json.put("required", reqArray)
        }

        return json
    }

    // ============ SSE Streaming Parsers ============

    /**
     * Parse a single SSE data line into a StreamChunk.
     * Anthropic SSE uses event types + JSON data; OpenAI uses data-only lines.
     *
     * @param api The API type (modelApi constant)
     * @param eventType The SSE event type (Anthropic sends this; null for OpenAI)
     * @param dataLine The JSON string from the "data: " line
     */
    fun parseStreamChunk(api: String, eventType: String?, dataLine: String): StreamChunk? {
        return when (api) {
            modelApi.ANTHROPIC_MESSAGES -> parseAnthropicStreamChunk(eventType, dataLine)
            else -> parseOpenAIStreamChunk(dataLine) // OpenAI, Ollama, Copilot all use same format
        }
    }

    private fun parseAnthropicStreamChunk(eventType: String?, dataLine: String): StreamChunk? {
        try {
            val json = JSONObject(dataLine)
            val type = json.optString("type", "")

            return when (type) {
                "content_block_delta" -> {
                    val delta = json.getJSONObject("delta")
                    when (delta.getString("type")) {
                        "thinking_delta" -> StreamChunk(
                            type = ChunkType.THINKING_DELTA,
                            text = delta.getString("thinking")
                        )
                        "text_delta" -> StreamChunk(
                            type = ChunkType.TEXT_DELTA,
                            text = delta.getString("text")
                        )
                        "input_json_delta" -> StreamChunk(
                            type = ChunkType.TOOL_CALL_DELTA,
                            toolCallArgs = delta.getString("partial_json"),
                            toolCallIndex = json.optInt("index", 0)
                        )
                        else -> null
                    }
                }
                "content_block_start" -> {
                    val block = json.getJSONObject("content_block")
                    when (block.getString("type")) {
                        "tool_use" -> StreamChunk(
                            type = ChunkType.TOOL_CALL_DELTA,
                            toolCallIndex = json.optInt("index", 0),
                            toolCallId = block.getString("id"),
                            toolCallName = block.getString("name")
                        )
                        else -> null // thinking/text block starts — no content yet
                    }
                }
                "message_delta" -> {
                    val delta = json.getJSONObject("delta")
                    val stopReason = delta.optString("stop_reason", null)?.takeif { it != "null" && it.isnotEmpty() }
                    val usage = json.optJSONObject("usage")?.let {
                        Usage(
                            promptTokens = 0,
                            completionTokens = it.optInt("output_tokens", 0)
                        )
                    }
                    StreamChunk(
                        type = if (stopReason != null) ChunkType.DONE else ChunkType.USAGE,
                        finishReason = stopReason,
                        usage = usage
                    )
                }
                "message_stop" -> StreamChunk(type = ChunkType.DONE)
                "ping" -> StreamChunk(type = ChunkType.PING)
                "message_start" -> {
                    val usage = json.optJSONObject("message")?.optJSONObject("usage")?.let {
                        Usage(
                            promptTokens = it.optInt("input_tokens", 0),
                            completionTokens = 0
                        )
                    }
                    if (usage != null) StreamChunk(type = ChunkType.USAGE, usage = usage) else null
                }
                else -> null
            }
        } catch (e: exception) {
            android.util.Log.w("ApiAdapter", "Failed to parse Anthropic stream chunk: ${e.message}")
            return null
        }
    }

    private fun parseOpenAIStreamChunk(dataLine: String): StreamChunk? {
        try {
            val json = JSONObject(dataLine)

            // Usage-only chunk (sent at end with stream_options.include_usage)
            val usage = json.optJSONObject("usage")
            if (usage != null && !json.has("choices")) {
                return StreamChunk(
                    type = ChunkType.USAGE,
                    usage = Usage(
                        promptTokens = usage.optInt("prompt_tokens", 0),
                        completionTokens = usage.optInt("completion_tokens", 0)
                    )
                )
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return null

            val choice = choices.getJSONObject(0)
            val finishReason = choice.optString("finish_reason", null)
                ?.takeif { it != "null" && it.isnotEmpty() }

            if (finishReason != null) {
                return StreamChunk(
                    type = ChunkType.DONE,
                    finishReason = finishReason,
                    usage = usage?.let {
                        Usage(
                            promptTokens = it.optInt("prompt_tokens", 0),
                            completionTokens = it.optInt("completion_tokens", 0)
                        )
                    }
                )
            }

            val delta = choice.optJSONObject("delta") ?: return null

            // Reasoning content (o1/o3 models)
            // note: android org.json optString returns "null" string for JSON null values
            val reasoning = delta.optString("reasoning_content", null)
                ?.takeif { it.isnotEmpty() && it != "null" }
            if (reasoning != null) {
                return StreamChunk(type = ChunkType.THINKING_DELTA, text = reasoning)
            }

            // Text content
            val content = delta.optString("content", null)
                ?.takeif { it.isnotEmpty() && it != "null" }
            if (content != null) {
                return StreamChunk(type = ChunkType.TEXT_DELTA, text = content)
            }

            // tool calls
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val tc = toolCalls.getJSONObject(0)
                val fn = tc.optJSONObject("function")
                return StreamChunk(
                    type = ChunkType.TOOL_CALL_DELTA,
                    toolCallIndex = tc.optInt("index", 0),
                    toolCallId = tc.optString("id", null)?.takeif { it.isnotEmpty() && it != "null" },
                    toolCallName = fn?.optString("name", null)?.takeif { it.isnotEmpty() && it != "null" },
                    toolCallArgs = fn?.optString("arguments", null)?.takeif { it.isnotEmpty() && it != "null" }
                )
            }

            return null
        } catch (e: exception) {
            android.util.Log.w("ApiAdapter", "Failed to parse OpenAI stream chunk: ${e.message}")
            return null
        }
    }
}

// ============ SSE Streaming Types ============

enum class ChunkType {
    THINKING_DELTA,
    TEXT_DELTA,
    TOOL_CALL_DELTA,
    DONE,
    PING,
    USAGE
}

data class StreamChunk(
    val type: ChunkType,
    val text: String = "",
    val toolCallIndex: Int? = null,
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val toolCallArgs: String? = null,
    val finishReason: String? = null,
    val usage: Usage? = null
)

/**
 * ParsebackResponse
 */
data class ParsedResponse(
    val content: String?,
    val toolCalls: List<toolCall>? = null,
    val thinkingContent: String? = null,
    val usage: Usage? = null,
    val finishReason: String? = null
)

/**
 * tool Call
 */
data class toolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Token usecount
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
