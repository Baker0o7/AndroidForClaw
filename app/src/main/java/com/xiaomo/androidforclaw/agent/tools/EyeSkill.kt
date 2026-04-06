package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraHandler.kt
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraCapturemanager.kt
 *
 * androidforClaw adaptation: 眼睛 skill
 * 手机Frontback摄like头Is agent 两只眼睛, 用于观察物理Environment. 
 * Support list / look / watch three种Action
 */

import android.Manifest
import android.content.context
import android.content.pm.Packagemanager
import com.xiaomo.androidforclaw.camera.CameraCapturemanager
import com.xiaomo.androidforclaw.camera.CameraPermissionActivity
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.media.ImageSanitizer
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import com.xiaomo.androidforclaw.workspace.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import java.io.File

/**
 * 眼睛 skill — Your两只眼睛(Front置 + back置摄like头)
 *
 * when你need观察物理Environment、seeseeweek围发生whathour, usethiscount工具. 
 * - front(Front眼): 面向user摄like头, canseetouseranduser面FrontEnvironment
 * - back(back眼): 背向user摄like头, canseeto手机背面correct着Environment
 *
 * Aligned with OpenClaw camera.list / camera.snap / camera.clip
 */
class Eyeskill(
    private val context: context,
    private val cameramanager: CameraCapturemanager,
) : skill {
    companion object {
        private const val TAG = "Eyeskill"
    }

    override val name = "eye"
    override val description = "Your eyes — use the phone's cameras to observe the physical environment. " +
        "front eye faces the user, back eye faces outward. " +
        "Actions: list (list available eyes), look (take a photo and see — image is embeed for you to understand), " +
        "snap (take a photo and save — only returns file path, no image embeed), " +
        "watch (record a short video to observe over time)"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "action" to Propertyschema(
                            type = "string",
                            description = "Action type: list(ListAvailable眼睛), look(seeone眼并理解, image直接嵌入给你see), snap(pure拍照, 只ReturnFile path), watch(持续观察, 录制short视频)",
                            enum = listOf("list", "look", "snap", "watch")
                        ),
                        "facing" to Propertyschema(
                            type = "string",
                            description = "usewhich只眼睛: front(Front眼, 面向user) or back(back眼, 面向ExternalEnvironment), Default back",
                            enum = listOf("front", "back")
                        ),
                        "quality" to Propertyschema(
                            type = "number",
                            description = "Graphlike质量 0.1-1.0, Default 0.95(仅 look)"
                        ),
                        "max_width" to Propertyschema(
                            type = "number",
                            description = "MaxGraphlikeBreadth(like素), Default 1600(仅 look)"
                        ),
                        "duration_ms" to Propertyschema(
                            type = "number",
                            description = "观察duration(毫seconds), Default 3000, Max 60000(仅 watch)"
                        ),
                        "include_audio" to Propertyschema(
                            type = "boolean",
                            description = "whetherat the same time聆听声音, Default true(仅 watch)"
                        ),
                        "device_id" to Propertyschema(
                            type = "string",
                            description = "指定摄like头 ID(from list Get, Optional)"
                        ),
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val action = (args["action"] as? String)?.lowercase()
            ?: return skillresult.error("Missing required parameter: action")

        // 兼容old action Name
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
            else -> skillresult.error("Unknown action: $action. use: list, look, snap, watch")
        }
    }

    /**
     * Check相机Permission, ifNonethen弹出Transparent Activity Request. 
     * @return null=PermissionalreadyReady, skillresult=Permission被denyErrorresult
     */
    private suspend fun ensureCameraPermission(): skillresult? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
            Packagemanager.PERMISSION_GRANTED
        ) {
            return null // alreadyHasPermission
        }

        Log.d(TAG, "CAMERA permission not granted, requesting via CameraPermissionActivity")
        val granted = CameraPermissionActivity.requestPermission(context)

        return if (granted) {
            Log.d(TAG, "CAMERA permission granted by user")
            null // Permissionalreadygrant
        } else {
            Log.w(TAG, "CAMERA permission denied by user")
            skillresult.error("need相机Permission才canuse眼睛. pleasein系统Settings中grant相机Permissionbackretry. ")
        }
    }

    /**
     * ListAvailable眼睛(摄like头)
     */
    private suspend fun executeList(): skillresult {
        return try {
            val devices = cameramanager.listDevices()
            if (devices.isEmpty()) {
                return skillresult.success("NoneDetectedAvailable眼睛(摄like头)")
            }
            val output = buildString {
                appendLine("Available眼睛 (${devices.size} count):")
                devices.forEach { d ->
                    val eyeName = when (d.position) {
                        "front" -> "Front眼(面向user)"
                        "back" -> "back眼(面向Environment)"
                        else -> d.position
                    }
                    appendLine("  - id: ${d.id}, $eyeName, type: ${d.deviceType}")
                }
            }
            skillresult.success(output, mapOf("device_count" to devices.size))
        } catch (e: exception) {
            Log.e(TAG, "eye.list failed", e)
            skillresult.error("ListAvailable眼睛Failed: ${e.message}")
        }
    }

    /**
     * seeone眼(拍照)
     * @param embedImage true=look(GraphEmbed image for model), false=snap(只ReturnFile path)
     */
    private suspend fun executeLook(args: Map<String, Any?>, embedImage: Boolean = true): skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val quality = (args["quality"] as? Number)?.toDouble() ?: 0.95
            val maxWidth = (args["max_width"] as? Number)?.toInt() ?: 1600
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "Front眼" else "back眼"
            Log.d(TAG, "eye.look: facing=$facing($eyeName), quality=$quality, maxWidth=$maxWidth")

            val result = cameramanager.snap(
                facing = facing,
                quality = quality,
                maxWidth = maxWidth,
                deviceId = deviceId,
            )

            // compressimage(Aligned with OpenClaw image-sanitization Policy)
            val sanitized = withcontext(Dispatchers.IO) {
                ImageSanitizer.sanitize(result.base64, "image/jpeg")
            } ?: return skillresult.error("imagecompressFailed")

            // Saveto workSpace
            val photoDir = File(StoragePaths.workspace, "eye").app { mkdirs() }
            val photoFile = File(photoDir, "look_${System.currentTimeMillis()}.jpg")
            withcontext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(sanitized.base64, android.util.Base64.NO_WRAP)
                photoFile.writeBytes(bytes)
            }

            val output = buildString {
                if (embedImage) {
                    appendLine("👁️ through${eyeName}观察Complete")
                    appendLine("minute辨率: ${sanitized.width}x${sanitized.height}")
                    appendLine("files: ${photoFile.absolutePath}")
                    appendLine("(imagealreadyinside嵌, please直接Description你seetocontent)")
                } else {
                    appendLine("📸 through${eyeName}拍照Complete")
                    appendLine("minute辨率: ${sanitized.width}x${sanitized.height}")
                    appendLine("files: ${photoFile.absolutePath}")
                }
            }

            skillresult.success(
                output,
                mapOf(
                    "format" to "jpeg",
                    "width" to sanitized.width,
                    "height" to sanitized.height,
                    "file_path" to photoFile.absolutePath,
                ),
                images = if (embedImage) listOf(ImageBlock(base64 = sanitized.base64, mimeType = sanitized.mimeType)) else null
            )
        } catch (e: exception) {
            Log.e(TAG, "eye.look failed", e)
            skillresult.error("观察Failed: ${e.message}")
        }
    }

    /**
     * 持续观察(录like)
     */
    private suspend fun executeWatch(args: Map<String, Any?>): skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val durationMs = (args["duration_ms"] as? Number)?.toInt() ?: 3000
            val includeAudio = (args["include_audio"] as? Boolean) ?: true
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "Front眼" else "back眼"
            Log.d(TAG, "eye.watch: facing=$facing($eyeName), duration=$durationMs, audio=$includeAudio")

            val result = cameramanager.clip(
                facing = facing,
                durationMs = durationMs,
                includeAudio = includeAudio,
                deviceId = deviceId,
            )

            // Saveto workSpace
            val videoDir = File(StoragePaths.workspace, "eye").app { mkdirs() }
            val videoFile = File(videoDir, "watch_${System.currentTimeMillis()}.mp4")
            withcontext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(result.base64, android.util.Base64.NO_WRAP)
                videoFile.writeBytes(bytes)
            }

            val output = buildString {
                appendLine("👁️ through${eyeName}持续观察Complete")
                appendLine("duration: ${result.durationMs}ms")
                appendLine("声音: ${if (result.hasAudio) "Has" else "None"}")
                appendLine("files: ${videoFile.absolutePath}")
            }

            skillresult.success(
                output,
                mapOf(
                    "format" to result.format,
                    "duration_ms" to result.durationMs,
                    "has_audio" to result.hasAudio,
                    "file_path" to videoFile.absolutePath,
                )
            )
        } catch (e: exception) {
            Log.e(TAG, "eye.watch failed", e)
            skillresult.error("持续观察Failed: ${e.message}")
        }
    }
}
