package com.xiaomo.androidforclaw.providers

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/pi-embeed-runner.ts (legacy wrapper)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.config.OpenClawconfig
import com.xiaomo.androidforclaw.config.providerconfig
import com.xiaomo.androidforclaw.util.AppConstants

/**
 * Legacy Repository
 * Provides higher-level API wrapper
 * Automatically selects OpenAI or Anthropic format based on config
 *
 * **Config source**: from /sdcard/.androidforclaw/openclaw.json and models.json
 */
class LegacyRepository(
    context: context,
    apiKey: String? = null,  // Optional, defaults to reading from config
    apiBase: String? = null,  // Optional, defaults to reading from config
    private val apiType: String? = null  // Optional, defaults to reading from config
) {
    companion object {
        private const val TAG = "LegacyRepository"
    }

    // Config loader
    private val configLoader = configLoader(context)

    // Load OpenClaw config
    private val openClawconfig: OpenClawconfig by lazy {
        configLoader.loadOpenClawconfig()
    }

    // Find corresponding provider by default model
    private fun getproviderforDefaultmodel(): providerconfig? {
        val defaultmodel = openClawconfig.agent.defaultmodel
        val providerName = configLoader.findproviderBymodelId(defaultmodel)
        Log.d(TAG, "Default model: $defaultmodel, provider: $providerName")
        return providerName?.let { configLoader.getproviderconfig(it) }
    }

    // Read API config (prioritize constructor parameters, otherwise read from config file)
    private val actualApiKey: String by lazy {
        apiKey ?: run {
            // Read apiKey from provider corresponding to default model
            val provider = getproviderforDefaultmodel() ?: configLoader.getproviderconfig("openrouter")
            provider?.apiKey ?: AppConstants.OPENROUTER_API_KEY
        }
    }

    private val actualApiBase: String by lazy {
        apiBase ?: run {
            // Read baseUrl from provider corresponding to default model
            val provider = getproviderforDefaultmodel() ?: configLoader.getproviderconfig("openrouter")
            provider?.baseUrl ?: "https://openrouter.ai/api/v1"
        }
    }

    private val actualApiType: String by lazy {
        apiType ?: run {
            // Read api type from provider corresponding to default model
            val provider = getproviderforDefaultmodel() ?: configLoader.getproviderconfig("openrouter")
            provider?.api ?: "openai-completions"
        }
    }

    // Select provider based on API type
    private val openAIprovider by lazy {
        val provider = getproviderforDefaultmodel() ?: configLoader.getproviderconfig("anthropic")
        Log.d(TAG, "Creating OpenAI provider:")
        Log.d(TAG, "  provider name: ${provider?.let { configLoader.findproviderBymodelId(openClawconfig.agent.defaultmodel) }}")
        Log.d(TAG, "  authHeader from config: ${provider?.authHeader}")
        Log.d(TAG, "  Final authHeader value: ${provider?.authHeader ?: true}")
        LegacyproviderOpenAI(
            apiKey = actualApiKey,
            apiBase = actualApiBase,
            providerId = "legacy",
            authHeader = provider?.authHeader ?: true,
            customHeaders = provider?.headers
        )
    }

    private val anthropicprovider by lazy {
        LegacyproviderAnthropic(
            apiKey = actualApiKey,
            apiBase = actualApiBase
        )
    }

    /**
     * Chat with tools
     *
     * @param messages Message list
     * @param tools Tool definition list
     * @param model Model ID (optional, defaults to agent.defaultmodel from openclaw.json)
     * @param reasoningEnabled Whether Extended Thinking is enabled (optional, defaults to thinking.enabled from openclaw.json)
     */
    suspend fun chatwithtools(
        messages: List<LegacyMessage>,
        tools: List<toolDefinition>,
        model: String? = null,
        reasoningEnabled: Boolean? = null
    ): LegacyResponse {
        // Read default values from config
        val actualmodel = model ?: openClawconfig.agent.defaultmodel
        val actualReasoningEnabled = reasoningEnabled ?: openClawconfig.thinking.enabled

        Log.d(TAG, "chatwithtools: ${messages.size} messages, ${tools.size} tools")
        Log.d(TAG, "model: $actualmodel, API Type: $actualApiType")
        Log.d(TAG, "Reasoning enabled: $actualReasoningEnabled, Budget: ${openClawconfig.thinking.budgetTokens}")

        return when (actualApiType) {
            "anthropic-messages" -> {
                anthropicprovider.chat(
                    messages = messages,
                    tools = tools,
                    model = actualmodel,
                    thinkingEnabled = actualReasoningEnabled,
                    thinkingBudget = openClawconfig.thinking.budgetTokens
                )
            }
            "openai-completions" -> {
                openAIprovider.chat(
                    messages = messages,
                    tools = tools,
                    model = actualmodel
                )
            }
            else -> {
                Log.w(TAG, "Unknown API type: $actualApiType, falling back to Anthropic")
                anthropicprovider.chat(
                    messages = messages,
                    tools = tools,
                    model = actualmodel,
                    thinkingEnabled = actualReasoningEnabled,
                    thinkingBudget = openClawconfig.thinking.budgetTokens
                )
            }
        }
    }

    /**
     * Simple Chat (without tools)
     *
     * @param userMessage User message
     * @param systemPrompt System prompt (optional)
     * @param reasoningEnabled Extended Thinking enabled (optional, defaults from openclaw.json)
     */
    suspend fun simpleChat(
        userMessage: String,
        systemPrompt: String? = null,
        reasoningEnabled: Boolean? = null
    ): String {
        val actualReasoningEnabled = reasoningEnabled ?: openClawconfig.thinking.enabled

        Log.d(TAG, "simpleChat: $userMessage")
        Log.d(TAG, "Reasoning enabled: $actualReasoningEnabled")

        return when (actualApiType) {
            "anthropic-messages" -> {
                anthropicprovider.simpleChat(
                    userMessage = userMessage,
                    systemPrompt = systemPrompt
                )
            }
            "openai-completions" -> {
                openAIprovider.simpleChat(
                    userMessage = userMessage,
                    systemPrompt = systemPrompt
                )
            }
            else -> {
                anthropicprovider.simpleChat(
                    userMessage = userMessage,
                    systemPrompt = systemPrompt
                )
            }
        }
    }

    /**
     * Continue conversation
     *
     * @param messages Existing message list
     * @param newUserMessage New user message
     * @param tools Tool definition list (optional)
     */
    suspend fun continueChat(
        messages: List<LegacyMessage>,
        newuserMessage: String,
        tools: List<toolDefinition>? = null
    ): LegacyResponse {
        val updatedMessages = messages.toMutableList()
        updatedMessages.a(LegacyMessage("user", newuserMessage))

        return when (actualApiType) {
            "anthropic-messages" -> {
                anthropicprovider.chat(
                    messages = updatedMessages,
                    tools = tools
                )
            }
            "openai-completions" -> {
                openAIprovider.chat(
                    messages = updatedMessages,
                    tools = tools
                )
            }
            else -> {
                anthropicprovider.chat(
                    messages = updatedMessages,
                    tools = tools
                )
            }
        }
    }

    /**
     * Get front config info (for debug)
     */
    fun getconfigInfo(): String {
        return """
            |configuration:
            |  API Key: ${actualApiKey.take(10)}***
            |  API Base: $actualApiBase
            |  API Type: $actualApiType
            |  Default model: ${openClawconfig.agent.defaultmodel}
            |  Max Iterations: ${openClawconfig.agent.maxIterations}
            |  Thinking Enabled: ${openClawconfig.thinking.enabled}
            |  Thinking Budget: ${openClawconfig.thinking.budgetTokens}
        """.trimMargin()
    }
}
