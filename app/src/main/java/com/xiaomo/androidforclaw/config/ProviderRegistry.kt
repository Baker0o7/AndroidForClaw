/**
 * OpenClaw Source Reference:
 * - src/agents/models-config.ts
 * - src/agents/model-catalog.ts
 */
package com.xiaomo.androidforclaw.config

import android.content.context
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenClaw provider Registry
 *
 * from assets/providers.json Load provider 定义. 
 * providers.json by scripts/sync-providers.py from OpenClaw 源码生成. 
 * 保持and OpenClaw one致只needreRun脚本并Replace JSON. 
 *
 * at the same time保留硬Encode fallback, in JSON LoadFailedhouruse. 
 */
object providerRegistry {

    @Volatile
    private var _providers: List<providerDefinition>? = null

    /**
     * from assets/providers.json Initialize(shouldin Application.onCreate 中call)
     */
    fun init(context: context) {
        if (_providers != null) return
        try {
            val json = context.assets.open("providers.json").bufferedReader().use { it.readText() }
            _providers = parseproviders(json)
        } catch (e: exception) {
            android.util.Log.w("providerRegistry", "Failed to load providers.json, using fallback", e)
            _providers = FALLBACK_PROVIDERS
        }
    }

    /**
     * from JSON StringInitialize(用于单元Test, Noneneed android context)
     */
    @JvmStatic
    fun initfromJson(json: String) {
        _providers = parseproviders(json)
    }

    /**
     * ResetfornotInitializeStatus(Test用)
     */
    @JvmStatic
    fun reset() {
        _providers = null
    }

    /** Allregistered provider, 按 order Sort */
    val ALL: List<providerDefinition>
        get() = _providers ?: FALLBACK_PROVIDERS

    /** 按 group minuteGroup */
    val PRIMARY_PROVIDERS: List<providerDefinition>
        get() = ALL.filter { it.group == providerGroup.PRIMARY }
    val MORE_PROVIDERS: List<providerDefinition>
        get() = ALL.filter { it.group == providerGroup.MORE }
    val CUSTOM_PROVIDERS: List<providerDefinition>
        get() = ALL.filter { it.group == providerGroup.CUSTOM }

    /** 按 ID Find provider */
    fun findById(id: String): providerDefinition? {
        val normalized = normalizeproviderId(id)
        return ALL.firstorNull { it.id == normalized }
    }

    /**
     * Aligned with OpenClaw normalizeproviderId()
     */
    fun normalizeproviderId(provider: String): String {
        val normalized = provider.trim().lowercase()
        return when (normalized) {
            "z.ai", "z-ai" -> "zai"
            "opencode-zen" -> "opencode"
            "qwen" -> "qwen-portal"
            "kimi-code", "kimi-coding" -> "kimi-coding"
            "kimi" -> "moonshot"
            "moonshot-cn" -> "moonshot"
            "bedrock", "aws-bedrock" -> "amazon-bedrock"
            "bytedance", "doubao" -> "volcengine"
            else -> normalized
        }
    }

    /**
     * according to providerDefinition 生成 providerconfig(用于Write openclaw.json)
     */
    fun buildproviderconfig(
        definition: providerDefinition,
        apiKey: String?,
        baseUrl: String? = null,
        apiType: String? = null,
        selectedmodels: List<Presetmodel>? = null
    ): providerconfig {
        val effectiveBaseUrl = baseUrl?.takeif { it.isnotBlank() } ?: definition.baseUrl
        val effectiveApi = apiType?.takeif { it.isnotBlank() } ?: definition.api

        val models = (selectedmodels ?: definition.presetmodels).map { preset ->
            modelDefinition(
                id = preset.id,
                name = preset.name,
                reasoning = preset.reasoning,
                input = preset.input,
                contextWindow = preset.contextWindow,
                maxTokens = preset.maxTokens
            )
        }

        return providerconfig(
            baseUrl = effectiveBaseUrl,
            apiKey = apiKey,
            api = effectiveApi,
            authHeader = definition.authHeader,
            headers = definition.headers,
            models = models
        )
    }

    /** 生成 provider/modelId format引用 */
    fun buildmodelRef(providerId: String, modelId: String): String = "$providerId/$modelId"

    /** Custom API TypeList(Spinner 用) */
    val CUSTOM_API_TYPES = listOf(
        modelApi.OPENAI_COMPLETIONS to "OpenAI Compatible",
        modelApi.ANTHROPIC_MESSAGES to "Anthropic Compatible",
        modelApi.OLLAMA to "Ollama"
    )

    // ========== JSON Parse ==========

    private fun parseproviders(json: String): List<providerDefinition> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("providers")
        val result = mutableListOf<providerDefinition>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.a(parseprovider(obj))
        }
        return result.sortedBy { it.order }
    }

    private fun parseprovider(obj: JSONObject): providerDefinition {
        val id = obj.getString("id")
        val group = when (obj.optString("group", "more")) {
            "primary" -> providerGroup.PRIMARY
            "custom" -> providerGroup.CUSTOM
            else -> providerGroup.MORE
        }

        // Parse models
        val modelsArr = obj.optJSONArray("models") ?: JSONArray()
        val models = mutableListOf<Presetmodel>()
        for (i in 0 until modelsArr.length()) {
            val m = modelsArr.getJSONObject(i)
            models.a(
                Presetmodel(
                    id = m.getString("id"),
                    name = m.optString("name", m.getString("id")),
                    free = m.optBoolean("free", false),
                    contextWindow = m.optInt("contextWindow", 128000),
                    maxTokens = m.optInt("maxTokens", 8192),
                    reasoning = m.optBoolean("reasoning", false),
                    input = m.optJSONArray("input")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: listOf("text")
                )
            )
        }

        // Parse tutorial steps
        val stepsArr = obj.optJSONArray("tutorialSteps") ?: JSONArray()
        val steps = (0 until stepsArr.length()).map { stepsArr.getString(it) }

        // Parse envVars (take first as primary)
        val envArr = obj.optJSONArray("envVars") ?: JSONArray()
        val envVarName = if (envArr.length() > 0) envArr.getString(0) else ""

        // Parse optional headers
        val headersObj = obj.optJSONObject("headers")
        val headers = headersObj?.let { h ->
            val map = mutableMapOf<String, String>()
            h.keys().forEach { key -> map[key] = h.getString(key) }
            map
        }

        return providerDefinition(
            id = id,
            name = obj.getString("name"),
            description = obj.optString("description", ""),
            baseUrl = obj.optString("baseUrl", ""),
            api = obj.optString("api", modelApi.OPENAI_COMPLETIONS),
            keyRequired = obj.optBoolean("keyRequired", true),
            keyHint = obj.optString("keyHint", "API Key"),
            envVarName = envVarName,
            authHeader = obj.optBoolean("authHeader", true),
            headers = headers,
            tutorialSteps = steps,
            tutorialUrl = obj.optString("tutorialUrl", ""),
            presetmodels = models,
            supportsDiscovery = obj.optBoolean("supportsDiscovery", false),
            discoveryEndpoint = obj.optString("discoveryEndpoint", "/models"),
            group = group,
            order = obj.optInt("order", 100)
        )
    }

    // ========== Fallback(JSON LoadFailedhouruse) ==========

    private val FALLBACK_PROVIDERS = listOf(
        providerDefinition(
            id = "openrouter", name = "OpenRouter", description = "Aggregateplatform",
            baseUrl = "https://openrouter.ai/api/v1", api = modelApi.OPENAI_COMPLETIONS,
            keyRequired = false, keyHint = "OpenRouter API Key", envVarName = "OPENROUTER_API_KEY",
            group = providerGroup.PRIMARY, order = 10, supportsDiscovery = true
        ),
        providerDefinition(
            id = "anthropic", name = "Anthropic", description = "Claude 系Column",
            baseUrl = "https://api.anthropic.com", api = modelApi.ANTHROPIC_MESSAGES,
            keyRequired = true, keyHint = "Anthropic API Key", envVarName = "ANTHROPIC_API_KEY",
            authHeader = false, group = providerGroup.PRIMARY, order = 20
        ),
        providerDefinition(
            id = "openai", name = "OpenAI", description = "GPT 系Column",
            baseUrl = "https://api.openai.com/v1", api = modelApi.OPENAI_COMPLETIONS,
            keyRequired = true, keyHint = "OpenAI API Key", envVarName = "OPENAI_API_KEY",
            group = providerGroup.PRIMARY, order = 30, supportsDiscovery = true
        ),
        providerDefinition(
            id = "google", name = "Google (Gemini)", description = "Gemini 系Column",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            api = modelApi.GOOGLE_GENERATIVE_AI,
            keyRequired = true, keyHint = "Gemini API Key", envVarName = "GEMINI_API_KEY",
            group = providerGroup.PRIMARY, order = 40
        ),
        providerDefinition(
            id = "ollama", name = "Ollama (本地)", description = "本地模型",
            baseUrl = "http://127.0.0.1:11434", api = modelApi.OLLAMA,
            keyRequired = false, keyHint = "Optional", envVarName = "OLLAMA_API_KEY",
            group = providerGroup.PRIMARY, order = 70, supportsDiscovery = true,
            discoveryEndpoint = "/api/tags"
        ),
        providerDefinition(
            id = "nvidia", name = "NVIDIA NIM", description = "NVIDIA 托管模型",
            baseUrl = "https://integrate.api.nvidia.com/v1", api = modelApi.OPENAI_COMPLETIONS,
            keyRequired = true, keyHint = "NVIDIA API Key", envVarName = "NVIDIA_API_KEY",
            group = providerGroup.PRIMARY, order = 60
        ),
        providerDefinition(
            id = "minimax", name = "MiniMax", description = "MiniMax M2.7 系Column",
            baseUrl = "https://api.minimax.io/anthropic", api = modelApi.ANTHROPIC_MESSAGES,
            keyRequired = true, keyHint = "MiniMax API Key", envVarName = "MINIMAX_API_KEY",
            group = providerGroup.PRIMARY, order = 55
        ),
        providerDefinition(
            id = "custom", name = "Custom (OpenAI 兼容)", description = "Custom API",
            baseUrl = "", api = modelApi.OPENAI_COMPLETIONS,
            keyRequired = false, keyHint = "API Key", envVarName = "",
            group = providerGroup.CUSTOM, order = 999, supportsDiscovery = true
        )
    )
}

/**
 * provider 定义 — from providers.json Load
 */
data class providerDefinition(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val api: String,
    val keyRequired: Boolean,
    val keyHint: String,
    val envVarName: String,
    val authHeader: Boolean = true,
    val headers: Map<String, String>? = null,
    val tutorialSteps: List<String> = emptyList(),
    val tutorialUrl: String = "",
    val presetmodels: List<Presetmodel> = emptyList(),
    val supportsDiscovery: Boolean = false,
    val discoveryEndpoint: String = "/models",
    val group: providerGroup = providerGroup.PRIMARY,
    val order: Int = 100
)

data class Presetmodel(
    val id: String,
    val name: String,
    val free: Boolean = false,
    val contextWindow: Int = 128000,
    val maxTokens: Int = 8192,
    val reasoning: Boolean = false,
    val input: List<String> = listOf("text")
)

enum class providerGroup {
    PRIMARY, MORE, CUSTOM
}
