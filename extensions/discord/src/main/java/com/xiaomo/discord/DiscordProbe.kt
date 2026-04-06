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
 * Discord Connection Probe
 * Reference Feishu FeishuProbe.kt
 *
 * Used for Health Check and Status Monitoring
 */
object DiscordProbe {
    private const val TAG = "DiscordProbe"
    private const val DEFAULT_TIMEOUT_MS = 5000L

    /**
     * Probe Result
     */
    data class ProbeResult(
        val ok: Boolean,
        val latencyMs: Long? = null,
        val error: String? = null,
        val bot: BotInfo? = null,
        val application: ApplicationInfo? = null
    )

    /**
     * Probe Discord Connection
     */
    suspend fun probe(
        token: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        includeApplication: Boolean = false
    ): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()

            // Create Temporary Client
            val client = DiscordClient(token)

            // Get Current Bot Info
            val result = withTimeoutOrNull(timeoutMs) {
                client.getCurrentUser()
            }

            if (result == null) {
                return@withContext ProbeResult(
                    ok = false,
                    error = "Timeout after ${timeoutMs}ms"
                )
            }

            if (result.isFailure) {
                return@withContext ProbeResult(
                    ok = false,
                    error = result.exceptionOrNull()?.message ?: "Unknown error"
                )
            }

            val latency = System.currentTimeMillis() - startTime
            val userData = result.getOrNull()

            if (userData == null) {
                return@withContext ProbeResult(
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
                    val appResult = client.getApplication()
                    appResult.getOrNull()?.let { appData ->
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

            ProbeResult(
                ok = true,
                latencyMs = latency,
                bot = botInfo,
                application = appInfo
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Discord probe failed", e)
            ProbeResult(
                ok = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Fast Health Check
     */
    suspend fun healthCheck(token: String): Boolean {
        val result = probe(token, timeoutMs = 3000L, includeApplication = false)
        return result.ok
    }
}
