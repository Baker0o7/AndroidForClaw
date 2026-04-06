package com.xiaomo.feishu.tools.media

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/feishu/(all)
 *
 * AndroidForClaw adaptation: Feishu channel tool definitions.
 */


import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Feishu image upload tool (Kotlin Implementation, Stable version)
 *
 * Stability improvements:
 * 1. Use Kotlin coroutines + sync HTTP call
 * 2. Detailed error logs and retry mechanism
 * 3. Complete request/response validation
 * 4. File format and size check
 */
class FeishuImageUploadTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    companion object {
        private const val TAG = "FeishuImageUploadTool"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val MAX_FILE_SIZE_MB = 10
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override val name = "feishu_upload_image"

    override val description = "Upload image to Feishu and return image_key, available for sending messages later"

    override fun isEnabledd() = true

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "image_path" to PropertySchema(
                            type = "string",
                            description = "Image file absolute path (supports PNG, JPG, JPEG, GIF, BMP)"
                        )
                    ),
                    required = listOf("image_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val imagePath = args["image_path"] as? String
            ?: return Toolresult.error("Missing parameter: image_path")

        Log.i(TAG, "Start uploading image: $imagePath")

        // Validate file
        val imageFile = File(imagePath)
        val validationResult = validateImageFile(imageFile)
        if (!validationResult.success) {
            return validationResult
        }

        // Upload image (with retry) - run sync HTTP call in IO thread
        return withContext(Dispatchers.IO) {
            for (attempt in 1..MAX_RETRIES) {
                Log.d(TAG, "Upload attempt $attempt/$MAX_RETRIES")

                try {
                    val imageKey = uploadImageWithDetails(imageFile)
                    Log.i(TAG, "Image uploaded successfully: $imageKey")

                    return@withContext Toolresult.success(
                        data = imageKey,
                        metadata = mapOf(
                            "image_key" to imageKey,
                            "file_name" to imageFile.name,
                            "file_size" to imageFile.length(),
                            "attempts" to attempt
                        )
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed (attempt $attempt/$MAX_RETRIES): ${e.message}", e)

                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS * attempt) // Incremental delay
                    } else {
                        return@withContext Toolresult.error(
                            "Image upload failed (retried $MAX_RETRIES times): ${e.message}"
                        )
                    }
                }
            }

            Toolresult.error("Image upload failed: exceeded max retry count")
        }
    }

    /**
     * Validate image file
     */
    private fun validateImageFile(file: File): Toolresult {
        // Check if file exists
        if (!file.exists()) {
            return Toolresult.error("File does not exist: ${file.absolutePath}")
        }

        if (!file.isFile) {
            return Toolresult.error("Not a file: ${file.absolutePath}")
        }

        if (!file.canRead()) {
            return Toolresult.error("File not readable: ${file.absolutePath}")
        }

        // Check file size
        val fileSizeBytes = file.length()
        if (fileSizeBytes == 0L) {
            return Toolresult.error("File is empty: ${file.absolutePath}")
        }

        val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)
        if (fileSizeMB > MAX_FILE_SIZE_MB) {
            return Toolresult.error(
                "File too large: %.2fMB > %dMB".format(fileSizeMB, MAX_FILE_SIZE_MB)
            )
        }

        // Check file extension
        val fileName = file.name.lowercase()
        if (!fileName.endsWith(".png") &&
            !fileName.endsWith(".jpg") &&
            !fileName.endsWith(".jpeg") &&
            !fileName.endsWith(".gif") &&
            !fileName.endsWith(".bmp")
        ) {
            return Toolresult.error(
                "Unsupported image format. Supported: PNG, JPG, JPEG, GIF, BMP"
            )
        }

        Log.d(TAG, "File validation passed: ${file.name} (%.2fMB)".format(fileSizeMB))

        return Toolresult.success()
    }

    /**
     * Upload image and return image_key (detailed log version)
     * Note: This is a sync method, need to call in IO thread
     */
    private fun uploadImageWithDetails(imageFile: File): String {
        // 1. Get access token
        Log.d(TAG, "Step 1: Get tenant_access_token")
        val token = client.getTenantAccessTokenSync()
            ?: throw IOException("Get token failed")
        Log.d(TAG, "Token obtained successfully")

        // 2. Build request
        Log.d(TAG, "Step 2: Build multipart request")
        val fileBody = imageFile.asRequestBody("image/png".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image_type", "message")  // Important: use "message" instead of "image"
            .addFormDataPart("image", imageFile.name, fileBody)
            .build()

        val url = "${config.getApiBaseUrl()}/open-apis/im/v1/images"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        Log.d(TAG, "Request URL: $url")
        Log.d(TAG, "File name: ${imageFile.name}")
        Log.d(TAG, "File size: ${imageFile.length()} bytes")

        // 3. Execute request
        Log.d(TAG, "Step 3: Send HTTP request")
        val response = httpClient.newCall(request).execute()

        response.use {
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "Response status code: $statusCode")
            Log.d(TAG, "Response content: $responseBody")

            // 4. Check HTTP status code
            if (!response.isSuccessful) {
                throw IOException("HTTP request failed [$statusCode]: $responseBody")
            }

            // 5. Parse response
            Log.d(TAG, "Step 4: Parse response JSON")
            val json = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Response JSON is null")

            // Check code field
            val code = json.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "Unknown error"
                throw IOException("Feishu API error [code=$code]: $msg")
            }

            // Extract image_key
            val data = json.getAsJsonObject("data")
                ?: throw IOException("Response missing data field")

            val imageKey = data.get("image_key")?.asString
                ?: throw IOException("Response missing image_key field")

            if (imageKey.isEmpty()) {
                throw IOException("image_key is null")
            }

            return imageKey
        }
    }
}
