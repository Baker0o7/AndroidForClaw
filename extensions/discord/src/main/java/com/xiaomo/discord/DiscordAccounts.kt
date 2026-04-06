/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log

/**
 * Discord Multi-Account Manager
 * Reference OpenClaw Discord and Feishu FeishuAccounts.kt
 */
object DiscordAccounts {
    private const val TAG = "DiscordAccounts"

    /**
     * Parse Discord Account Config
     * Supports Multiple Accounts and Default Account
     */
    fun resolveAccount(config: DiscordConfig, accountId: String? = null): DiscordAccount {
        val targetAccountId = accountId ?: DiscordConfig.DEFAULT_ACCOUNT_ID

        // If default account, return main config
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

        // Find specified account
        val accountConfig = config.accounts?.get(targetAccountId)
            ?: throw IllegalArgumentException("Discord account not found: $targetAccountId")

        // Merge config (account config takes priority)
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
     * Merge Account Config and Default Config
     * Account Config Priority Higher than Default Config
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
            groupPolicy = baseConfig.groupPolicy, // Do not inherit account-level groupPolicy
            guilds = accountConfig.guilds ?: baseConfig.guilds,
            replyToMode = baseConfig.replyToMode,
            accounts = null // Do not nest
        )
    }

    /**
     * List All Account IDs
     */
    fun listAccountIds(config: DiscordConfig): List<String> {
        val accountIds = mutableListOf<String>()

        // Add Default Account
        if (config.token != null) {
            accountIds.add(DiscordConfig.DEFAULT_ACCOUNT_ID)
        }

        // Add Sub Accounts
        config.accounts?.keys?.forEach { accountId ->
            accountIds.add(accountId)
        }

        return accountIds
    }

    /**
     * Resolve Default Account ID
     */
    fun resolveDefaultAccountId(config: DiscordConfig): String {
        return if (config.token != null) {
            DiscordConfig.DEFAULT_ACCOUNT_ID
        } else {
            // If no default account, return first sub-account
            config.accounts?.keys?.firstOrNull() ?: DiscordConfig.DEFAULT_ACCOUNT_ID
        }
    }

    /**
     * Check if Account is Configured
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
     * Get Account Description Info
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
     * Normalize Account ID
     */
    fun normalizeAccountId(accountId: String?): String {
        return accountId?.takeIf { it.isNotBlank() } ?: DiscordConfig.DEFAULT_ACCOUNT_ID
    }
}
