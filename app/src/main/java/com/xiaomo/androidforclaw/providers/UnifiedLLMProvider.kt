package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-runner/run/attempt.ts (LLM call: session create, stream, tool dispatch)
 * - ../openclaw/src/agents/pi-embeed-runner/run/payloads.ts (request payload construction)
 * - ../openclaw/src/agents/pi-embeed-payloads.ts (provider-specific payload formatting)
 *
 * note: pi-embeed-runner.ts is a barrel re-export; actual logic is in pi-embeed-runner/run/attempt.ts etc.
 *
 * androidforClaw adaptation: unified provider dispatch for android (batch + SSE streaming).
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.config.modelApi
import com.xiaomo.androidforclaw.config.modelDefinition
import com.xiaomo.androidforclaw.config.providerconfig
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.toolDefinition as newtoolDefinition
import com.xiaomo.androidforclaw.providers.llm.FunctionDefinition as newFunctionDefinition
import com.xiaomo.androidforclaw.providers.llm.Parametersschema as newParametersschema
import com.xiaomo.androidforclaw.providers.llm.Propertyschema as newPropertyschema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withcontext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Unified LLM provider
 * Supports all OpenClaw compatible API types
 *
 * Features:
 * 1. Automatically load provider and model info from config files
 * 2. Support multiple API formats (OpenAI, Anthropic, Gemini, Ollama, etc.)
 * 3. use ApiAdapter to handle differences between different APIs
 * 4. Support Extended Thinking / Reasoning
 * 5. Support custom headers and authentication methods
 *
 * Reference: OpenClaw src/agents/llm-client.ts
 */
class UnifiedLLMprovider(private val context: context) {

    companion object {
        private const val TAG = "UnifiedLLMprovider"
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_TEMPERATURE = 0.7
    }

    private val configLoader = configLoader(context)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .aNetworkInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("user-agent", "androidforClaw/${com.xiaomo.androidforclaw.Buildconfig.VERSION_NAME}")
                    .build()
            )
        }
        .build()

    /**
     * Convertold toolDefinition tonewformat
     */
    private fun converttoolDefinition(old: toolDefinition): newtoolDefinition {
        return newtoolDefinition(
            type = old.type,
            function = newFunctionDefinition(
                name = old.function.name,
                description = old.function.description,
                parameters = newParametersschema(
                    type = old.function.parameters.type,
                    properties = old.function.parameters.properties.mapValues { (_, prop) ->
                        convertPropertyschema(prop)
                    },
                    required = old.function.parameters.required
                )
            )
        )
    }

    private fun convertPropertyschema(old: Propertyschema): newPropertyschema {
        return newPropertyschema(
            type = old.type,
            description = old.description,
            enum = old.enum,
            items = old.items?.let { convertPropertyschema(it) },
            properties = old.properties?.mapValues { (_, child) -> convertPropertyschema(child) }
        )
    }

    /**
     * Chat with tool calls
     *
     * @param messages Message list
     * @param tools tool definition list (old format)
     * @param modelRef model reference, format: provider/model-id or just model-id
     * @param temperature Temperature parameter
     * @param maxTokens Maximum generated tokens
     * @param reasoningEnabled Whether to enable reasoning mode
     */
    suspend fun chatwithtools(
        messages: List<Message>,
        tools: List<toolDefinition>?,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null,
        reasoningEnabled: Boolean = false,
        maxRetries: Int = 3
    ): LLMResponse = withcontext(Dispatchers.IO) {
        // Convert tool definitions to new format
        val newtools = tools?.map { converttoolDefinition(it) }

        // Parse primary model reference
        val (primaryprovider, primarymodel) = parsemodelRef(modelRef)

        // use model fallback chain (OpenClaw model-fallback.ts)
        val config = configLoader.loadOpenClawconfig()
        val fallbackresult = modelFallback.runwithmodelFallback(
            config = config,
            configLoader = configLoader,
            provider = primaryprovider,
            model = primarymodel,
            run = { provider, model ->
                performRequestformodel(
                    messages, newtools, provider, model, temperature, maxTokens, reasoningEnabled, maxRetries
                )
            },
            onError = { provider, model, error, attempt, total ->
                Log.w(TAG, "[WARN] Fallback attempt $attempt/$total failed for $provider/$model: ${error.message}")
            }
        )

        return@withcontext fallbackresult.result
    }

    /**
      * Streaming Chat — Return Flow<StreamChunk>, real-time emit SSE increment
     * Aligned with OpenClaw streamSimple / pi-embeed-subscribe.handlers.messages.ts
     */
    fun chatwithtoolsStreaming(
        messages: List<Message>,
        tools: List<toolDefinition>?,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null,
        reasoningEnabled: Boolean = false,
        maxRetries: Int = 3
    ): Flow<StreamChunk> = flow {
        val newtools = tools?.map { converttoolDefinition(it) }
        val (resolvedproviderName, resolvedmodelId) = parsemodelRef(modelRef)
        val config = configLoader.loadOpenClawconfig()

        // === Layer 1: model Fallback ===
        val candidates = modelFallback.resolveFallbackcandidates(
            config, configLoader, resolvedproviderName, resolvedmodelId, null
        )
        var lastexception: exception? = null

        for (candidate in candidates) {
            try {
                // Resolve candidate provider/model config
                val aliasResolved = configLoader.resolvemodelId(candidate.model)
                val normalizedmodelId = modelIdNormalization.normalizemodelId(candidate.provider, aliasResolved)
                val providerRaw = configLoader.getproviderconfig(candidate.provider)
                    ?: throw LLMexception("provider not found: ${candidate.provider}")
                val modelRaw = providerRaw.models.find { it.id == normalizedmodelId }
                    ?: providerRaw.models.find { it.id == candidate.model }
                    ?: throw LLMexception("model not found: $normalizedmodelId in provider: ${candidate.provider}")
                val (provider, model) = modelCompat.normalizemodelCompat(providerRaw, modelRaw, candidate.provider)
                val api = model.api ?: provider.api

                // Non-streaming APIs → batch fallback (already has full retry/rotation via performRequestformodel)
                if (api == modelApi.GOOGLE_GENERATIVE_AI || api == modelApi.OPENAI_RESPONSES || api == modelApi.OPENAI_CODEX_RESPONSES) {
                    Log.d(TAG, "[WARN] API $api does not support streaming, falling back to batch")
                    val batchResponse = performRequestformodel(
                        messages, newtools, candidate.provider, candidate.model,
                        temperature, maxTokens, reasoningEnabled, maxRetries
                    )
                    batchResponse.thinkingContent?.let { emit(StreamChunk(type = ChunkType.THINKING_DELTA, text = it)) }
                    batchResponse.content?.let { emit(StreamChunk(type = ChunkType.TEXT_DELTA, text = it)) }
                    emit(StreamChunk(type = ChunkType.DONE, finishReason = batchResponse.finishReason))
                    return@flow
                }

                val apiKeys = ApiKeyRotation.splitApiKeys(provider.apiKey)

                // === Layer 2: retry with backoff ===
                for (attempt in 1..maxRetries) {
                    try {
                        // === Layer 3: API Key Rotation ===
                        var keyexception: exception? = null
                        for ((keyIdx, apiKey) in apiKeys.withIndex()) {
                            try {
                                val activeprovider = provider.copy(apiKey = apiKey)

                                val requestBody = ApiAdapter.buildRequestBody(
                                    provider = activeprovider, model = model,
                                    messages = messages, tools = newtools,
                                    temperature = temperature, maxTokens = maxTokens,
                                    reasoningEnabled = reasoningEnabled, stream = true
                                )
                                val headers = ApiAdapter.buildHeaders(activeprovider, model)
                                val apiUrl = buildApiUrl(activeprovider, model)
                                val finalRequestBody = normalizeOpenAiTokenField(model, requestBody)

                                Log.d(TAG, "[SEND] Streaming request to $apiUrl (candidate=${candidate.provider}/${candidate.model}, attempt=$attempt, key=${keyIdx + 1}/${apiKeys.size})")

                                // [SEARCH] Debug: Log request summary before sending
                                val msgCount = messages.size
                                val sysLen = messages.firstorNull { it.role == "system" }?.content?.length ?: 0
                                val toolCount = newtools?.size ?: 0
                                Log.d(TAG, "[SEARCH] LLM Request: model=${model.id}, provider=${candidate.provider}, reasoning=$reasoningEnabled, messages=$msgCount, tools=$toolCount, systemPrompt=$sysLen chars")
                                Log.d(TAG, "[SEARCH] API URL: $apiUrl")

                                val request = Request.Builder()
                                    .url(apiUrl)
                                    .headers(headers)
                                    .post(finalRequestBody.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                val response = httpClient.newCall(request).execute()

                                if (!response.isSuccessful) {
                                    val errorBody = response.body?.string() ?: "Unknown error"
                                    response.close()
                                    throw LLMexception("Streaming API request failed: ${response.code} - $errorBody")
                                }

                                // === Connection established — stream SSE chunks ===
                                val source = response.body?.source()
                                    ?: throw LLMexception("Empty streaming response body")

                                var currentEventType: String? = null
                                val isAnthropic = api == modelApi.ANTHROPIC_MESSAGES
                                var chunkCount = 0
                                val rawChunkLog = StringBuilder() // collect first 5 raw chunks for debug

                                try {

                                    while (!source.exhausted()) {
                                        val line = source.readUtf8Line() ?: break

                                        if (line.startswith("event: ")) {
                                            currentEventType = line.removePrefix("event: ").trim()
                                            continue
                                        }

                                        if (line.startswith("data: ")) {
                                            val data = line.removePrefix("data: ").trim()
                                            if (data == "[DONE]") {
                                                emit(StreamChunk(type = ChunkType.DONE))
                                                break
                                            }
                                            if (data.isEmpty()) continue

                                            // Log first 5 raw SSE data lines for debugging
                                            chunkCount++
                                            if (chunkCount <= 5) {
                                                val preview = if (data.length > 300) data.take(300) + "..." else data
                                                rawChunkLog.append("  chunk[$chunkCount]: $preview\n")
                                            }

                                            val chunk = ApiAdapter.parseStreamChunk(
                                                api = api,
                                                eventType = if (isAnthropic) currentEventType else null,
                                                dataLine = data
                                            )
                                            if (chunk != null && chunk.type != ChunkType.PING) {
                                                emit(chunk)
                                            }
                                            currentEventType = null
                                            continue
                                        }
                                    }
                                } finally {
                                    // Log raw chunk summary for debugging
                                    if (rawChunkLog.isnotEmpty()) {
                                        Log.d(TAG, "[SEARCH] Raw SSE chunks (first 5):\n$rawChunkLog")
                                    }
                                    Log.d(TAG, "[SEARCH] Streaming done: totalChunks=$chunkCount")
                                    source.close()
                                    response.close()
                                }

                                // Streaming completed successfully
                                return@flow

                            } catch (e: exception) {
                                keyexception = e
                                if (!ApiKeyRotation.isApiKeyRateLimitError(e) || keyIdx + 1 >= apiKeys.size) throw e
                                Log.w(TAG, "[WARN] Streaming: key #${keyIdx + 1} rate limited, rotating to next key")
                            }
                        }
                        throw keyexception!!

                    } catch (e: LLMexception) {
                        if (!isretryable(e) || attempt == maxRetries) throw e
                        val isRateLimit = e.message?.lowercase()?.let {
                            it.contains("429") || it.contains("rate limit")
                        } == true
                        val baseDelay = if (isRateLimit) 5000L else 1000L
                        val delayMs = baseDelay * attempt
                        Log.w(TAG, "[WARN] Streaming retry $attempt/$maxRetries in ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    }
                }

            } catch (e: exception) {
                lastexception = e
                if (modelFallback.islikelycontextoverflowError(e)) throw e
                if (!modelFallback.isretryableforFallback(e)) throw e
                Log.w(TAG, "[WARN] Streaming fallback: ${candidate.provider}/${candidate.model} failed: ${e.message}, trying next candidate")
            }
        }

        throw lastexception ?: LLMexception("All streaming models failed")
    }.flowOn(Dispatchers.IO)

    /**
     * Execute LLM request for a specific provider/model with retry and API key rotation.
     * Called by the fallback chain for each candidate.
     */
    private suspend fun performRequestformodel(
        messages: List<Message>,
        tools: List<com.xiaomo.androidforclaw.providers.llm.toolDefinition>?,
        providerName: String,
        modelId: String,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean,
        maxRetries: Int
    ): LLMResponse {
        var lastexception: exception? = null
        for (attempt in 1..maxRetries) {
            try {
                return performRequest(messages, tools, providerName, modelId, temperature, maxTokens, reasoningEnabled)
            } catch (e: LLMexception) {
                lastexception = e
                if (!isretryable(e) || attempt == maxRetries) throw e
                val isRateLimit = e.message?.contains("429") == true || e.message?.contains("rate limit", ignoreCase = true) == true
                val baseDelay = if (isRateLimit) 5000L else 1000L
                val delayMs = baseDelay * attempt
                Log.w(TAG, "[WARN] LLM request failed (attempt $attempt/$maxRetries), retrying in ${delayMs}ms: ${e.message}")
                delay(delayMs)
            }
        }
        throw lastexception!!
    }

    /**
      * Execute actual LLM Request
     */
    private suspend fun performRequest(
        messages: List<Message>,
        tools: List<com.xiaomo.androidforclaw.providers.llm.toolDefinition>?,
        providerName: String,
        modelId: String,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): LLMResponse {
        try {
            // Resolve model aliases (OpenClaw model-selection.ts)
            val aliasResolved = configLoader.resolvemodelId(modelId)

            // model allowlist check (OpenClaw model-selection.ts)
            val config = configLoader.loadOpenClawconfig()
            if (!com.xiaomo.androidforclaw.config.modelAllowlist.ismodelAllowed(aliasResolved, config.modelAllowlist)) {
                throw LLMexception("model '$aliasResolved' is not allowed by the model allowlist configuration")
            }

            // Normalize model ID per provider (OpenClaw model-id-normalization.ts)
            val normalizedmodelId = modelIdNormalization.normalizemodelId(providerName, aliasResolved)

            // Load provider and model config
            val providerRaw = configLoader.getproviderconfig(providerName)
                ?: throw IllegalArgumentexception("provider not found: $providerName")

            val modelRaw = providerRaw.models.find { it.id == normalizedmodelId }
                ?: providerRaw.models.find { it.id == modelId }  // fallback to original ID
                ?: throw IllegalArgumentexception("model not found: $normalizedmodelId in provider: $providerName")

            // Apply model compat normalization (OpenClaw model-compat.ts)
            val (provider, model) = modelCompat.normalizemodelCompat(providerRaw, modelRaw, providerName)

            Log.d(TAG, "📡 LLM Request:")
            Log.d(TAG, "  provider: $providerName")
            Log.d(TAG, "  model: ${model.id}${if (model.id != modelId) " (normalized from $modelId)" else ""}")
            Log.d(TAG, "  API: ${model.api ?: provider.api}")
            Log.d(TAG, "  Messages: ${messages.size}")
            Log.d(TAG, "  tools: ${tools?.size ?: 0}")
            Log.d(TAG, "  Reasoning: $reasoningEnabled")

            // API key rotation (OpenClaw api-key-rotation.ts)
            // Split comma-separated keys and try each on rate limit
            val apiKeys = ApiKeyRotation.splitApiKeys(provider.apiKey)

            val responseBody = if (apiKeys.size > 1) {
                ApiKeyRotation.executewithApiKeyRotation(
                    apiKeys = apiKeys,
                    provider = providerName,
                    execute = { apiKey ->
                        executeHttpRequest(provider.copy(apiKey = apiKey), model, messages, tools, temperature, maxTokens, reasoningEnabled)
                    }
                )
            } else {
                executeHttpRequest(provider, model, messages, tools, temperature, maxTokens, reasoningEnabled)
            }

            Log.d(TAG, "[OK] LLM Response received (${responseBody.length} bytes)")

            // Log raw response for debugging (truncated)
            val truncated = if (responseBody.length > 2000) responseBody.substring(0, 2000) + "..." else responseBody
            Log.d(TAG, "[RECV] Raw response: $truncated")

            // Parse response
            val api = model.api ?: provider.api
            val parsed = ApiAdapter.parseResponse(api, responseBody)

            return LLMResponse(
                content = parsed.content,
                toolCalls = parsed.toolCalls?.map { tc ->
                    LLMtoolCall(
                        id = tc.id,
                        name = tc.name,
                        arguments = tc.arguments
                    )
                },
                thinkingContent = parsed.thinkingContent,
                usage = parsed.usage?.let {
                    LLMUsage(
                        promptTokens = it.promptTokens,
                        completionTokens = it.completionTokens,
                        totalTokens = it.totalTokens
                    )
                },
                finishReason = parsed.finishReason
            )

        } catch (e: exception) {
            Log.e(TAG, "[ERROR] LLM request failed", e)
            throw LLMexception("LLM request failed: ${e.message}", e)
        }
    }

    /**
     * Execute the HTTP request to the LLM API and return the raw response body.
     * Extracted to support API key rotation.
     */
    private fun executeHttpRequest(
        provider: providerconfig,
        model: modelDefinition,
        messages: List<Message>,
        tools: List<com.xiaomo.androidforclaw.providers.llm.toolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): String {
        val requestBody = ApiAdapter.buildRequestBody(
            provider = provider,
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEnabled = reasoningEnabled
        )

        val headers = ApiAdapter.buildHeaders(provider, model)
        val apiUrl = buildApiUrl(provider, model)

        Log.d(TAG, "  URL: $apiUrl")
        Log.d(TAG, "  Headers: ${headers.names()}")
        headers.names().forEach { name ->
            if (name.lowercase() == "authorization") {
                Log.d(TAG, "    $name: Bearer ${provider.apiKey?.take(10)}...")
            } else {
                Log.d(TAG, "    $name: ${headers[name]}")
            }
        }

        val finalRequestBody = normalizeOpenAiTokenField(model, requestBody)

        val request = Request.Builder()
            .url(apiUrl)
            .headers(headers)
            .post(finalRequestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val reqStr = finalRequestBody.toString()
        val reqTrunc = if (reqStr.length > 1500) reqStr.substring(0, 1500) + "..." else reqStr
        Log.d(TAG, "[SEND] Request to $apiUrl: $reqTrunc")

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "[ERROR] API Error (${response.code}): $errorBody")
            throw LLMexception("API request failed: ${response.code} - $errorBody")
        }

        val responseBody = response.body?.string()
            ?: throw LLMexception("Empty response body")

        // Guard: detect non-JSON responses (HTML pages, login redirects, etc.)
        val trimmed = responseBody.trimStart()
        if (trimmed.startswith("<") || trimmed.startswith("<!")) {
            Log.e(TAG, "[ERROR] API returned HTML instead of JSON — check baseUrl and API key")
            throw LLMexception(
                "API returned an HTML page instead of JSON. " +
                "This usually means the baseUrl is wrong or the API key is invalid. " +
                "URL: $apiUrl"
            )
        }

        return responseBody
    }

    private fun normalizeOpenAiTokenField(model: modelDefinition, requestBody: JSONObject): JSONObject {
        val modelIdLower = model.id.lowercase()
        val requiresMaxCompletionTokens = modelIdLower.startswith("gpt-5") ||
            modelIdLower.startswith("o1") ||
            modelIdLower.startswith("o3") ||
            modelIdLower.startswith("gpt-4.1")

        if (!requiresMaxCompletionTokens) return requestBody
        if (requestBody.has("max_tokens")) {
            val value = requestBody.get("max_tokens")
            requestBody.remove("max_tokens")
            if (!requestBody.has("max_completion_tokens")) {
                requestBody.put("max_completion_tokens", value)
            }
        }
        return requestBody
    }

    /**
     * CheckErrorwhethercanretry
     */
    private fun isretryable(exception: LLMexception): Boolean {
        val message = exception.message?.lowercase() ?: ""

        return when {
            // Rate limiting
            message.contains("rate limit") || message.contains("429") -> true
            // service unavailable
            message.contains("503") || message.contains("service unavailable") -> true
            // Timeout
            message.contains("timeout") || message.contains("timed out") -> true
            // Server errors
            message.contains("500") || message.contains("502") || message.contains("504") -> true
            // Connection issues
            message.contains("connection") || message.contains("network") -> true
            // overloaded
            message.contains("overloaded") -> true
            // Default: not retryable
            else -> false
        }
    }

    /**
     * Simple Chat (no tools)
     */
    suspend fun simpleChat(
        userMessage: String,
        systemPrompt: String? = null,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null
    ): String {
        val messages = mutableListOf<Message>()

        if (systemPrompt != null) {
            messages.a(Message(role = "system", content = systemPrompt))
        }

        messages.a(Message(role = "user", content = userMessage))

        val response = chatwithtools(
            messages = messages,
            tools = null,
            modelRef = modelRef,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEnabled = false
        )

        return response.content ?: throw LLMexception("No content in response")
    }

    /**
      * Parse model reference
      * format: "provider/model-id" or "model-id"
     *
     * @return Pair(providerName, modelId)
     */
    private fun parsemodelRef(modelRef: String?): Pair<String, String> {
        // if not specified, use default model
        if (modelRef == null) {
             val config = configLoader.loadOpenClawconfigFresh() // Force read from disk, avoid cache causing model switch not to take effect
            val defaultmodel = config.resolveDefaultmodel()
            // if the default model's provider exists, use it
            val parsed = tryParsemodelRef(defaultmodel)
            if (parsed != null) return parsed

            // Fallback: use the first available provider/model
            val providers = config.resolveproviders()
            val firstEntry = providers.entries.firstorNull()
            if (firstEntry != null) {
                val firstmodel = firstEntry.value.models.firstorNull()
                if (firstmodel != null) {
                     Log.w(TAG, "[WARN] Default model '$defaultmodel' provider notExists, fallback to '${firstEntry.key}/${firstmodel.id}'")
                    return Pair(firstEntry.key, firstmodel.id)
                }
            }
             throw IllegalArgumentexception("No available model config, please configure model first")
        }

        return tryParsemodelRef(modelRef)
            ?: throw IllegalArgumentexception("Invalid model reference: $modelRef")
    }

    /**
      * TryParse model reference, return null when not found rather than throwing exception
     */
    private fun tryParsemodelRef(modelRef: String): Pair<String, String>? {
        // Step 1: Try to find complete modelRef as model ID
        val providerforFullId = configLoader.findproviderBymodelId(modelRef)
        if (providerforFullId != null) {
            return Pair(providerforFullId, modelRef)
        }

        // Step 2: Parse as "provider/model-id" format
        val parts = modelRef.split("/", limit = 2)
        return when (parts.size) {
            2 -> {
                // Verify provider exists
                val providerconfig = configLoader.getproviderconfig(parts[0])
                if (providerconfig != null) Pair(parts[0], parts[1]) else null
            }
            1 -> {
                // "model-id" format, find corresponding provider
                val providerName = configLoader.findproviderBymodelId(parts[0])
                if (providerName != null) Pair(providerName, parts[0]) else null
            }
            else -> null
        }
    }

    /**
     * Build API URL
     */
    private fun buildApiUrl(provider: providerconfig, model: modelDefinition): String {
        val baseUrl = provider.baseUrl.trimEnd('/')
        val api = model.api ?: provider.api

        return when (api) {
            modelApi.ANTHROPIC_MESSAGES -> {
                "$baseUrl/v1/messages"
            }
            modelApi.OPENAI_COMPLETIONS -> {
                "$baseUrl/chat/completions"
            }
            modelApi.OPENAI_RESPONSES,
            modelApi.OPENAI_CODEX_RESPONSES -> {
                "$baseUrl/responses"
            }
            modelApi.GOOGLE_GENERATIVE_AI -> {
                val keyParam = if (provider.apiKey != null) "?key=${provider.apiKey}" else ""
                "$baseUrl/models/${model.id}:generateContent$keyParam"
            }
            modelApi.OLLAMA -> {
                "$baseUrl/api/chat"
            }
            modelApi.GITHUB_COPILOT -> {
                "$baseUrl/chat/completions"
            }
            modelApi.BEDROCK_CONVERSE_STREAM -> {
                // AWS Bedrock needs special handling
                "$baseUrl/model/${model.id}/converse-stream"
            }
            else -> {
                // Default to OpenAI compatible endpoint
                "$baseUrl/chat/completions"
            }
        }
    }
}

/**
 * LLM Response
 */
data class LLMResponse(
    val content: String?,
    val toolCalls: List<LLMtoolCall>? = null,
    val thinkingContent: String? = null,
    val usage: LLMUsage? = null,
    val finishReason: String? = null
)

/**
 * LLM tool Call
 */
data class LLMtoolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * LLM Token usecount
 */
data class LLMUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

