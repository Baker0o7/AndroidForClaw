package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.content.context
import android.graphics.Bitmap
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.DeviceController
import com.xiaomo.androidforclaw.media.ImageSanitizer
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withcontext
import java.io.ByteArrayOutputStream

/**
 * Screenshot skill
 * Capture current screen + UI tree (complete information)
 *
 * note: This tool has high overhead (requires screenshot + UI tree), please use get_view_tree first.
 * Only use in the following cases:
 * - need to view visual information (colors, icons, images)
 * - Operation failed and needs visual confirmation
 * - UI tree information is insufficient
 */
class Screenshotskill(private val context: context) : skill {
    companion object {
        private const val TAG = "Screenshotskill"
    }

    override val name = "screenshot"
    override val description = "Capture screen image with UI tree (prefer get_view_tree for most cases)"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        Log.d(TAG, "Taking screenshot with UI tree...")

        // Screenshot function is always enabled, controlled by MediaProjection permission

        return try {
            // 1. Get UI tree (always enabled)
            val (originalNodes, processedNodes) = run {
                val result = DeviceController.detectIcons(context)
                if (result == null) {
                    Log.w(TAG, "cannotGet UI Tree(AccessibilityservicenotEnableorFailed), ContinueScreenshot")
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
                    process.waitfor(5, java.util.concurrent.TimeUnit.SECONDS)
                    val file = java.io.File(screenshotPath)
                    if (file.exists() && file.length() > 0) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(screenshotPath)
                        if (bitmap != null) {
                            Log.d(TAG, "Shell screencap fallback succeeded: $screenshotPath")
                            Pair(bitmap, screenshotPath)
                        } else null
                    } else null
                } catch (e: exception) {
                    Log.w(TAG, "Shell screencap fallback failed: ${e.message}")
                    null
                }
            }
            if (screenshotresult == null) {
                return skillresult.error("Screenshot failed: MediaProjection not authorized and shell screencap unavailable. please open the app and grant screen capture permission.")
            }

            val (bitmap, path) = screenshotresult
            Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}, path: $path")

            // 4. compress screenshot for model (aligned with OpenClaw image-sanitization)
            val imageBlock = withcontext(Dispatchers.IO) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.compressformat.JPEG, 90, baos)
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
                appendLine("minute辨率: ${bitmap.width}x${bitmap.height}")
                appendLine("Path: $path")
                appendLine("(Screenshotalreadyinside嵌, please直接Description你seetocontent)")
                appendLine()

                appendLine("【Screen UI Element】(共 ${processedNodes.size} count)")
                appendLine()

                processedNodes.forEachIndexed { index, node ->
                    val text = node.text?.takeif { it.isnotBlank() }
                        ?: node.contentDesc?.takeif { it.isnotBlank() }
                        ?: "[NoneText]"

                    append("[$index] \"$text\" (${node.point.x}, ${node.point.y})")

                    if (node.clickable) {
                        append(" [canclick]")
                    }

                    appendLine()
                }

                appendLine()
                appendLine("Hint: use坐标 (x,y) intoRow tap Action")
            }

            skillresult.success(
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
        } catch (e: exception) {
            Log.e(TAG, "Screenshot with UI tree failed", e)
            skillresult.error("Screenshot with UI tree failed: ${e.message}")
        }
    }
}
