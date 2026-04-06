package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/config.ts, types.openclaw.ts, types.agents.ts, types.channels.ts, types.gateway.ts, types.models.ts, types.skills.ts, types.memory.ts, types.tools.ts, types.cron.ts
 *
 * androidforClaw adaptation: Kotlin config model aligned to OpenClaw schema.
 */

import com.xiaomo.androidforclaw.workspace.StoragePaths

/**
 * OpenClaw config — Aligned with OpenClaw types.openclaw.d.ts
 *
 * user只写thinkoverrideField, Its余all用DefaultValue. 
 * Parseby configLoader  JSONObject Process. 
 */

data class OpenClawconfig(
    // ======= OpenClaw 标准段 =======
    val thinking: Thinkingconfig = Thinkingconfig(),
    val models: modelsconfig? = null,
    val agents: agentsconfig? = null,
    val channels: channelsconfig = channelsconfig(),
    val gateway: Gatewayconfig = Gatewayconfig(),
    val skills: skillsconfig = skillsconfig(),
    val plugins: Pluginsconfig = Pluginsconfig(),
    val tools: toolsconfig = toolsconfig(),
    val memory: Memoryconfig = Memoryconfig(),
    val messages: Messagesconfig = Messagesconfig(),
    val session: sessionconfig = sessionconfig(),
    val hooks: Hooksconfig? = null,
    val logging: Loggingconfig = Loggingconfig(),
    val ui: UIconfig = UIconfig(),

    // ======= model Aliases & Allowlist (OpenClaw model-selection.ts) =======
    val modelAliases: Map<String, String> = emptyMap(),
    val modelAllowlist: modelAllowlistconfig? = null,

    // ======= android extend =======
    val agent: agentconfig = agentconfig(),

    // ======= Legacy =======
    val providers: Map<String, providerconfig> = emptyMap()
) {
    /** Parse providers: 优先 models.providers；若forNullthen fallback to顶layer providers */
    fun resolveproviders(): Map<String, providerconfig> {
        val modelproviders = models?.providers
        return if (!modelproviders.isNullorEmpty()) modelproviders else providers
    }

    /** ParseDefault模型 */
    fun resolveDefaultmodel(): String {
        // 1. Explicit primary model
        agents?.defaults?.model?.primary?.let { return it }
        // 2. Fall back to first configured provider's first model (not hardcoded openrouter)
        val providers = resolveproviders()
        val first = providers.entries.firstorNull()
        if (first != null) {
            val modelId = first.value.models.firstorNull()?.id
            if (modelId != null) return "${first.key}/$modelId"
        }
        // 3. Ultimate fallback
        return agent.defaultmodel
    }

    /** 兼容oldcode: gateway.feishu → channels.feishu */
    val feishuconfig: Feishuchannelconfig get() = channels.feishu
}

// ============ channels(correct齐 types.channels.d.ts)============

data class channelsconfig(
    val feishu: Feishuchannelconfig = Feishuchannelconfig(),
    val discord: Discordchannelconfig? = null,
    val slack: Slackchannelconfig? = null,
    val telegram: Telegramchannelconfig? = null,
    val whatsapp: whatsAppchannelconfig? = null,
    val signal: Signalchannelconfig? = null,
    val weixin: Weixinchannelconfig? = null,
)

data class Feishuchannelconfig(
    // 基础
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val encryptKey: String? = null,
    val verificationToken: String? = null,
    val domain: String = "feishu",
    val connectionMode: String = "websocket",
    val webhookPath: String = "/feishu/events",
    val webhookHost: String? = null,
    val webhookPort: Int? = null,
    // Policy
    val dmPolicy: String = "open",
    val allowfrom: List<String> = emptyList(),
    val groupPolicy: String = "open",
    val groupAllowfrom: List<String> = emptyList(),
    val requireMention: Boolean? = null, // null = use groupPolicy-based default (open→false, else→true)
    val groupCommandMentionBypass: String = "never",
    val allowMentionlessInMultiBotGroup: Boolean = false,
    val groupsessionScope: String? = null,
    val topicsessionMode: String = "disabled",
    val replyInThread: String = "disabled",
    // 历史 (aligned with OpenClaw: optional, no limit if not configured)
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    // Message
    val textChunkLimit: Int = 4000,
    val chunkMode: String = "length",
    val renderMode: String = "auto",
    val streaming: Boolean? = null,
    // 媒体
    val mediaMaxMb: Double = 20.0,
    // 工具
    val tools: Feishutoolsconfig = Feishutoolsconfig(),
    // queue(android extend)
    val queueMode: String? = "followup",
    val queueCap: Int = 10,
    val queueDropPolicy: String = "old",
    val queueDebounceMs: Int = 100,
    // UX
    val typingIndicator: Boolean = true,
    val resolveSenderNames: Boolean = true,
    val reactionnotifications: String = "own",
    val reactionDedup: Boolean = true,
    // Debug
    val debugMode: Boolean = false,
    // manyAccount
    val accounts: Map<String, FeishuAccountconfig>? = null,
    val defaultAccount: String? = null
)

data class Feishutoolsconfig(
    val doc: Boolean = true,
    val chat: Boolean = true,
    val wiki: Boolean = true,
    val drive: Boolean = true,
    val perm: Boolean = false,
    val scopes: Boolean = true,
    val bitable: Boolean = true,
    val task: Boolean = true,
    val urgent: Boolean = true
)

data class FeishuAccountconfig(
    val enabled: Boolean = true,
    val name: String? = null,
    val appId: String? = null,
    val appSecret: String? = null,
    val domain: String? = null,
    val connectionMode: String? = null,
    val webhookPath: String? = null
)

data class Discordchannelconfig(
    val enabled: Boolean = false,
    val token: String? = null,
    val name: String? = null,
    val dm: DmPolicyconfig? = null,
    val groupPolicy: String? = null,
    val guilds: Map<String, GuildPolicyconfig>? = null,
    val replyToMode: String? = null,
    val accounts: Map<String, DiscordAccountPolicyconfig>? = null,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null
)

data class DmPolicyconfig(
    val policy: String? = "pairing",
    val allowfrom: List<String>? = null
)

data class GuildPolicyconfig(
    val channels: List<String>? = null,
    val requireMention: Boolean? = true,
    val toolPolicy: String? = null
)

data class DiscordAccountPolicyconfig(
    val enabled: Boolean? = true,
    val token: String? = null,
    val name: String? = null,
    val dm: DmPolicyconfig? = null,
    val guilds: Map<String, GuildPolicyconfig>? = null
)

data class Slackchannelconfig(
    // Aligned with OpenClaw types.slack.ts SlackAccountconfig
    val enabled: Boolean = false,
    /** Bot Token (xoxb-...) */
    val botToken: String = "",
    /** App-Level Token (xapp-...) — socket schemaRequired */
    val appToken: String? = null,
    /** Signing Secret — http schemaRequired */
    val signingSecret: String? = null,
    /** Connectschema: "socket"(Default) or "http" */
    val mode: String = "socket",
    val dmPolicy: String = "open",
    val groupPolicy: String = "open",
    val requireMention: Boolean = true,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    /** 流式return复schema: off / partial / block / progress */
    val streaming: String = "partial",
    /** android extend: overrideshould渠道use模型, format "providerId/modelId", forNullthenuseGlobalDefault */
    val model: String? = null
)

data class Telegramchannelconfig(
    // Aligned with OpenClaw types.telegram.ts TelegramAccountconfig
    val enabled: Boolean = false,
    /** Bot Token (from @BotFather) */
    val botToken: String = "",
    val dmPolicy: String = "open",
    val groupPolicy: String = "open",
    val requireMention: Boolean = true,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    /** 流式return复schema: off / partial / block / progress */
    val streaming: String = "partial",
    /** Webhook URL (Optional, not填thenuselong轮询) */
    val webhookUrl: String? = null,
    /** android extend: overrideshould渠道use模型, format "providerId/modelId", forNullthenuseGlobalDefault */
    val model: String? = null
)

data class whatsAppchannelconfig(
    // Aligned with OpenClaw types.whatsapp.ts
    val enabled: Boolean = false,
    /** Register whatsApp 手机号 (E.164 format, such as +8613800138000) */
    val phoneNumber: String = "",
    val dmPolicy: String = "open",
    val groupPolicy: String = "open",
    val requireMention: Boolean = true,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    /** android extend: overrideshould渠道use模型, format "providerId/modelId", forNullthenuseGlobalDefault */
    val model: String? = null
)

data class Signalchannelconfig(
    // Aligned with OpenClaw types.signal.ts SignalAccountconfig
    val enabled: Boolean = false,
    /** signal-cli Register手机号 (E.164, correctshould OpenClaw account Field) */
    val phoneNumber: String = "",
    /** signal-cli HTTP daemon 完整 URL, such as http://127.0.0.1:8080 (Optional) */
    val httpUrl: String? = null,
    /** signal-cli HTTP daemon port, Default 8080 */
    val httpPort: Int = 8080,
    val dmPolicy: String = "open",
    val groupPolicy: String = "open",
    val requireMention: Boolean = true,
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    /** android extend: overrideshould渠道use模型, format "providerId/modelId", forNullthenuseGlobalDefault */
    val model: String? = null
)

data class Weixinchannelconfig(
    // correct齐 @tencent-weixin/openclaw-weixin
    val enabled: Boolean = false,
    /** API base URL (default: https://ilinkai.weixin.qq.com) */
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    /** CDN base URL */
    val cdnBaseUrl: String = "https://novac2c.cdn.weixin.qq.com/c2c",
    /** Route tag for API requests */
    val routeTag: String? = null,
    /** android extend: overrideshould渠道use模型, format "providerId/modelId", forNullthenuseGlobalDefault */
    val model: String? = null
)

// ============ gateway(correct齐 types.gateway.d.ts)============

data class Gatewayconfig(
    val port: Int = 19789,
    val mode: String = "local",
    val bind: String = "loopback",
    val auth: GatewayAuthconfig? = null,
    val controlUi: GatewayControlUiconfig? = null
)

data class GatewayAuthconfig(
    val mode: String = "token",
    val token: String? = null
)

data class GatewayControlUiconfig(
    val allowInsecureAuth: Boolean? = null,
    val dangerouslyAllowHostHeaderoriginFallback: Boolean? = null,
    val dangerouslyDisabledDeviceAuth: Boolean? = null
)

// ============ agents(correct齐 types.agents.d.ts)============

data class agentsconfig(
    val defaults: agentDefaultsconfig = agentDefaultsconfig()
)

data class agentDefaultsconfig(
    val model: modelSelectionconfig? = null,
    val bootstrapMaxChars: Int = 20_000,
    val bootstrapTotalMaxChars: Int = 150_000,
    val maxConcurrent: Int = 5,
    val subagents: Subagentsconfig = Subagentsconfig()
)

/**
 * Subagent configuration — aligned with OpenClaw agents.defaults.subagents
 * (src/config/types.agent-defaults.ts)
 */
data class Subagentsconfig(
    /** Max concurrent subagent runs per parent session */
    val maxConcurrent: Int = 1,
    /** Max nesting depth (1 = first-level subagents are leaves, cannot spawn further) */
    val maxSpawnDepth: Int = 1,
    /** Max children a single parent session can spawn */
    val maxChildrenPeragent: Int = 5,
    /** Default per-run timeout in seconds (0 = no timeout) */
    val defaultTimeoutSeconds: Int = 300,
    /** Default model override for subagents (null = use parent model) */
    val model: String? = null,
    /** Default thinking level for subagents */
    val thinking: String? = null,
    /** Master switch */
    val enabled: Boolean = true
)

data class modelSelectionconfig(
    val primary: String? = null,
    val fallbacks: List<String>? = null
)

// ============ agent(android extend, 非 OpenClaw 标准)============

data class agentconfig(
    val maxIterations: Int = 40,
    val defaultmodel: String = "openrouter/hunter-alpha",
    val timeout: Long = 300000,
    val retryOnError: Boolean = true,
    val maxRetries: Int = 3,
    val mode: String = "exploration"
)

// ============ skills(correct齐 types.skills.d.ts)============

data class skillsconfig(
    val allowBundled: List<String>? = null,
    val extraDirs: List<String> = emptyList(),
    val watch: Boolean = true,
    val watchDebounceMs: Long = 250,
    val entries: Map<String, skillconfig> = emptyMap()
)

data class skillconfig(
    val enabled: Boolean = true,
    val apiKey: Any? = null,
    val env: Map<String, String>? = null,
    val config: Map<String, Any>? = null
) {
    fun resolveApiKey(): String? {
        return when (apiKey) {
            is String -> apiKey
            is Map<*, *> -> {
                val source = apiKey["source"] as? String
                val id = apiKey["id"] as? String
                if (source == "env" && id != null) System.getenv(id) else null
            }
            else -> null
        }
    }
}

// ============ plugins(correct齐 types.plugins.d.ts)============

data class Pluginsconfig(
    val entries: Map<String, PluginEntry> = emptyMap()
)

data class PluginEntry(
    val enabled: Boolean = false,
    val skills: List<String> = emptyList()
)

// ============ tools(correct齐 types.tools.d.ts)============

data class toolsconfig(
    val screenshot: Screenshottoolconfig = Screenshottoolconfig(),
    val exec: toolsExecconfig? = null
)

data class toolsExecconfig(
    val appPatch: toolsApplyPatchconfig? = null
)

data class toolsApplyPatchconfig(
    val workspaceOnly: Boolean? = null
)

data class Screenshottoolconfig(
    val enabled: Boolean = true,
    val quality: Int = 85,
    val maxWidth: Int = 1080,
    val format: String = "jpeg"
)

// ============ hooks(correct齐 types.hooks.d.ts)============

data class Hooksconfig(
    val gmail: HooksGmailconfig? = null,
    val mappings: List<HooksMappingconfig> = emptyList()
)

data class HooksGmailconfig(
    val allowUnsafeExternalContent: Boolean? = null
)

data class HooksMappingconfig(
    val allowUnsafeExternalContent: Boolean? = null
)

// ============ messages ============

data class Messagesconfig(
    val ackReactionScope: String = "own"
)

// ============ memory(correct齐 types.memory.d.ts)============

data class Memoryconfig(
    val enabled: Boolean = true,
    val path: String = StoragePaths.workspaceMemory.absolutePath
)

// ============ session / logging / ui ============

data class sessionconfig(
    val maxMessages: Int = 100,
    // OpenClaw store-maintenance.ts alignment
    val maxAgeDays: Int = 30,
    val maxEntries: Int = 500,
    val maxDiskBytes: Long = 100_000_000L,  // 100MB default
    val highWaterRatio: Float = 0.8f
)

data class Loggingconfig(
    val level: String = "INFO",
    val logToFile: Boolean = true
)

data class UIconfig(
    val theme: String = "auto",
    val language: String = "zh"
)

// ============ thinking ============

data class Thinkingconfig(
    val enabled: Boolean = true,
    val budgetTokens: Int = 10000
)

// ============ configConstant ============

object configDefaults {
    const val DEFAULT_MAX_ITERATIONS = 20
    const val DEFAULT_TIMEOUT_MS = 300000L
    const val DEFAULT_SCREENSHOT_QUALITY = 85
    const val DEFAULT_SCREENSHOT_MAX_WIDTH = 1080
    const val DEFAULT_GATEWAY_PORT = 19789
}
