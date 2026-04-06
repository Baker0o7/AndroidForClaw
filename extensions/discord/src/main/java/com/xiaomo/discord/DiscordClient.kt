/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/discord/(all)
 *
 * AndroidForClaw adaptation: Discord channel runtime.
 */
package com.xiaomo.discord

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Discord API Client
 * Based on Discord REST API v10
 */
class DiscordClient(
    private val token: String
) {
    companion object {
        private const val TAG = "DiscordClient"
        private const val BASE_URL = "https://discord.com/api/v10"
        private const val USER_AGENT = "AndroidForClaw (https://github.com/SelectXn00b/AndroidForClaw, 3.0)"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Send Message to Discord Channel
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        embeds: List<Map<String, Any>>? = null,
        components: List<Map<String, Any>>? = null,
        messageReference: Map<String, Any>? = null
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val payload = mutableMapOf<String, Any>(
                "content" to content
            )

            embeds?.let { payload["embeds"] = it }
            components?.let { payload["components"] = it }
            messageReference?.let { payload["message_reference"] = it }

            val body = gson.toJson(payload).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId/messages")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                Log.d(TAG, "Message sent successfully to channel $channelId")
                result.success(json)
            } else {
                Log.e(TAG, "Failed to send message: ${response.code} - $responseBody")
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            result.failure(e)
        }
    }

    /**
     * Send DM (Private Message)
     */
    suspend fun sendDirectMessage(
        userId: String,
        content: String
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            // 1. Create DM Channel
            val dmChannel = createDMChannel(userId).getOrThrow()
            val channelId = dmChannel.get("id").asString

            // 2. Send Message
            sendMessage(channelId, content)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending DM to user $userId", e)
            result.failure(e)
        }
    }

    /**
     * Create DM Channel
     */
    private suspend fun createDMChannel(userId: String): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val payload = mapOf("recipient_id" to userId)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$BASE_URL/users/@me/channels")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                result.success(json)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    /**
     * Add Reaction (Emoji)
     */
    suspend fun addReaction(
        channelId: String,
        messageId: String,
        emoji: String
    ): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encodedEmoji = java.net.URLEncoder.encode(emoji, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId/messages/$messageId/reactions/$encodedEmoji/@me")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .put("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Reaction added: $emoji")
                result.success(Unit)
            } else {
                Log.e(TAG, "Failed to add reaction: ${response.code}")
                result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reaction", e)
            result.failure(e)
        }
    }

    /**
     * Remove Reaction
     */
    suspend fun removeReaction(
        channelId: String,
        messageId: String,
        emoji: String
    ): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encodedEmoji = java.net.URLEncoder.encode(emoji, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId/messages/$messageId/reactions/$encodedEmoji/@me")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .delete()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.d(TAG, "Reaction removed: $emoji")
                result.success(Unit)
            } else {
                Log.e(TAG, "Failed to remove reaction: ${response.code}")
                result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing reaction", e)
            result.failure(e)
        }
    }

    /**
     * Trigger Input Status (Typing Indicator)
     */
    suspend fun triggerTyping(channelId: String): result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId/typing")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .post("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                result.success(Unit)
            } else {
                result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    /**
     * Get Current Bot User Info
     */
    suspend fun getCurrentUser(): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/users/@me")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                result.success(json)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    /**
     * Get Guild (Server) Info
     */
    suspend fun getGuild(guildId: String): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/guilds/$guildId")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                result.success(json)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    /**
     * Get Channel Info
     */
    suspend fun getChannel(channelId: String): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                result.success(json)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    suspend fun getUserGuilds(): result<com.google.gson.JsonArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/users/@me/guilds")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val array = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                result.success(array)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    suspend fun getGuildChannels(guildId: String): result<com.google.gson.JsonArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/guilds/$guildId/channels")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val array = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                result.success(array)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }

    suspend fun getApplication(): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/oauth2/applications/@me")
                .header("Authorization", "Bot $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                result.success(json)
            } else {
                result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            result.failure(e)
        }
    }
}
