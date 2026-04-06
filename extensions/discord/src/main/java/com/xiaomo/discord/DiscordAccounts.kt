/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log

/**
 * Discord 多AccountManage
 * 参考 OpenClaw Discord 和 Feishu FeishuAccounts.kt
 */
object DiscordAccounts {
    private const val TAG = "DiscordAccounts"

    /**
     * Parse Discord AccountConfig
     * Support多Account和DefaultAccount
     */
    fun resolveAccount(config: DiscordConfig, accountId: String? = null): DiscordAccount {
        val targetAccountId = accountId ?: DiscordConfig.DEFAULT_ACCOUNT_ID

        // ifYesDefaultAccount, Return主Config
        if (targetAccountId == DiscordConfig.DEFAULT_ACCOUNT_ID) {
            val token = config.token ?: throw IllegalArgumentException("Discord token is required")
            return DiscordAccount(
                accountId = DiscordConfig.DEFAULT_ACCOUNT_ID,
                token = token,
                name = config.name,
                config = config,
                enabled = config.enabled
            )
        }

        // Find指定Account
        val accountConfig = config.accounts?.get(targetAccountId)
            ?: throw IllegalArgumentException("Discord account not found: $targetAccountId")

        // MergeConfig (AccountConfig优先)
        val mergedConfig = mergeAccountConfig(config, accountConfig)

        val token = accountConfig.token
            ?: throw IllegalArgumentException("Discord token is required for account: $targetAccountId")

        return DiscordAccount(
            accountId = targetAccountId,
            token = token,
            name = accountConfig.name ?: config.name,
            config = mergedConfig,
            enabled = accountConfig.enabled
        )
    }

    /**
     * MergeAccountConfig和DefaultConfig
     * AccountConfigPriority高于DefaultConfig
     */
    private fun mergeAccountConfig(
        baseConfig: DiscordConfig,
        accountConfig: DiscordConfig.DiscordAccountConfig
    ): DiscordConfig {
        return DiscordConfig(
            enabled = accountConfig.enabled,
            token = accountConfig.token,
            name = accountConfig.name ?: baseConfig.name,
            dm = accountConfig.dm ?: baseConfig.dm,
            groupPolicy = baseConfig.groupPolicy, // 不InheritAccount级别的 groupPolicy
            guilds = accountConfig.guilds ?: baseConfig.guilds,
            replyToMode = baseConfig.replyToMode,
            accounts = null // 不嵌套
        )
    }

    /**
     * ListAllAccount ID
     */
    fun listAccountIds(config: DiscordConfig): List<String> {
        val accountIds = mutableListOf<String>()

        // AddDefaultAccount
        if (config.token != null) {
            accountIds.add(DiscordConfig.DEFAULT_ACCOUNT_ID)
        }

        // Add子Account
        config.accounts?.keys?.forEach { accountId ->
            accountIds.add(accountId)
        }

        return accountIds
    }

    /**
     * ParseDefaultAccount ID
     */
    fun resolveDefaultAccountId(config: DiscordConfig): String {
        return if (config.token != null) {
            DiscordConfig.DEFAULT_ACCOUNT_ID
        } else {
            // ifNoneDefaultAccount, ReturnFirst子Account
            config.accounts?.keys?.firstOrNull() ?: DiscordConfig.DEFAULT_ACCOUNT_ID
        }
    }

    /**
     * CheckAccountYesNo已Config
     */
    fun isAccountConfigured(config: DiscordConfig, accountId: String? = null): Boolean {
        return try {
            val account = resolveAccount(config, accountId)
            account.token.isNotBlank() && account.enabled
        } catch (e: Exception) {
            Log.w(TAG, "Account not configured: $accountId", e)
            false
        }
    }

    /**
     * GetAccountDescriptionInfo
     */
    fun describeAccount(account: DiscordAccount): Map<String, Any?> {
        return mapOf(
            "accountId" to account.accountId,
            "name" to account.name,
            "enabled" to account.enabled,
            "configured" to account.token.isNotBlank(),
            "dmPolicy" to account.config.dm?.policy,
            "groupPolicy" to account.config.groupPolicy
        )
    }

    /**
     * 规范化Account ID
     */
    fun normalizeAccountId(accountId: String?): String {
        return accountId?.takeIf { it.isNotBlank() } ?: DiscordConfig.DEFAULT_ACCOUNT_ID
    }
}
