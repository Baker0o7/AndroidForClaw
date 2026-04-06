package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/io.ts
 *
 * androidforClaw adaptation: load/save/observe openclaw.json on android storage.
 */


import android.content.context
import android.os.FileObserver
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * configLoad器 - Aligned with OpenClaw configLoad逻辑(Global单例)
 *
 * use org.json.JSONObject Parse, 缺失FieldAuto用 data class DefaultValue. 
 * user config 只need写thinkoverrideField, Its他all用DefaultValue. 
 */
class configLoader private constructor() {

    companion object {
        private const val TAG = "configLoader"
        private const val OPENCLAW_CONFIG_FILE = "openclaw.json"

        @Volatile
        private var INSTANCE: configLoader? = null

        @JvmStatic
        fun getInstance(): configLoader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: configLoader().also { INSTANCE = it }
            }
        }

        /** Convenience: same as getInstance(), for call sites that used to pass context. */
        @JvmStatic
        operator fun invoke(context: context): configLoader {
            val instance = getInstance()
            instance.initcontext(context)
            return instance
        }
    }

    private lateinit var context: context

    fun initcontext(ctx: context) {
        if (!::context.isInitialized) {
            context = ctx.applicationcontext
        }
    }

    private val configDir: File get() = com.xiaomo.androidforclaw.workspace.StoragePaths.root
    private val openclawconfigFile: File get() = File(configDir, OPENCLAW_CONFIG_FILE)

    // config cache
    private var cachedOpenClawconfig: OpenClawconfig? = null
    private var openclawconfigCacheValid = false

    // Hot reload support
    private var fileObserver: FileObserver? = null
    private var hotReloadEnabled = false
    private var reloadCallback: ((OpenClawconfig) -> Unit)? = null

    init {
        Log.d(TAG, "configdirectory: ${configDir.absolutePath}")
    }

    /**
     * Load OpenClaw mainconfig(带Autobackupandresume)
     */
    fun loadOpenClawconfig(): OpenClawconfig {
        if (openclawconfigCacheValid && cachedOpenClawconfig != null) {
            return cachedOpenClawconfig!!
        }

        val backupmanager = configbackupmanager(context)
        val config = backupmanager.loadconfigSafely {
            loadOpenClawconfigInternal()
        }

        if (config != null) {
            cachedOpenClawconfig = config
            openclawconfigCacheValid = true
            return config
        } else {
            Log.w(TAG, "useDefaultconfig")
            val defaultconfig = OpenClawconfig()
            cachedOpenClawconfig = defaultconfig
            openclawconfigCacheValid = true
            return defaultconfig
        }
    }

    private fun loadOpenClawconfigInternal(): OpenClawconfig {
        ensureconfigDir()

        if (!openclawconfigFile.exists()) {
            Log.i(TAG, "configfilesnotExists, CreateDefaultconfig: ${openclawconfigFile.absolutePath}")
            createDefaultconfig()
        }

        var configJson = openclawconfigFile.readText()
        val processedJson = replaceEnvVars(configJson)
        var rootJson = JSONObject(processedJson)

        // Workspace config merge (OpenClaw merge-config.ts)
        val workspaceconfigFile = File(StoragePaths.workspace, "openclaw.json")
        if (workspaceconfigFile.exists() && workspaceconfigFile.absolutePath != openclawconfigFile.absolutePath) {
            try {
                val workspaceJson = JSONObject(replaceEnvVars(workspaceconfigFile.readText()))
                rootJson = configMerge.mergeJsonconfigs(rootJson, workspaceJson)
                Log.i(TAG, "[PACKAGE] Workspace config merged from: ${workspaceconfigFile.absolutePath}")
            } catch (e: exception) {
                Log.w(TAG, "Failed to merge workspace config: ${e.message}")
            }
        }

        val config = parseconfig(rootJson.toString())
        val migrated = migrateproviderIds(config)
        validateconfig(migrated)

        Log.i(TAG, "[OK] config loaded successfully")
        return migrated
    }

    /**
     * Resolve a model ID through the model aliases map.
     * Returns the aliased ID if found, otherwise the original.
     * (OpenClaw model-selection.ts: buildmodelAliasIndex)
     */
    fun resolvemodelId(modelId: String): String {
        val config = loadOpenClawconfig()
        return configMerge.resolvemodelAlias(modelId, config.modelAliases)
    }

    /**
     * Migrateold provider ID tonew ID(Aligned with OpenClaw 官方命名)
     * "mimo" → "xiaomi"
     */
    private fun migrateproviderIds(config: OpenClawconfig): OpenClawconfig {
        val providers = config.models?.providers ?: return config
        if (!providers.containsKey("mimo")) return config

        Log.i(TAG, "[SYNC] Migrate provider ID: mimo → xiaomi")
        val migrated = providers.toMutableMap()
        val mimoprovider = migrated.remove("mimo")!!
        migrated["xiaomi"] = mimoprovider

        // Also update default model ref if it points to mimo/
        val agents = config.agents
        val updatedagents = if (agents?.defaults?.model?.primary?.startswith("mimo/") == true) {
            val newPrimary = agents.defaults.model.primary.replace("mimo/", "xiaomi/")
            agents.copy(
                defaults = agents.defaults.copy(
                    model = modelSelectionconfig(primary = newPrimary)
                )
            )
        } else agents

        val migratedconfig = config.copy(
            models = config.models?.copy(providers = migrated),
            agents = updatedagents
        )

        // Persist the migration
        try {
            val root = JSONObject(openclawconfigFile.readText())
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
                if (primary?.startswith("mimo/") == true) {
                    modelJson.put("primary", primary.replace("mimo/", "xiaomi/"))
                }

                writeJsonToFile(openclawconfigFile, root)
                Log.i(TAG, "[OK] provider ID MigratealreadyPersistent化")
            }
        } catch (e: exception) {
            Log.w(TAG, "[WARN] provider ID MigratePersistent化Failed: ${e.message}")
        }

        return migratedconfig
    }

    /**
     * from JSON StringParse完整config
     * All缺失Field都用 data class DefaultValue
     */
    private fun parseconfig(json: String): OpenClawconfig {
        val root = JSONObject(json)

        // models
        val modelsJson = root.optJSONObject("models")
        val models = modelsJson?.let { parsemodelsconfig(it) }

        // agents
        val agentsJson = root.optJSONObject("agents")
        val agents = agentsJson?.let { parseagentsconfig(it) }

        // agent (android extension, legacy)
        val agentJson = root.optJSONObject("agent")
        val agent = agentJson?.let { parseagentconfig(it) } ?: agentconfig()

        // channels(Aligned with OpenClaw: channels.feishu / channels.discord)
        val channelsJson = root.optJSONObject("channels")
        // 兼容oldformat: if channels None feishu, fallback to gateway.feishu
        val gatewayJson = root.optJSONObject("gateway")
        val channels = parsechannelsconfig(channelsJson, gatewayJson)

        // Gateway(Aligned with OpenClaw: 只Has port/mode/bind/auth)
        val gateway = gatewayJson?.let { parseGatewayconfig(it) } ?: Gatewayconfig()

        // skills
        val skillsJson = root.optJSONObject("skills")
        val skills = skillsJson?.let { parseskillsconfig(it) } ?: skillsconfig()

        // Plugins
        val pluginsJson = root.optJSONObject("plugins")
        val plugins = pluginsJson?.let { parsePluginsconfig(it) } ?: Pluginsconfig()

        // tools
        val toolsJson = root.optJSONObject("tools")
        val tools = toolsJson?.let { parsetoolsconfig(it) } ?: toolsconfig()

        // UI
        val uiJson = root.optJSONObject("ui")
        val ui = uiJson?.let { parseUIconfig(it) } ?: UIconfig()

        // Logging
        val loggingJson = root.optJSONObject("logging")
        val logging = loggingJson?.let { parseLoggingconfig(it) } ?: Loggingconfig()

        // Memory
        val memoryJson = root.optJSONObject("memory")
        val memory = memoryJson?.let { parseMemoryconfig(it) } ?: Memoryconfig()

        // Messages
        val messagesJson = root.optJSONObject("messages")
        val messages = messagesJson?.let {
            Messagesconfig(ackReactionScope = it.optString("ackReactionScope", "own"))
        } ?: Messagesconfig()

        // session
        val sessionJson = root.optJSONObject("session")
        val session = sessionJson?.let { parsesessionconfig(it) } ?: sessionconfig()

        // Legacy providers (top-level)
        val legacyproviders = root.optJSONObject("providers")?.let { parseprovidersMap(it) } ?: emptyMap()

        // model aliases (OpenClaw model-selection.ts)
        val modelAliases = mutableMapOf<String, String>()
        root.optJSONObject("modelAliases")?.let { aliasJson ->
            for (key in aliasJson.keys()) {
                modelAliases[key] = aliasJson.optString(key, key)
            }
        }

        // model allowlist/blocklist (OpenClaw model-selection.ts)
        val modelAllowlist = root.optJSONObject("modelAllowlist")?.let { alJson ->
            val allow = alJson.optJSONArray("allow")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isnotEmpty() }
            }
            val block = alJson.optJSONArray("block")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isnotEmpty() }
            }
            modelAllowlistconfig(allow = allow, block = block)
        }

        return OpenClawconfig(
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
            providers = legacyproviders
        )
    }

    // ============ Section Parsers ============

    private fun parsemodelsconfig(json: JSONObject): modelsconfig {
        val providersJson = json.optJSONObject("providers")
        val providers = providersJson?.let { parseprovidersMap(it) } ?: emptyMap()
        return modelsconfig(
            mode = json.optString("mode", "merge"),
            providers = providers
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
     * Parse channels config(Aligned with OpenClaw: channels.feishu)
     * 兼容oldformat: if channels.feishu notExists, fallback to gateway.feishu
     */
    private fun parsechannelsconfig(channelsJson: JSONObject?, gatewayJson: JSONObject?): channelsconfig {
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
     * Parse gateway(Aligned with OpenClaw: 只Has port/mode/bind/auth)
     */
    private fun parseGatewayconfig(json: JSONObject): Gatewayconfig {
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

    private fun parseFeishuconfig(json: JSONObject): Feishuchannelconfig {
        // tools 子config(Aligned with OpenClaw Feishutoolsconfigschema)
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

    private fun parseSlackconfig(json: JSONObject): Slackchannelconfig {
        // 兼容oldField token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return Slackchannelconfig(
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

    private fun parseTelegramconfig(json: JSONObject): Telegramchannelconfig {
        // 兼容oldField token → botToken
        val botToken = json.optString("botToken", "").ifEmpty { json.optString("token", "") }
        return Telegramchannelconfig(
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

    private fun parsewhatsAppconfig(json: JSONObject): whatsAppchannelconfig {
        return whatsAppchannelconfig(
            enabled = json.optBoolean("enabled", false),
            phoneNumber = json.optString("phoneNumber", ""),
            dmPolicy = json.optString("dmPolicy", "open"),
            groupPolicy = json.optString("groupPolicy", "open"),
            requireMention = json.optBoolean("requireMention", true),
            historyLimit = if (json.has("historyLimit")) json.optInt("historyLimit") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseSignalconfig(json: JSONObject): Signalchannelconfig {
        // 兼容 OpenClaw account Field → phoneNumber
        val phoneNumber = json.optString("phoneNumber", "").ifEmpty { json.optString("account", "") }
        return Signalchannelconfig(
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

    private fun parseWeixinconfig(json: JSONObject): Weixinchannelconfig {
        return Weixinchannelconfig(
            enabled = json.optBoolean("enabled", false),
            baseUrl = json.optString("baseUrl", "https://ilinkai.weixin.qq.com")
                .ifEmpty { "https://ilinkai.weixin.qq.com" },
            cdnBaseUrl = json.optString("cdnBaseUrl", "https://novac2c.cdn.weixin.qq.com/c2c")
                .ifEmpty { "https://novac2c.cdn.weixin.qq.com/c2c" },
            routeTag = if (json.has("routeTag")) json.optString("routeTag") else null,
            model = if (json.has("model")) json.optString("model") else null
        )
    }

    private fun parseskillsconfig(json: JSONObject): skillsconfig {
        val entries = json.optJSONObject("entries")?.let { e ->
            val map = mutableMapOf<String, skillconfig>()
            e.keys().forEach { key ->
                e.optJSONObject(key)?.let { sc ->
                    map[key] = skillconfig(
                        enabled = sc.optBoolean("enabled", true),
                        apiKey = if (sc.has("apiKey")) sc.get("apiKey") else null,
                        env = sc.optJSONObject("env")?.let { envObj ->
                            envObj.keys().asSequence().associatewith { envObj.optString(it, "") }
                        }
                    )
                }
            }
            map
        } ?: emptyMap()

        val extraDirs = json.optJSONArray("extraDirs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return skillsconfig(
            allowBundled = json.optJSONArray("allowBundled")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            extraDirs = extraDirs,
            watch = json.optBoolean("watch", true),
            watchDebounceMs = json.optLong("watchDebounceMs", 250),
            entries = entries
        )
    }

    private fun parsePluginsconfig(json: JSONObject): Pluginsconfig {
        val entriesJson = json.optJSONObject("entries") ?: return Pluginsconfig()
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
        return Pluginsconfig(entries = map)
    }

    private fun parsetoolsconfig(json: JSONObject): toolsconfig {
        val ssJson = json.optJSONObject("screenshot")
        val screenshot = ssJson?.let {
            Screenshottoolconfig(
                enabled = it.optBoolean("enabled", true),
                quality = it.optInt("quality", 85),
                maxWidth = it.optInt("maxWidth", 1080),
                format = it.optString("format", "jpeg")
            )
        } ?: Screenshottoolconfig()

        return toolsconfig(screenshot = screenshot)
    }

    private fun parseThinkingconfig(json: JSONObject): Thinkingconfig {
        return Thinkingconfig(
            enabled = json.optBoolean("enabled", true),
            budgetTokens = json.optInt("budgetTokens", 10000)
        )
    }

    private fun parseUIconfig(json: JSONObject): UIconfig {
        return UIconfig(
            theme = json.optString("theme", "auto"),
            language = json.optString("language", "zh")
        )
    }

    private fun parseLoggingconfig(json: JSONObject): Loggingconfig {
        return Loggingconfig(
            level = json.optString("level", "INFO"),
            logToFile = json.optBoolean("logToFile", true)
        )
    }

    private fun parseMemoryconfig(json: JSONObject): Memoryconfig {
        return Memoryconfig(
            enabled = json.optBoolean("enabled", true),
            path = json.optString("path", StoragePaths.workspaceMemory.absolutePath)
        )
    }

    private fun parsesessionconfig(json: JSONObject): sessionconfig {
        return sessionconfig(
            maxMessages = json.optInt("maxMessages", 100),
            maxAgeDays = json.optInt("maxAgeDays", 30),
            maxEntries = json.optInt("maxEntries", 500),
            maxDiskBytes = json.optLong("maxDiskBytes", 100_000_000L),
            highWaterRatio = json.optDouble("highWaterRatio", 0.8).toFloat()
        )
    }

    // ============ Public API ============

    fun getproviderconfig(providerName: String): providerconfig? {
        return loadOpenClawconfigFresh().resolveproviders()[providerName]
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
     * 强制fromDiskReadconfig, IgnoreCache. 
     * 用于 LLM RequestPath, EnsureGetmostnewconfig(避免跨 configLoader InstanceCachenotone致). 
     */
    fun loadOpenClawconfigFresh(): OpenClawconfig {
        openclawconfigCacheValid = false
        return loadOpenClawconfig()
    }

    /**
     * Saveconfig - 用 JSONObject Serialize(notDependency Gson)
     */
    fun saveOpenClawconfig(config: OpenClawconfig): Boolean {
        return try {
            ensureconfigDir()
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
     * will config Object关KeyFieldWrite JSONObject(保留files中Its他Field)
     */
    private fun mergeconfigToJson(root: JSONObject, config: OpenClawconfig) {
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
            // OpenClaw use account Field存手机号
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

        // Gateway (Aligned with OpenClaw: 只Has port/mode/bind/auth)
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
                        Log.i(TAG, "Detectedconfigfiles变化")
                        val newconfig = reloadOpenClawconfig()
                        reloadCallback?.invoke(newconfig)
                    }
                }
            }
            fileObserver?.startWatching()
            hotReloadEnabled = true
            Log.i(TAG, "[OK] config热overloadalreadyEnable")
        } catch (e: exception) {
            Log.e(TAG, "Enable热overloadFailed", e)
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
     * 写 JSON tofiles, 统one UTF-8 Encode并go掉many余 \/ 转义
     */
    private fun writeJsonToFile(file: File, json: JSONObject, indent: Int = 4) {
        val text = json.toString(indent).replace("\\/", "/")
        file.writeText(text, Charsets.UTF_8)
    }

    private fun ensureconfigDir() {
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
     * already知EnvironmentVariable名 → provider ID Map
     * come源: OpenClaw PROVIDER_ENV_API_KEY_CANDIDATES
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
                    Log.i(TAG, "[KEY] useinside置 Key Replace: \${$varName}")
                } else {
                    val providerId = ENV_VAR_TO_PROVIDER[varName]
                    if (providerId != null) {
                        unresolvedKnown.a(varName)
                        Log.w(TAG, "[WARN] EnvironmentVariable \${$varName} (provider: $providerId) notSettings. " +
                            "pleaseinconfig中直接填入 API Key. ")
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
     * 精简validation — 只Check关KeyField
     */
    private fun validateconfig(config: OpenClawconfig) {
        // validation feishu if enabled
        val feishu = config.channels.feishu
        if (feishu.enabled) {
            require(feishu.appId.isnotBlank() && !feishu.appId.startswith("\${")) {
                "Feishu alreadyEnablebut appId not configured"
            }
            require(feishu.appSecret.isnotBlank() && !feishu.appSecret.startswith("\${")) {
                "Feishu alreadyEnablebut appSecret not configured"
            }
        }

        // validation providers have baseUrl and apiKey (for key-required providers)
        config.resolveproviders().forEach { (name, provider) ->
            require(provider.baseUrl.isnotBlank()) {
                "provider '$name' 缺few baseUrl"
            }
            val def = providerRegistry.findById(name)
            if (def?.keyRequired == true && provider.apiKey.isNullorBlank()) {
                Log.w(TAG, "[WARN] provider '${def.name}' need API Key butnot configured. " +
                    "pleaseinSettings中填入 API Key, NothenRequestwillReturn 401. ")
            }
        }

        Log.i(TAG, "[OK] configvalidationthrough")
    }
}
