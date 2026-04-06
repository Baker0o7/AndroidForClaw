package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Feishu connection probe
 * Aligned with OpenClaw probe.ts
 *
 * Feature: 
 * - Probe Feishu API connection status
 * - Get bot info
 * - Cache probe results to reduce API calls
 */
object FeishuProbe {
    private const val TAG = "FeishuProbe"

    // Probe cache
    private val probeCache = mutableMapOf<String, CachedProbeResult>()
    private const val PROBE_SUCCESS_TTL_MS = 10 * 60 * 1000L // 10 minutes
    private const val PROBE_ERROR_TTL_MS = 60 * 1000L // 1 minute
    private const val MAX_PROBE_CACHE_SIZE = 64
    private const val FEISHU_PROBE_REQUEST_TIMEOUT_MS = 10_000L

    /**
     * Probe result
     */
    data class ProbeResult(
        val ok: Boolean,
        val appId: String?,
        val botName: String? = null,
        val botOpenId: String? = null,
        val error: String? = null
    )

    /**
     * Cached probe result
     */
    private data class CachedProbeResult(
        val result: ProbeResult,
        val expiresAt: Long
    )

    /**
     * Bot info response
     */
    private data class BotInfoResponse(
        val code: Int,
        val msg: String?,
        val bot: BotInfo?,
        val data: BotData?
    )

    private data class BotInfo(
        val bot_name: String?,
        val open_id: String?
    )

    private data class BotData(
        val bot: BotInfo?
    )

    /**
     * Probe Feishu connection
     *
     * @param client Feishu client
     * @param appId App ID
     * @param timeoutMs Timeout in milliseconds
     * @return ProbeResult
     */
    suspend fun probe(
        client: FeishuClient,
        appId: String,
        timeoutMs: Long = FEISHU_PROBE_REQUEST_TIMEOUT_MS
    ): ProbeResult = withContext(Dispatchers.IO) {
        // Check cache
        val cacheKey = appId
        val cached = probeCache[cacheKey]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            Log.d(TAG, "Returning cached probe result for $appId")
            return@withContext cached.result
        }

        try {
            // Call bot info API
            val result = withTimeout(timeoutMs) {
                client.get("/open-apis/bot/v3/info")
            }

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Log.w(TAG, "Probe failed for $appId: ${error?.message}")
                val probeResult = ProbeResult(
                    ok = false,
                    appId = appId,
                    error = error?.message ?: "Unknown error"
                )
                setCachedProbeResult(cacheKey, probeResult, PROBE_ERROR_TTL_MS)
                return@withContext probeResult
            }

            val response = result.getOrNull()
            val code = response?.get("code")?.asInt ?: -1

            if (code != 0) {
                val msg = response?.get("msg")?.asString ?: "code $code"
                Log.w(TAG, "Probe API error for $appId: $msg")
                val probeResult = ProbeResult(
                    ok = false,
                    appId = appId,
                    error = "API error: $msg"
                )
                setCachedProbeResult(cacheKey, probeResult, PROBE_ERROR_TTL_MS)
                return@withContext probeResult
            }

            // Parse bot info
            val bot = response?.getAsJsonObject("bot")
                ?: response?.getAsJsonObject("data")?.getAsJsonObject("bot")

            val botName = bot?.get("bot_name")?.asString
            val botOpenId = bot?.get("open_id")?.asString

            Log.d(TAG, "Probe successful for $appId: bot=$botName, openId=$botOpenId")
            val probeResult = ProbeResult(
                ok = true,
                appId = appId,
                botName = botName,
                botOpenId = botOpenId
            )
            setCachedProbeResult(cacheKey, probeResult, PROBE_SUCCESS_TTL_MS)
            return@withContext probeResult

        } catch (e: Exception) {
            Log.e(TAG, "Probe exception for $appId", e)
            val probeResult = ProbeResult(
                ok = false,
                appId = appId,
                error = e.message ?: "Unknown error"
            )
            setCachedProbeResult(cacheKey, probeResult, PROBE_ERROR_TTL_MS)
            return@withContext probeResult
        }
    }

    /**
     * Cache probe result
     */
    private fun setCachedProbeResult(
        cacheKey: String,
        result: ProbeResult,
        ttlMs: Long
    ): ProbeResult {
        probeCache[cacheKey] = CachedProbeResult(
            result = result,
            expiresAt = System.currentTimeMillis() + ttlMs
        )

        // Limit cache size
        if (probeCache.size > MAX_PROBE_CACHE_SIZE) {
            val oldest = probeCache.keys.firstOrNull()
            if (oldest != null) {
                probeCache.remove(oldest)
            }
        }

        return result
    }

    /**
     * Clear probe cache (for testing)
     */
    fun clearCache() {
        probeCache.clear()
    }
}
