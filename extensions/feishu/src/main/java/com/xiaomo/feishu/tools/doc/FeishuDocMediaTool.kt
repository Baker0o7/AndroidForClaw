package com.xiaomo.feishu.tools.doc

// @aligned openclaw-lark v2026.3.30 — line-by-line translation
/**
 * Line-by-line translation of official @larksuite/openclaw-lark feishu_doc_media OAPI tool.
 * Official JS source: /tools/oapi/drive/doc-media.js
 *
 * Actions: insert (image/file into doc), download (media/whiteboard).
 */

import android.util.Log
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "FeishuDocMediaTool"

// @aligned openclaw-lark v2026.3.30 — line-by-line (matching official MIME_TO_EXT)
private val MIME_TO_EXT = mapOf(
    "image/png" to ".png",
    "image/jpeg" to ".jpg",
    "image/jpg" to ".jpg",
    "image/gif" to ".gif",
    "image/webp" to ".webp",
    "image/svg+xml" to ".svg",
    "image/bmp" to ".bmp",
    "image/tiff" to ".tiff",
    "video/mp4" to ".mp4",
    "video/mpeg" to ".mpeg",
    "video/quicktime" to ".mov",
    "video/x-msvideo" to ".avi",
    "video/webm" to ".webm",
    "application/pdf" to ".pdf",
    "application/msword" to ".doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx",
    "application/vnd.ms-excel" to ".xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
    "application/vnd.ms-powerpoint" to ".ppt",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
    "application/zip" to ".zip",
    "application/x-rar-compressed" to ".rar",
    "text/plain" to ".txt",
    "application/json" to ".json",
)

// @aligned openclaw-lark v2026.3.30 — line-by-line (matching official ALIGN_MAP)
private val ALIGN_MAP = mapOf(
    "left" to 1,
    "center" to 2,
    "right" to 3,
)

// @aligned openclaw-lark v2026.3.30 — line-by-line (matching official MAX_FILE_SIZE)
private const val MAX_FILE_SIZE = 20L * 1024 * 1024 // 20MB

class FeishuDocMediaTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_doc_media"
    // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official description)
    override val description = "[As user] Document media management tool. " +
        "Supports two actions: " +
        "(1) insert - Insert local image or file at end of Feishu document (requires document ID + local file path);" +
        "(2) download - Download document media or whiteboard thumbnail to local (requires resource token + output path). " +
        "\n\n[Important] insert only supports local file path. For URL images, use create-doc/update-doc <image url=\"...\"/> syntax. "

    override fun isEnabledd() = config.enableDocTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official action dispatch)
            when (action) {
                "insert" -> handleInsert(args)
                "download" -> handleDownload(args)
                else -> Toolresult.error("unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feishu_doc_media failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official handleInsert)
    private suspend fun handleInsert(args: Map<String, Any?>): Toolresult {
        val rawDocId = args["doc_id"] as? String
            ?: return Toolresult.error("Missing doc_id for insert action")
        val documentId = extractDocumentId(rawDocId)
        val filePath = args["file_path"] as? String
            ?: return Toolresult.error("Missing file_path for insert action")
        val mediaType = args["type"] as? String ?: "image"
        val align = args["align"] as? String ?: "center"
        val caption = args["caption"] as? String

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official file validation)
        val file = File(filePath)
        if (!file.exists()) {
            return Toolresult.error("failed to read file: File not found")
        }

        val fileSize = file.length()
        if (fileSize > MAX_FILE_SIZE) {
            return Toolresult.error("file ${(fileSize.toDouble() / 1024 / 1024).let { "%.1f".format(it) }}MB exceeds 20MB limit")
        }

        val fileName = file.name
        Log.i(TAG, "insert: doc=$documentId, type=$mediaType, file=$fileName, size=$fileSize")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official MEDIA_CONFIG)
        val blockType: Int
        val blockData: Map<String, Any?>
        val parentType: String
        val label: String

        if (mediaType == "image") {
            blockType = 27
            blockData = mapOf("image" to emptyMap<String, Any?>())
            parentType = "docx_image"
            label = "image"
        } else {
            blockType = 23
            blockData = mapOf("file" to mapOf("token" to ""))
            parentType = "docx_file"
            label = "file"
        }

        // @aligned openclaw-lark v2026.3.30 — line-by-line (Step 2: create empty block)
        val createBody = mapOf(
            "children" to listOf(
                mapOf("block_type" to blockType) + blockData
            )
        )
        val createRes = client.post(
            "/open-apis/docx/v1/documents/$documentId/blocks/$documentId/children?document_revision_id=-1",
            createBody
        )
        if (createRes.isFailure) {
            return Toolresult.error("failed to create $label block: ${createRes.exceptionOrNull()?.message}")
        }

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official block_id extraction)
        val data = createRes.getOrNull()?.getAsJsonObject("data")
        val children = data?.getAsJsonArray("children")
        val blockId: String? = if (mediaType == "file") {
            // File Block returns View Block (block_type: 33) as container
            children?.get(0)?.asJsonObject?.getAsJsonArray("children")?.get(0)?.asString
        } else {
            // Image Block returns directly
            children?.get(0)?.asJsonObject?.get("block_id")?.asString
        }

        if (blockId == null) {
            return Toolresult.error("failed to create $label block: no block_id returned")
        }

        Log.i(TAG, "insert: created $mediaType block $blockId")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (Step 3: upload media)
        val fileBytes = file.readBytes()
        val extra = mapOf("drive_route_token" to documentId)
        val uploadresult = client.uploadMedia(
            fileName = fileName,
            fileBytes = fileBytes,
            parentType = parentType,
            parentNode = blockId,
            extra = extra
        )

        if (uploadresult.isFailure) {
            return Toolresult.error("failed to upload $label media: ${uploadresult.exceptionOrNull()?.message}")
        }

        val fileToken = uploadresult.getOrNull()?.get("file_token")?.asString
        if (fileToken == null) {
            return Toolresult.error("failed to upload $label media: no file_token returned")
        }

        Log.i(TAG, "insert: uploaded media, file_token=$fileToken")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (Step 4: batch update block with token)
        val patchRequest = mutableMapOf<String, Any?>("block_id" to blockId)

        if (mediaType == "image") {
            // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official align/width/height/caption)
            val alignNum = ALIGN_MAP[align] ?: 2

            // Auto-detect image dimensions (matching official behavior)
            var width: Int? = null
            var height: Int? = null
            try {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    width = options.outWidth
                    height = options.outHeight
                    Log.i(TAG, "insert: detected image size ${width}x${height}")
                }
            } catch (e: Exception) {
                Log.i(TAG, "insert: could not detect image dimensions, skipping")
            }

            val replaceImage = mutableMapOf<String, Any?>(
                "token" to fileToken,
                "align" to alignNum
            )
            width?.let { replaceImage["width"] = it }
            height?.let { replaceImage["height"] = it }
            caption?.let { replaceImage["caption"] = mapOf("content" to it) }

            patchRequest["replace_image"] = replaceImage
        } else {
            patchRequest["replace_file"] = mapOf("token" to fileToken)
        }

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official batchUpdate call)
        val patchBody = mapOf("requests" to listOf(patchRequest))
        val patchRes = client.post(
            "/open-apis/docx/v1/documents/$documentId/blocks/batch_update?document_revision_id=-1",
            patchBody
        )

        if (patchRes.isFailure) {
            return Toolresult.error("failed to patch $label block: ${patchRes.exceptionOrNull()?.message}")
        }

        Log.i(TAG, "insert: patched $mediaType block with file_token")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official return format)
        return Toolresult.success(mapOf(
            "success" to true,
            "type" to mediaType,
            "document_id" to documentId,
            "block_id" to blockId,
            "file_token" to fileToken,
            "file_name" to fileName
        ))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official handleDownload)
    private suspend fun handleDownload(args: Map<String, Any?>): Toolresult {
        val resourceToken = args["resource_token"] as? String
            ?: return Toolresult.error("Missing resource_token for download action")
        val resourceType = args["resource_type"] as? String
            ?: return Toolresult.error("Missing resource_type for download action")
        val outputPath = args["output_path"] as? String
            ?: return Toolresult.error("Missing output_path for download action")

        Log.i(TAG, "download: resource_type=$resourceType, token=\"$resourceToken\"")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official API call)
        val result = if (resourceType == "media") {
            client.downloadRawWithHeaders("/open-apis/drive/v1/medias/$resourceToken/download")
        } else {
            client.downloadRawWithHeaders("/open-apis/board/v1/whiteboards/$resourceToken/download_as_image")
        }

        if (result.isFailure) {
            return Toolresult.error("failed to download: ${result.exceptionOrNull()?.message}")
        }

        val (buffer, headers) = result.getOrNull() ?: return Toolresult.error("No data returned")

        Log.i(TAG, "download: received ${buffer.size} bytes")

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official Content-Type extension detection)
        val contentType = headers["content-type"] ?: ""
        var finalPath = outputPath
        val currentExt = File(outputPath).extension

        if (currentExt.isEmpty() && contentType.isNotEmpty()) {
            val mimeType = contentType.split(";")[0].trim()
            val defaultExt = if (resourceType == "whiteboard") ".png" else null
            val suggestedExt = MIME_TO_EXT[mimeType] ?: defaultExt

            if (suggestedExt != null) {
                finalPath = outputPath + suggestedExt
                Log.i(TAG, "download: auto-detected extension $suggestedExt")
            }
        }

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official file save)
        val outputFile = File(finalPath)
        try {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(buffer)
            Log.i(TAG, "download: saved to $finalPath")
        } catch (err: Exception) {
            return Toolresult.error("failed to save file: ${err.message}")
        }

        // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official return format)
        return Toolresult.success(mapOf(
            "resource_type" to resourceType,
            "resource_token" to resourceToken,
            "size_bytes" to buffer.size,
            "content_type" to contentType,
            "saved_path" to finalPath
        ))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official extractDocumentId)
    private fun extractDocumentId(input: String): String {
        val trimmed = input.trim()
        val urlMatch = Regex("/docx/([A-Za-z0-9]+)").find(trimmed)
        return urlMatch?.groupValues?.get(1) ?: trimmed
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line (matching official DocMediaSchema)
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "action" to PropertySchema("string", "Action: insert or download",
                        enum = listOf("insert", "download")),
                    "doc_id" to PropertySchema("string", "Document ID or document URL (required for insert). Supports auto-extract document_id from URL"),
                    "file_path" to PropertySchema("string", "Absolute local file path (required for insert). Images support jpg/png/gif/webp etc, files support any format, max 20MB"),
                    "type" to PropertySchema("string", "Media type: \"image\" (image, default) or \"file\" (file attachment)",
                        enum = listOf("image", "file")),
                    "align" to PropertySchema("string", "Alignment (only for images): \"center\" (default center), \"left\" (left), \"right\" (right)",
                        enum = listOf("left", "center", "right")),
                    "caption" to PropertySchema("string", "Image caption/title (optional, only for images)"),
                    "resource_token" to PropertySchema("string", "Unique identifier for resource (file_token for document media, whiteboard_id for whiteboard)"),
                    "resource_type" to PropertySchema("string", "Resource type: media (document media: images, videos, files, etc) or whiteboard (whiteboard thumbnail)",
                        enum = listOf("media", "whiteboard")),
                    "output_path" to PropertySchema("string", "Full local path to save file. Can include extension (e.g. /tmp/image.png), or without extension - system will auto-add based on Content-Type")
                ),
                required = listOf("action")
            )
        )
    )
}
