package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


/**
 * 飞书Config
 * Aligned with OpenClaw feishu plugin Config结构
 */
data class FeishuConfig(
    // ===== 基础Config =====
    val enabled: Boolean = false,
    val appId: String,
    val appSecret: String,
    val encryptKey: String? = null,
    val verificationToken: String? = null,

    // ===== 域名Config =====
    val domain: String = "feishu", // "feishu", "lark", or custom domain

    // ===== ConnectSchema =====
    val connectionMode: ConnectionMode = ConnectionMode.WEBSOCKET,
    val webhookPath: String = "/feishu/webhook",
    val webhookPort: Int = 8765,

    // ===== DM Policy =====
    val dmPolicy: DmPolicy = DmPolicy.PAIRING,
    val allowFrom: List<String> = emptyList(),

    // ===== GroupPolicy =====
    val groupPolicy: GroupPolicy = GroupPolicy.ALLOWLIST,
    val groupAllowFrom: List<String> = emptyList(),
    val requireMention: Boolean? = null, // null = use groupPolicy-based default (open→false, else→true)
    val groupCommandMentionBypass: MentionBypass = MentionBypass.NEVER,
    val allowMentionlessInMultiBotGroup: Boolean = false,

    // ===== SessionSchema =====
    val groupSessionScope: String? = null, // "per-user" = isolate per sender in groups
    val topicSessionMode: TopicSessionMode = TopicSessionMode.DISABLED,

    // ===== 历史Record =====
    val historyLimit: Int = 20,
    val dmHistoryLimit: Int = 10,

    // ===== Message分块 =====
    val textChunkLimit: Int = 4000,
    val chunkMode: ChunkMode = ChunkMode.LENGTH,
    val maxTablesPerCard: Int = 3,  // Max tables supported by Feishu card (according to API Limit)

    // ===== 媒体Config =====
    val mediaMaxMb: Double = 20.0,
    val audioMaxDurationSec: Int = 300,

    // ===== 工具Config =====
    val enableDocTools: Boolean = true,
    val enableWikiTools: Boolean = true,
    val enableDriveTools: Boolean = true,
    val enableBitableTools: Boolean = true,
    val enableTaskTools: Boolean = true,
    val enableChatTools: Boolean = true,
    val enablePermTools: Boolean = true,
    val enableUrgentTools: Boolean = true,
    val enableSheetTools: Boolean = true,
    val enableCalendarTools: Boolean = true,
    val enableImTools: Boolean = true,
    val enableSearchTools: Boolean = true,
    val enableCommonTools: Boolean = true,

    // ===== Its他Config =====
    val typingIndicator: Boolean = true,
    val reactionDedup: Boolean = true,
    val debugMode: Boolean = false
) {
    enum class ConnectionMode {
        WEBSOCKET, WEBHOOK
    }

    enum class DmPolicy {
        OPEN, PAIRING, ALLOWLIST
    }

    enum class GroupPolicy {
        OPEN, ALLOWLIST, DISABLED
    }

    enum class MentionBypass {
        NEVER, SINGLE_BOT, ALWAYS
    }

    enum class TopicSessionMode {
        DISABLED, ENABLED
    }

    enum class ChunkMode {
        LENGTH, NEWLINE
    }

    /**
     * Get API 基础 URL
     */
    fun getApiBaseUrl(): String {
        return when (domain.lowercase()) {
            "feishu" -> "https://open.feishu.cn"
            "lark" -> "https://open.larksuite.com"
            else -> {
                // Custom domain: EnsureHas https:// Front缀
                if (domain.startsWith("http://") || domain.startsWith("https://")) {
                    domain
                } else {
                    "https://$domain"
                }
            }
        }
    }

    /**
     * ValidateConfig
     */
    fun validate(): result<Unit> {
        if (appId.isBlank()) {
            return result.failure(IllegalArgumentException("appId is required"))
        }
        if (appSecret.isBlank()) {
            return result.failure(IllegalArgumentException("appSecret is required"))
        }
        if (connectionMode == ConnectionMode.WEBHOOK && verificationToken.isNullOrBlank()) {
            return result.failure(IllegalArgumentException("verificationToken is required for webhook mode"))
        }
        return result.success(Unit)
    }
}
