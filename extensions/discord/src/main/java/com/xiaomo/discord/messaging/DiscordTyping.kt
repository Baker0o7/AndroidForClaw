/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord.messaging

import android.util.Log
import com.xiaomo.discord.DiscordClient
import kotlinx.coroutines.*

/**
 * Discord InputStatus指示器
 * 参考 Feishu FeishuTyping.kt
 */
class DiscordTyping(private val client: DiscordClient) {
    companion object {
        private const val TAG = "DiscordTyping"
        private const val TYPING_DURATION_MS = 10000L // Discord typing indicator lasts 10 seconds
        private const val RENEWAL_INTERVAL_MS = 7000L // Renew every 7 seconds
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTypingJobs = mutableMapOf<String, Job>()

    /**
     * 触发InputStatus
     * 单次触发, 持续 10 秒
     */
    suspend fun trigger(channelId: String): result<Unit> {
        return try {
            val result = client.triggerTyping(channelId)
            if (result.isSuccess) {
                Log.d(TAG, "Typing indicator triggered for channel: $channelId")
            } else {
                Log.w(TAG, "Failed to trigger typing: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering typing", e)
            result.failure(e)
        }
    }

    /**
     * Start持续InputStatus
     * Auto续期, 直到call stop()
     */
    fun startContinuous(channelId: String) {
        // Cancel已Exists的Task
        stopContinuous(channelId)

        val job = scope.launch {
            try {
                Log.d(TAG, "Starting continuous typing for channel: $channelId")

                while (isActive) {
                    // 触发InputStatus
                    trigger(channelId)

                    // Wait续期Interval
                    delay(RENEWAL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Continuous typing cancelled for channel: $channelId")
            } catch (e: Exception) {
                Log.e(TAG, "Error in continuous typing", e)
            }
        }

        activeTypingJobs[channelId] = job
    }

    /**
     * Stop持续InputStatus
     */
    fun stopContinuous(channelId: String) {
        activeTypingJobs[channelId]?.let { job ->
            job.cancel()
            activeTypingJobs.remove(channelId)
            Log.d(TAG, "Stopped continuous typing for channel: $channelId")
        }
    }

    /**
     * StopAll持续InputStatus
     */
    fun stopAll() {
        activeTypingJobs.keys.toList().forEach { channelId ->
            stopContinuous(channelId)
        }
    }

    /**
     * 清理Resource
     */
    fun cleanup() {
        stopAll()
        scope.cancel()
    }
}
