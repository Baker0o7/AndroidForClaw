package com.xiaomo.androidforclaw.providers

import android.content.Context
import android.util.Log
import com.xiaomo.androidforclaw.config.ConfigLoader
import com.xiaomo.androidforclaw.config.ModelApi
import com.xiaomo.androidforclaw.config.ModelDefinition
import com.xiaomo.androidforclaw.config.ProviderConfig
import com.xiaomo.androidforclaw.providers.llm.Message
import com.xiaomo.androidforclaw.providers.llm.ToolDefinition as NewToolDefinition
import com.xiaomo.androidforclaw.providers.llm.FunctionDefinition as NewFunctionDefinition
import com.xiaomo.androidforclaw.providers.llm.ParametersSchema as NewParametersSchema
import com.xiaomo.androidforclaw.providers.llm.PropertySchema as NewPropertySchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 统一 LLM Provider
 * 支持所有 OpenClaw 兼容的 API 类型
 *
 * 功能：
 * 1. 自动从配置文件加载 provider 和 model 信息
 * 2. 支持多种 API 格式（OpenAI, Anthropic, Gemini, Ollama 等）
 * 3. 使用 ApiAdapter 处理不同 API 的差异
 * 4. 支持 Extended Thinking / Reasoning
 * 5. 支持自定义 headers 和认证方式
 *
 * 参考：OpenClaw src/agents/llm-client.ts
 */
class UnifiedLLMProvider(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedLLMProvider"
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_TEMPERATURE = 0.7
    }

    private val configLoader = ConfigLoader(context)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 转换旧的 ToolDefinition 到新格式
     */
    private fun convertToolDefinition(old: ToolDefinition): NewToolDefinition {
        return NewToolDefinition(
            type = old.type,
            function = NewFunctionDefinition(
                name = old.function.name,
                description = old.function.description,
                parameters = NewParametersSchema(
                    type = old.function.parameters.type,
                    properties = old.function.parameters.properties.mapValues { (_, prop) ->
                        convertPropertySchema(prop)
                    },
                    required = old.function.parameters.required
                )
            )
        )
    }

    private fun convertPropertySchema(old: PropertySchema): NewPropertySchema {
        return NewPropertySchema(
            type = old.type,
            description = old.description,
            enum = old.enum,
            items = old.items?.let { convertPropertySchema(it) }
        )
    }

    /**
     * 带工具调用的聊天
     *
     * @param messages 消息列表
     * @param tools 工具定义列表（旧格式）
     * @param modelRef 模型引用，格式：provider/model-id 或直接 model-id
     * @param temperature 温度参数
     * @param maxTokens 最大生成 token 数
     * @param reasoningEnabled 是否启用推理模式
     */
    suspend fun chatWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        modelRef: String? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        maxTokens: Int? = null,
        reasoningEnabled: Boolean = false
    ): LLMResponse = withContext(Dispatchers.IO) {
        // 转换工具定义到新格式
        val newTools = tools?.map { convertToolDefinition(it) }
        try {
            // 解析模型引用
            val (providerName, modelId) = parseModelRef(modelRef)

            // 加载 provider 和 model 配置
            val provider = configLoader.getProviderConfig(providerName)
                ?: throw IllegalArgumentException("Provider not found: $providerName")

            val model = provider.models.find { it.id == modelId }
                ?: throw IllegalArgumentException("Model not found: $modelId in provider: $providerName")

            Log.d(TAG, "📡 LLM Request:")
            Log.d(TAG, "  Provider: $providerName")
            Log.d(TAG, "  Model: $modelId")
            Log.d(TAG, "  API: ${model.api ?: provider.api}")
            Log.d(TAG, "  Messages: ${messages.size}")
            Log.d(TAG, "  Tools: ${tools?.size ?: 0}")
            Log.d(TAG, "  Reasoning: $reasoningEnabled")

            // 构建请求（使用转换后的新格式 tools）
            val requestBody = ApiAdapter.buildRequestBody(
                provider = provider,
                model = model,
                messages = messages,
                tools = newTools,
                temperature = temperature,
                maxTokens = maxTokens,
                reasoningEnabled = reasoningEnabled
            )

            val headers = ApiAdapter.buildHeaders(provider, model)

            // 构建完整的 API 端点
            val apiUrl = buildApiUrl(provider, model)

            Log.d(TAG, "  URL: $apiUrl")

            // 发送请求
            val request = Request.Builder()
                .url(apiUrl)
                .headers(headers)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "❌ API Error (${response.code}): $errorBody")
                throw LLMException("API request failed: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string()
                ?: throw LLMException("Empty response body")

            Log.d(TAG, "✅ LLM Response received (${responseBody.length} bytes)")

            // 解析响应
            val api = model.api ?: provider.api
            val parsed = ApiAdapter.parseResponse(api, responseBody)

            LLMResponse(
                content = parsed.content,
                toolCalls = parsed.toolCalls?.map { tc ->
                    LLMToolCall(
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

        } catch (e: Exception) {
            Log.e(TAG, "❌ LLM request failed", e)
            throw LLMException("LLM request failed: ${e.message}", e)
        }
    }

    /**
     * 简单聊天（无工具）
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
            messages.add(Message(role = "system", content = systemPrompt))
        }

        messages.add(Message(role = "user", content = userMessage))

        val response = chatWithTools(
            messages = messages,
            tools = null,
            modelRef = modelRef,
            temperature = temperature,
            maxTokens = maxTokens,
            reasoningEnabled = false
        )

        return response.content ?: throw LLMException("No content in response")
    }

    /**
     * 解析模型引用
     * 格式: "provider/model-id" 或 "model-id"
     *
     * @return Pair(providerName, modelId)
     */
    private fun parseModelRef(modelRef: String?): Pair<String, String> {
        // 如果未指定，使用默认模型
        if (modelRef == null) {
            val defaultModel = configLoader.loadOpenClawConfig().resolveDefaultModel()
            return parseModelRef(defaultModel)
        }

        // 第一步:尝试将完整的 modelRef 作为 model ID 查找
        // 这样可以支持 model ID 本身包含 "/" 的情况 (如 "anthropic/claude-opus-4-6")
        val providerForFullId = configLoader.findProviderByModelId(modelRef)
        if (providerForFullId != null) {
            return Pair(providerForFullId, modelRef)
        }

        // 第二步:按照 "provider/model-id" 格式解析
        val parts = modelRef.split("/", limit = 2)
        return when (parts.size) {
            2 -> {
                // "provider/model-id" 格式 (如 "openrouter/anthropic/claude-opus-4-6")
                Pair(parts[0], parts[1])
            }
            1 -> {
                // "model-id" 格式，查找对应的 provider
                val providerName = configLoader.findProviderByModelId(parts[0])
                    ?: throw IllegalArgumentException("Cannot find provider for model: ${parts[0]}")
                Pair(providerName, parts[0])
            }
            else -> throw IllegalArgumentException("Invalid model reference: $modelRef")
        }
    }

    /**
     * 构建 API URL
     */
    private fun buildApiUrl(provider: ProviderConfig, model: ModelDefinition): String {
        val baseUrl = provider.baseUrl.trimEnd('/')
        val api = model.api ?: provider.api

        return when (api) {
            ModelApi.ANTHROPIC_MESSAGES -> {
                "$baseUrl/messages"
            }
            ModelApi.OPENAI_COMPLETIONS,
            ModelApi.OPENAI_RESPONSES -> {
                "$baseUrl/chat/completions"
            }
            ModelApi.GOOGLE_GENERATIVE_AI -> {
                "$baseUrl/models/${model.id}:generateContent"
            }
            ModelApi.OLLAMA -> {
                "$baseUrl/api/chat"
            }
            ModelApi.GITHUB_COPILOT -> {
                "$baseUrl/chat/completions"
            }
            ModelApi.BEDROCK_CONVERSE_STREAM -> {
                // AWS Bedrock 需要特殊处理
                "$baseUrl/model/${model.id}/converse-stream"
            }
            else -> {
                // 默认使用 OpenAI 兼容端点
                "$baseUrl/chat/completions"
            }
        }
    }
}

/**
 * LLM 响应
 */
data class LLMResponse(
    val content: String?,
    val toolCalls: List<LLMToolCall>? = null,
    val thinkingContent: String? = null,
    val usage: LLMUsage? = null,
    val finishReason: String? = null
)

/**
 * LLM Tool Call
 */
data class LLMToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * LLM Token 使用统计
 */
data class LLMUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * LLM 异常
 */
