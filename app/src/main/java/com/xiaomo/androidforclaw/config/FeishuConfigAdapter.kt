package com.xiaomo.androidforclaw.config

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/channel-config.ts
 */


import com.xiaomo.feishu.Feishuconfig

/**
 * Feishu config Adapter
 *
 * Converts Feishuchannelconfig from OpenClawconfig to Feishuconfig for feishu-channel module
 */
object FeishuconfigAdapter {

    /**
     * Convert from Feishuchannelconfig to Feishuconfig
     */
    fun toFeishuconfig(channelconfig: Feishuchannelconfig): Feishuconfig {
        return Feishuconfig(
            enabled = channelconfig.enabled,
            appId = channelconfig.appId,
            appSecret = channelconfig.appSecret,
            encryptKey = channelconfig.encryptKey,
            verificationToken = channelconfig.verificationToken,
            domain = channelconfig.domain,
            connectionMode = when (channelconfig.connectionMode) {
                "websocket" -> Feishuconfig.ConnectionMode.WEBSOCKET
                "webhook" -> Feishuconfig.ConnectionMode.WEBHOOK
                else -> Feishuconfig.ConnectionMode.WEBSOCKET
            },
            webhookPath = channelconfig.webhookPath,
            webhookPort = channelconfig.webhookPort ?: 8765,
            dmPolicy = when (channelconfig.dmPolicy) {
                "open" -> Feishuconfig.DmPolicy.OPEN
                "pairing" -> Feishuconfig.DmPolicy.PAIRING
                "allowlist" -> Feishuconfig.DmPolicy.ALLOWLIST
                else -> Feishuconfig.DmPolicy.PAIRING
            },
            allowfrom = channelconfig.allowfrom,
            groupPolicy = when (channelconfig.groupPolicy) {
                "open" -> Feishuconfig.GroupPolicy.OPEN
                "allowlist" -> Feishuconfig.GroupPolicy.ALLOWLIST
                "disabled" -> Feishuconfig.GroupPolicy.DISABLED
                else -> Feishuconfig.GroupPolicy.ALLOWLIST
            },
            groupAllowfrom = channelconfig.groupAllowfrom,
            requireMention = channelconfig.requireMention,
            groupCommandMentionBypass = when (channelconfig.groupCommandMentionBypass.lowercase()) {
                "single_bot" -> Feishuconfig.MentionBypass.SINGLE_BOT
                "always" -> Feishuconfig.MentionBypass.ALWAYS
                else -> Feishuconfig.MentionBypass.NEVER
            },
            allowMentionlessInMultiBotGroup = channelconfig.allowMentionlessInMultiBotGroup,
            groupsessionScope = channelconfig.groupsessionScope,
            topicsessionMode = when (channelconfig.topicsessionMode) {
                "enabled" -> Feishuconfig.TopicsessionMode.ENABLED
                "disabled" -> Feishuconfig.TopicsessionMode.DISABLED
                else -> Feishuconfig.TopicsessionMode.DISABLED
            },
            historyLimit = channelconfig.historyLimit ?: 0,
            dmHistoryLimit = channelconfig.dmHistoryLimit ?: 0,
            textChunkLimit = channelconfig.textChunkLimit,
            chunkMode = when (channelconfig.chunkMode) {
                "length" -> Feishuconfig.ChunkMode.LENGTH
                "newline" -> Feishuconfig.ChunkMode.NEWLINE
                else -> Feishuconfig.ChunkMode.LENGTH
            },
            mediaMaxMb = channelconfig.mediaMaxMb,
            audioMaxDurationSec = 300,
            enableDoctools = channelconfig.tools.doc,
            enableWikitools = channelconfig.tools.wiki,
            enableDrivetools = channelconfig.tools.drive,
            enableBitabletools = channelconfig.tools.bitable,
            enableTasktools = channelconfig.tools.task,
            enableChattools = channelconfig.tools.chat,
            enablePermtools = channelconfig.tools.perm,
            enableUrgenttools = channelconfig.tools.urgent,
            typingIndicator = channelconfig.typingIndicator,
            reactionDedup = channelconfig.reactionDedup,
            debugMode = channelconfig.debugMode
        )
    }

    /**
     * Convert from Feishuconfig to Feishuchannelconfig
     */
    fun fromFeishuconfig(feishuconfig: Feishuconfig): Feishuchannelconfig {
        return Feishuchannelconfig(
            enabled = feishuconfig.enabled,
            appId = feishuconfig.appId,
            appSecret = feishuconfig.appSecret,
            encryptKey = feishuconfig.encryptKey,
            verificationToken = feishuconfig.verificationToken,
            domain = feishuconfig.domain,
            connectionMode = when (feishuconfig.connectionMode) {
                Feishuconfig.ConnectionMode.WEBSOCKET -> "websocket"
                Feishuconfig.ConnectionMode.WEBHOOK -> "webhook"
            },
            webhookPath = feishuconfig.webhookPath,
            webhookPort = feishuconfig.webhookPort,
            dmPolicy = when (feishuconfig.dmPolicy) {
                Feishuconfig.DmPolicy.OPEN -> "open"
                Feishuconfig.DmPolicy.PAIRING -> "pairing"
                Feishuconfig.DmPolicy.ALLOWLIST -> "allowlist"
            },
            allowfrom = feishuconfig.allowfrom,
            groupPolicy = when (feishuconfig.groupPolicy) {
                Feishuconfig.GroupPolicy.OPEN -> "open"
                Feishuconfig.GroupPolicy.ALLOWLIST -> "allowlist"
                Feishuconfig.GroupPolicy.DISABLED -> "disabled"
            },
            groupAllowfrom = feishuconfig.groupAllowfrom,
            requireMention = feishuconfig.requireMention,
            groupCommandMentionBypass = when (feishuconfig.groupCommandMentionBypass) {
                Feishuconfig.MentionBypass.SINGLE_BOT -> "single_bot"
                Feishuconfig.MentionBypass.ALWAYS -> "always"
                Feishuconfig.MentionBypass.NEVER -> "never"
            },
            allowMentionlessInMultiBotGroup = feishuconfig.allowMentionlessInMultiBotGroup,
            groupsessionScope = feishuconfig.groupsessionScope,
            topicsessionMode = when (feishuconfig.topicsessionMode) {
                Feishuconfig.TopicsessionMode.ENABLED -> "enabled"
                Feishuconfig.TopicsessionMode.DISABLED -> "disabled"
            },
            historyLimit = feishuconfig.historyLimit,
            dmHistoryLimit = feishuconfig.dmHistoryLimit,
            textChunkLimit = feishuconfig.textChunkLimit,
            chunkMode = when (feishuconfig.chunkMode) {
                Feishuconfig.ChunkMode.LENGTH -> "length"
                Feishuconfig.ChunkMode.NEWLINE -> "newline"
            },
            mediaMaxMb = feishuconfig.mediaMaxMb,
            tools = Feishutoolsconfig(
                doc = feishuconfig.enableDoctools,
                wiki = feishuconfig.enableWikitools,
                drive = feishuconfig.enableDrivetools,
                bitable = feishuconfig.enableBitabletools,
                task = feishuconfig.enableTasktools,
                chat = feishuconfig.enableChattools,
                perm = feishuconfig.enablePermtools,
                urgent = feishuconfig.enableUrgenttools
            ),
            typingIndicator = feishuconfig.typingIndicator,
            reactionDedup = feishuconfig.reactionDedup,
            debugMode = feishuconfig.debugMode
        )
    }
}
