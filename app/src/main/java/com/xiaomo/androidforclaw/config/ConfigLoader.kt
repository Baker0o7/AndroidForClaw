package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/io.ts
 *
 * AndroidForClaw adaptation: load/save/observe openclaw.json on Android storage.
 */


import android.content.Context
import android.os.FileObserver
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Config Loader - Aligned with OpenClaw config loading logic (global singleton)
 *
 * Uses org.json.JSONObject parse, missing fields automatically use data class default values.
 * User config only needs to write override fields, others use default values.
 */
class ConfigLoader private constructor() {

    companion object {
        private const val TAG = "ConfigLoader"
        private const val OPENCLAW_CONFIG_FILE = "openclaw.json"

        @Volatile
        private var INSTANCE: ConfigLoader? = null

        @JvmStatic
        fun getInstance(): ConfigLoader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigLoader().also { INSTANCE = it }
            }
        }

        /** Convenience: same as getInstance(), for call sites that used to pass context. */
        @JvmStatic
        operator fun invoke(context: Context): ConfigLoader {
            val instance = getInstance()
            instance.initContext(context)
            return instance
        }
    }

    private lateinit var context: Context

    fun initContext(ctx: Context) {
        if (!::context.isInitialized) {
            context = ctx.applicationContext
        }
    }

    private val configDir: File get() = com.xiaomo.androidforclaw.workspace.StoragePaths.root
    private val openclawConfigFile: File get() = File(configDir, OPENCLAW_CONFIG_FILE)

    // Config cache
    private var cachedOpenClawConfig: OpenClawConfig? = null
    private var openclawConfigCacheValid = false

    // Hot reload support
    private var fileObserver: FileObserver? = null
    private var hotReloadEnabled = false
    private var reloadCallback: ((OpenClawConfig) -> Unit)? = null

    init {
        Log.d(TAG, "Config directory: ${configDir.absolutePath}")
    }

    /**
     * Load OpenClaw main config (with auto backup and resume)
     */
    fun loadOpenClawConfig(): OpenClawConfig {
        if (openclawConfigCacheValid && cachedOpenClawConfig != null) {
            return cachedOpenClawConfig!!
        }

        val backupManager = ConfigBackupManager(context)
        val config = backupManager.loadConfigSafely {
            loadOpenClawConfigInternal()
        }

        if (config != null) {
            cachedOpenClawConfig = config
            openclawConfigCacheValid = true
            return config
        } else {
            Log.w(TAG, "Using default config")
            val defaultConfig = OpenClawConfig()
            cachedOpenClawConfig = defaultConfig
            openclawConfigCacheValid = true
            return defaultConfig
        }
    }

    private fun loadOpenClawConfigInternal(): OpenClawConfig {
        ensureConfigDir()

        if (!openclawConfigFile.exists()) {
            Log.i(TAG, "Config file not exists, creating default config: ${openclawConfigFile.absolutePath}")
            createDefaultConfig()
        }

        var configJson = openclawConfigFile.readText()
        val processedJson = replaceEnvVars(configJson)
        var rootJson = JSONObject(processedJson)

        // Workspace config merge (OpenClaw merge-config.ts)
        val workspaceConfigFile = File(StoragePaths.workspace, "openclaw.json")
        if (workspaceConfigFile.exists() && workspaceConfigFile.absolutePath != openclawConfigFile.absolutePath) {
            try {
                val workspaceJson = JSONObject(replaceEnvVars(workspaceConfigFile.readText()))
                rootJson = ConfigMerge.mergeJsonConfigs(rootJson, workspaceJson)
                Log.i(TAG, "[PACKAGE] Workspace config merged from: ${workspaceConfigFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to merge workspace config: ${e.message}")
            }
        }

        val config = parseConfig(rootJson.toString())
        val migrated = migrateProviderIds(config)
        validateConfig(migrated)

        Log.i(TAG, "[OK] Config loaded successfully")
        return migrated
    }

    /**
     * Resolve a model ID through the model aliases map.
     * Returns the aliased ID if found, otherwise the original.
     * (OpenClaw model-selection.ts: buildModelAliasIndex)
     */
    fun resolveModelId(modelId: String): String {
        val config = loadOpenClawConfig()
        return ConfigMerge.resolveModelAlias(modelId, config.modelAliases)
    }

    /**
     * Migrate old provider ID to new ID (Aligned with OpenClaw official naming)
     * "mimo" → "xiaomi"
     */
    private fun migrateProviderIds(config: OpenClawConfig): OpenClawConfig {
        val providers = config.models?.providers ?: return config
        if (!providers.containsKey("mimo")) return config

        Log.i(TAG, "[SYNC] Migrate provider ID: mimo → xiaomi")
        val migrated = providers.toMutableMap()
        val mimoProvider = migrated.remove("mimo")!!
        migrated["xiaomi"] = mimoProvider

        // Also update default model ref if it points to mimo/
        val agents = config.agents
        val updatedAgents = if (agents?.defaults?.model?.primary?.startsWith("mimo/") == true) {
            val newPrimary = agents.defaults.model.primary.replace("mimo/", "xiaomi/")
            agents.copy(
                defaults = agents.defaults.copy(
                    model = ModelSelectionConfig(primary = newPrimary)
                )
            )
        } else agents

        val migratedConfig = config.copy(
            models = config.models?.copy(providers = migrated),
            agents = updatedAgents
        )

        // Persist the migration
        try {
            val root = JSONObject(openclawConfigFile.readText())
            val modelsJson = root.optJSONObject("models")
            val providersJson = modelsJson?.optJSONObject("providers")
            if (providersJson?.has("mimo") == true) {
                val mimoJson = providersJson.getJSONObject("mimo")
                providersJson.put("xiaomi", mimoJson)
                providersJson.remove("mimo")

                // Update default model ref
                val agentsJson = root.optJSONObject("agents")
                val defaultsJson = agentsJson?.optJSONObject("defaults")
                val modelJson = defaultsJson?.optJSONObject("model")
                val primary = modelJson?.optString("primary")
                if (primary?.startsWith("mimo/") == true) {
                    modelJson.put("primary", primary.replace("mimo/", "xiaomi/"))
                }

                writeJsonToFile(openclawConfigFile, root)
                Log.i(TAG, "[OK] Provider ID migration already persisted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WARN] Provider ID migration persistence failed: ${e.message}")
        }

        return migratedConfig
    }

    /**
     * Parse full config from JSON string
     * All missing fields use data class default values
     */
    private fun parseConfig(json: String): OpenClawConfig {
        val root = JSONObject(json)

        // models
        val modelsJson = root.optJSONObject("models")
        val models = modelsJson?.let { parseModelsConfig(it) }

        // agents
        val agentsJson = root.optJSONObject("agents")
        val agents = agentsJson?.let { parseAgentsConfig(it) }

        // agent (Android extension, legacy)
        val agentJson = root.optJSONObject("agent")
        val agent = agentJson?.let { parseAgentConfig(it) } ?: AgentConfig()

        // channels (Aligned with OpenClaw: channels.feishu / channels.discord)
        val channelsJson = root.optJSONObject("channels")
        // Compatible with old format: if channels has no feishu, fallback to gateway.feishu
        val gatewayJson = root.optJSONObject("gateway")
        val channels = parseChannelsConfig(channelsJson, gatewayJson)

        // Gateway (Aligned with OpenClaw: only has port/mode/bind/auth)
        val gateway = gatewayJson?.let { parseGatewayConfig(it) } ?: GatewayConfig()

        // skills
        val skillsJson = root.optJSONObject("skills")
        val skills = skillsJson?.let { parseSkillsConfig(it) } ?: SkillsConfig()

        // Plugins
        val pluginsJson = root.optJSONObject("plugins")
        val plugins = pluginsJson?.let { parsePluginsConfig(it) } ?: PluginsConfig()

        // tools
        val toolsJson = root.optJSONObject("tools")
        val tools = toolsJson?.let { parseToolsConfig(it) } ?: ToolsConfig()

        // UI
        val uiJson = root.optJSONObject("ui")
        val ui = uiJson?.let { parseUIConfig(it) } ?: UIConfig()

        // Logging
        val loggingJson = root.optJSONObject("logging")
        val logging = loggingJson?.let { parseLoggingConfig(it) } ?: LoggingConfig()

        // Memory
        val memoryJson = root.optJSONObject("memory")
        val memory = memoryJson?.let { parseMemoryConfig(it) } ?: MemoryConfig()

        // Messages
        val messagesJson = root.optJSONObject("messages")
        val messages = messagesJson?.let {
            MessagesConfig(ackReactionScope = it.optString("ackReactionScope", "own"))
        } ?: MessagesConfig()

        // Session
        val sessionJson = root.optJSONObject("session")
        val session = sessionJson?.let { parseSessionConfig(it) } ?: SessionConfig()

        // Legacy providers (top-level)
        val legacyProviders = root.optJSONObject("providers")?.let { parseProvidersMap(it) } ?: emptyMap()

        // Model aliases (OpenClaw model-selection.ts)
        val modelAliases = mutableMapOf<String, String>()
        root.optJSONObject("modelAliases")?.let { aliasJson ->
            for (key in aliasJson.keys()) {
                modelAliases[key] = aliasJson.optString(key, key)
            }
        }

        // Model allowlist/blocklist (OpenClaw model-selection.ts)
        val modelAllowlist = root.optJSONObject("modelAllowlist")?.let { alJson ->
            val allow = alJson.optJSONArray("allow")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }
            val block = alJson.optJSONArray("block")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }
            ModelAllowlistConfig(allow = allow, block = block)
        }

        return OpenClawConfig(
            models = models,
            agents = agents,
            channels = channels,
            gateway = gateway,
            skills = skills,
            plugins = plugins,
            tools = tools,
            memory = memory,
            messages = messages,
            session = session,
            logging = logging,
            ui = ui,
            modelAliases = modelAliases,
            modelAllowlist = modelAllowlist,
            agent = agent,
            providers = legacyProviders
        )
    }

    // ============ Section Parsers ============

    private fun parseModelsConfig(json: JSONObject): ModelsConfig {
        val providersJson = json.optJSONObject("providers")
        val providers = providersJson?.let { parseProvidersMap(it) } ?: emptyMap()
        return ModelsConfig(
            mode = json.optString("mode", "merge"),
            providers = providers
        )
    }

    private fun parseProvidersMap(json: JSONObject): Map<String, ProviderConfig> {
        val map = mutableMapOf<String, ProviderConfig>()
        json.keys().forEach { key ->
            json.optJSONObject(key)?.let { map[key] = parseProviderConfig(it) }
        }
        return map
    }

    private fun parseProviderConfig(json: JSONObject): ProviderConfig {
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val defaultApi = json.optString("api", "openai-completions")
        val models = (0 until modelsArray.length()).mapNotNull { i ->
            modelsArray.optJSONObject(i)?.let { parseModelDefinition(it, defaultApi) }
        }

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it, "") }
        }

        return ProviderConfig(
            baseUrl = json.optString("baseUrl", ""),
            apiKey = json.optString("apiKey", null),
            api = defaultApi,
            auth = json.optString("auth", null),
            authHeader = json.optBoolean("authHeader", true),
            headers = headers,
            injectNumCtxForOpenAICompat = if (json.has("injectNumCtxForOpenAICompat")) json.optBoolean("injectNumCtxForOpenAICompat") else null,
            models = models
        )
    }

    private fun parseModelDefinition(json: JSONObject, defaultApi: String): ModelDefinition {
        val input: List<Any> = if (json.has("input")) {
            val arr = json.optJSONArray("input") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val item = arr.get(i)
                if (item is JSONObject) item.toString() else item.toString()
            }
        } else listOf("text")

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it, "") }
        }

        val cost = json.optJSONObject("cost")?.let { c ->
            CostConfig(
                input = c.optDouble("input", 0.0),
                output = c.optDouble("output", 0.0),
                cacheRead = c.optDouble("cacheRead", 0.0),
                cacheWrite = c.optDouble("cacheWrite", 0.0)
            )
        }

        val compat = json.optJSONObject("compat")?.let { c ->
            ModelCompatConfig(
                supportsStore = if (c.has("supportsStore")) c.optBoolean("supportsStore") else null,
                supportsReasoningEffort = if (c.has("supportsReasoningEffort")) c.optBoolean("supportsReasoningEffort") else null,
                maxTokensField = if (c.has("maxTokensField")) c.optString("maxTokensField") else null,
                thinkingFormat = if (c.has("thinkingFormat")) c.optString("thinkingFormat") else null,
                requiresToolResultName = if (c.has("requiresToolResultName")) c.optBoolean("requiresToolResultName") else null,
                requiresAssistantAfterToolResult = if (c.has("requiresAssistantAfterToolResult")) c.optBoolean("requiresAssistantAfterToolResult") else null,
                toolSchemaProfile = if (c.has("toolSchemaProfile")) c.optString("toolSchemaProfile") else null,
                nativeWebSearchTool = if (c.has("nativeWebSearchTool")) c.optBoolean("nativeWebSearchTool") else null,
                toolCallArgumentsEncoding = if (c.has("toolCallArgumentsEncoding")) c.optString("toolCallArgumentsEncoding") else null,
                supportsDeveloperRole = if (c.has("supportsDeveloperRole")) c.optBoolean("supportsDeveloperRole") else null,
                supportsUsageInStreaming = if (c.has("supportsUsageInStreaming")) c.optBoolean("supportsUsageInStreaming") else null,
                supportsStrictMode = if (c.has("supportsStrictMode")) c.optBoolean("supportsStrictMode") else null
            )
        }

        val modelId = json.optString("id", "")
        val modelIdLower = modelId.lowercase()
        val compatWithDefaults = if (compat?.maxTokensField == null) {
            val defaultMaxTokensField = when {
                modelIdLower.startsWith("gpt-5") -> "max_completion_tokens"
                modelIdLower.startsWith("o1") -> "max_completion_tokens"
                modelIdLower.startsWith("o3") -> "max_completion_tokens"
                modelIdLower.startsWith("gpt-4.1") -> "max_completion_tokens"
                else -> null
            }
            if (defaultMaxTokensField != null) {
                (compat ?: ModelCompatConfig()).copy(maxTokensField = defaultMaxTokensField)
            } else {
                compat
            }
        } else {
            compat
        }

        return ModelDefinition(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            api = if (json.has("api")) json.optString("api") else null,
            reasoning = json.optBoolean("reasoning", false),
            input = input,
            cost = cost,
            contextWindow = json.optInt("contextWindow", 128000),
            maxTokens = json.optInt("maxTokens", 8192),
            headers = headers,
            compat = compatWithDefaults
        )
    }

    private fun parseAgentsConfig(json: JSONObject): AgentsConfig {
        val defaultsJson = json.optJSONObject("defaults")
        val defaults = if (defaultsJson != null) {
            val modelJson = defaultsJson.optJSONObject("model")
            val model = modelJson?.let {
                ModelSelectionConfig(
                    primary = it.optString("primary", null),
                    fallbacks = it.optJSONArray("fallbacks")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.getString(i) }
                    }
                )
            }
            val subagentsJson = defaultsJson.optJSONObject("subagents")
            val subagents = if (subagentsJson != null) SubAgentsConfig(
                maxConcurrent = subagentsJson.optInt("maxConcurrent", 1),
                maxSpawnDepth = subagentsJson.optInt("maxSpawnDepth", 1),
                maxChildrenPerAgent = subagentsJson.optInt("maxChildrenPerAgent", 5),
                defaultTimeoutSeconds = subagentsJson.optInt("defaultTimeoutSeconds", 300),
                model = subagentsJson.optString("model", null)?.takeIf { it.isNotBlank() },
                thinking = subagentsJson.optString("thinking", null)?.takeIf { it.isNotBlank() },
                enabled = subagentsJson.optBoolean("enabled", true)
            ) else SubAgentsConfig()

            AgentDefaultsConfig(
                model = model,
                bootstrapMaxChars = defaultsJson.optInt("bootstrapMaxChars", 20_000),
                bootstrapTotalMaxChars = defaultsJson.optInt("bootstrapTotalMaxChars", 150_000),
                subagents = subagents
            )
        } else AgentDefaultsConfig()
        return AgentsConfig(defaults = defaults)
    }

    private fun parseAgentConfig(json: JSONObject): AgentConfig {
        return AgentConfig(
            maxIterations = json.optInt("maxIterations", 40),
            defaultModel = json.optString("defaultModel", "openrouter/hunter-alpha"),
            timeout = json.optLong("timeout", 300000),
            retryOnError = json.optBoolean("retryOnError", true),
            maxRetries = json.optInt("maxRetries", 3),
            mode = json.optString("mode", "exploration")
        )
    }

    /**
     * Parse channels config (Aligned with OpenClaw: channels.feishu)
     * Compatible with old format: if channels.feishu not exists, fallback to gateway.feishu
     */
    private fun parseChannelsConfig(channelsJson: JSONObject?, gatewayJson: JSONObject?): ChannelsConfig {
        val feishuJson = channelsJson?.optJSONObject("feishu")
            ?: gatewayJson?.optJSONObject("feishu")  // legacy fallback
        val feishu = feishuJson?.let { parseFeishuConfig(it) } ?: FeishuChannelConfig()

        val discordJson = channelsJson?.optJSONObject("discord")
            ?: gatewayJson?.optJSONObject("discord")  // legacy fallback
        val discord = discordJson?.let { parseDiscordConfig(it) }

        val slackJson = channelsJson?.optJSONObject("slack")
        val slack = slackJson?.let { parseSlackConfig(it) }

        val telegramJson = channelsJson?.optJSONObject("telegram")
        val telegram = telegramJson?.let { parseTelegramConfig(it) }

        val whatsappJson = channelsJson?.optJSONObject("whatsapp")
        val whatsapp = whatsappJson?.let { parseWhatsAppConfig(it) }

        val signalJson = channelsJson?.optJSONObject("signal")
        val signal = signalJson?.let { parseSignalConfig(it) }

        val weixinJson = channelsJson?.optJSONObject("weixin")
            ?: channelsJson?.optJSONObject("openclaw-weixin")
        val weixin = weixinJson?.let { parseWeixinConfig(it) }

        return ChannelsConfig(feishu = feishu, discord = discord, slack = slack, telegram = telegram, whatsapp = whatsapp, signal = signal, weixin = weixin)
    }

    /**
     * Parse gateway (Aligned with OpenClaw: only has port/mode/bind/auth)
     */
    private fun parseGatewayConfig(json: JSONObject): GatewayConfig {
        val authJson = json.optJSONObject("auth")
        val auth = authJson?.let {
            GatewayAuthConfig(
                mode = it.optString("mode", "token"),
                token = if (it.has("token")) it.optString("token") else null
            )
        }

        return GatewayConfig(
            port = json.optInt("port", 19789),
            mode = json.optString("mode", "local"),
            bind = json.optString("bind", "loopback"),
            auth = auth
        )
    }

    private fun parseFeishuConfig(json: JSONObject): FeishuChannelConfig {
        // Tools sub config (Aligned with OpenClaw FeishuToolsConfigSchema)
        val toolsJson = json.optJSONObject("tools")
        val tools = toolsJson?.let {
            FeishuToolsConfig(
                doc = it.optBoolean("doc", true),
                chat = it.optBoolean("chat", true),
                wiki = it.optBoolean("wiki", true),
                drive = it.optBoolean("drive", true),
                perm = it.optBoolean("perm", false),
                scopes = it.optBoolean("scopes", true),
                bitable = it.optBoolean("bitable", true),
                task = it.optBoolean("task", true),
                urgent = it.optBoolean("urgent", true)
            )
        } ?: FeishuToolsConfig()

        // Multi-account
        val accountsJson = json.optJSONObject("accounts")
        val accounts = accountsJson?.let { a ->
            val map = mutableMapOf<String, FeishuAccountConfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = FeishuAccountConfig(
                        enabled = aj.optBoolean("enabled", true),
                        name = if (aj.has("name")) aj.optString("name") else null,
                        appId = if (aj.has("appId")) aj.optString("appId") else null,
                        appSecret = if (aj.has("appSecret")) aj.optString("appSecret") else null,
                        domain = if (aj.has("domain")) aj.optString("domain") else null,
                        connectionMode = if (aj.has("connectionMode")) aj.optString("connectionMode") else null,
                        webhookPath = if (aj.has("webhookPath")) aj.optString("webhookPath") else null
                    )
                }
            }
            map
        }

        return FeishuChannelConfig(
            enabled = json.optBoolean("enabled", false),
            appId = json.optString("appId", ""),
            appSecret = json.optString("appSecret", ""),
            encryptKey = if (json.has("encryptKey")) json.optString("encryptKey") else null,
            verificationToken = if (json.has("verificationToken")) json.optString("verificationToken") else null,
            domain = json.optString("domain", "feishu"),
            connectionMode = json.optString("connectionMode", "websocket"),
            webhookPath = json.optString("webhookPath", "/feishu/events"),
            webhookHost = if (json.has("webhookHost")) json.optString("webhookHost") else null,
            webhookPort = if (json.has("webhookPort")) json.optInt("webhookPort") else null,
            dmPolicy = json.optString("dmPolicy", "pairing"),
            allowFrom = json.optJSONArray("allowFrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            groupPolicy = json.optString("groupPolicy", "allowlist"),
            groupAllowFrom = json.optJSONArray("groupAllowFrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            requireMention = if (json.has("requireMention")) json.optBoolean("requireMention") else null,
            groupCommandMentionBypass = json.optString("groupCommandMentionBypass", "never"),
            allowMentionlessInMultiBotGroup = json.optBoolean("allowMentionlessInMultiBotGroup", false),
            groupSessionScope = if (json.has("groupSessionScope")) json.optString("groupSessionScope") else null,
            topicSessionMode = json.optString("topicSessionMode", "disabled"),
            replyInThread = json.optString("replyInThread", "disabled"),
            historyLimit = json.optInt("historyLimit", 20),
            dmHistoryLimit = json.optInt("dmHistoryLimit", 100),
            textChunkLimit = json.optInt("textChunkLimit", 4000),
            chunkMode = json.optString("chunkMode", "length"),
            renderMode = json.optString("renderMode", "auto"),
            streaming = if (json.has("streaming")) json.optBoolean("streaming") else null,
            mediaMaxMb = json.optDouble("mediaMaxMb", 20.0),
            tools = tools,
            queueMode = if (json.has("queueMode")) json.optString("queueMode") else "followup",
            queueCap = json.optInt("queueCap", 10),
            queueDropPolicy = json.optString("queueDropPolicy", "old"),
            queueDebounceMs = json.optInt("queueDebounceMs", 100),
            typingIndicator = json.optBoolean("typingIndicator", true),
            resolveSenderNames = json.optBoolean("resolveSenderNames", true),
            reactionNotifications = json.optString("reactionNotifications", "own"),
            reactionDedup = json.optBoolean("reactionDedup", true),
            debugMode = json.optBoolean("debugMode", false),
            accounts = accounts,
            defaultAccount = if (json.has("defaultAccount")) json.optString("defaultAccount") else null
        )
    }

    private fun parseDiscordConfig(json: JSONObject): DiscordChannelConfig {
        val dm = json.optJSONObject("dm")?.let { d ->
            DmPolicyConfig(
                policy = d.optString("policy", "pairing"),
                allowFrom = d.optJSONArray("allowFrom")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            )
        }

        val guilds = json.optJSONObject("guilds")?.let { g ->
            val map = mutableMapOf<String, GuildPolicyConfig>()
            g.keys().forEach { key ->
                g.optJSONObject(key)?.let { gj ->
                    map[key] = GuildPolicyConfig(
                        channels = gj.optJSONArray("channels")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        },
                        requireMention = if (gj.has("requireMention")) gj.optBoolean("requireMention") else true,
                        toolPolicy = if (gj.has("toolPolicy")) gj.optString("toolPolicy") else null
                    )
                }
            }
            map
        }

        val accounts = json.optJSONObject("accounts")?.let { a ->
            val map = mutableMapOf<String, DiscordAccountPolicyConfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = DiscordAccountPolicyConfig(
                        enabled = aj.optBoolean("enabled", true),
                        token = if (aj.has("token")) aj.optString("token") else null,
                        name = if (aj.has("name")) aj.optString("name") else null,
                        dm = aj.optJSONObject("dm")?.let { d ->
                            DmPolicyConfig(
                                policy = d.optString("policy", "pairing"),
                                allowFrom = d.optJSONArray("allowFrom")?.let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                }
                            )
                        },
                        guilds = aj.optJSONObject("guilds")?.let { g ->
                            val gmap = mutableMapOf<String, GuildPolicyConfig>()
                            g.keys().forEach { gk ->
                                g.optJSONObject(gk)?.let { gj ->
                                    gmap[gk] = GuildPolicyConfig(
                                        channels = gj.optJSONArray("channels")?.let { arr ->
                                            (0 until arr.length()).map { arr.getString(it) }
                                        }
                                    )
                                }
                            }
                            gmap
                        }
                    )
                }
            }
            map
        }

        return DiscordChannelConfig(
            enabled = json.optBoolean("enabled", false),
            token = if (json.has("token")) json.optString("token") else null,
            name = if (json.has("name")) json.optString("name") else null,
            dm = dm,
            groupPolicy = if (json.has("groupPolicy")) json.optString("groupPolicy") else null,
            guilds = guilds,
            replyToMode = if (json.has("replyToMode")) json.optString("replyToMode") else null,
            accounts = accounts
        )
    }

    private fun parseSlackConfig(json: JSONObject): SlackChannelConfig {
        // Compatible with old field token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return SlackChannelConfig(
            enabled = json.optBoolean("enabled", false),
            botToken = botToken,
            appToken = if (json.has("appToken")) json.optString("appToken") else null,
            signingSecret = if (json.has("signingSecret")) json.optString("signingSecret") else null,
            mode = json.optString("mode", "socket"),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            streaming = json.optString("streaming", "partial"),
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseTelegramConfig(json: JSONObject): TelegramChannelConfig {
        // Compatible with old field token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return TelegramChannelConfig(
            enabled = json.optBoolean("enabled", false),
            botToken = botToken,
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            streaming = json.optString("streaming", "partial"),
            webhookUrl = if (json.has("webhookUrl")) json.optString("webhookUrl") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseWhatsAppConfig(json: JSONObject): WhatsAppChannelConfig {
        return WhatsAppChannelConfig(
            enabled = json.optBoolean("enabled", false),
            phoneNumber = json.optString("phoneNumber", ""),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseSignalConfig(json: JSONObject): SignalChannelConfig {
        // Compatible with OpenClaw account field → phoneNumber
        val phoneNumber = json.optString("phoneNumber", "").ifEmpty { json.optString("account", "") }
        return SignalChannelConfig(
            enabled = json.optBoolean("enabled", false),
            phoneNumber = phoneNumber,
            httpUrl = if (json.has("httpUrl")) json.optString("httpUrl") else null,
            httpPort = json.optInt("httpPort", 8080),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseWeixinConfig(json: JSONObject): WeixinChannelConfig {
        return WeixinChannelConfig(
            enabled = json.optBoolean("enabled", false),
            baseUrl = json.optString("baseUrl", "https://ilinkai.weixin.qq.com")
                .ifEmpty { "https://ilinkai.weixin.qq.com" },
            cdnBaseUrl = json.optString("cdnBaseUrl", "https://novac2c.cdn.weixin.qq.com/c2c")
                .ifEmpty { "https://novac2c.cdn.weixin.qq.com/c2c" },
            routeTag = if (json.has("routeTag")) json.optString("routeTag") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseSkillsConfig(json: JSONObject): SkillsConfig {
        val entries = json.optJSONObject("entries")?.let { e ->
            val map = mutableMapOf<String, SkillConfig>()
            e.keys().forEach { key ->
                e.optJSONObject(key)?.let { sc ->
                    map[key] = SkillConfig(
                        enabled = sc.optBoolean("enabled", true),
                        apiKey = if (sc.has("apiKey")) sc.get("apiKey") else null,
                        env = sc.optJSONObject("env")?.let { envObj ->
                            envObj.keys().asSequence().associateWith { envObj.optString(it, "") }
                        }
                    )
                }
            }
            map
        } ?: emptyMap()

        val extraDirs = json.optJSONArray("extraDirs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return SkillsConfig(
            allowBundled = json.optJSONArray("allowBundled")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            extraDirs = extraDirs,
            watch = json.optBoolean("watch", true),
            watchDebounceMs = json.optLong("watchDebounceMs", 250),
            entries = entries
        )
    }

    private fun parsePluginsConfig(json: JSONObject): PluginsConfig {
        val entriesJson = json.optJSONObject("entries") ?: return PluginsConfig()
        val map = mutableMapOf<String, PluginEntry>()
        entriesJson.keys().forEach { key ->
            entriesJson.optJSONObject(key)?.let { pe ->
                val skills = pe.optJSONArray("skills")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                map[key] = PluginEntry(
                    enabled = pe.optBoolean("enabled", false),
                    skills = skills
                )
            }
        }
        return PluginsConfig(entries = map)
    }

    private fun parseToolsConfig(json: JSONObject): ToolsConfig {
        val ssJson = json.optJSONObject("screenshot")
        val screenshot = ssJson?.let {
            ScreenshotToolConfig(
                enabled = it.optBoolean("enabled", true),
                quality = it.optInt("quality", 85),
                maxWidth = it.optInt("maxWidth", 1080),
                format = it.optString("format", "jpeg")
            )
        } ?: ScreenshotToolConfig()

        return ToolsConfig(screenshot = screenshot)
    }

    private fun parseThinkingConfig(json: JSONObject): ThinkingConfig {
        return ThinkingConfig(
            enabled = json.optBoolean("enabled", true),
            budgetTokens = json.optInt("budgetTokens", 10000)
        )
    }

    private fun parseUIConfig(json: JSONObject): UIConfig {
        return UIConfig(
            theme = json.optString("theme", "auto"),
            language = json.optString("language", "zh")
        )
    }

    private fun parseLoggingConfig(json: JSONObject): LoggingConfig {
        return LoggingConfig(
            level = json.optString("level", "INFO"),
            logToFile = json.optBoolean("logToFile", true)
        )
    }

    private fun parseMemoryConfig(json: JSONObject): MemoryConfig {
        return MemoryConfig(
            enabled = json.optBoolean("enabled", true),
            path = json.optString("path", StoragePaths.workspaceMemory.absolutePath)
        )
    }

    private fun parseSessionConfig(json: JSONObject): SessionConfig {
        return SessionConfig(
            maxMessages = json.optInt("maxMessages", 100),
            maxAgeDays = json.optInt("maxAgeDays", 30),
            maxEntries = json.optInt("maxEntries", 500),
            maxDiskBytes = json.optLong("maxDiskBytes", 100_000_000L),
            highWaterRatio = json.optDouble("highWaterRatio", 0.8).toFloat()
        )
    }

    private fun parseprovidersMap(json: JSONObject): Map<String, providerconfig> {
        val map = mutableMapOf<String, providerconfig>()
        json.keys().forEach { key ->
            json.optJSONObject(key)?.let { map[key] = parseproviderconfig(it) }
        }
        return map
    }

    private fun parseproviderconfig(json: JSONObject): providerconfig {
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val defaultApi = json.optString("api", "openai-completions")
        val models = (0 until modelsArray.length()).mapnotNull { i ->
            modelsArray.optJSONObject(i)?.let { parsemodelDefinition(it, defaultApi) }
        }

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associatewith { h.optString(it, "") }
        }

        return providerconfig(
            baseUrl = json.optString("baseUrl", ""),
            apiKey = json.optString("apiKey", null),
            api = defaultApi,
            auth = json.optString("auth", null),
            authHeader = json.optBoolean("authHeader", true),
            headers = headers,
            injectNumCtxforOpenAICompat = if (json.has("injectNumCtxforOpenAICompat")) json.optBoolean("injectNumCtxforOpenAICompat") else null,
            models = models
        )
    }

    private fun parsemodelDefinition(json: JSONObject, defaultApi: String): modelDefinition {
        val input: List<Any> = if (json.has("input")) {
            val arr = json.optJSONArray("input") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val item = arr.get(i)
                if (item is JSONObject) item.toString() else item.toString()
            }
        } else listOf("text")

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associatewith { h.optString(it, "") }
        }

        val cost = json.optJSONObject("cost")?.let { c ->
            Costconfig(
                input = c.optDouble("input", 0.0),
                output = c.optDouble("output", 0.0),
                cacheRead = c.optDouble("cacheRead", 0.0),
                cacheWrite = c.optDouble("cacheWrite", 0.0)
            )
        }

        val compat = json.optJSONObject("compat")?.let { c ->
            modelCompatconfig(
                supportsStore = if (c.has("supportsStore")) c.optBoolean("supportsStore") else null,
                supportsReasoningEffort = if (c.has("supportsReasoningEffort")) c.optBoolean("supportsReasoningEffort") else null,
                maxTokensField = if (c.has("maxTokensField")) c.optString("maxTokensField") else null,
                thinkingformat = if (c.has("thinkingformat")) c.optString("thinkingformat") else null,
                requirestoolresultName = if (c.has("requirestoolresultName")) c.optBoolean("requirestoolresultName") else null,
                requiresAssistantaftertoolresult = if (c.has("requiresAssistantaftertoolresult")) c.optBoolean("requiresAssistantaftertoolresult") else null,
                toolschemaProfile = if (c.has("toolschemaProfile")) c.optString("toolschemaProfile") else null,
                nativeWebSearchtool = if (c.has("nativeWebSearchtool")) c.optBoolean("nativeWebSearchtool") else null,
                toolCallArgumentsEncoding = if (c.has("toolCallArgumentsEncoding")) c.optString("toolCallArgumentsEncoding") else null,
                supportsDeveloperRole = if (c.has("supportsDeveloperRole")) c.optBoolean("supportsDeveloperRole") else null,
                supportsUsageInStreaming = if (c.has("supportsUsageInStreaming")) c.optBoolean("supportsUsageInStreaming") else null,
                supportsStrictMode = if (c.has("supportsStrictMode")) c.optBoolean("supportsStrictMode") else null
            )
        }

        val modelId = json.optString("id", "")
        val modelIdLower = modelId.lowercase()
        val compatwithDefaults = if (compat?.maxTokensField == null) {
            val defaultMaxTokensField = when {
                modelIdLower.startswith("gpt-5") -> "max_completion_tokens"
                modelIdLower.startswith("o1") -> "max_completion_tokens"
                modelIdLower.startswith("o3") -> "max_completion_tokens"
                modelIdLower.startswith("gpt-4.1") -> "max_completion_tokens"
                else -> null
            }
            if (defaultMaxTokensField != null) {
                (compat ?: modelCompatconfig()).copy(maxTokensField = defaultMaxTokensField)
            } else {
                compat
            }
        } else {
            compat
        }

        return modelDefinition(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            api = if (json.has("api")) json.optString("api") else null,
            reasoning = json.optBoolean("reasoning", false),
            input = input,
            cost = cost,
            contextWindow = json.optInt("contextWindow", 128000),
            maxTokens = json.optInt("maxTokens", 8192),
            headers = headers,
            compat = compatwithDefaults
        )
    }

    private fun parseagentsconfig(json: JSONObject): agentsconfig {
        val defaultsJson = json.optJSONObject("defaults")
        val defaults = if (defaultsJson != null) {
            val modelJson = defaultsJson.optJSONObject("model")
            val model = modelJson?.let {
                modelSelectionconfig(
                    primary = it.optString("primary", null),
                    fallbacks = it.optJSONArray("fallbacks")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.getString(i) }
                    }
                )
            }
            val subagentsJson = defaultsJson.optJSONObject("subagents")
            val subagents = if (subagentsJson != null) Subagentsconfig(
                maxConcurrent = subagentsJson.optInt("maxConcurrent", 1),
                maxSpawnDepth = subagentsJson.optInt("maxSpawnDepth", 1),
                maxChildrenPeragent = subagentsJson.optInt("maxChildrenPeragent", 5),
                defaultTimeoutSeconds = subagentsJson.optInt("defaultTimeoutSeconds", 300),
                model = subagentsJson.optString("model", null)?.takeif { it.isnotBlank() },
                thinking = subagentsJson.optString("thinking", null)?.takeif { it.isnotBlank() },
                enabled = subagentsJson.optBoolean("enabled", true)
            ) else Subagentsconfig()

            agentDefaultsconfig(
                model = model,
                bootstrapMaxChars = defaultsJson.optInt("bootstrapMaxChars", 20_000),
                bootstrapTotalMaxChars = defaultsJson.optInt("bootstrapTotalMaxChars", 150_000),
                subagents = subagents
            )
        } else agentDefaultsconfig()
        return agentsconfig(defaults = defaults)
    }

    private fun parseagentconfig(json: JSONObject): agentconfig {
        return agentconfig(
            maxIterations = json.optInt("maxIterations", 40),
            defaultmodel = json.optString("defaultmodel", "openrouter/hunter-alpha"),
            timeout = json.optLong("timeout", 300000),
            retryOnError = json.optBoolean("retryOnError", true),
            maxRetries = json.optInt("maxRetries", 3),
            mode = json.optString("mode", "exploration")
        )
    }

    /**
     * Parse channels config (Aligned with OpenClaw: channels.feishu)
     * Compatible with old format: if channels.feishu not exists, fallback to gateway.feishu
     */
    private fun parseChannelsConfig(channelsJson: JSONObject?, gatewayJson: JSONObject?): ChannelsConfig {
        val feishuJson = channelsJson?.optJSONObject("feishu")
            ?: gatewayJson?.optJSONObject("feishu")  // legacy fallback
        val feishu = feishuJson?.let { parseFeishuconfig(it) } ?: Feishuchannelconfig()

        val discordJson = channelsJson?.optJSONObject("discord")
            ?: gatewayJson?.optJSONObject("discord")  // legacy fallback
        val discord = discordJson?.let { parseDiscordconfig(it) }

        val slackJson = channelsJson?.optJSONObject("slack")
        val slack = slackJson?.let { parseSlackconfig(it) }

        val telegramJson = channelsJson?.optJSONObject("telegram")
        val telegram = telegramJson?.let { parseTelegramconfig(it) }

        val whatsappJson = channelsJson?.optJSONObject("whatsapp")
        val whatsapp = whatsappJson?.let { parsewhatsAppconfig(it) }

        val signalJson = channelsJson?.optJSONObject("signal")
        val signal = signalJson?.let { parseSignalconfig(it) }

        val weixinJson = channelsJson?.optJSONObject("weixin")
            ?: channelsJson?.optJSONObject("openclaw-weixin")
        val weixin = weixinJson?.let { parseWeixinconfig(it) }

        return channelsconfig(feishu = feishu, discord = discord, slack = slack, telegram = telegram, whatsapp = whatsapp, signal = signal, weixin = weixin)
    }

    /**
     * Parse gateway (Aligned with OpenClaw: only has port/mode/bind/auth)
     */
    private fun parseGatewayConfig(json: JSONObject): GatewayConfig {
        val authJson = json.optJSONObject("auth")
        val auth = authJson?.let {
            GatewayAuthconfig(
                mode = it.optString("mode", "token"),
                token = if (it.has("token")) it.optString("token") else null
            )
        }

        return Gatewayconfig(
            port = json.optInt("port", 19789),
            mode = json.optString("mode", "local"),
            bind = json.optString("bind", "loopback"),
            auth = auth
        )
    }

    private fun parseFeishuConfig(json: JSONObject): FeishuChannelConfig {
        // Tools sub config (Aligned with OpenClaw FeishuToolsConfigSchema)
        val toolsJson = json.optJSONObject("tools")
        val tools = toolsJson?.let {
            Feishutoolsconfig(
                doc = it.optBoolean("doc", true),
                chat = it.optBoolean("chat", true),
                wiki = it.optBoolean("wiki", true),
                drive = it.optBoolean("drive", true),
                perm = it.optBoolean("perm", false),
                scopes = it.optBoolean("scopes", true),
                bitable = it.optBoolean("bitable", true),
                task = it.optBoolean("task", true),
                urgent = it.optBoolean("urgent", true)
            )
        } ?: Feishutoolsconfig()

        // manyAccount
        val accountsJson = json.optJSONObject("accounts")
        val accounts = accountsJson?.let { a ->
            val map = mutableMapOf<String, FeishuAccountconfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = FeishuAccountconfig(
                        enabled = aj.optBoolean("enabled", true),
                        name = if (aj.has("name")) aj.optString("name") else null,
                        appId = if (aj.has("appId")) aj.optString("appId") else null,
                        appSecret = if (aj.has("appSecret")) aj.optString("appSecret") else null,
                        domain = if (aj.has("domain")) aj.optString("domain") else null,
                        connectionMode = if (aj.has("connectionMode")) aj.optString("connectionMode") else null,
                        webhookPath = if (aj.has("webhookPath")) aj.optString("webhookPath") else null
                    )
                }
            }
            map
        }

        return Feishuchannelconfig(
            enabled = json.optBoolean("enabled", false),
            appId = json.optString("appId", ""),
            appSecret = json.optString("appSecret", ""),
            encryptKey = if (json.has("encryptKey")) json.optString("encryptKey") else null,
            verificationToken = if (json.has("verificationToken")) json.optString("verificationToken") else null,
            domain = json.optString("domain", "feishu"),
            connectionMode = json.optString("connectionMode", "websocket"),
            webhookPath = json.optString("webhookPath", "/feishu/events"),
            webhookHost = if (json.has("webhookHost")) json.optString("webhookHost") else null,
            webhookPort = if (json.has("webhookPort")) json.optInt("webhookPort") else null,
            dmPolicy = json.optString("dmPolicy", "pairing"),
            allowfrom = json.optJSONArray("allowfrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            groupPolicy = json.optString("groupPolicy", "allowlist"),
            groupAllowfrom = json.optJSONArray("groupAllowfrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            requireMention = if (json.has("requireMention")) json.optBoolean("requireMention") else null,
            groupCommandMentionBypass = json.optString("groupCommandMentionBypass", "never"),
            allowMentionlessInMultiBotGroup = json.optBoolean("allowMentionlessInMultiBotGroup", false),
            groupsessionScope = if (json.has("groupsessionScope")) json.optString("groupsessionScope") else null,
            topicsessionMode = json.optString("topicsessionMode", "disabled"),
            replyInThread = json.optString("replyInThread", "disabled"),
            historyLimit = json.optInt("historyLimit", 20),
            dmHistoryLimit = json.optInt("dmHistoryLimit", 100),
            textChunkLimit = json.optInt("textChunkLimit", 4000),
            chunkMode = json.optString("chunkMode", "length"),
            renderMode = json.optString("renderMode", "auto"),
            streaming = if (json.has("streaming")) json.optBoolean("streaming") else null,
            mediaMaxMb = json.optDouble("mediaMaxMb", 20.0),
            tools = tools,
            queueMode = if (json.has("queueMode")) json.optString("queueMode") else "followup",
            queueCap = json.optInt("queueCap", 10),
            queueDropPolicy = json.optString("queueDropPolicy", "old"),
            queueDebounceMs = json.optInt("queueDebounceMs", 100),
            typingIndicator = json.optBoolean("typingIndicator", true),
            resolveSenderNames = json.optBoolean("resolveSenderNames", true),
            reactionnotifications = json.optString("reactionnotifications", "own"),
            reactionDedup = json.optBoolean("reactionDedup", true),
            debugMode = json.optBoolean("debugMode", false),
            accounts = accounts,
            defaultAccount = if (json.has("defaultAccount")) json.optString("defaultAccount") else null
        )
    }

    private fun parseDiscordconfig(json: JSONObject): Discordchannelconfig {
        val dm = json.optJSONObject("dm")?.let { d ->
            DmPolicyconfig(
                policy = d.optString("policy", "pairing"),
                allowfrom = d.optJSONArray("allowfrom")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            )
        }

        val guilds = json.optJSONObject("guilds")?.let { g ->
            val map = mutableMapOf<String, GuildPolicyconfig>()
            g.keys().forEach { key ->
                g.optJSONObject(key)?.let { gj ->
                    map[key] = GuildPolicyconfig(
                        channels = gj.optJSONArray("channels")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        },
                        requireMention = if (gj.has("requireMention")) gj.optBoolean("requireMention") else true,
                        toolPolicy = if (gj.has("toolPolicy")) gj.optString("toolPolicy") else null
                    )
                }
            }
            map
        }

        val accounts = json.optJSONObject("accounts")?.let { a ->
            val map = mutableMapOf<String, DiscordAccountPolicyconfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = DiscordAccountPolicyconfig(
                        enabled = aj.optBoolean("enabled", true),
                        token = if (aj.has("token")) aj.optString("token") else null,
                        name = if (aj.has("name")) aj.optString("name") else null,
                        dm = aj.optJSONObject("dm")?.let { d ->
                            DmPolicyconfig(
                                policy = d.optString("policy", "pairing"),
                                allowfrom = d.optJSONArray("allowfrom")?.let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                }
                            )
                        },
                        guilds = aj.optJSONObject("guilds")?.let { g ->
                            val gmap = mutableMapOf<String, GuildPolicyconfig>()
                            g.keys().forEach { gk ->
                                g.optJSONObject(gk)?.let { gj ->
                                    gmap[gk] = GuildPolicyconfig(
                                        channels = gj.optJSONArray("channels")?.let { arr ->
                                            (0 until arr.length()).map { arr.getString(it) }
                                        }
                                    )
                                }
                            }
                            gmap
                        }
                    )
                }
            }
            map
        }

        return Discordchannelconfig(
            enabled = json.optBoolean("enabled", false),
            token = if (json.has("token")) json.optString("token") else null,
            name = if (json.has("name")) json.optString("name") else null,
            dm = dm,
            groupPolicy = if (json.has("groupPolicy")) json.optString("groupPolicy") else null,
            guilds = guilds,
            replyToMode = if (json.has("replyToMode")) json.optString("replyToMode") else null,
            accounts = accounts
        )
    }

    private fun parseSlackConfig(json: JSONObject): SlackChannelConfig {
        // Compatible with old field token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return SlackChannelConfig(
            enabled = json.optBoolean("enabled", false),
            botToken = botToken,
            appToken = if (json.has("appToken")) json.optString("appToken") else null,
            signingSecret = if (json.has("signingSecret")) json.optString("signingSecret") else null,
            mode = json.optString("mode", "socket"),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            streaming = json.optString("streaming", "partial"),
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseTelegramConfig(json: JSONObject): TelegramChannelConfig {
        // Compatible with old field token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return TelegramChannelConfig(
            enabled = json.optBoolean("enabled", false),
            botToken = botToken,
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            streaming = json.optString("streaming", "partial"),
            webhookUrl = if (json.has("webhookUrl")) json.optString("webhookUrl") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseWhatsAppConfig(json: JSONObject): WhatsAppChannelConfig {
        return WhatsAppChannelConfig(
            enabled = json.optBoolean("enabled", false),
            phoneNumber = json.optString("phoneNumber", ""),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseSignalConfig(json: JSONObject): SignalChannelConfig {
        // Compatible with OpenClaw account field → phoneNumber
        val phoneNumber = json.optString("phoneNumber", "").ifEmpty { json.optString("account", "") }
        return SignalChannelConfig(
            enabled = json.optBoolean("enabled", false),
            phoneNumber = phoneNumber,
            httpUrl = if (json.has("httpUrl")) json.optString("httpUrl") else null,
            httpPort = json.optInt("httpPort", 8080),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseWeixinConfig(json: JSONObject): WeixinChannelConfig {
        return WeixinChannelConfig(
            enabled = json.optBoolean("enabled", false),
            baseUrl = json.optString("baseUrl", "https://ilinkai.weixin.qq.com")
                .ifEmpty { "https://ilinkai.weixin.qq.com" },
            cdnBaseUrl = json.optString("cdnBaseUrl", "https://novac2c.cdn.weixin.qq.com/c2c")
                .ifEmpty { "https://novac2c.cdn.weixin.qq.com/c2c" },
            routeTag = if (json.has("routeTag")) json.optString("routeTag") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseSkillsConfig(json: JSONObject): SkillsConfig {
        val entries = json.optJSONObject("entries")?.let { e ->
            val map = mutableMapOf<String, SkillConfig>()
            e.keys().forEach { key ->
                e.optJSONObject(key)?.let { sc ->
                    map[key] = SkillConfig(
                        enabled = sc.optBoolean("enabled", true),
                        apiKey = if (sc.has("apiKey")) sc.get("apiKey") else null,
                        env = sc.optJSONObject("env")?.let { envObj ->
                            envObj.keys().asSequence().associateWith { envObj.optString(it, "") }
                        }
                    )
                }
            }
            map
        } ?: emptyMap()

        val extraDirs = json.optJSONArray("extraDirs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return SkillsConfig(
            allowBundled = json.optJSONArray("allowBundled")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            extraDirs = extraDirs,
            watch = json.optBoolean("watch", true),
            watchDebounceMs = json.optLong("watchDebounceMs", 250),
            entries = entries
        )
    }

    private fun parsePluginsConfig(json: JSONObject): PluginsConfig {
        val entriesJson = json.optJSONObject("entries") ?: return PluginsConfig()
        val map = mutableMapOf<String, PluginEntry>()
        entriesJson.keys().forEach { key ->
            entriesJson.optJSONObject(key)?.let { pe ->
                val skills = pe.optJSONArray("skills")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                map[key] = PluginEntry(
                    enabled = pe.optBoolean("enabled", false),
                    skills = skills
                )
            }
        }
        return PluginsConfig(entries = map)
    }

    private fun parseToolsConfig(json: JSONObject): ToolsConfig {
        val ssJson = json.optJSONObject("screenshot")
        val screenshot = ssJson?.let {
            ScreenshotToolConfig(
                enabled = it.optBoolean("enabled", true),
                quality = it.optInt("quality", 85),
                maxWidth = it.optInt("maxWidth", 1080),
                format = it.optString("format", "jpeg")
            )
        } ?: ScreenshotToolConfig()

        return ToolsConfig(screenshot = screenshot)
    }

    private fun parseThinkingConfig(json: JSONObject): ThinkingConfig {
        return ThinkingConfig(
            enabled = json.optBoolean("enabled", true),
            budgetTokens = json.optInt("budgetTokens", 10000)
        )
    }

    private fun parseUIConfig(json: JSONObject): UIConfig {
        return UIConfig(
            theme = json.optString("theme", "auto"),
            language = json.optString("language", "zh")
        )
    }

    private fun parseLoggingConfig(json: JSONObject): LoggingConfig {
        return LoggingConfig(
            level = json.optString("level", "INFO"),
            logToFile = json.optBoolean("logToFile", true)
        )
    }

    private fun parseMemoryConfig(json: JSONObject): MemoryConfig {
        return MemoryConfig(
            enabled = json.optBoolean("enabled", true),
            path = json.optString("path", StoragePaths.workspaceMemory.absolutePath)
        )
    }

    private fun parseSessionConfig(json: JSONObject): SessionConfig {
        return SessionConfig(
            maxMessages = json.optInt("maxMessages", 100),
            maxAgeDays = json.optInt("maxAgeDays", 30),
            maxEntries = json.optInt("maxEntries", 500),
            maxDiskBytes = json.optLong("maxDiskBytes", 100_000_000L),
            highWaterRatio = json.optDouble("highWaterRatio", 0.8).toFloat()
        )
    }

    // ============ Public API ============

    fun getProviderConfig(providerName: String): ProviderConfig? {
        return loadOpenClawConfigFresh().resolveProviders()[providerName]
    }

    fun getModelDefinition(providerName: String, modelId: String): ModelDefinition? {
        return getProviderConfig(providerName)?.models?.find { it.id == modelId }
    }

    fun listAllModels(): List<Pair<String, ModelDefinition>> {
        val config = loadOpenClawConfigFresh()
        return config.resolveProviders().flatMap { (name, provider) ->
            provider.models.map { name to it }
        }
    }

    fun findProviderByModelId(modelId: String): String? {
        return loadOpenClawConfigFresh().resolveProviders().entries.find { (_, provider) ->
            provider.models.any { it.id == modelId }
        }?.key
    }

    /**
     * Force read config from disk, ignore cache.
     * Used for LLM request path, ensures getting most new config (avoid cross configLoader instance cache not updated).
     */
    fun loadOpenClawConfigFresh(): OpenClawConfig {
        openclawConfigCacheValid = false
        return loadOpenClawConfig()
    }

    /**
     * Save config - use JSONObject serialize (not dependency Gson)
     */
    fun saveOpenClawConfig(config: OpenClawConfig): Boolean {
        return try {
            ensureConfigDir()
            // Read existing file to preserve unknown fields
            val existingJson = if (openclawConfigFile.exists()) {
                JSONObject(openclawConfigFile.readText())
            } else JSONObject()

            // Merge known fields into existing JSON
            mergeConfigToJson(existingJson, config)

            writeJsonToFile(openclawConfigFile, existingJson)
            Log.i(TAG, "[OK] Config save success")
            openclawConfigCacheValid = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Config save failed: ${e.message}", e)
            false
        }
    }

    /**
     * Write config object key fields to JSONObject (preserve other fields in file)
     */
    private fun mergeConfigToJson(root: JSONObject, config: OpenClawConfig) {
        // models + providers
        config.models?.let { m ->
            val modelsObj = root.optJSONObject("models") ?: JSONObject()
            val providersObj = JSONObject()
            m.providers.forEach { (name, p) ->
                val pObj = JSONObject()
                pObj.put("baseUrl", p.baseUrl)
                if (p.apiKey != null) pObj.put("apiKey", p.apiKey)
                pObj.put("api", p.api)
                pObj.put("authHeader", p.authHeader)
                p.headers?.let { h -> pObj.put("headers", JSONObject(h)) }
                val modelsArr = JSONArray()
                p.models.forEach { md ->
                    val mObj = JSONObject()
                    mObj.put("id", md.id)
                    mObj.put("name", md.name)
                    mObj.put("reasoning", md.reasoning)
                    mObj.put("contextWindow", md.contextWindow)
                    mObj.put("maxTokens", md.maxTokens)
                    md.api?.let { mObj.put("api", it) }
                    modelsArr.put(mObj)
                }
                pObj.put("models", modelsArr)
                providersObj.put(name, pObj)
            }
            modelsObj.put("providers", providersObj)
            root.put("models", modelsObj)
        }

        // agents
        config.agents?.let { a ->
            val agentsObj = root.optJSONObject("agents") ?: JSONObject()
            val defaultsObj = JSONObject()
            a.defaults.model?.let { model ->
                val modelObj = JSONObject()
                model.primary?.let { modelObj.put("primary", it) }
                defaultsObj.put("model", modelObj)
            }
            agentsObj.put("defaults", defaultsObj)
            root.put("agents", agentsObj)
        }

        // agent (Android extension)
        val agentObj = root.optJSONObject("agent") ?: JSONObject()
        agentObj.put("defaultModel", config.agent.defaultModel)
        agentObj.put("maxIterations", config.agent.maxIterations)
        root.put("agent", agentObj)

        // channels (Aligned with OpenClaw: channels.feishu)
        val channelsObj = root.optJSONObject("channels") ?: JSONObject()
        val feishu = config.channels.feishu
        val feishuObj = JSONObject()
        feishuObj.put("enabled", feishu.enabled)
        feishuObj.put("appId", feishu.appId)
        feishuObj.put("appSecret", feishu.appSecret)
        feishuObj.put("domain", feishu.domain)
        feishuObj.put("connectionMode", feishu.connectionMode)
        feishuObj.put("dmPolicy", feishu.dmPolicy)
        feishuObj.put("groupPolicy", feishu.groupPolicy)
        feishu.requireMention?.let { feishuObj.put("requireMention", it) }
        feishuObj.put("groupCommandMentionBypass", feishu.groupCommandMentionBypass)
        feishuObj.put("allowMentionlessInMultiBotGroup", feishu.allowMentionlessInMultiBotGroup)
        channelsObj.put("feishu", feishuObj)

        config.channels.discord?.let { discord ->
            val existing = channelsObj.optJSONObject("discord") ?: JSONObject()
            existing.put("enabled", discord.enabled)
            discord.token?.let { existing.put("token", it) }
            discord.name?.let { existing.put("name", it) }
            discord.groupPolicy?.let { existing.put("groupPolicy", it) }
            discord.replyToMode?.let { existing.put("replyToMode", it) }
            discord.dm?.let { dm ->
                val dmObj = JSONObject()
                dm.policy?.let { dmObj.put("policy", it) }
                dm.allowFrom?.let { list ->
                    val arr = JSONArray(); list.forEach { arr.put(it) }
                    dmObj.put("allowFrom", arr)
                }
                existing.put("dm", dmObj)
            }
            channelsObj.put("discord", existing)
        }

        config.channels.slack?.let { slack ->
            val obj = channelsObj.optJSONObject("slack") ?: JSONObject()
            obj.put("enabled", slack.enabled)
            obj.put("botToken", slack.botToken)
            obj.put("mode", slack.mode)
            obj.put("dmPolicy", slack.dmPolicy)
            obj.put("groupPolicy", slack.groupPolicy)
            obj.put("requireMention", slack.requireMention)
            obj.put("streaming", slack.streaming)
            slack.appToken?.let { obj.put("appToken", it) }
            slack.signingSecret?.let { obj.put("signingSecret", it) }
            if (slack.historyLimit != null) obj.put("historyLimit", slack.historyLimit) else obj.remove("historyLimit")
            if (slack.model != null) obj.put("model", slack.model) else obj.remove("model")
            channelsObj.put("slack", obj)
        }

        config.channels.telegram?.let { telegram ->
            val obj = channelsObj.optJSONObject("telegram") ?: JSONObject()
            obj.put("enabled", telegram.enabled)
            obj.put("botToken", telegram.botToken)
            obj.put("dmPolicy", telegram.dmPolicy)
            obj.put("groupPolicy", telegram.groupPolicy)
            obj.put("requireMention", telegram.requireMention)
            obj.put("streaming", telegram.streaming)
            telegram.webhookUrl?.let { obj.put("webhookUrl", it) }
            if (telegram.historyLimit != null) obj.put("historyLimit", telegram.historyLimit) else obj.remove("historyLimit")
            if (telegram.model != null) obj.put("model", telegram.model) else obj.remove("model")
            channelsObj.put("telegram", obj)
        }

        config.channels.whatsapp?.let { whatsapp ->
            val obj = channelsObj.optJSONObject("whatsapp") ?: JSONObject()
            obj.put("enabled", whatsapp.enabled)
            obj.put("phoneNumber", whatsapp.phoneNumber)
            obj.put("dmPolicy", whatsapp.dmPolicy)
            obj.put("groupPolicy", whatsapp.groupPolicy)
            obj.put("requireMention", whatsapp.requireMention)
            if (whatsapp.historyLimit != null) obj.put("historyLimit", whatsapp.historyLimit) else obj.remove("historyLimit")
            if (whatsapp.model != null) obj.put("model", whatsapp.model) else obj.remove("model")
            channelsObj.put("whatsapp", obj)
        }

        config.channels.signal?.let { signal ->
            val obj = channelsObj.optJSONObject("signal") ?: JSONObject()
            obj.put("enabled", signal.enabled)
            // OpenClaw use account field to store phone number
            obj.put("account", signal.phoneNumber)
            obj.put("phoneNumber", signal.phoneNumber)
            signal.httpUrl?.let { obj.put("httpUrl", it) }
            obj.put("httpPort", signal.httpPort)
            obj.put("dmPolicy", signal.dmPolicy)
            obj.put("groupPolicy", signal.groupPolicy)
            obj.put("requireMention", signal.requireMention)
            if (signal.historyLimit != null) obj.put("historyLimit", signal.historyLimit) else obj.remove("historyLimit")
            if (signal.model != null) obj.put("model", signal.model) else obj.remove("model")
            channelsObj.put("signal", obj)
        }

        config.channels.weixin?.let { weixin ->
            val obj = channelsObj.optJSONObject("weixin") ?: JSONObject()
            obj.put("enabled", weixin.enabled)
            obj.put("baseUrl", weixin.baseUrl)
            obj.put("cdnBaseUrl", weixin.cdnBaseUrl)
            if (weixin.routeTag != null) obj.put("routeTag", weixin.routeTag) else obj.remove("routeTag")
            if (weixin.model != null) obj.put("model", weixin.model) else obj.remove("model")
            channelsObj.put("weixin", obj)
        }

        root.put("channels", channelsObj)

        // Gateway (Aligned with OpenClaw: only has port/mode/bind/auth)
        val gwObj = root.optJSONObject("gateway") ?: JSONObject()
        gwObj.put("port", config.gateway.port)
        root.put("gateway", gwObj)
    }

    fun reloadOpenClawConfig(): OpenClawConfig {
        Log.i(TAG, "Reloading config...")
        openclawConfigCacheValid = false
        return loadOpenClawConfig()
    }

    fun enableHotReload(callback: ((OpenClawConfig) -> Unit)? = null) {
        if (hotReloadEnabled) return
        this.reloadCallback = callback
        try {
            ensureConfigDir()
            fileObserver = object : FileObserver(configDir, MODIFY or CREATE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == OPENCLAW_CONFIG_FILE) {
                        Log.i(TAG, "Detected config file change")
                        val newConfig = reloadOpenClawConfig()
                        reloadCallback?.invoke(newConfig)
                    }
                }
            }
            fileObserver?.startWatching()
            hotReloadEnabled = true
            Log.i(TAG, "[OK] Config hot reload enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Enable hot reload failed", e)
        }
    }

    fun disableHotReload() {
        fileObserver?.stopWatching()
        fileObserver = null
        reloadCallback = null
        hotReloadEnabled = false
    }

    fun isHotReloadEnabled(): Boolean = hotReloadEnabled

    fun getFeishuConfig(): com.xiaomo.feishu.FeishuConfig {
        return FeishuConfigAdapter.toFeishuConfig(loadOpenClawConfig().channels.feishu)
    }

    // ============ Private helpers ============

    /**
     * Write JSON to file, unified UTF-8 encoding and remove extra \/ escaping
     */
    private fun writeJsonToFile(file: File, json: JSONObject, indent: Int = 4) {
        val text = json.toString(indent).replace("\\/", "/")
        file.writeText(text, Charsets.UTF_8)
    }

    private fun ensureConfigDir() {
        if (!configDir.exists()) configDir.mkdirs()
    }

    private fun createDefaultConfig() {
        try {
            val defaultConfig = context.assets.open("openclaw.json.default.txt")
                .bufferedReader().use { it.readText() }
            openclawConfigFile.writeText(defaultConfig, Charsets.UTF_8)
            Log.i(TAG, "[OK] Created default config: ${openclawConfigFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Create default config failed", e)
            throw e
        }
    }

    /**
     * Known environment variable name → provider ID map
     * Source: OpenClaw PROVIDER_ENV_API_KEY_CANDIDATES
     */
    private val ENV_VAR_TO_PROVIDER = mapOf(
        "OPENROUTER_API_KEY" to "openrouter",
        "ANTHROPIC_API_KEY" to "anthropic",
        "OPENAI_API_KEY" to "openai",
        "GEMINI_API_KEY" to "google",
        "DEEPSEEK_API_KEY" to "deepseek",
        "XAI_API_KEY" to "xai",
        "OLLAMA_API_KEY" to "ollama",
        "MOONSHOT_API_KEY" to "moonshot",
        "KIMI_API_KEY" to "moonshot",
        "KIMICODE_API_KEY" to "kimi-coding",
        "XIAOMI_API_KEY" to "xiaomi",
        "MISTRAL_API_KEY" to "mistral",
        "TOGETHER_API_KEY" to "together",
        "HF_TOKEN" to "huggingface",
        "NVIDIA_API_KEY" to "nvidia",
        "QIANFAN_API_KEY" to "qianfan",
        "VOLCANO_ENGINE_API_KEY" to "volcengine"
    )

    private fun replaceEnvVars(json: String): String {
        var result = json
        val pattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")
        val unresolvedKnown = mutableListOf<String>()

        pattern.findAll(json).forEach { match ->
            val varName = match.groupValues[1]
            val value = System.getenv(varName)
            if (value != null) {
                result = result.replace("\${$varName}", value)
            } else {
                // Fallback 1: built-in key (currently only OpenRouter)
                val builtInValue = when (varName) {
                    "OPENROUTER_API_KEY" -> BuiltInKeyProvider.getKey()
                    else -> null
                }
                if (builtInValue != null) {
                    result = result.replace("\${$varName}", builtInValue)
                    Log.i(TAG, "[KEY] Used built-in key for: \${$varName}")
                } else {
                    val providerId = ENV_VAR_TO_PROVIDER[varName]
                    if (providerId != null) {
                        unresolvedKnown.add(varName)
                        Log.w(TAG, "[WARN] Environment variable \${$varName} (provider: $providerId) not set. " +
                            "Please fill in API Key directly in config.")
                    } else {
                        Log.w(TAG, "[WARN] Unknown environment variable: \${$varName}")
                    }
                }
            }
        }

        // Strip unresolved known provider env vars from JSON to avoid sending literal
        // `${VAR}` as Bearer token (causes confusing 401 "Missing Authentication header").
        // After stripping, apiKey becomes null → config validation catches it with a clear message.
        if (unresolvedKnown.isNotEmpty()) {
            for (varName in unresolvedKnown) {
                // Match: "apiKey": "${VAR_NAME}" (with optional whitespace)
                val stripPattern = Regex("""("apiKey"\s*:\s*)"\$\{$varName\}"""")
                result = stripPattern.replace(result) { "${it.groupValues[1]}null" }
            }
            invalidateConfigCache()
        }

        return result
    }

    private fun invalidateConfigCache() {
        openclawConfigCacheValid = false
        cachedOpenClawConfig = null
    }

    /**
     * Minimal validation — only check key fields
     */
    private fun validateConfig(config: OpenClawConfig) {
        // Validate feishu if enabled
        val feishu = config.channels.feishu
        if (feishu.enabled) {
            require(feishu.appId.isNotBlank() && !feishu.appId.startsWith("\${")) {
                "Feishu is enabled but appId not configured"
            }
            require(feishu.appSecret.isNotBlank() && !feishu.appSecret.startsWith("\${")) {
                "Feishu is enabled but appSecret not configured"
            }
        }

        // Validate providers have baseUrl and apiKey (for key-required providers)
        config.resolveProviders().forEach { (name, provider) ->
            require(provider.baseUrl.isNotBlank()) {
                "Provider '$name' missing baseUrl"
            }
            val def = ProviderRegistry.findById(name)
            if (def?.keyRequired == true && provider.apiKey.isNullOrBlank()) {
                Log.w(TAG, "[WARN] Provider '${def.name}' requires API Key but not configured. " +
                    "Please fill in API Key in settings, otherwise requests will return 401.")
            }
        }

        Log.i(TAG, "[OK] Config validation passed")
    }
}

    fun getmodelDefinition(providerName: String, modelId: String): modelDefinition? {
        return getproviderconfig(providerName)?.models?.find { it.id == modelId }
    }

    fun listAllmodels(): List<Pair<String, modelDefinition>> {
        val config = loadOpenClawconfigFresh()
        return config.resolveproviders().flatMap { (name, provider) ->
            provider.models.map { name to it }
        }
    }

    fun findproviderBymodelId(modelId: String): String? {
        return loadOpenClawconfigFresh().resolveproviders().entries.find { (_, provider) ->
            provider.models.any { it.id == modelId }
        }?.key
    }

    /**
     * Force read config from disk, ignore cache.
     * Used for LLM request path, to ensure getting the most recent config (avoid cache inconsistency across configLoader instances).
     */
    fun loadOpenClawConfigFresh(): OpenClawConfig {
        openclawconfigCacheValid = false
        return loadOpenClawconfig()
    }

    /**
     * Save config - using JSONObject serialization (not dependent on Gson)
     */
    fun saveOpenClawConfig(config: OpenClawConfig): Boolean {
        return try {
            ensureConfigDir()
            // Read existing file to preserve unknown fields
            val existingJson = if (openclawconfigFile.exists()) {
                JSONObject(openclawconfigFile.readText())
            } else JSONObject()

            // Merge known fields into existing JSON
            mergeconfigToJson(existingJson, config)

            writeJsonToFile(openclawconfigFile, existingJson)
            Log.i(TAG, "[OK] configSaveSuccess")
            openclawconfigCacheValid = false
            true
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] configSaveFailed: ${e.message}", e)
            false
        }
    }

    /**
     * Will write config object non-null fields to JSONObject (preserve other fields in file)
     */
    private fun mergeConfigToJson(root: JSONObject, config: OpenClawConfig) {
        // models + providers
        config.models?.let { m ->
            val modelsObj = root.optJSONObject("models") ?: JSONObject()
            val providersObj = JSONObject()
            m.providers.forEach { (name, p) ->
                val pObj = JSONObject()
                pObj.put("baseUrl", p.baseUrl)
                if (p.apiKey != null) pObj.put("apiKey", p.apiKey)
                pObj.put("api", p.api)
                pObj.put("authHeader", p.authHeader)
                p.headers?.let { h -> pObj.put("headers", JSONObject(h)) }
                val modelsArr = JSONArray()
                p.models.forEach { md ->
                    val mObj = JSONObject()
                    mObj.put("id", md.id)
                    mObj.put("name", md.name)
                    mObj.put("reasoning", md.reasoning)
                    mObj.put("contextWindow", md.contextWindow)
                    mObj.put("maxTokens", md.maxTokens)
                    md.api?.let { mObj.put("api", it) }
                    modelsArr.put(mObj)
                }
                pObj.put("models", modelsArr)
                providersObj.put(name, pObj)
            }
            modelsObj.put("providers", providersObj)
            root.put("models", modelsObj)
        }

        // agents
        config.agents?.let { a ->
            val agentsObj = root.optJSONObject("agents") ?: JSONObject()
            val defaultsObj = JSONObject()
            a.defaults.model?.let { model ->
                val modelObj = JSONObject()
                model.primary?.let { modelObj.put("primary", it) }
                defaultsObj.put("model", modelObj)
            }
            agentsObj.put("defaults", defaultsObj)
            root.put("agents", agentsObj)
        }

        // agent (android extension)
        val agentObj = root.optJSONObject("agent") ?: JSONObject()
        agentObj.put("defaultmodel", config.agent.defaultmodel)
        agentObj.put("maxIterations", config.agent.maxIterations)
        root.put("agent", agentObj)

        // channels (Aligned with OpenClaw: channels.feishu)
        val channelsObj = root.optJSONObject("channels") ?: JSONObject()
        val feishu = config.channels.feishu
        val feishuObj = JSONObject()
        feishuObj.put("enabled", feishu.enabled)
        feishuObj.put("appId", feishu.appId)
        feishuObj.put("appSecret", feishu.appSecret)
        feishuObj.put("domain", feishu.domain)
        feishuObj.put("connectionMode", feishu.connectionMode)
        feishuObj.put("dmPolicy", feishu.dmPolicy)
        feishuObj.put("groupPolicy", feishu.groupPolicy)
        feishu.requireMention?.let { feishuObj.put("requireMention", it) }
        feishuObj.put("groupCommandMentionBypass", feishu.groupCommandMentionBypass)
        feishuObj.put("allowMentionlessInMultiBotGroup", feishu.allowMentionlessInMultiBotGroup)
        channelsObj.put("feishu", feishuObj)

        config.channels.discord?.let { discord ->
            val existing = channelsObj.optJSONObject("discord") ?: JSONObject()
            existing.put("enabled", discord.enabled)
            discord.token?.let { existing.put("token", it) }
            discord.name?.let { existing.put("name", it) }
            discord.groupPolicy?.let { existing.put("groupPolicy", it) }
            discord.replyToMode?.let { existing.put("replyToMode", it) }
            discord.dm?.let { dm ->
                val dmObj = JSONObject()
                dm.policy?.let { dmObj.put("policy", it) }
                dm.allowfrom?.let { list ->
                    val arr = JSONArray(); list.forEach { arr.put(it) }
                    dmObj.put("allowfrom", arr)
                }
                existing.put("dm", dmObj)
            }
            channelsObj.put("discord", existing)
        }

        config.channels.slack?.let { slack ->
            val obj = channelsObj.optJSONObject("slack") ?: JSONObject()
            obj.put("enabled", slack.enabled)
            obj.put("botToken", slack.botToken)
            obj.put("mode", slack.mode)
            obj.put("dmPolicy", slack.dmPolicy)
            obj.put("groupPolicy", slack.groupPolicy)
            obj.put("requireMention", slack.requireMention)
            obj.put("streaming", slack.streaming)
            slack.appToken?.let { obj.put("appToken", it) }
            slack.signingSecret?.let { obj.put("signingSecret", it) }
            if (slack.historyLimit != null) obj.put("historyLimit", slack.historyLimit) else obj.remove("historyLimit")
            if (slack.model != null) obj.put("model", slack.model) else obj.remove("model")
            channelsObj.put("slack", obj)
        }

        config.channels.telegram?.let { telegram ->
            val obj = channelsObj.optJSONObject("telegram") ?: JSONObject()
            obj.put("enabled", telegram.enabled)
            obj.put("botToken", telegram.botToken)
            obj.put("dmPolicy", telegram.dmPolicy)
            obj.put("groupPolicy", telegram.groupPolicy)
            obj.put("requireMention", telegram.requireMention)
            obj.put("streaming", telegram.streaming)
            telegram.webhookUrl?.let { obj.put("webhookUrl", it) }
            if (telegram.historyLimit != null) obj.put("historyLimit", telegram.historyLimit) else obj.remove("historyLimit")
            if (telegram.model != null) obj.put("model", telegram.model) else obj.remove("model")
            channelsObj.put("telegram", obj)
        }

        config.channels.whatsapp?.let { whatsapp ->
            val obj = channelsObj.optJSONObject("whatsapp") ?: JSONObject()
            obj.put("enabled", whatsapp.enabled)
            obj.put("phoneNumber", whatsapp.phoneNumber)
            obj.put("dmPolicy", whatsapp.dmPolicy)
            obj.put("groupPolicy", whatsapp.groupPolicy)
            obj.put("requireMention", whatsapp.requireMention)
            if (whatsapp.historyLimit != null) obj.put("historyLimit", whatsapp.historyLimit) else obj.remove("historyLimit")
            if (whatsapp.model != null) obj.put("model", whatsapp.model) else obj.remove("model")
            channelsObj.put("whatsapp", obj)
        }

        config.channels.signal?.let { signal ->
            val obj = channelsObj.optJSONObject("signal") ?: JSONObject()
            obj.put("enabled", signal.enabled)
            // OpenClaw uses account field to store phone number
            obj.put("account", signal.phoneNumber)
            obj.put("phoneNumber", signal.phoneNumber)
            signal.httpUrl?.let { obj.put("httpUrl", it) }
            obj.put("httpPort", signal.httpPort)
            obj.put("dmPolicy", signal.dmPolicy)
            obj.put("groupPolicy", signal.groupPolicy)
            obj.put("requireMention", signal.requireMention)
            if (signal.historyLimit != null) obj.put("historyLimit", signal.historyLimit) else obj.remove("historyLimit")
            if (signal.model != null) obj.put("model", signal.model) else obj.remove("model")
            channelsObj.put("signal", obj)
        }

        config.channels.weixin?.let { weixin ->
            val obj = channelsObj.optJSONObject("weixin") ?: JSONObject()
            obj.put("enabled", weixin.enabled)
            obj.put("baseUrl", weixin.baseUrl)
            obj.put("cdnBaseUrl", weixin.cdnBaseUrl)
            if (weixin.routeTag != null) obj.put("routeTag", weixin.routeTag) else obj.remove("routeTag")
            if (weixin.model != null) obj.put("model", weixin.model) else obj.remove("model")
            channelsObj.put("weixin", obj)
        }

        root.put("channels", channelsObj)

        // Gateway (Aligned with OpenClaw: only has port/mode/bind/auth)
        val gwObj = root.optJSONObject("gateway") ?: JSONObject()
        gwObj.put("port", config.gateway.port)
        root.put("gateway", gwObj)
    }

    fun reloadOpenClawconfig(): OpenClawconfig {
        Log.i(TAG, "reLoadconfig...")
        openclawconfigCacheValid = false
        return loadOpenClawconfig()
    }

    fun enableHotReload(callback: ((OpenClawconfig) -> Unit)? = null) {
        if (hotReloadEnabled) return
        this.reloadCallback = callback
        try {
            ensureconfigDir()
            fileObserver = object : FileObserver(configDir, MODIFY or CREATE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == OPENCLAW_CONFIG_FILE) {
                        Log.i(TAG, "Detected config file change")
                        val newconfig = reloadOpenClawconfig()
                        reloadCallback?.invoke(newconfig)
                    }
                }
            }
            fileObserver?.startWatching()
            hotReloadEnabled = true
            Log.i(TAG, "[OK] Config hot reload already enabled")
        } catch (e: exception) {
            Log.e(TAG, "Enable hot reload failed", e)
        }
    }

    fun disableHotReload() {
        fileObserver?.stopWatching()
        fileObserver = null
        reloadCallback = null
        hotReloadEnabled = false
    }

    fun isHotReloadEnabled(): Boolean = hotReloadEnabled

    fun getFeishuconfig(): com.xiaomo.feishu.Feishuconfig {
        return FeishuconfigAdapter.toFeishuconfig(loadOpenClawconfig().channels.feishu)
    }

    // ============ Private helpers ============

    /**
     * Write JSON to file, unified UTF-8 encoding and remove extra \/ escaping
     */
    private fun writeJsonToFile(file: File, json: JSONObject, indent: Int = 4) {
        val text = json.toString(indent).replace("\\/", "/")
        file.writeText(text, Charsets.UTF_8)
    }

    private fun ensureConfigDir() {
        if (!configDir.exists()) configDir.mkdirs()
    }

    private fun createDefaultconfig() {
        try {
            val defaultconfig = context.assets.open("openclaw.json.default.txt")
                .bufferedReader().use { it.readText() }
            openclawconfigFile.writeText(defaultconfig, Charsets.UTF_8)
            Log.i(TAG, "[OK] CreateDefaultconfig: ${openclawconfigFile.absolutePath}")
        } catch (e: exception) {
            Log.e(TAG, "CreateDefaultconfigFailed", e)
            throw e
        }
    }

    /**
     * Known environment variable name → provider ID map
     * Source: OpenClaw PROVIDER_ENV_API_KEY_CANDIDATES
     */
    private val ENV_VAR_TO_PROVIDER = mapOf(
        "OPENROUTER_API_KEY" to "openrouter",
        "ANTHROPIC_API_KEY" to "anthropic",
        "OPENAI_API_KEY" to "openai",
        "GEMINI_API_KEY" to "google",
        "DEEPSEEK_API_KEY" to "deepseek",
        "XAI_API_KEY" to "xai",
        "OLLAMA_API_KEY" to "ollama",
        "MOONSHOT_API_KEY" to "moonshot",
        "KIMI_API_KEY" to "moonshot",
        "KIMICODE_API_KEY" to "kimi-coding",
        "XIAOMI_API_KEY" to "xiaomi",
        "MISTRAL_API_KEY" to "mistral",
        "TOGETHER_API_KEY" to "together",
        "HF_TOKEN" to "huggingface",
        "NVIDIA_API_KEY" to "nvidia",
        "QIANFAN_API_KEY" to "qianfan",
        "VOLCANO_ENGINE_API_KEY" to "volcengine"
    )

    private fun replaceEnvVars(json: String): String {
        var result = json
        val pattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        val unresolvedKnown = mutableListOf<String>()

        pattern.findAll(json).forEach { match ->
            val varName = match.groupValues[1]
            val value = System.getenv(varName)
            if (value != null) {
                result = result.replace("\${$varName}", value)
            } else {
                // Fallback 1: built-in key (currently only OpenRouter)
                val builtInValue = when (varName) {
                    "OPENROUTER_API_KEY" -> BuiltInKeyprovider.getKey()
                    else -> null
                }
                if (builtInValue != null) {
                    result = result.replace("\${$varName}", builtInValue)
                    Log.i(TAG, "[KEY] Use built-in key replacement: \${$varName}")
                } else {
                    val providerId = ENV_VAR_TO_PROVIDER[varName]
                    if (providerId != null) {
                        unresolvedKnown.a(varName)
                        Log.w(TAG, "[WARN] Environment variable \${$varName} (provider: $providerId) not set. " +
                            "Please enter API Key directly in config. ")
                    } else {
                        Log.w(TAG, "[WARN] UnknownEnvironmentVariable: \${$varName}")
                    }
                }
            }
        }

        // Strip unresolved known provider env vars from JSON to avoid sending literal
        // `${VAR}` as Bearer token (causes confusing 401 "Missing Authentication header").
        // after stripping, apiKey becomes null → config validation catches it with a clear message.
        if (unresolvedKnown.isnotEmpty()) {
            for (varName in unresolvedKnown) {
                // Match: "apiKey": "${VAR_NAME}" (with optional whitespace)
                val stripPattern = Regex("""("apiKey"\s*:\s*)"\$\{$varName\}"""")
                result = stripPattern.replace(result) { "${it.groupValues[1]}null" }
            }
            invalidateconfigCache()
        }

        return result
    }

    private fun invalidateconfigCache() {
        openclawconfigCacheValid = false
        cachedOpenClawconfig = null
    }

    /**
     * Simplified validation — only check key fields
     */
    private fun validateConfig(config: OpenClawConfig) {
        // Validate feishu if enabled
        val feishu = config.channels.feishu
        if (feishu.enabled) {
            require(feishu.appId.isNotBlank() && !feishu.appId.startsWith("\${")) {
                "Feishu enabled but appId not configured"
            }
            require(feishu.appSecret.isNotBlank() && !feishu.appSecret.startsWith("\${")) {
                "Feishu enabled but appSecret not configured"
            }
        }

        // Validate providers have baseUrl and apiKey (for key-required providers)
        config.resolveProviders().forEach { (name, provider) ->
            require(provider.baseUrl.isNotBlank()) {
                "Provider '$name' missing baseUrl"
            }
            val def = ProviderRegistry.findById(name)
            if (def?.keyRequired == true && provider.apiKey.isNullOrBlank()) {
                Log.w(TAG, "[WARN] Provider '${def.name}' needs API Key but not configured. " +
                    "Please enter API Key in Settings, otherwise requests will return 401. ")
            }
        }

        Log.i(TAG, "[OK] Config validation passed")
    }
}
