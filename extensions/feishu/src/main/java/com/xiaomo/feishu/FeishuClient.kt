package com.xiaomo.feishu

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel runtime.
 */


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Feishu API Client
 * Aligned with OpenClaw feishu client.ts
 */
class FeishuClient(private val config: FeishuConfig) {
    companion object {
        private const val TAG = "FeishuClient"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Longer timeout for media downloads (aligned with OpenClaw: 120s)
    private val mediaHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val baseUrl = config.getApiBaseUrl()

    // Access token cache
    private var cachedAccessToken: String? = null
    private var tokenExpireTime: Long = 0

    /**
     * Get tenant_access_token (Coroutine Version)
     */
    suspend fun getTenantAccessToken(): result<String> = withContext(Dispatchers.IO) {
        try {
            // Check cache
            val now = System.currentTimeMillis()
            if (cachedAccessToken != null && now < tokenExpireTime) {
                return@withContext result.success(cachedAccessToken!!)
            }

            // Request new token
            val url = "$baseUrl/open-apis/auth/v3/tenant_access_token/internal"
            val requestBody = mapOf(
                "app_id" to config.appId,
                "app_secret" to config.appSecret
            )

            val body = gson.toJson(requestBody)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get tenant access token: $responseBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1

            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                Log.e(TAG, "Feishu API error: $msg")
                return@withContext result.failure(Exception(msg))
            }

            val token = json.get("tenant_access_token")?.asString
                ?: return@withContext result.failure(Exception("Missing tenant_access_token"))

            val expire = json.get("expire")?.asInt ?: 7200

            // Cache token (5 minutes before expiry)
            cachedAccessToken = token
            tokenExpireTime = now + (expire - 300) * 1000L

            Log.d(TAG, "Got tenant access token, expires in ${expire}s")
            result.success(token)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tenant access token", e)
            result.failure(e)
        }
    }

    /**
     * Get tenant_access_token (Sync Version)
     * Used for direct calls on IO thread
     */
    fun getTenantAccessTokenSync(): String? {
        try {
            // Check cache
            val now = System.currentTimeMillis()
            if (cachedAccessToken != null && now < tokenExpireTime) {
                return cachedAccessToken
            }

            // Request new token
            val url = "$baseUrl/open-apis/auth/v3/tenant_access_token/internal"
            val requestBody = mapOf(
                "app_id" to config.appId,
                "app_secret" to config.appSecret
            )

            val body = gson.toJson(requestBody)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get tenant access token: $responseBody")
                return null
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1

            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                Log.e(TAG, "Feishu API error: $msg")
                return null
            }

            val token = json.get("tenant_access_token")?.asString ?: return null
            val expire = json.get("expire")?.asInt ?: 7200

            // Cache token (5 minutes before expiry)
            cachedAccessToken = token
            tokenExpireTime = now + (expire - 300) * 1000L

            Log.d(TAG, "Got tenant access token (sync), expires in ${expire}s")
            return token

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tenant access token (sync)", e)
            return null
        }
    }

    /**
     * Send API Request
     * @param headers External request headers (optional), used for X-Chat-Custom-Header etc.
     */
    suspend fun apiRequest(
        method: String,
        path: String,
        body: Any? = null,
        requireAuth: Boolean = true,
        headers: Map<String, String>? = null
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl$path"

            val requestBuilder = Request.Builder().url(url)

            // Add authentication header
            if (requireAuth) {
                val tokenResult = getTenantAccessToken()
                if (tokenResult.isFailure) {
                    return@withContext result.failure(tokenResult.exceptionOrNull()!!)
                }
                requestBuilder.addHeader("Authorization", "Bearer ${tokenResult.getOrNull()}")
            }

            // Add external request headers
            headers?.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            // Add request body
            if (body != null) {
                val json = if (body is String) body else gson.toJson(body)
                val requestBody = json.toRequestBody("application/json".toMediaType())

                when (method.uppercase()) {
                    "POST" -> requestBuilder.post(requestBody)
                    "PUT" -> requestBuilder.put(requestBody)
                    "PATCH" -> requestBuilder.patch(requestBody)
                    else -> requestBuilder.method(method.uppercase(), requestBody)
                }
            } else {
                when (method.uppercase()) {
                    "GET" -> requestBuilder.get()
                    "DELETE" -> requestBuilder.delete()
                    else -> requestBuilder.method(method.uppercase(), null)
                }
            }

            val request = requestBuilder.build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "API request failed: $method $path - HTTP ${response.code}")
                Log.e(TAG, "Response: $responseBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            // Defensive JSON parsing — handle non-JSON or non-Object responses
            val jsonElement = try {
                gson.fromJson(responseBody, com.google.gson.JsonElement::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Response is not valid JSON: $responseBody")
                return@withContext result.failure(Exception("Invalid JSON response from $method $path"))
            }

            if (jsonElement == null || !jsonElement.isJsonObject) {
                Log.e(TAG, "Response is not a JSON object: $responseBody")
                return@withContext result.failure(Exception("Expected JSON object from $method $path, got: ${jsonElement?.javaClass?.simpleName ?: "null"}"))
            }

            val json = jsonElement.asJsonObject
            val code = json.get("code")?.asInt ?: 0

            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                Log.e(TAG, "Feishu API error: $msg (code=$code)")
                return@withContext result.failure(Exception("$msg (code=$code)"))
            }

            result.success(json)

        } catch (e: Exception) {
            Log.e(TAG, "API request exception: $method $path", e)
            result.failure(e)
        }
    }

    /**
     * GET Request
     */
    suspend fun get(path: String, headers: Map<String, String>? = null): result<JsonObject> =
        apiRequest("GET", path, headers = headers)

    /**
     * POST Request
     */
    suspend fun post(path: String, body: Any, headers: Map<String, String>? = null): result<JsonObject> =
        apiRequest("POST", path, body, headers = headers)

    /**
     * PUT Request
     */
    suspend fun put(path: String, body: Any): result<JsonObject> = apiRequest("PUT", path, body)

    /**
     * DELETE Request
     */
    suspend fun delete(path: String): result<JsonObject> = apiRequest("DELETE", path)

    /**
     * PATCH Request
     */
    suspend fun patch(path: String, body: Any): result<JsonObject> = apiRequest("PATCH", path, body)

    /**
     * Upload file to cloud space (upload_all, small files <=15MB)
     * @aligned openclaw-lark v2026.3.30 — line-by-line
     */
    suspend fun uploadFile(
        fileName: String,
        parentType: String,
        parentNode: String,
        size: Int,
        data: ByteArray
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val token = getTenantAccessToken().getOrThrow()
            val url = "$baseUrl/open-apis/drive/v1/files/upload_all"

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_name", fileName)
                .addFormDataPart("parent_type", parentType)
                .addFormDataPart("parent_node", parentNode)
                .addFormDataPart("size", size.toString())
                .addFormDataPart(
                    "file", fileName,
                    data.toRequestBody("application/octet-stream".toMediaType())
                )

            val request = Request.Builder()
                .url(url)
                .post(bodyBuilder.build())
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = mediaHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "Upload file failed: HTTP ${response.code} - $responseBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: 0
            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                return@withContext result.failure(Exception("$msg (code=$code)"))
            }

            result.success(json)
        } catch (e: Exception) {
            Log.e(TAG, "Upload file failed", e)
            result.failure(e)
        }
    }

    /**
     * Sharding upload - Prepare (uploadPrepare)
     * @aligned openclaw-lark v2026.3.30 — line-by-line
     */
    suspend fun uploadPrepare(
        fileName: String,
        parentType: String,
        parentNode: String,
        size: Int
    ): result<JsonObject> {
        val body = mapOf(
            "file_name" to fileName,
            "parent_type" to parentType,
            "parent_node" to parentNode,
            "size" to size
        )
        return post("/open-apis/drive/v1/files/upload_prepare", body)
    }

    /**
     * Sharding upload - Upload Sharding (uploadPart)
     * @aligned openclaw-lark v2026.3.30 — line-by-line
     */
    suspend fun uploadPart(
        uploadId: String,
        seq: Int,
        size: Int,
        data: ByteArray
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val token = getTenantAccessToken().getOrThrow()
            val url = "$baseUrl/open-apis/drive/v1/files/upload_part"

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_id", uploadId)
                .addFormDataPart("seq", seq.toString())
                .addFormDataPart("size", size.toString())
                .addFormDataPart(
                    "file", "chunk_$seq",
                    data.toRequestBody("application/octet-stream".toMediaType())
                )

            val request = Request.Builder()
                .url(url)
                .post(bodyBuilder.build())
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = mediaHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "Upload part failed: HTTP ${response.code} - $responseBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: 0
            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                return@withContext result.failure(Exception("$msg (code=$code)"))
            }

            result.success(json)
        } catch (e: Exception) {
            Log.e(TAG, "Upload part failed", e)
            result.failure(e)
        }
    }

    /**
     * Sharding upload - Complete (uploadFinish)
     * @aligned openclaw-lark v2026.3.30 — line-by-line
     */
    suspend fun uploadFinish(
        uploadId: String,
        blockNum: Int
    ): result<JsonObject> {
        val body = mapOf(
            "upload_id" to uploadId,
            "block_num" to blockNum
        )
        return post("/open-apis/drive/v1/files/upload_finish", body)
    }

    /**
     * Download binary data (for media file download)
     * Aligned with OpenClaw downloadImageFeishu / downloadMessageResourceFeishu
     */
    suspend fun downloadRaw(path: String): result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl$path"
            val token = getTenantAccessToken().getOrThrow()

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = mediaHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Download failed: HTTP ${response.code} - $errorBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val bytes = response.body?.bytes()
                ?: return@withContext result.failure(Exception("Empty response body"))

            Log.d(TAG, "Downloaded ${bytes.size} bytes from $path")
            result.success(bytes)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $path", e)
            result.failure(e)
        }
    }

    /**
     * Download binary data with headers.
     * @aligned openclaw-lark v2026.3.30
     * Returns Pair(bytes, headers) where headers is a map of response headers.
     */
    suspend fun downloadRawWithHeaders(path: String): result<Pair<ByteArray, Map<String, String>>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl$path"
            val token = getTenantAccessToken().getOrThrow()

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = mediaHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Download failed: HTTP ${response.code} - $errorBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val bytes = response.body?.bytes()
                ?: return@withContext result.failure(Exception("Empty response body"))

            val headers = mutableMapOf<String, String>()
            response.headers.forEach { (name, value) ->
                headers[name.lowercase()] = value
            }

            Log.d(TAG, "Downloaded ${bytes.size} bytes from $path, content-type=${headers["content-type"]}")
            result.success(bytes to headers)

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $path", e)
            result.failure(e)
        }
    }

    /**
     * Upload media file (image/file) to Feishu
     * Aligned with @larksuite/openclaw-lark doc-media insert flow.
     *
     * @param fileName File name
     * @param fileBytes File bytes
     * @param parentType Parent type: docx_image, docx_file, etc.
     * @param parentNode Parent block ID
     * @param extra Optional extra data map (e.g. drive_route_token)
     */
    suspend fun uploadMedia(
        fileName: String,
        fileBytes: ByteArray,
        parentType: String,
        parentNode: String,
        extra: Map<String, Any?>? = null
    ): result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            val token = getTenantAccessToken().getOrThrow()
            val url = "$baseUrl/open-apis/drive/v1/medias/upload_all"

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_name", fileName)
                .addFormDataPart("parent_type", parentType)
                .addFormDataPart("parent_node", parentNode)
                .addFormDataPart("size", fileBytes.size.toString())
                .addFormDataPart(
                    "file", fileName,
                    fileBytes.toRequestBody("application/octet-stream".toMediaType())
                )

            if (extra != null) {
                bodyBuilder.addFormDataPart("extra", gson.toJson(extra))
            }

            val request = Request.Builder()
                .url(url)
                .post(bodyBuilder.build())
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = mediaHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e(TAG, "Upload media failed: HTTP ${response.code} - $responseBody")
                return@withContext result.failure(Exception("HTTP ${response.code}"))
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: 0
            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                Log.e(TAG, "Upload media error: $msg (code=$code)")
                return@withContext result.failure(Exception("$msg (code=$code)"))
            }

            result.success(json.getAsJsonObject("data") ?: JsonObject())
        } catch (e: Exception) {
            Log.e(TAG, "Upload media failed", e)
            result.failure(e)
        }
    }

    /**
     * Get bot info (Aligned with OpenClaw probe.ts)
     * https://open.feishu.cn/document/server-docs/bot-v3/bot-overview
     */
    suspend fun getBotInfo(): result<BotInfo> = withContext(Dispatchers.IO) {
        try {
            val token = getTenantAccessToken().getOrThrow()
            val url = "$baseUrl/open-apis/bot/v3/info"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                val error = try {
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    json.get("msg")?.asString ?: responseBody
                } catch (e: Exception) {
                    responseBody
                }
                return@withContext result.failure(Exception("getBotInfo failed: $error"))
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val code = json.get("code")?.asInt ?: -1

            Log.d(TAG, "getBotInfo response: $responseBody")

            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                return@withContext result.failure(Exception("getBotInfo failed: $msg (code: $code)"))
            }

            // Feishu API v3 response structure: { code: 0, bot: { activate_status: 2, app_name: "...", open_id: "...", ... }, msg: "ok" }
            val bot = json.getAsJsonObject("bot")
            Log.d(TAG, "bot object: $bot")

            if (bot == null) {
                return@withContext result.failure(Exception("Missing bot in response"))
            }

            val openId = bot.get("open_id")?.asString
            val name = bot.get("app_name")?.asString

            Log.d(TAG, "Got bot info: open_id=$openId, name=$name")

            result.success(BotInfo(openId = openId, name = name))

        } catch (e: Exception) {
            Log.e(TAG, "getBotInfo failed", e)
            result.failure(e)
        }
    }
}

/**
 * Bot Info
 */
data class BotInfo(
    val openId: String?,
    val name: String?
)
