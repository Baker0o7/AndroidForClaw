package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraHandler.kt
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraCaptureManager.kt
 *
 * AndroidForClaw adaptation: Eye skill
 * The phone's front/back cameras are the agent's two eyes, used to observe the physical environment.
 * Supports list / look / snap / watch three actions
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
 * Eye skill — Your two eyes (front camera + back camera)
 *
 * When you need to observe the physical environment and see what's happening around you, use this tool.
 * - front (front eye): Faces the user camera, can see the user and the environment in front of them
 * - back (back eye): Faces away from the user, can see the environment behind the phone
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
                            description = "Action type: list (list available cameras), look (take a photo and see — image is embedded for you to understand), snap (take a photo and save — only returns file path, no image embed), watch (continuous observation, record short video)",
                            enum = listOf("list", "look", "snap", "watch")
                        ),
                        "facing" to Propertyschema(
                            type = "string",
                            description = "Which eye to use: front (front camera, faces user) or back (back camera, faces external environment), default back",
                            enum = listOf("front", "back")
                        ),
                        "quality" to Propertyschema(
                            type = "number",
                            description = "Image quality 0.1-1.0, default 0.95 (look only)"
                        ),
                        "max_width" to Propertyschema(
                            type = "number",
                            description = "Max image width (pixels), default 1600 (look only)"
                        ),
                        "duration_ms" to Propertyschema(
                            type = "number",
                            description = "Observation duration (milliseconds), default 3000, max 60000 (watch only)"
                        ),
                        "include_audio" to Propertyschema(
                            type = "boolean",
                            description = "Whether to also capture audio, default true (watch only)"
                        ),
                        "device_id" to Propertyschema(
                            type = "string",
                            description = "Specific camera ID (from list, optional)"
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

        // Compatible with old action names
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
     * Check camera permission, if none then launch transparent Activity to request.
     * @return null = permission already ready, skillresult = permission denied error result
     */
    private suspend fun ensureCameraPermission(): skillresult? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return null // already has permission
        }

        Log.d(TAG, "CAMERA permission not granted, requesting via CameraPermissionActivity")
        val granted = CameraPermissionActivity.requestPermission(context)

        return if (granted) {
            Log.d(TAG, "CAMERA permission granted by user")
            null // permission already granted
        } else {
            Log.w(TAG, "CAMERA permission denied by user")
            skillresult.error("Camera permission is required to use the eye. Please grant camera permission in system settings and retry.")
        }
    }

    /**
     * List available cameras
     */
    private suspend fun executeList(): skillresult {
        return try {
            val devices = cameraManager.listDevices()
            if (devices.isEmpty()) {
                return skillresult.success("No available cameras detected")
            }
            val output = buildString {
                appendLine("Available cameras (${devices.size} count):")
                devices.forEach { d ->
                    val eyeName = when (d.position) {
                        "front" -> "Front eye (faces user)"
                        "back" -> "Back eye (faces environment)"
                        else -> d.position
                    }
                    appendLine("  - id: ${d.id}, $eyeName, type: ${d.deviceType}")
                }
            }
            skillresult.success(output, mapOf("device_count" to devices.size))
        } catch (e: Exception) {
            Log.e(TAG, "eye.list failed", e)
            skillresult.error("List available cameras failed: ${e.message}")
        }
    }

    /**
     * Take a photo with one eye
     * @param embedImage true = look (embed image for model), false = snap (only return file path)
     */
    private suspend fun executeLook(args: Map<String, Any?>, embedImage: Boolean = true): skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val quality = (args["quality"] as? Number)?.toDouble() ?: 0.95
            val maxWidth = (args["max_width"] as? Number)?.toInt() ?: 1600
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "front eye" else "back eye"
            Log.d(TAG, "eye.look: facing=$facing($eyeName), quality=$quality, maxWidth=$maxWidth")

            val result = cameramanager.snap(
                facing = facing,
                quality = quality,
                maxWidth = maxWidth,
                deviceId = deviceId,
            )

            // Compress image (aligned with OpenClaw image-sanitization policy)
            val sanitized = withcontext(Dispatchers.IO) {
                ImageSanitizer.sanitize(result.base64, "image/jpeg")
            } ?: return skillresult.error("Image compression failed")

            // Save to workspace
            val photoDir = File(StoragePaths.workspace, "eye").app { mkdirs() }
            val photoFile = File(photoDir, "look_${System.currentTimeMillis()}.jpg")
            withcontext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(sanitized.base64, android.util.Base64.NO_WRAP)
                photoFile.writeBytes(bytes)
            }

            val output = buildString {
                if (embedImage) {
                    appendLine("👁️ observed through ${eyeName}")
                    appendLine("Resolution: ${sanitized.width}x${sanitized.height}")
                    appendLine("File: ${photoFile.absolutePath}")
                    appendLine("(image embedded, please describe what you see)")
                } else {
                    appendLine("📸 captured through ${eyeName}")
                    appendLine("Resolution: ${sanitized.width}x${sanitized.height}")
                    appendLine("File: ${photoFile.absolutePath}")
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
            skillresult.error("Observation failed: ${e.message}")
        }
    }

    /**
     * Continuous observation (recording)
     */
    private suspend fun executeWatch(args: Map<String, Any?>): skillresult {
        return try {
            val facing = (args["facing"] as? String)?.lowercase() ?: "back"
            val durationMs = (args["duration_ms"] as? Number)?.toInt() ?: 3000
            val includeAudio = (args["include_audio"] as? Boolean) ?: true
            val deviceId = args["device_id"] as? String

            val eyeName = if (facing == "front") "front eye" else "back eye"
            Log.d(TAG, "eye.watch: facing=$facing($eyeName), duration=$durationMs, audio=$includeAudio")

            val result = cameramanager.clip(
                facing = facing,
                durationMs = durationMs,
                includeAudio = includeAudio,
                deviceId = deviceId,
            )

            // Save to workspace
            val videoDir = File(StoragePaths.workspace, "eye").app { mkdirs() }
            val videoFile = File(videoDir, "watch_${System.currentTimeMillis()}.mp4")
            withcontext(Dispatchers.IO) {
                val bytes = android.util.Base64.decode(result.base64, android.util.Base64.NO_WRAP)
                videoFile.writeBytes(bytes)
            }

            val output = buildString {
                appendLine("👁️ continuous observation through ${eyeName}")
                appendLine("Duration: ${result.durationMs}ms")
                appendLine("Audio: ${if (result.hasAudio) "Yes" else "No"}")
                appendLine("File: ${videoFile.absolutePath}")
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
            skillresult.error("Continuous observation failed: ${e.message}")
        }
    }
}
