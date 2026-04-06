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
 * 飞书ConnectProbe
 * Aligned with OpenClaw probe.ts
 *
 * Feature: 
 * - Probe飞书 API ConnectStatus
 * - Get机器人Info
 * - CacheProberesult以减少 API call
 */
object FeishuProbe {
    private const val TAG = "FeishuProbe"

    // ProbeCache
    private val probeCache = mutableMapOf<String, CachedProberesult>()
    private const val PROBE_SUCCESS_TTL_MS = 10 * 60 * 1000L // 10 minutes
    private const val PROBE_ERROR_TTL_MS = 60 * 1000L // 1 minute
    private const val MAX_PROBE_CACHE_SIZE = 64
    private const val FEISHU_PROBE_REQUEST_TIMEOUT_MS = 10_000L

    /**
     * Proberesult
     */
    data class Proberesult(
        val ok: Boolean,
        val appId: String?,
        val botName: String? = null,
        val botOpenId: String? = null,
        val error: String? = null
    )

    /**
     * Cache的Proberesult
     */
    private data class CachedProberesult(
        val result: Proberesult,
        val expiresAt: Long
    )

    /**
     * 机器人InfoResponse
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
     * Probe飞书Connect
     *
     * @param client Feishu Client
     * @param appId App ID
     * @param timeoutMs TimeoutTime(毫秒)
     * @return Proberesult
     */
    suspend fun probe(
        client: FeishuClient,
        appId: String,
        timeoutMs: Long = FEISHU_PROBE_REQUEST_TIMEOUT_MS
    ): Proberesult = withContext(Dispatchers.IO) {
        // CheckCache
        val cacheKey = appId
        val cached = probeCache[cacheKey]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            Log.d(TAG, "Returning cached probe result for $appId")
            return@withContext cached.result
        }

        try {
            // call机器人Info API
            val result = withTimeout(timeoutMs) {
                client.get("/open-apis/bot/v3/info")
            }

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Log.w(TAG, "Probe failed for $appId: ${error?.message}")
                val proberesult = Proberesult(
                    ok = false,
                    appId = appId,
                    error = error?.message ?: "Unknown error"
                )
                setCachedProberesult(cacheKey, proberesult, PROBE_ERROR_TTL_MS)
                return@withContext proberesult
            }

            val response = result.getOrNull()
            val code = response?.get("code")?.asInt ?: -1

            if (code != 0) {
                val msg = response?.get("msg")?.asString ?: "code $code"
                Log.w(TAG, "Probe API error for $appId: $msg")
                val proberesult = Proberesult(
                    ok = false,
                    appId = appId,
                    error = "API error: $msg"
                )
                setCachedProberesult(cacheKey, proberesult, PROBE_ERROR_TTL_MS)
                return@withContext proberesult
            }

            // Parse机器人Info
            val bot = response?.getAsJsonObject("bot")
                ?: response?.getAsJsonObject("data")?.getAsJsonObject("bot")

            val botName = bot?.get("bot_name")?.asString
            val botOpenId = bot?.get("open_id")?.asString

            Log.d(TAG, "Probe successful for $appId: bot=$botName, openId=$botOpenId")
            val proberesult = Proberesult(
                ok = true,
                appId = appId,
                botName = botName,
                botOpenId = botOpenId
            )
            setCachedProberesult(cacheKey, proberesult, PROBE_SUCCESS_TTL_MS)
            return@withContext proberesult

        } catch (e: Exception) {
            Log.e(TAG, "Probe exception for $appId", e)
            val proberesult = Proberesult(
                ok = false,
                appId = appId,
                error = e.message ?: "Unknown error"
            )
            setCachedProberesult(cacheKey, proberesult, PROBE_ERROR_TTL_MS)
            return@withContext proberesult
        }
    }

    /**
     * CacheProberesult
     */
    private fun setCachedProberesult(
        cacheKey: String,
        result: Proberesult,
        ttlMs: Long
    ): Proberesult {
        probeCache[cacheKey] = CachedProberesult(
            result = result,
            expiresAt = System.currentTimeMillis() + ttlMs
        )

        // LimitCacheSize
        if (probeCache.size > MAX_PROBE_CACHE_SIZE) {
            val oldest = probeCache.keys.firstOrNull()
            if (oldest != null) {
                probeCache.remove(oldest)
            }
        }

        return result
    }

    /**
     * clearProbeCache(用于Test)
     */
    fun clearCache() {
        probeCache.clear()
    }
}
