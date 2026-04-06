package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log

/**
 * 飞书AccountManage
 * Aligned with OpenClaw accounts.ts
 *
 * Feature: 
 * - 多AccountConfigManage
 * - Accountchoose和Parse
 * - 凭证Validate
 */
object FeishuAccounts {
    private const val TAG = "FeishuAccounts"
    private const val DEFAULT_ACCOUNT_ID = "default"

    /**
     * AccountConfig
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
     * 多AccountConfig
     */
    data class MultiAccountConfig(
        val defaultAccount: String? = null,
        val accounts: Map<String, AccountConfig> = emptyMap(),
        val baseConfig: FeishuConfig? = null
    )

    /**
     * Accountchoose来源
     */
    enum class AccountSelectionSource {
        EXPLICIT,           // 明确指定的Account
        EXPLICIT_DEFAULT,   // Config中明确指定的DefaultAccount
        MAPPED_DEFAULT,     // Map到 default Account
        FALLBACK            // Fallback到FirstAccount
    }

    /**
     * ParseBack的Account
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
     * 规范化Account ID
     */
    fun normalizeAccountId(accountId: String?): String {
        return accountId?.trim()?.ifEmpty { DEFAULT_ACCOUNT_ID } ?: DEFAULT_ACCOUNT_ID
    }

    /**
     * ListConfig的AllAccount ID
     */
    fun listAccountIds(config: MultiAccountConfig): List<String> {
        val ids = config.accounts.keys.filter { it.isNotBlank() }
        if (ids.isEmpty()) {
            // 向Back兼容: NoneConfigAccount时Return default
            return listOf(DEFAULT_ACCOUNT_ID)
        }
        return ids.sorted()
    }

    /**
     * ParseDefaultAccountchoose
     */
    fun resolveDefaultAccountSelection(config: MultiAccountConfig): Pair<String, AccountSelectionSource> {
        // 1. 明确指定的DefaultAccount
        val preferred = config.defaultAccount?.trim()
        if (!preferred.isNullOrEmpty()) {
            return Pair(normalizeAccountId(preferred), AccountSelectionSource.EXPLICIT_DEFAULT)
        }

        // 2. Map到 default Account
        val ids = listAccountIds(config)
        if (ids.contains(DEFAULT_ACCOUNT_ID)) {
            return Pair(DEFAULT_ACCOUNT_ID, AccountSelectionSource.MAPPED_DEFAULT)
        }

        // 3. Fallback到FirstAccount
        return Pair(ids.firstOrNull() ?: DEFAULT_ACCOUNT_ID, AccountSelectionSource.FALLBACK)
    }

    /**
     * ParseDefaultAccount ID
     */
    fun resolveDefaultAccountId(config: MultiAccountConfig): String {
        return resolveDefaultAccountSelection(config).first
    }

    /**
     * GetAccount特定Config
     */
    private fun getAccountConfig(
        config: MultiAccountConfig,
        accountId: String
    ): AccountConfig? {
        return config.accounts[accountId]
    }

    /**
     * MergeAccountConfig
     * Account特定ConfigOverride基础Config
     */
    private fun mergeAccountConfig(
        config: MultiAccountConfig,
        accountId: String
    ): FeishuConfig? {
        val base = config.baseConfig
        val account = getAccountConfig(config, accountId)

        // ifNone基础Config, useAccountConfig
        if (base == null) {
            return account?.config
        }

        // ifNoneAccountConfig, use基础Config
        if (account?.config == null) {
            return base
        }

        // MergeConfig(AccountConfig优先)
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
     * Validate凭证
     */
    fun validateCredentials(
        appId: String?,
        appSecret: String?
    ): Boolean {
        return !appId.isNullOrBlank() && !appSecret.isNullOrBlank()
    }

    /**
     * ParseAccount
     *
     * @param config 多AccountConfig
     * @param accountId Account ID(null Table示useDefaultAccount)
     * @return ParseBack的Account
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

        // GetAccountConfig
        val accountConfig = getAccountConfig(config, resolvedAccountId)
        val baseEnabledd = config.baseConfig?.enabled ?: true
        val accountEnabledd = accountConfig?.enabled ?: true
        val enabled = baseEnabledd && accountEnabledd

        // MergeConfig
        val mergedConfig = mergeAccountConfig(config, resolvedAccountId)

        // Validate凭证
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
     * ListAllEnabled且Config完整的Account
     */
    fun listEnableddAccounts(config: MultiAccountConfig): List<ResolvedAccount> {
        return listAccountIds(config)
            .map { accountId -> resolveAccount(config, accountId) }
            .filter { it.enabled && it.configured }
    }

    /**
     * 从单一ConfigCreate多AccountConfig(向Back兼容)
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
