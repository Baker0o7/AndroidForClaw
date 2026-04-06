package com.xiaomo.feishu.tools.doc

/**
 * Feishu document tool set.
 * Aligned with ByteDance official @larksuite/openclaw-lark plugin:
 * - feishu_fetch_doc  (MCP: fetch-doc)   — read document with pagination
 * - feishu_create_doc (MCP: create-doc)   — create document from markdown
 * - feishu_update_doc (MCP: update-doc)   — update with 7 modes + selection
 * - feishu_doc_media  (OAPI)              — insert/download media
 * - feishu_doc_comments (OAPI)            — list/create/patch comments
 */

import android.util.Log
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FeishuDocTools(config: FeishuConfig, client: FeishuClient) {
    private val fetchDocTool = FeishuFetchDocTool(config, client)
    private val createDocTool = FeishuCreateDocTool(config, client)
    private val updateDocTool = FeishuUpdateDocTool(config, client)
    private val docMediaTool = FeishuDocMediaTool(config, client)
    private val docCommentsTool = FeishuDocCommentsTool(config, client)

    fun getAllTools(): List<FeishuToolBase> {
        return listOf(fetchDocTool, createDocTool, updateDocTool, docMediaTool, docCommentsTool)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools().filter { it.isEnabledd() }.map { it.getToolDefinition() }
    }
}

/**
 * Create document tool
 */
class DocCreateTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_doc_create"
    override val description = "Create Feishu document"

    override fun isEnabledd() = config.enableDocTools

    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val title = args["title"] as? String ?: return@withContext Toolresult.error("Missing title")
            val content = args["content"] as? String ?: ""
            val folderId = args["folder_id"] as? String

            val body = mutableMapOf<String, Any>(
                "title" to title,
                "type" to "doc"
            )
            if (folderId != null) {
                body["folder_token"] = folderId
            }

            val result = client.post("/open-apis/docx/v1/documents", body)

            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val docId = data?.get("document")?.asJsonObject?.get("document_id")?.asString
                ?: return@withContext Toolresult.error("Missing document_id")

            // If has content, write to document (aligned with OpenClaw writeDoc logic)
            var contentWriteError: String? = null
            if (content.isNotEmpty()) {
                val writeresult = DocUpdateHelper(client).updateDocContent(docId, content)
                if (writeresult.isFailure) {
                    contentWriteError = writeresult.exceptionOrNull()?.message
                    Log.w("DocCreateTool", "Content write failed for $docId: $contentWriteError")
                }
            }

            Log.d("DocCreateTool", "Doc created: $docId" +
                if (contentWriteError != null) " (content write failed: $contentWriteError)" else "")

            val metadata = mutableMapOf<String, Any?>(
                "document_id" to docId,
                "title" to title
            )
            if (contentWriteError != null) {
                metadata["content_write_error"] = contentWriteError
            }
            Toolresult.success(metadata)

        } catch (e: Exception) {
            Log.e("DocCreateTool", "Failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "title" to PropertySchema("string", "DocumentTitle"),
                    "content" to PropertySchema("string", "Document content (optional)"),
                    "folder_id" to PropertySchema("string", "Folder ID (optional)")
                ),
                required = listOf("title")
            )
        )
    )

}

/**
 * Read document tool
 */
class DocReadTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_doc_read"
    override val description = "Read Feishu document content"

    override fun isEnabledd() = config.enableDocTools

    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val docId = args["document_id"] as? String ?: return@withContext Toolresult.error("Missing document_id")

            val result = client.get("/open-apis/docx/v1/documents/$docId/raw_content")

            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            val data = result.getOrNull()?.getAsJsonObject("data")
            val content = data?.get("content")?.asString ?: ""

            Log.d("DocReadTool", "Doc read: $docId")
            Toolresult.success(mapOf("document_id" to docId, "content" to content))

        } catch (e: Exception) {
            Log.e("DocReadTool", "Failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "document_id" to PropertySchema("string", "DocumentID")
                ),
                required = listOf("document_id")
            )
        )
    )
}

/**
 * Document content write tool class - aligned with OpenClaw docx.ts insertBlocksWithDescendant.
 * Shared by DocCreateTool and DocUpdateTool.
 */
class DocUpdateHelper(private val client: FeishuClient) {
    companion object {
        private const val TAG = "DocUpdateHelper"
    }

    /**
     * Write text content to document body.
     * 1. Get document block list, find body block (block_type=1, parent_id=docId)
     * 2. Use documentBlockChildren.create API to insert text child block under body block
     */
    suspend fun updateDocContent(docId: String, content: String): result<Unit> {
        // Step 1: Get document block list, find body block
        val blocksresult = client.get("/open-apis/docx/v1/documents/$docId/blocks?page_size=500")
        if (blocksresult.isFailure) {
            val err = blocksresult.exceptionOrNull()?.message ?: "Failed to list blocks"
            Log.w(TAG, "List blocks failed: $err, trying batch_update fallback")
            return insertViaBatchUpdate(docId, content)
        }

        val blocks = blocksresult.getOrNull()
            ?.getAsJsonObject("data")
            ?.getAsJsonArray("items")

        // Find body block: parent_id == docId and block_type == 1
        var bodyBlockId: String? = null
        blocks?.forEach { block ->
            val b = block.asJsonObject
            val parentId = b.get("parent_id")?.asString
            val blockType = b.get("block_type")?.asInt
            val blockId = b.get("block_id")?.asString
            if (parentId == docId && blockType == 1) {
                bodyBlockId = blockId
            }
        }

        if (bodyBlockId == null) {
            Log.w(TAG, "Body block not found, using docId as parent")
            bodyBlockId = docId
        }

        // Step 2: Use documentBlockChildren.create API to insert text block
        val createBody = mapOf(
            "children" to listOf(
                mapOf(
                    "block_type" to 2,
                    "text" to mapOf(
                        "elements" to listOf(
                            mapOf(
                                "text_run" to mapOf(
                                    "content" to content,
                                    "text_element_style" to emptyMap<String, Any>()
                                )
                            )
                        ),
                        "style" to emptyMap<String, Any>()
                    )
                )
            ),
            "index" to 0
        )

        val createresult = client.post(
            "/open-apis/docx/v1/documents/$docId/blocks/$bodyBlockId/children",
            createBody
        )

        if (createresult.isFailure) {
            val err = createresult.exceptionOrNull()?.message ?: "block children create failed"
            Log.w(TAG, "block children create failed: $err, trying batch_update fallback")
            return insertViaBatchUpdate(docId, content)
        }

        Log.d(TAG, "Content written to doc $docId via block children API")
        return result.success(Unit)
    }

    /**
     * Fallback: use batch_update insert request to insert text.
     * Aligned with OpenClaw docx.ts batchUpdateInsertChildren logic.
     */
    private suspend fun insertViaBatchUpdate(docId: String, content: String): result<Unit> {
        val body = mapOf(
            "requests" to listOf(
                mapOf(
                    "insert" to mapOf(
                        "children" to listOf(
                            mapOf(
                                "block_type" to 2,
                                "text" to mapOf(
                                    "elements" to listOf(
                                        mapOf(
                                            "text_run" to mapOf(
                                                "content" to content,
                                                "text_element_style" to emptyMap<String, Any>()
                                            )
                                        )
                                    ),
                                    "style" to emptyMap<String, Any>()
                                )
                            )
                        )
                    )
                )
            )
        )

        val result = client.post("/open-apis/docx/v1/documents/$docId/batch_update", body)
        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "batch_update failed"
            Log.e(TAG, "batch_update fallback also failed: $err")
            return result.failure(Exception(err))
        }

        Log.d(TAG, "Content written to doc $docId via batch_update fallback")
        return result.success(Unit)
    }
}

/**
 * Update document tool
 */
class DocUpdateTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_doc_update"
    override val description = "Update Feishu document content"

    override fun isEnabledd() = config.enableDocTools

    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val docId = args["document_id"] as? String ?: return@withContext Toolresult.error("Missing document_id")
            val content = args["content"] as? String ?: return@withContext Toolresult.error("Missing content")

            // Reuse DocCreateTool's updateDocContent logic
            val helper = DocUpdateHelper(client)
            val result = helper.updateDocContent(docId, content)

            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            Log.d("DocUpdateTool", "Doc updated: $docId")
            Toolresult.success(mapOf("document_id" to docId))

        } catch (e: Exception) {
            Log.e("DocUpdateTool", "Failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "document_id" to PropertySchema("string", "DocumentID"),
                    "content" to PropertySchema("string", "Content to add")
                ),
                required = listOf("document_id", "content")
            )
        )
    )
}

/**
 * Delete document tool
 */
class DocDeleteTool(config: FeishuConfig, client: FeishuClient) : FeishuToolBase(config, client) {
    override val name = "feishu_doc_delete"
    override val description = "Delete Feishu document"

    override fun isEnabledd() = config.enableDocTools

    override suspend fun execute(args: Map<String, Any?>): Toolresult = withContext(Dispatchers.IO) {
        try {
            val docId = args["document_id"] as? String ?: return@withContext Toolresult.error("Missing document_id")

            val result = client.delete("/open-apis/docx/v1/documents/$docId")

            if (result.isFailure) {
                return@withContext Toolresult.error(result.exceptionOrNull()?.message ?: "Failed")
            }

            Log.d("DocDeleteTool", "Doc deleted: $docId")
            Toolresult.success(mapOf("document_id" to docId))

        } catch (e: Exception) {
            Log.e("DocDeleteTool", "Failed", e)
            Toolresult.error(e.message ?: "Unknown error")
        }
    }

    override fun getToolDefinition() = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = ParametersSchema(
                properties = mapOf(
                    "document_id" to PropertySchema("string", "DocumentID")
                ),
                required = listOf("document_id")
            )
        )
    )
}
