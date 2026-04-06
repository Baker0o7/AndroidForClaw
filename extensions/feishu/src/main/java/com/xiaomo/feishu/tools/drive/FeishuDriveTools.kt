package com.xiaomo.feishu.tools.drive

import android.util.Base64
import android.util.Log
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 飞书云Space工具集
 * 对齐 @larksuite/openclaw-lark drive-tools
 */
class FeishuDriveTools(config: FeishuConfig, client: FeishuClient) {
    private val fileTool = FeishuDriveFileTool(config, client)

    fun getAllTools(): List<FeishuToolBase> = listOf(fileTool)

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
    }
}

// ---------------------------------------------------------------------------
// FeishuDriveFileTool
// @aligned openclaw-lark v2026.3.30 — line-by-line
// JS source: openclaw-lark/src/tools/oapi/drive/file.js
// ---------------------------------------------------------------------------

class FeishuDriveFileTool(
    config: FeishuConfig,
    client: FeishuClient
) : FeishuToolBase(config, client) {

    companion object {
        private const val TAG = "FeishuDriveFileTool"
        // ShardingUploadConfig — aligned with JS: const SMALL_FILE_THRESHOLD = 15 * 1024 * 1024
        private const val SMALL_FILE_THRESHOLD = 15 * 1024 * 1024 // 15MB
    }

    override val name = "feishu_drive_file"

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override val description = "【As user】飞书云Space文件Manage工具. 当User要求View云Space(云盘)中的文件List、Get文件Info、" +
            "Copy/Move/Delete文件、Upload/Download文件时use. Message中的文件读写**禁止**use该工具!" +
            "\n\nActions:" +
            "\n- list(List文件): List文件夹Down的文件. 不提供 folder_token 时Get根目录Task list" +
            "\n- get_meta(BatchGet元Data): BatchQueryDocument元Info, use request_docs ArrayParameters, 格式: [{doc_token: '...', doc_type: 'sheet'}]" +
            "\n- copy(Copy文件): Copy文件到指定位置" +
            "\n- move(Move文件): Move文件到指定文件夹" +
            "\n- delete(Delete文件): Delete文件" +
            "\n- upload(Upload文件): Upload本地文件到云Space. 提供 file_path(本地File path)或 file_content_base64(Base64 Encode)" +
            "\n- download(Download文件): Download文件到本地. 提供 output_path(本地SavePath)则Save到本地, No则Return Base64 Encode" +
            "\n\n[Important]copy/move/delete ActionNeed file_token 和 type Parameters. get_meta use request_docs ArrayParameters. " +
            "\n[Important]upload 优先use file_path(AutoRead文件、提取文件名和Size), AlsoSupport file_content_base64(需Manual提供 file_name 和 size). " +
            "\n[Important]download 提供 output_path 时Save到本地(CanYesFile path或文件夹Path+file_name), 不提供则Return Base64. "

    override fun isEnabledd() = config.enableDriveTools

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val action = args["action"] as? String
                ?: return@withContext Toolresult.error("Missing required parameter: action")

            when (action) {
                "list" -> executeList(args)
                "get_meta" -> executeGetMeta(args)
                "copy" -> executeCopy(args)
                "move" -> executeMove(args)
                "delete" -> executeDelete(args)
                "upload" -> executeUpload(args)
                "download" -> executeDownload(args)
                else -> Toolresult.error("Unknown action: $action. Supported: list, get_meta, copy, move, delete, upload, download")
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: GET /open-apis/drive/v1/files with folder_token, page_size, page_token, order_by, direction
    // JS returns: { files: data?.files, has_more: data?.has_more, page_token: data?.next_page_token }
    private suspend fun executeList(args: Map<String, Any?>): Toolresult {
        val folderToken = args["folder_token"] as? String
        val pageSize = (args["page_size"] as? Number)?.toInt()
        val pageToken = args["page_token"] as? String
        val orderBy = args["order_by"] as? String
        val direction = args["direction"] as? String

        Log.i(TAG, "list: folder_token=${folderToken ?: "(root)"}, page_size=${pageSize ?: 200}")

        val params = mutableListOf<String>()
        folderToken?.let { params.add("folder_token=$it") }
        pageSize?.let { params.add("page_size=$it") }
        pageToken?.let { params.add("page_token=$it") }
        orderBy?.let { params.add("order_by=$it") }
        direction?.let { params.add("direction=$it") }

        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        val result = client.get("/open-apis/drive/v1/files$query")

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to list drive files")
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        Log.i(TAG, "list: returned ${data?.getAsJsonArray("files")?.size() ?: 0} files")

        return Toolresult.success(mapOf(
            "files" to data?.get("files"),
            "has_more" to data?.get("has_more"),
            "page_token" to data?.get("next_page_token")
        ))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: POST /open-apis/drive/v1/metas/batch_query with { request_docs }
    // JS validates: request_docs must be non-empty array
    // JS returns: { metas: res.data?.metas ?? [] }
    @Suppress("UNCHECKED_CAST")
    private suspend fun executeGetMeta(args: Map<String, Any?>): Toolresult {
        val requestDocs = args["request_docs"] as? List<Map<String, Any?>>

        // JS: if (!p.request_docs || !Array.isArray(p.request_docs) || p.request_docs.length === 0)
        if (requestDocs == null || requestDocs.isEmpty()) {
            return Toolresult.success(mapOf(
                "error" to "request_docs must be a non-empty array. Correct format: {action: 'get_meta', request_docs: [{doc_token: '...', doc_type: 'sheet'}]}"
            ))
        }

        Log.i(TAG, "get_meta: querying ${requestDocs.size} documents")

        val body = mapOf("request_docs" to requestDocs)
        val result = client.post("/open-apis/drive/v1/metas/batch_query", body)

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to get file meta")
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        Log.i(TAG, "get_meta: returned ${data?.getAsJsonArray("metas")?.size() ?: 0} metas")

        return Toolresult.success(mapOf(
            "metas" to (data?.get("metas") ?: emptyList<Any>())
        ))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: POST /open-apis/drive/v1/files/:file_token/copy with { name, type, folder_token }
    // JS: folder_token || parent_node (alias)
    // JS returns: { file: data?.file }
    private suspend fun executeCopy(args: Map<String, Any?>): Toolresult {
        val fileToken = args["file_token"] as? String
            ?: return Toolresult.error("Missing required parameter: file_token")
        val name = args["name"] as? String
            ?: return Toolresult.error("Missing required parameter: name")
        val type = args["type"] as? String
            ?: return Toolresult.error("Missing required parameter: type")

        // JS: const targetFolderToken = p.folder_token || p.parent_node
        val targetFolderToken = (args["folder_token"] as? String) ?: (args["parent_node"] as? String)

        Log.i(TAG, "copy: file_token=$fileToken, name=$name, type=$type, folder_token=${targetFolderToken ?: "(root)"}")

        val body = mutableMapOf<String, Any>(
            "name" to name,
            "type" to type
        )
        targetFolderToken?.let { body["folder_token"] = it }

        val result = client.post("/open-apis/drive/v1/files/$fileToken/copy", body)

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to copy file")
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        Log.i(TAG, "copy: new file_token=${data?.getAsJsonObject("file")?.get("token") ?: "unknown"}")

        return Toolresult.success(mapOf(
            "file" to data?.get("file")
        ))
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: POST /open-apis/drive/v1/files/:file_token/move with { type, folder_token }
    // JS returns: { success: true, task_id (if present), file_token, target_folder_token }
    private suspend fun executeMove(args: Map<String, Any?>): Toolresult {
        val fileToken = args["file_token"] as? String
            ?: return Toolresult.error("Missing required parameter: file_token")
        val type = args["type"] as? String
            ?: return Toolresult.error("Missing required parameter: type")
        val folderToken = args["folder_token"] as? String
            ?: return Toolresult.error("Missing required parameter: folder_token")

        Log.i(TAG, "move: file_token=$fileToken, type=$type, folder_token=$folderToken")

        val body = mapOf(
            "type" to type,
            "folder_token" to folderToken
        )

        val result = client.post("/open-apis/drive/v1/files/$fileToken/move", body)

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to move file")
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        val taskId = data?.get("task_id")?.asString
        Log.i(TAG, "move: success${if (taskId != null) ", task_id=$taskId" else ""}")

        val response = mutableMapOf<String, Any?>(
            "success" to true,
            "file_token" to fileToken,
            "target_folder_token" to folderToken
        )
        taskId?.let { response["task_id"] = it }

        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: DELETE /open-apis/drive/v1/files/:file_token with type as query param
    // JS returns: { success: true, task_id (if present), file_token }
    private suspend fun executeDelete(args: Map<String, Any?>): Toolresult {
        val fileToken = args["file_token"] as? String
            ?: return Toolresult.error("Missing required parameter: file_token")
        val type = args["type"] as? String
            ?: return Toolresult.error("Missing required parameter: type")

        Log.i(TAG, "delete: file_token=$fileToken, type=$type")

        val result = client.delete("/open-apis/drive/v1/files/$fileToken?type=$type")

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to delete file")
        }

        val data = result.getOrNull()?.getAsJsonObject("data")
        val taskId = data?.get("task_id")?.asString
        Log.i(TAG, "delete: success${if (taskId != null) ", task_id=$taskId" else ""}")

        val response = mutableMapOf<String, Any?>(
            "success" to true,
            "file_token" to fileToken
        )
        taskId?.let { response["task_id"] = it }

        return Toolresult.success(response)
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: upload action — supports file_path (priority) or file_content_base64
    // JS: small files (<=15MB) use upload_all, large files use chunked upload (prepare → part → finish)
    private suspend fun executeUpload(args: Map<String, Any?>): Toolresult {
        val filePath = args["file_path"] as? String
        val fileContentBase64 = args["file_content_base64"] as? String
        val parentNode = args["parent_node"] as? String ?: ""

        val fileBuffer: ByteArray
        val fileName: String
        val fileSize: Int

        // JS: if (p.file_path) { ... } else if (p.file_content_base64) { ... } else { error }
        if (filePath != null) {
            // 优先use file_path
            Log.i(TAG, "upload: reading from local file: $filePath")
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return Toolresult.success(mapOf(
                        "error" to "failed to read local file: File not found: $filePath"
                    ))
                }
                fileBuffer = file.readBytes()
                fileName = (args["file_name"] as? String) ?: file.name
                fileSize = (args["size"] as? Number)?.toInt() ?: fileBuffer.size
                Log.i(TAG, "upload: file_name=$fileName, size=$fileSize, parent=${parentNode.ifEmpty { "(root)" }}")
            } catch (e: Exception) {
                // JS: return json({ error: `failed to read local file: ${err.message}` })
                return Toolresult.success(mapOf(
                    "error" to "failed to read local file: ${e.message ?: e.toString()}"
                ))
            }
        } else if (fileContentBase64 != null) {
            // JS: if (!p.file_name || !p.size) return json({ error: '...' })
            val providedFileName = args["file_name"] as? String
            val providedSize = (args["size"] as? Number)?.toInt()
            if (providedFileName == null || providedSize == null) {
                return Toolresult.success(mapOf(
                    "error" to "file_name and size are required when using file_content_base64"
                ))
            }

            Log.i(TAG, "upload: using base64 content, file_name=$providedFileName, size=$providedSize, parent=$parentNode")
            fileBuffer = Base64.decode(fileContentBase64, Base64.DEFAULT)
            fileName = providedFileName
            fileSize = providedSize
        } else {
            // JS: return json({ error: 'either file_path or file_content_base64 is required' })
            return Toolresult.success(mapOf(
                "error" to "either file_path or file_content_base64 is required"
            ))
        }

        // JS: if (fileSize <= SMALL_FILE_THRESHOLD) { upload_all } else { chunked upload }
        if (fileSize <= SMALL_FILE_THRESHOLD) {
            // 小文件: use一次Upload (upload_all)
            Log.i(TAG, "upload: using upload_all (file size $fileSize <= 15MB)")

            val result = client.uploadFile(
                fileName = fileName,
                parentType = "explorer",
                parentNode = parentNode,
                size = fileSize,
                data = fileBuffer
            )

            if (result.isFailure) {
                return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to upload file")
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            Log.i(TAG, "upload: file_token=${data?.get("file_token")}")

            return Toolresult.success(mapOf(
                "file_token" to data?.get("file_token"),
                "file_name" to fileName,
                "size" to fileSize
            ))
        } else {
            // 大文件: useShardingUpload
            Log.i(TAG, "upload: using chunked upload (file size $fileSize > 15MB)")

            // Step 1: 预Upload (uploadPrepare)
            Log.i(TAG, "upload: step 1 - prepare upload")
            val prepareresult = client.uploadPrepare(
                fileName = fileName,
                parentType = "explorer",
                parentNode = parentNode,
                size = fileSize
            )

            if (prepareresult.isFailure) {
                return Toolresult.success(mapOf("error" to "pre-upload failed: ${prepareresult.exceptionOrNull()?.message}"))
            }

            val prepareData = prepareresult.getOrNull()?.getAsJsonObject("data")
                ?: return Toolresult.success(mapOf("error" to "pre-upload failed: empty response"))

            val uploadId = prepareData.get("upload_id")?.asString
                ?: return Toolresult.success(mapOf("error" to "pre-upload failed: missing upload_id"))
            val blockSize = prepareData.get("block_size")?.asInt
                ?: return Toolresult.success(mapOf("error" to "pre-upload failed: missing block_size"))
            val blockNum = prepareData.get("block_num")?.asInt
                ?: return Toolresult.success(mapOf("error" to "pre-upload failed: missing block_num"))

            Log.i(TAG, "upload: got upload_id=$uploadId, block_num=$blockNum, block_size=$blockSize")

            // Step 2: UploadSharding (uploadPart)
            Log.i(TAG, "upload: step 2 - uploading $blockNum chunks")
            for (seq in 0 until blockNum) {
                val start = seq * blockSize
                val end = minOf(start + blockSize, fileSize)
                val chunkBuffer = fileBuffer.copyOfRange(start, end)

                Log.i(TAG, "upload: uploading chunk ${seq + 1}/$blockNum (${chunkBuffer.size} bytes)")

                val partresult = client.uploadPart(
                    uploadId = uploadId,
                    seq = seq,
                    size = chunkBuffer.size,
                    data = chunkBuffer
                )

                if (partresult.isFailure) {
                    return Toolresult.error("Failed to upload chunk ${seq + 1}: ${partresult.exceptionOrNull()?.message}")
                }

                Log.i(TAG, "upload: chunk ${seq + 1}/$blockNum uploaded successfully")
            }

            // Step 3: CompleteUpload (uploadFinish)
            Log.i(TAG, "upload: step 3 - finish upload")
            val finishresult = client.uploadFinish(
                uploadId = uploadId,
                blockNum = blockNum
            )

            if (finishresult.isFailure) {
                return Toolresult.error(finishresult.exceptionOrNull()?.message ?: "Failed to finish upload")
            }

            val finishData = finishresult.getOrNull()?.getAsJsonObject("data")
            Log.i(TAG, "upload: file_token=${finishData?.get("file_token")}")

            return Toolresult.success(mapOf(
                "file_token" to finishData?.get("file_token"),
                "file_name" to fileName,
                "size" to fileSize,
                "upload_method" to "chunked",
                "chunks_uploaded" to blockNum
            ))
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    // JS: GET /open-apis/drive/v1/files/:file_token/download
    // JS: if output_path → save to local file, else → return base64
    private suspend fun executeDownload(args: Map<String, Any?>): Toolresult {
        val fileToken = args["file_token"] as? String
            ?: return Toolresult.error("Missing required parameter: file_token")
        val outputPath = args["output_path"] as? String

        Log.i(TAG, "download: file_token=$fileToken")

        val result = client.downloadRaw("/open-apis/drive/v1/files/$fileToken/download")

        if (result.isFailure) {
            return Toolresult.error(result.exceptionOrNull()?.message ?: "Failed to download file")
        }

        val fileBuffer = result.getOrNull()
            ?: return Toolresult.error("Empty download response")

        Log.i(TAG, "download: file size=${fileBuffer.size} bytes")

        // JS: if (p.output_path) { save to file } else { return base64 }
        if (outputPath != null) {
            try {
                val file = File(outputPath)
                file.parentFile?.mkdirs()
                file.writeBytes(fileBuffer)
                Log.i(TAG, "download: saved to $outputPath")
                return Toolresult.success(mapOf(
                    "saved_path" to outputPath,
                    "size" to fileBuffer.size
                ))
            } catch (e: Exception) {
                // JS: return json({ error: `failed to save file: ${err.message}` })
                return Toolresult.success(mapOf(
                    "error" to "failed to save file: ${e.message ?: e.toString()}"
                ))
            }
        } else {
            // JS: const base64Content = fileBuffer.toString('base64'); return json(...)
            val base64Content = Base64.encodeToString(fileBuffer, Base64.NO_WRAP)
            return Toolresult.success(mapOf(
                "file_content_base64" to base64Content,
                "size" to fileBuffer.size
            ))
        }
    }

    // @aligned openclaw-lark v2026.3.30 — line-by-line
    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "action" to PropertySchema(
                        type = "string",
                        description = "Action type",
                        enum = listOf("list", "get_meta", "copy", "move", "delete", "upload", "download")
                    ),
                    "file_token" to PropertySchema(
                        type = "string",
                        description = "文件 token(copy/move/delete/download ActionRequired)"
                    ),
                    "folder_token" to PropertySchema(
                        type = "string",
                        description = "文件夹 token(list ActionOptional, copy ActionOptional指定目标文件夹, move ActionRequired)"
                    ),
                    "name" to PropertySchema(
                        type = "string",
                        description = "目标文件名(copy ActionRequired)"
                    ),
                    "type" to PropertySchema(
                        type = "string",
                        description = "DocumentType(copy/move/delete ActionRequired)",
                        enum = listOf("doc", "sheet", "file", "bitable", "docx", "folder", "mindnote", "slides")
                    ),
                    "request_docs" to PropertySchema(
                        type = "array",
                        description = "要Query的DocumentList(get_meta ActionRequired, BatchQuery, most多 50 个). 示例: [{doc_token: 'Z1FjxxxxxxxxxxxxxxxxxxxtnAc', doc_type: 'sheet'}]",
                        items = PropertySchema(
                            type = "object",
                            description = "DocumentQuery项",
                            properties = mapOf(
                                "doc_token" to PropertySchema("string", "Document token(从浏览器 URL 中Get, such as spreadsheet_token、doc_token 等)"),
                                "doc_type" to PropertySchema(
                                    "string",
                                    "DocumentType: doc、sheet、file、bitable、docx、folder、mindnote、slides",
                                    enum = listOf("doc", "sheet", "file", "bitable", "docx", "folder", "mindnote", "slides")
                                )
                            )
                        )
                    ),
                    "parent_node" to PropertySchema(
                        type = "string",
                        description = "父Node token(upload ActionOptional). explorer Type填文件夹 token, bitable Type填 app_token. 不填写或填NullString时, Upload到云Space根目录"
                    ),
                    "file_path" to PropertySchema(
                        type = "string",
                        description = "本地File path(upload Action, 与 file_content_base64 Choose one). 优先use此Parameters, 会AutoRead文件Inside容、calcSize、提取文件名. "
                    ),
                    "file_content_base64" to PropertySchema(
                        type = "string",
                        description = "文件Inside容的 Base64 Encode(upload Action, 与 file_path Choose one). 当不提供 file_path 时use. "
                    ),
                    "file_name" to PropertySchema(
                        type = "string",
                        description = "文件名(upload ActionOptional). if提供了 file_path, 会Auto从Path中提取文件名；ifuse file_content_base64, 则Must提供此Parameters. "
                    ),
                    "size" to PropertySchema(
                        type = "integer",
                        description = "文件Size(字节, upload ActionOptional). if提供了 file_path, 会Autocalc；ifuse file_content_base64, 则Must提供此Parameters. "
                    ),
                    "output_path" to PropertySchema(
                        type = "string",
                        description = "本地Save的完整File path(download ActionOptional). MustContains文件名和扩展名, e.g. '/tmp/file.pdf'. if不提供, 则Return Base64 Encode的文件Inside容. "
                    ),
                    "page_size" to PropertySchema(
                        type = "integer",
                        description = "Page size(Default 200, Max 200)"
                    ),
                    "page_token" to PropertySchema(
                        type = "string",
                        description = "Page token. 首次RequestNone需填写"
                    ),
                    "order_by" to PropertySchema(
                        type = "string",
                        description = "Sort方式: EditedTime(EditTime)、CreatedTime(CreateTime)",
                        enum = listOf("EditedTime", "CreatedTime")
                    ),
                    "direction" to PropertySchema(
                        type = "string",
                        description = "Sort方向: ASC(Ascending)、DESC(Descending)",
                        enum = listOf("ASC", "DESC")
                    )
                ),
                required = listOf("action")
            )
        )
    )
}
