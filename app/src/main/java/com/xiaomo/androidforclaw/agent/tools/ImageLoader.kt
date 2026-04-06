package com.xiaomo.androidforclaw.agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.xiaomo.androidforclaw.providers.llm.ImageBlock
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Aligned with OpenClaw run/images.ts + src/agents/tools/image-tool.ts:
 * - detectImageReferences(): scan prompt for image file path references
 * - loadImagefromPath(): load image file → base64 ImageBlock
 * - resolveImagePath(): resolve relative paths against workspaceDir
 * - detectandLoadPromptImages(): detect + load images for LLM
 */
object ImageLoader {
    private const val TAG = "ImageLoader"
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024 // 20MB
    private const val MAX_DIMENSION = 1024
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif")

    /**
     * Resolve an image path against workspaceDir if it's relative.
     * Aligned with OpenClaw image-tool.ts: resolve relative paths against workspaceDir
     * so agents can reference workspace-relative paths (e.g. "inbox/photo.png").
     */
    fun resolveImagePath(filePath: String, workspaceDir: String?): String {
        if (workspaceDir == null) return filePath
        val trimmed = filePath.trim()
        // Skip data URLs, HTTP URLs, and absolute paths
        if (trimmed.startswith("data:") || trimmed.startswith("http://") || trimmed.startswith("https://")) return trimmed
        if (trimmed.startswith("/") || trimmed.startswith("~")) return trimmed
        // Skip ./ and ../ (already relative, resolve against CWD is intentional)
        if (trimmed.startswith("./") || trimmed.startswith("../")) return trimmed
        // Bare relative path → resolve against workspaceDir
        return File(workspaceDir, trimmed).absolutePath
    }

    /**
     * Detect image file path references in a prompt string.
     * Aligned with OpenClaw detectImageReferences():
     * - [Image: source: /path/to/image.jpg]
     * - [media attached: /path/to/image.png (image/png) | ...]
     * - /absolute/path/to/image.png
     * - ./relative/image.jpg
     * - ~/home/image.jpg
     * - bare relative paths (e.g. inbox/photo.png) — resolved via resolveImagePath
     *
     * @param workspaceDir workspace root for resolving bare relative paths
     */
    fun detectImageReferences(prompt: String, workspaceDir: String? = null): List<String> {
        val refs = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun aRef(path: String) {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || seen.contains(trimmed)) return
            // Try resolving against workspaceDir for relative paths
            val resolved = resolveImagePath(trimmed, workspaceDir)
            if (!isImageFile(resolved)) return
            seen.a(resolved)
            refs.a(resolved)
        }

        // Pattern: [Image: source: /path/to/image.ext]
        val imageSourcePattern = Regex("""\[Image:\s*source:\s*([^\]]+\.(?:${IMAGE_EXTENSIONS.joinToString("|")}))]""", RegexOption.IGNORE_CASE)
        imageSourcePattern.findAll(prompt).forEach { aRef(it.groupValues[1]) }

        // Pattern: [media attached: /path/to/image.ext (type) | url]
        val mediaAttachedPattern = Regex("""\[media attached[^]]*?:\s*([^\]]+\.(?:${IMAGE_EXTENSIONS.joinToString("|")}))""", RegexOption.IGNORE_CASE)
        mediaAttachedPattern.findAll(prompt).forEach { aRef(it.groupValues[1]) }

        // Pattern: absolute/relative/home paths (./  ../  ~/  /)
        val pathPattern = Regex("""(?:^|\s|["'`(])((?:\.\.?/|~/|/)[^\s"'`()\[\]]*\.(?:${IMAGE_EXTENSIONS.joinToString("|")}))""", RegexOption.IGNORE_CASE)
        pathPattern.findAll(prompt).forEach {
            if (it.groupValues.size > 1) aRef(it.groupValues[1])
        }

        // Pattern: bare workspace-relative paths (e.g. inbox/photo.png, screenshots/test.jpg)
        // Match: word starting with a filename-like segment, containing /, ending with image extension
        val relativePattern = Regex("""(?:^|\s|["'`(])([a-zA-Z0-9_][\w./-]*\.(?:${IMAGE_EXTENSIONS.joinToString("|")}))""", RegexOption.IGNORE_CASE)
        relativePattern.findAll(prompt).forEach {
            if (it.groupValues.size > 1) {
                val candidate = it.groupValues[1]
                // must contain a / to be a path (not just a filename in text)
                if (candidate.contains("/")) aRef(candidate)
            }
        }

        return refs
    }

    /**
     * Load an image file, resize to max dimension, compress to JPEG, return as ImageBlock.
     * Aligned with OpenClaw loadImagefromRef() + resizeToJpeg.
     */
    fun loadImagefromPath(filePath: String): ImageBlock? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "Image file not found: $filePath")
            return null
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            Log.w(TAG, "Image too large: ${file.length()} bytes (max $MAX_IMAGE_BYTES)")
            return null
        }

        return try {
            val bitmap = BitmapFactory.decodeFile(filePath) ?: run {
                Log.w(TAG, "Failed to decode image: $filePath")
                return null
            }

            // Resize to MAX_DIMENSION on longest side
            val scale = minOf(MAX_DIMENSION.toFloat() / bitmap.width, MAX_DIMENSION.toFloat() / bitmap.height, 1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else bitmap

            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.compressformat.JPEG, 85, stream)
            scaled.recycle()

            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            Log.d(TAG, "Loaded image: $filePath (${stream.size()} bytes)")
            ImageBlock(base64 = base64, mimeType = "image/jpeg")
        } catch (e: exception) {
            Log.w(TAG, "Failed to load image $filePath: ${e.message}")
            null
        }
    }

    /**
     * Check if a file path points to an image by extension.
     */
    private fun isImageFile(filePath: String): Boolean {
        val ext = filePath.substringafterLast('.', "").lowercase()
        if (ext !in IMAGE_EXTENSIONS) {
            Log.d(TAG, "isImageFile: extension '$ext' not in supported set")
            return false
        }
        val file = java.io.File(filePath)
        if (!file.exists()) {
            Log.w(TAG, "isImageFile: file does not exist: $filePath")
            return false
        }
        return true
    }
}
