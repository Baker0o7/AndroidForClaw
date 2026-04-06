package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */


import android.content.Context
import android.graphics.Bitmap
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.media.ImageSanitizer
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.ToolDefinition
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Screenshot Skill
 * Capture current screen + UI tree (complete information)
 *
 * Note: This tool has high overhead (requires screenshot + UI tree), please use get_view_tree first.
 * Only use in the following cases:
 * - Need to view visual information (colors, icons, images)
 * - Operation failed and needs visual confirmation
 * - UI tree information is insufficient
 */
class ScreenshotSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ScreenshotSkill"
    }

    override val name = "screenshot"
    override val description = "Capture screen image with UI tree (prefer get_view_tree for most cases)"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Skillresult {
        Log.d(TAG, "Taking screenshot with UI tree...")

        // Screenshot function is always enabled, controlled by MediaProjection permission

        return try {
            // 1. Get UI tree (always enabled)
            val (originalNodes, processedNodes) = run {
                val result = DeviceController.detectIcons(context)
                if (result == null) {
                    Log.w(TAG, "CannotGet UI Tree(AccessibilityService未Enabledd或Failed), ContinueScreenshot")
                    Pair(emptyList(), emptyList())
                } else {
                    result
                }
            }
            Log.d(TAG, "UI tree captured: ${processedNodes.size} nodes")

            // 2. Brief delay to ensure UI stability
            // ⚡ Optimization: reduce to 50ms
            delay(50)

            // 3. Take screenshot (MediaProjection → shell screencap fallback)
            var screenshotresult = DeviceController.getScreenshot(context)
            if (screenshotresult == null) {
                Log.w(TAG, "MediaProjection unavailable, trying shell screencap fallback...")
                screenshotresult = try {
                    val screenshotPath = "${StoragePaths.workspaceScreenshots.absolutePath}/screenshot_${System.currentTimeMillis()}.png"
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $screenshotPath"))
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    val file = java.io.File(screenshotPath)
                    if (file.exists() && file.length() > 0) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(screenshotPath)
                        if (bitmap != null) {
                            Log.d(TAG, "Shell screencap fallback succeeded: $screenshotPath")
                            Pair(bitmap, screenshotPath)
                        } else null
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Shell screencap fallback failed: ${e.message}")
                    null
                }
            }
            if (screenshotresult == null) {
                return Skillresult.error("Screenshot failed: MediaProjection not authorized and shell screencap unavailable. Please open the app and grant screen capture permission.")
            }

            val (bitmap, path) = screenshotresult
            Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}, path: $path")

            // 4. Compress screenshot for model (aligned with OpenClaw image-sanitization)
            val imageBlock = withContext(Dispatchers.IO) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                val rawBytes = baos.toByteArray()
                val rawBase64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)
                val sanitized = ImageSanitizer.sanitize(rawBase64, "image/jpeg")
                if (sanitized != null) {
                    ImageBlock(base64 = sanitized.base64, mimeType = sanitized.mimeType)
                } else {
                    // Fallback: use raw JPEG if sanitization fails
                    ImageBlock(base64 = rawBase64, mimeType = "image/jpeg")
                }
            }

            // 5. Combine output
            val output = buildString {
                appendLine("【ScreenshotInfo】")
                appendLine("分辨率: ${bitmap.width}x${bitmap.height}")
                appendLine("Path: $path")
                appendLine("(Screenshot已Inside嵌, 请直接Description你看到的Inside容)")
                appendLine()

                appendLine("【Screen UI Element】(共 ${processedNodes.size} 个)")
                appendLine()

                processedNodes.forEachIndexed { index, node ->
                    val text = node.text?.takeIf { it.isNotBlank() }
                        ?: node.contentDesc?.takeIf { it.isNotBlank() }
                        ?: "[NoneText]"

                    append("[$index] \"$text\" (${node.point.x}, ${node.point.y})")

                    if (node.clickable) {
                        append(" [可click]")
                    }

                    appendLine()
                }

                appendLine()
                appendLine("Hint: use坐标 (x,y) IntoRow tap Action")
            }

            Skillresult.success(
                output,
                mapOf(
                    "screenshot_path" to path,
                    "width" to bitmap.width,
                    "height" to bitmap.height,
                    "view_count" to processedNodes.size,
                    "original_count" to originalNodes.size
                ),
                images = listOf(imageBlock)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot with UI tree failed", e)
            Skillresult.error("Screenshot with UI tree failed: ${e.message}")
        }
    }
}
