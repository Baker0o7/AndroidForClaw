package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraHandler.kt
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraCaptureManager.kt
 *
 * AndroidForClaw adaptation: 眼睛 Skill
 * 手机的FrontBack摄Like头Is Agent 的两只眼睛, 用于观察物理Environment. 
 * Support list / look / watch 三种Action
 */

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.xiaomo.androidforclaw.camera.CameraCaptureManager
import com.xiaomo.androidforclaw.camera.CameraPermissionActivity
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.media.ImageSanitizer
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import com.xiaomo.androidforclaw.workspace.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 眼睛 Skill — Your两只眼睛(Front置 + Back置摄Like头)
 *
 * 当你Need观察物理Environment、看看周围发生了什么时, use这个工具. 
 * - front(Front眼): 面向User的摄Like头, Can看到User和User面Front的Environment
 * - back(Back眼): 背向User的摄Like头, Can看到手机背面对着的Environment
 *
 * Aligned with OpenClaw camera.list / camera.snap / camera.clip
 */
class EyeSkill(
    private val context: Context,
    private val cameraManager: CameraCaptureManager,
) : Skill {
    companion object {
        private const val TAG = "EyeSkill"
    }

    override val name = "eye"
    override val description = "Your eyes — use the phone's cameras to observe the physical environment. " +
        "front eye faces the user, back eye faces outward. " +
        "Actions: list (list available eyes), look (take a photo and see — image is embedded for you to understand), " +
        "snap (take a photo and save — only returns file path, no image embedded), " +
        "watch (record a short video to observe over time)"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "Action type: list(ListAvailable的眼睛), look(看一眼并理解, Graph片直接嵌入给你看), snap(纯拍照, 只ReturnFile path), watch(持续观察, 录制短视频)",
                            enum = listOf("list", "look", "snap", "watch")
                        ),
                        "facing" to PropertySchema(
                            type = "string",
                            description = "use哪只眼睛: front(Front眼, 面向User) 或 back(Back眼, 面向ExternalEnvironment), Default back",
                            enum = listOf("front", "back")
                        ),
                        "quality" to PropertySchema(
                            type = "number",
                            description = "GraphLike质量 0.1-1.0, Default 0.95(仅 look)"
                        ),
                        "max_width" to PropertySchema(
                            type = "number",
                            description = "MaxGraphLikeBreadth(Like素), Default 1600(仅 look)"
                        ),
                        "duration_ms" to PropertySchema(
                            type = "number",
                            description = "观察时长(毫秒), Default 3000, Max 60000(仅 watch)"
                        ),
                        "include_audio" to PropertySchema(
                            type = "boolean",
                            description = "YesNoat the same time聆听声音, Default true(仅 watch)"
                        ),
                        "device_id" to PropertySchema(
                            type = "string",
                            description = "指定摄Like头 ID(从 list Get, Optional)"
                        ),
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        val action = (args["action"] as? String)?.lowercase()
            ?: return Skillresult.error("Missing required parameter: action")

        // 兼容Old的 action Name
        val normalizedAction = when (action) {
            "snap" -> "look"
            "clip" -> "watch"
            else -> action
        }

        return when (normalizedAction) {
            "list" -> executeList()
            "look" -> {
                val permresult = ensureCameraPermission()
                if (permresult != null) return permresult
                executeLook(args, embedImage = true)
            }
            "snap" -> {
                val permresult = ensureCameraPermission()
                if (permresult != null) return permresult
                executeLook(args, embedImage = false)
            }
            "watch" -> {
                val permresult = ensureCameraPermission()
                if (permresult != null) return permresult
                executeWatch(args)
            }
            else -> Skillresult.error("Unknown action: $action. Use: list, look, snap, watch")
        }
    }

    /**
     * Check相机Permission, ifNone则弹出Transparent Activity Request. 
     * @return null=Permission已Ready, Skillresult=Permission被deny的Errorresult
     */
    private suspend fun ensureCameraPermission(): Skillresult? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return null // 已HasPermission
        }

        Log.d(TAG, "CAMERA permission not granted, requesting via CameraPermissionActivity")
        val granted = CameraPermissionActivity.requestPermission(context)

        return if (granted) {
            Log.d(TAG, "CAMERA permission granted by user")
            null // Permission已grant
        } else {
            Log.w(TAG, "CAMERA permission denied by user")
            Skillresult.error("Need相机Permission才能use眼睛. 请在系统Settings中grant相机PermissionBackRetry. ")
        }
    }

    /**
     * ListAvailable的眼睛(摄Like头)
     */
    private suspend fun executeList(): Skillresult {
        return try {
            val devices = cameraManager.listDevices()
            if (devices.isEmpty()) {
                return Skillresult.success("NoneDetectedAvailable的眼睛(摄Like头)")
            }
            val output = buildString {
                appendLine("Available的眼睛 (${devices.size} 个):")
                devices.forEach { d ->
                    val eyeName = when (d.position) {
                        "front" -> "Front眼(面向User)"
                        "back" -> "Back眼(面向Environment)"
                        else -> d.position
                    }
                    appendLine("  - id: ${d.id}, $eyeName, type: ${d.deviceType}")
                }
            }
            Skillresult.success(output, mapOf("device_count" to devices.size))
        } catch (e: Exception) {
            Log.e(TAG, "eye.list failed", e)
            Skillresult.error("ListAvailable眼睛Failed: ${e.message}")
        }
    }

    /**
     * 看一眼(拍照)
     * @param embedImage true=look(GraphEmbed image for model), false=snap(只ReturnFile path)
     */
    private suspend fun executeLook(args: Map<String, Any?>, embedImage: Boolean = true): Skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val quality = (args["quality"] as? Number)?.toDouble() ?: 0.95
            val maxWidth = (args["max_width"] as? Number)?.toInt() ?: 1600
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "Front眼" else "Back眼"
            Log.d(TAG, "eye.look: facing=$facing($eyeName), quality=$quality, maxWidth=$maxWidth")

            val result = cameraManager.snap(
                facing = facing,
                quality = quality,
                maxWidth = maxWidth,
                deviceId = deviceId,
            )

            // CompressGraph片(Aligned with OpenClaw image-sanitization Policy)
            val sanitized = withContext(Dispatchers.IO) {
                ImageSanitizer.sanitize(result.base64, "image/jpeg")
            } ?: return Skillresult.error("Graph片CompressFailed")

            // Saveto workSpace
            val photoDir = File(StoragePaths.workspace, "eye").apply { mkdirs() }
            val photoFile = File(photoDir, "look_${System.currentTimeMillis()}.jpg")
            withContext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(sanitized.base64, android.util.Base64.NO_WRAP)
                photoFile.writeBytes(bytes)
            }

            val output = buildString {
                if (embedImage) {
                    appendLine("👁️ 通过${eyeName}观察Complete")
                    appendLine("分辨率: ${sanitized.width}x${sanitized.height}")
                    appendLine("文件: ${photoFile.absolutePath}")
                    appendLine("(Graph片已Inside嵌, 请直接Description你看到的Inside容)")
                } else {
                    appendLine("📸 通过${eyeName}拍照Complete")
                    appendLine("分辨率: ${sanitized.width}x${sanitized.height}")
                    appendLine("文件: ${photoFile.absolutePath}")
                }
            }

            Skillresult.success(
                output,
                mapOf(
                    "format" to "jpeg",
                    "width" to sanitized.width,
                    "height" to sanitized.height,
                    "file_path" to photoFile.absolutePath,
                ),
                images = if (embedImage) listOf(ImageBlock(base64 = sanitized.base64, mimeType = sanitized.mimeType)) else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "eye.look failed", e)
            Skillresult.error("观察Failed: ${e.message}")
        }
    }

    /**
     * 持续观察(录Like)
     */
    private suspend fun executeWatch(args: Map<String, Any?>): Skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val durationMs = (args["duration_ms"] as? Number)?.toInt() ?: 3000
            val includeAudio = (args["include_audio"] as? Boolean) ?: true
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "Front眼" else "Back眼"
            Log.d(TAG, "eye.watch: facing=$facing($eyeName), duration=$durationMs, audio=$includeAudio")

            val result = cameraManager.clip(
                facing = facing,
                durationMs = durationMs,
                includeAudio = includeAudio,
                deviceId = deviceId,
            )

            // Saveto workSpace
            val videoDir = File(StoragePaths.workspace, "eye").apply { mkdirs() }
            val videoFile = File(videoDir, "watch_${System.currentTimeMillis()}.mp4")
            withContext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(result.base64, android.util.Base64.NO_WRAP)
                videoFile.writeBytes(bytes)
            }

            val output = buildString {
                appendLine("👁️ 通过${eyeName}持续观察Complete")
                appendLine("时长: ${result.durationMs}ms")
                appendLine("声音: ${if (result.hasAudio) "Has" else "None"}")
                appendLine("文件: ${videoFile.absolutePath}")
            }

            Skillresult.success(
                output,
                mapOf(
                    "format" to result.format,
                    "duration_ms" to result.durationMs,
                    "has_audio" to result.hasAudio,
                    "file_path" to videoFile.absolutePath,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "eye.watch failed", e)
            Skillresult.error("持续观察Failed: ${e.message}")
        }
    }
}
