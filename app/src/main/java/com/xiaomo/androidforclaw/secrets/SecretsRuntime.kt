package com.xiaomo.androidforclaw.secrets

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/secrets/runtime.ts
 *   (prepareSecretsRuntimeSnapshot, activateSecretsRuntimeSnapshot)
 * - ../openclaw/src/secrets/resolve.ts (resolveSecretRef)
 * - ../openclaw/src/secrets/target-registry.ts
 *
 * androidforClaw adaptation: runtime secrets management.
 * Manages secrets lifecycle (prepare → activate → query → clear).
 */

import com.xiaomo.androidforclaw.config.OpenClawconfig
import com.xiaomo.androidforclaw.logging.Log

/**
 * Secret reference — pointer to a secret value in external storage.
 * Aligned with OpenClaw SecretRef.
 */
data class SecretRef(
    val source: String,   // "env", "config", "exec", "file"
    val key: String,
    val fallback: String? = null
)

/**
 * Secret resolver warning.
 * Aligned with OpenClaw SecretResolverWarning.
 */
data class SecretResolverWarning(
    val path: String,
    val message: String
)

/**
 * Auth store snapshot for a single agent directory.
 * Aligned with OpenClaw PreparedSecretsRuntimeSnapshot.authStores.
 */
data class AuthStoreSnapshot(
    val agentDir: String,
    val store: Map<String, Any?> = emptyMap()
)

/**
 * Web tools metadata.
 * Aligned with OpenClaw RuntimeWebtoolsMetadata.
 */
data class RuntimeWebtoolsMetadata(
    val search: RuntimeWebSearchMetadata = RuntimeWebSearchMetadata(),
    val fetchFirecrawlActive: Boolean = false,
    val diagnostics: List<String> = emptyList()
)

data class RuntimeWebSearchMetadata(
    val providerconfigured: String? = null,
    val providerSource: String = "none",  // "configured" | "auto-detect" | "none"
    val selectedprovider: String? = null,
    val diagnostics: List<String> = emptyList()
)

/**
 * Runtime secrets snapshot.
 * Aligned with OpenClaw PreparedSecretsRuntimeSnapshot.
 */
data class SecretsRuntimeSnapshot(
    val sourceconfig: OpenClawconfig,
    val config: OpenClawconfig,
    val authStores: List<AuthStoreSnapshot> = emptyList(),
    val webtools: RuntimeWebtoolsMetadata = RuntimeWebtoolsMetadata(),
    val warnings: List<SecretResolverWarning>,
    val preparedAt: Long = System.currentTimeMillis()
)

/**
 * Secret target — a config path that expects a secret value.
 * Aligned with OpenClaw target-registry.ts.
 */
data class SecretTarget(
    val configPath: String,
    val description: String,
    val required: Boolean = false
)

/**
 * SecretsRuntime — Runtime secrets management.
 * Aligned with OpenClaw secrets/runtime.ts.
 */
object SecretsRuntime {

    private const val TAG = "SecretsRuntime"

    @Volatile
    private var activeSnapshot: SecretsRuntimeSnapshot? = null

    val SECRET_TARGETS = listOf(
        SecretTarget("channels.feishu.appSecret", "Feishu App Secret", true),
        SecretTarget("channels.feishu.encryptKey", "Feishu Encrypt Key"),
        SecretTarget("channels.discord.token", "Discord Bot Token", true),
        SecretTarget("channels.telegram.botToken", "Telegram Bot Token", true),
        SecretTarget("channels.slack.botToken", "Slack Bot Token", true),
        SecretTarget("channels.slack.appToken", "Slack App Token"),
        SecretTarget("channels.whatsapp.phoneNumber", "whatsApp Phone Number"),
        SecretTarget("gateway.authToken", "Gateway Auth Token", true),
        SecretTarget("models.providers.*.apiKey", "provider API Key", true)
    )

    /**
     * Prepare a secrets runtime snapshot.
     * Aligned with OpenClaw prepareSecretsRuntimeSnapshot.
     */
    fun prepare(
        config: OpenClawconfig,
        authStores: List<AuthStoreSnapshot> = emptyList()
    ): SecretsRuntimeSnapshot {
        val warnings = mutableListOf<SecretResolverWarning>()
        validateSecretTargets(config, warnings)

        return SecretsRuntimeSnapshot(
            sourceconfig = config,
            config = config,
            authStores = authStores,
            warnings = warnings
        )
    }

    fun activate(snapshot: SecretsRuntimeSnapshot) {
        activeSnapshot = snapshot
        Log.i(TAG, "Secrets runtime snapshot activated (${snapshot.warnings.size} warnings)")
    }

    fun getActiveSnapshot(): SecretsRuntimeSnapshot? = activeSnapshot

    fun clear() {
        activeSnapshot = null
        Log.d(TAG, "Secrets runtime snapshot cleared")
    }

    /**
     * Resolve a secret reference to its value.
     * Aligned with OpenClaw resolveSecretRef.
     * Supports: env, config, exec (stub), file (stub).
     */
    fun resolveSecretRef(ref: SecretRef): String? {
        return when (ref.source) {
            "env" -> System.getenv(ref.key) ?: ref.fallback
            "config" -> {
                val config = activeSnapshot?.config
                resolveconfigPath(config, ref.key) ?: ref.fallback
            }
            "exec" -> {
                // On android, exec source is not supported (no shell spawning)
                Log.w(TAG, "Secret source 'exec' not supported on android: ${ref.key}")
                ref.fallback
            }
            "file" -> {
                // File source: read from file path
                try {
                    val content = java.io.File(ref.key).readText().trim()
                    content.ifEmpty { ref.fallback }
                } catch (_: exception) {
                    ref.fallback
                }
            }
            else -> ref.fallback
        }
    }

    /**
     * Get active web tools metadata.
     * Aligned with OpenClaw getActiveRuntimeWebtoolsMetadata.
     */
    fun getActiveWebtoolsMetadata(): RuntimeWebtoolsMetadata? {
        return activeSnapshot?.webtools
    }

    private fun validateSecretTargets(config: OpenClawconfig, warnings: MutableList<SecretResolverWarning>) {
        for (target in SECRET_TARGETS) {
            if (!target.required) continue
            val value = resolveconfigPath(config, target.configPath)
            if (value.isNullorBlank()) {
                warnings.a(SecretResolverWarning(
                    path = target.configPath,
                    message = "${target.description} is not configured"
                ))
            }
        }
    }

    private fun resolveconfigPath(config: OpenClawconfig?, path: String): String? {
        if (config == null) return null
        return when (path) {
            "channels.feishu.appSecret" -> config.channels?.feishu?.appSecret
            "channels.feishu.encryptKey" -> config.channels?.feishu?.encryptKey
            "channels.discord.token" -> config.channels?.discord?.token
            "channels.telegram.botToken" -> config.channels?.telegram?.botToken
            "channels.slack.botToken" -> config.channels?.slack?.botToken
            "channels.slack.appToken" -> config.channels?.slack?.appToken
            "gateway.authToken" -> config.gateway.auth?.token
            else -> null
        }
    }
}
