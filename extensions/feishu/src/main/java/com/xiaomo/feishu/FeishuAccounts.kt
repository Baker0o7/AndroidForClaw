package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log

/**
 * Feishu Account Management
 * Aligned with OpenClaw accounts.ts
 *
 * Feature: 
 * - Multi account config management
 * - Account selection and parsing
 * - Credential validation
 */
object FeishuAccounts {
    private const val TAG = "FeishuAccounts"
    private const val DEFAULT_ACCOUNT_ID = "default"

    /**
     * Account Config
     */
    data class AccountConfig(
        val name: String? = null,
        val enabled: Boolean = true,
        val appId: String,
        val appSecret: String,
        val encryptKey: String? = null,
        val verificationToken: String? = null,
        val domain: String = "feishu",
        val config: FeishuConfig? = null
    )

    /**
     * Multi account config
     */
    data class MultiAccountConfig(
        val defaultAccount: String? = null,
        val accounts: Map<String, AccountConfig> = emptyMap(),
        val baseConfig: FeishuConfig? = null
    )

    /**
     * Account selection source
     */
    enum class AccountSelectionSource {
        EXPLICIT,           // Explicitly specified account
        EXPLICIT_DEFAULT,   // Default account explicitly specified in config
        MAPPED_DEFAULT,     // Mapped to default account
        FALLBACK            // Fallback to first account
    }

    /**
     * Parsed back account
     */
    data class ResolvedAccount(
        val accountId: String,
        val selectionSource: AccountSelectionSource,
        val enabled: Boolean,
        val configured: Boolean,
        val name: String? = null,
        val appId: String? = null,
        val appSecret: String? = null,
        val encryptKey: String? = null,
        val verificationToken: String? = null,
        val domain: String = "feishu",
        val config: FeishuConfig? = null
    )

    /**
     * Normalize account ID
     */
    fun normalizeAccountId(accountId: String?): String {
        return accountId?.trim()?.ifEmpty { DEFAULT_ACCOUNT_ID } ?: DEFAULT_ACCOUNT_ID
    }

    /**
     * List all configured account IDs
     */
    fun listAccountIds(config: MultiAccountConfig): List<String> {
        val ids = config.accounts.keys.filter { it.isNotBlank() }
        if (ids.isEmpty()) {
            // Backward compatibility: return default when no accounts configured
            return listOf(DEFAULT_ACCOUNT_ID)
        }
        return ids.sorted()
    }

    /**
     * Parse default account selection
     */
    fun resolveDefaultAccountSelection(config: MultiAccountConfig): Pair<String, AccountSelectionSource> {
        // 1. Explicitly specified default account
        val preferred = config.defaultAccount?.trim()
        if (!preferred.isNullOrEmpty()) {
            return Pair(normalizeAccountId(preferred), AccountSelectionSource.EXPLICIT_DEFAULT)
        }

        // 2. Mapped to default account
        val ids = listAccountIds(config)
        if (ids.contains(DEFAULT_ACCOUNT_ID)) {
            return Pair(DEFAULT_ACCOUNT_ID, AccountSelectionSource.MAPPED_DEFAULT)
        }

        // 3. Fallback to first account
        return Pair(ids.firstOrNull() ?: DEFAULT_ACCOUNT_ID, AccountSelectionSource.FALLBACK)
    }

    /**
     * Parse default account ID
     */
    fun resolveDefaultAccountId(config: MultiAccountConfig): String {
        return resolveDefaultAccountSelection(config).first
    }

    /**
     * Get account specific config
     */
    private fun getAccountConfig(
        config: MultiAccountConfig,
        accountId: String
    ): AccountConfig? {
        return config.accounts[accountId]
    }

    /**
     * Merge account config
     * Account specific config overrides base config
     */
    private fun mergeAccountConfig(
        config: MultiAccountConfig,
        accountId: String
    ): FeishuConfig? {
        val base = config.baseConfig
        val account = getAccountConfig(config, accountId)

        // If no base config, use account config
        if (base == null) {
            return account?.config
        }

        // If no account config, use base config
        if (account?.config == null) {
            return base
        }

        // Merge config (account config takes priority)
        return base.copy(
            enabled = account.config.enabled,
            appId = account.appId,
            appSecret = account.appSecret,
            encryptKey = account.encryptKey ?: base.encryptKey,
            verificationToken = account.verificationToken ?: base.verificationToken,
            domain = account.domain
        )
    }

    /**
     * Validate credentials
     */
    fun validateCredentials(
        appId: String?,
        appSecret: String?
    ): Boolean {
        return !appId.isNullOrBlank() && !appSecret.isNullOrBlank()
    }

    /**
     * Parse account
     *
     * @param config Multi account config
     * @param accountId Account ID (null means use default account)
     * @return Parsed account
     */
    fun resolveAccount(
        config: MultiAccountConfig,
        accountId: String? = null
    ): ResolvedAccount {
        val hasExplicitAccountId = !accountId.isNullOrBlank()

        val defaultSelection = if (hasExplicitAccountId) {
            null
        } else {
            resolveDefaultAccountSelection(config)
        }

        val resolvedAccountId = if (hasExplicitAccountId) {
            normalizeAccountId(accountId)
        } else {
            defaultSelection?.first ?: DEFAULT_ACCOUNT_ID
        }

        val selectionSource = if (hasExplicitAccountId) {
            AccountSelectionSource.EXPLICIT
        } else {
            defaultSelection?.second ?: AccountSelectionSource.FALLBACK
        }

        // Get account config
        val accountConfig = getAccountConfig(config, resolvedAccountId)
        val baseEnabled = config.baseConfig?.enabled ?: true
        val accountEnabled = accountConfig?.enabled ?: true
        val enabled = baseEnabled && accountEnabled

        // Merge config
        val mergedConfig = mergeAccountConfig(config, resolvedAccountId)

        // Validate credentials
        val configured = validateCredentials(accountConfig?.appId, accountConfig?.appSecret)

        Log.d(TAG, "Resolved account: id=$resolvedAccountId, source=$selectionSource, " +
                "enabled=$enabled, configured=$configured")

        return ResolvedAccount(
            accountId = resolvedAccountId,
            selectionSource = selectionSource,
            enabled = enabled,
            configured = configured,
            name = accountConfig?.name,
            appId = accountConfig?.appId,
            appSecret = accountConfig?.appSecret,
            encryptKey = accountConfig?.encryptKey,
            verificationToken = accountConfig?.verificationToken,
            domain = accountConfig?.domain ?: "feishu",
            config = mergedConfig
        )
    }

    /**
     * List all enabled and configured accounts
     */
    fun listEnabledAccounts(config: MultiAccountConfig): List<ResolvedAccount> {
        return listAccountIds(config)
            .map { accountId -> resolveAccount(config, accountId) }
            .filter { it.enabled && it.configured }
    }

    /**
     * Create multi account config from single config (backward compatibility)
     */
    fun fromSingleConfig(config: FeishuConfig): MultiAccountConfig {
        return MultiAccountConfig(
            defaultAccount = DEFAULT_ACCOUNT_ID,
            accounts = mapOf(
                DEFAULT_ACCOUNT_ID to AccountConfig(
                    name = "Default Account",
                    enabled = config.enabled,
                    appId = config.appId,
                    appSecret = config.appSecret,
                    encryptKey = config.encryptKey,
                    verificationToken = config.verificationToken,
                    domain = config.domain,
                    config = config
                )
            ),
            baseConfig = config
        )
    }
}
