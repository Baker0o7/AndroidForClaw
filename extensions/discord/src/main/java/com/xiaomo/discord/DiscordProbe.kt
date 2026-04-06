/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Discord ConnectProbe
 * 参考 Feishu FeishuProbe.kt
 *
 * 用于HealthCheck和StatusMonitor
 */
object DiscordProbe {
    private const val TAG = "DiscordProbe"
    private const val DEFAULT_TIMEOUT_MS = 5000L

    /**
     * Proberesult
     */
    data class Proberesult(
        val ok: Boolean,
        val latencyMs: Long? = null,
        val error: String? = null,
        val bot: BotInfo? = null,
        val application: ApplicationInfo? = null
    )

    data class BotInfo(
        val id: String,
        val username: String,
        val discriminator: String,
        val bot: Boolean = true
    )

    data class ApplicationInfo(
        val id: String,
        val name: String,
        val verifyKey: String? = null,
        val intents: IntentsInfo? = null
    )

    data class IntentsInfo(
        val messageContent: String? = null // "enabled", "disabled", "limited"
    )

    /**
     * Probe Discord Connect
     */
    suspend fun probe(
        token: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        includeApplication: Boolean = false
    ): Proberesult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // CreateTemporaryClient
            val client = DiscordClient(token)

            // Get当Front Bot Info
            val result = withTimeoutOrNull(timeoutMs) {
                client.getCurrentUser()
            }

            if (result == null) {
                return@withContext Proberesult(
                    ok = false,
                    error = "Timeout after ${timeoutMs}ms"
                )
            }

            if (result.isFailure) {
                return@withContext Proberesult(
                    ok = false,
                    error = result.exceptionOrNull()?.message ?: "Unknown error"
                )
            }

            val latency = System.currentTimeMillis() - startTime
            val userData = result.getOrNull()

            if (userData == null) {
                return@withContext Proberesult(
                    ok = false,
                    error = "No user data returned"
                )
            }

            val botInfo = BotInfo(
                id = userData.get("id")?.asString ?: "",
                username = userData.get("username")?.asString ?: "",
                discriminator = userData.get("discriminator")?.asString ?: "0",
                bot = userData.get("bot")?.asBoolean ?: false
            )

            Log.i(TAG, "✅ Discord probe successful: ${botInfo.username} (${latency}ms)")

            val appInfo = if (includeApplication) {
                try {
                    val appresult = client.getApplication()
                    appresult.getOrNull()?.let { appData ->
                        ApplicationInfo(
                            id = appData.get("id")?.asString ?: "",
                            name = appData.get("name")?.asString ?: "",
                            verifyKey = appData.get("verify_key")?.asString,
                            intents = null
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch application info: ${e.message}")
                    null
                }
            } else null

            Proberesult(
                ok = true,
                latencyMs = latency,
                bot = botInfo,
                application = appInfo
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Discord probe failed", e)
            Proberesult(
                ok = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * FastHealthCheck
     */
    suspend fun healthCheck(token: String): Boolean {
        val result = probe(token, timeoutMs = 3000L, includeApplication = false)
        return result.ok
    }
}
