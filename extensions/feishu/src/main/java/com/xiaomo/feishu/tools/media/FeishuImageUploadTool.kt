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
 * 飞书Graph片Upload工具 (Kotlin Implementation,Stable版)
 *
 * Stable性Improve:
 * 1. use Kotlin 协程 + Sync HTTP call
 * 2. 详细的ErrorLog和Retry机制
 * 3. 完整的Request/ResponseValidate
 * 4. 文件格式和SizeCheck
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

    override val description = "UploadGraph片到飞书并Return image_key,Available于Back续sendMessage"

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
                            description = "Graph片文件的absolutelyPath (Support PNG, JPG, JPEG, GIF, BMP)"
                        )
                    ),
                    required = listOf("image_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val imagePath = args["image_path"] as? String
            ?: return Toolresult.error("缺少Parameters: image_path")

        Log.i(TAG, "StartUploadGraph片: $imagePath")

        // Validate文件
        val imageFile = File(imagePath)
        val validationresult = validateImageFile(imageFile)
        if (!validationresult.success) {
            return validationresult
        }

        // UploadGraph片 (带Retry) - 在 IO Thread执RowSync HTTP call
        return withContext(Dispatchers.IO) {
            for (attempt in 1..MAX_RETRIES) {
                Log.d(TAG, "UploadAttempt $attempt/$MAX_RETRIES")

                try {
                    val imageKey = uploadImageWithDetails(imageFile)
                    Log.i(TAG, "✅ Graph片UploadSuccess: $imageKey")

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
                    Log.e(TAG, "❌ UploadFailed (Attempt $attempt/$MAX_RETRIES): ${e.message}", e)

                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS * attempt) // 递增Delay
                    } else {
                        return@withContext Toolresult.error(
                            "Graph片UploadFailed (已Retry $MAX_RETRIES 次): ${e.message}"
                        )
                    }
                }
            }

            Toolresult.error("Graph片UploadFailed: 超过MaxRetry次数")
        }
    }

    /**
     * ValidateGraph片文件
     */
    private fun validateImageFile(file: File): Toolresult {
        // Check文件YesNoExists
        if (!file.exists()) {
            return Toolresult.error("文件不Exists: ${file.absolutePath}")
        }

        if (!file.isFile) {
            return Toolresult.error("不Yes文件: ${file.absolutePath}")
        }

        if (!file.canRead()) {
            return Toolresult.error("文件不可读: ${file.absolutePath}")
        }

        // Check文件Size
        val fileSizeBytes = file.length()
        if (fileSizeBytes == 0L) {
            return Toolresult.error("文件为Null: ${file.absolutePath}")
        }

        val fileSizeMB = fileSizeBytes / (1024.0 * 1024.0)
        if (fileSizeMB > MAX_FILE_SIZE_MB) {
            return Toolresult.error(
                "文件过大: %.2fMB > %dMB".format(fileSizeMB, MAX_FILE_SIZE_MB)
            )
        }

        // Check文件扩展名
        val fileName = file.name.lowercase()
        if (!fileName.endsWith(".png") &&
            !fileName.endsWith(".jpg") &&
            !fileName.endsWith(".jpeg") &&
            !fileName.endsWith(".gif") &&
            !fileName.endsWith(".bmp")
        ) {
            return Toolresult.error(
                "不Support的Graph片格式,仅Support: PNG, JPG, JPEG, GIF, BMP"
            )
        }

        Log.d(TAG, "✅ 文件Validate通过: ${file.name} (%.2fMB)".format(fileSizeMB))

        return Toolresult.success()
    }

    /**
     * UploadGraph片并Return image_key (详细LogVersion)
     * 注意: 这YesSyncMethod,Need在 IO Threadcall
     */
    private fun uploadImageWithDetails(imageFile: File): String {
        // 1. Get access token
        Log.d(TAG, "步骤 1: Get tenant_access_token")
        val token = client.getTenantAccessTokenSync()
            ?: throw IOException("Get token Failed")
        Log.d(TAG, "✅ Token GetSuccess")

        // 2. BuildRequest
        Log.d(TAG, "步骤 2: Build multipart Request")
        val fileBody = imageFile.asRequestBody("image/png".toMediaType())

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image_type", "message")  // 重要: use "message" 而不Yes "image"
            .addFormDataPart("image", imageFile.name, fileBody)
            .build()

        val url = "${config.getApiBaseUrl()}/open-apis/im/v1/images"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        Log.d(TAG, "Request URL: $url")
        Log.d(TAG, "文件名: ${imageFile.name}")
        Log.d(TAG, "文件Size: ${imageFile.length()} bytes")

        // 3. 执RowRequest
        Log.d(TAG, "步骤 3: send HTTP Request")
        val response = httpClient.newCall(request).execute()

        response.use {
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "ResponseStatus码: $statusCode")
            Log.d(TAG, "ResponseInside容: $responseBody")

            // 4. Check HTTP Status码
            if (!response.isSuccessful) {
                throw IOException("HTTP RequestFailed [$statusCode]: $responseBody")
            }

            // 5. ParseResponse
            Log.d(TAG, "步骤 4: ParseResponse JSON")
            val json = gson.fromJson(responseBody, JsonObject::class.java)
                ?: throw IOException("Response JSON 为Null")

            // Check code Field
            val code = json.get("code")?.asInt ?: -1
            if (code != 0) {
                val msg = json.get("msg")?.asString ?: "UnknownError"
                throw IOException("飞书 API Error [code=$code]: $msg")
            }

            // 提取 image_key
            val data = json.getAsJsonObject("data")
                ?: throw IOException("Response缺少 data Field")

            val imageKey = data.get("image_key")?.asString
                ?: throw IOException("Response缺少 image_key Field")

            if (imageKey.isEmpty()) {
                throw IOException("image_key 为Null")
            }

            return imageKey
        }
    }
}
