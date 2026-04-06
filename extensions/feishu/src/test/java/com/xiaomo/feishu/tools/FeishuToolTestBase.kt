package com.xiaomo.feishu.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.slot
import org.junit.After
import org.junit.Before

/**
 * Feishu tool unitTest基Class
 * 提供 MockK client + Default config + JSON 辅助Method
 */
open class FeishuToolTestBase {

    protected lateinit var client: FeishuClient
    protected lateinit var config: FeishuConfig

    @Before
    open fun baseSetUp() {
        client = mockk(relaxed = true)
        config = createDefaultConfig()
        MockKAnnotations.init(this)
    }

    @After
    open fun baseTearDown() {
        clearAllMocks()
    }

    /**
     * CreateDefaultAllEnabled的 FeishuConfig
     */
    protected fun createDefaultConfig(
        enableDocTools: Boolean = true,
        enableWikiTools: Boolean = true,
        enableDriveTools: Boolean = true,
        enableBitableTools: Boolean = true,
        enableTaskTools: Boolean = true,
        enableChatTools: Boolean = true,
        enablePermTools: Boolean = false,
        enableUrgentTools: Boolean = true,
        enableSheetTools: Boolean = true,
        enableCalendarTools: Boolean = true,
        enableImTools: Boolean = true,
        enableSearchTools: Boolean = true,
        enableCommonTools: Boolean = true
    ) = FeishuConfig(
        appId = "test_app_id",
        appSecret = "test_app_secret",
        enableDocTools = enableDocTools,
        enableWikiTools = enableWikiTools,
        enableDriveTools = enableDriveTools,
        enableBitableTools = enableBitableTools,
        enableTaskTools = enableTaskTools,
        enableChatTools = enableChatTools,
        enablePermTools = enablePermTools,
        enableUrgentTools = enableUrgentTools,
        enableSheetTools = enableSheetTools,
        enableCalendarTools = enableCalendarTools,
        enableImTools = enableImTools,
        enableSearchTools = enableSearchTools,
        enableCommonTools = enableCommonTools
    )

    // ─── Mock helpers ───────────────────────────────────────────

    /**
     * Mock client.get() 对指定 path Front缀ReturnSuccess JsonObject
     */
    protected fun mockGet(pathPrefix: String, data: JsonObject) {
        coEvery { client.get(match { it.startsWith(pathPrefix) }, any()) } returns
            result.success(wrapData(data))
    }

    /**
     * Mock client.get() 对指定 path Front缀ReturnSuccess (None headers Parameter version)
     */
    protected fun mockGetExact(path: String, data: JsonObject) {
        coEvery { client.get(path, any()) } returns result.success(wrapData(data))
    }

    /**
     * Mock client.post() 对指定 path Front缀ReturnSuccess
     */
    protected fun mockPost(pathPrefix: String, data: JsonObject) {
        coEvery { client.post(match { it.startsWith(pathPrefix) }, any(), any()) } returns
            result.success(wrapData(data))
    }

    /**
     * Mock client.put() ReturnSuccess
     */
    protected fun mockPut(pathPrefix: String, data: JsonObject) {
        coEvery { client.put(match { it.startsWith(pathPrefix) }, any()) } returns
            result.success(wrapData(data))
    }

    /**
     * Mock client.patch() ReturnSuccess
     */
    protected fun mockPatch(pathPrefix: String, data: JsonObject) {
        coEvery { client.patch(match { it.startsWith(pathPrefix) }, any()) } returns
            result.success(wrapData(data))
    }

    /**
     * Mock client.delete() ReturnSuccess
     */
    protected fun mockDelete(pathPrefix: String, data: JsonObject = JsonObject()) {
        coEvery { client.delete(match { it.startsWith(pathPrefix) }) } returns
            result.success(wrapData(data))
    }

    /**
     * Mock client.get() 对指定 path Front缀ReturnFailed
     */
    protected fun mockGetError(pathPrefix: String, msg: String = "API error") {
        coEvery { client.get(match { it.startsWith(pathPrefix) }, any()) } returns
            result.failure(Exception(msg))
    }

    /**
     * Mock client.post() 对指定 path Front缀ReturnFailed
     */
    protected fun mockPostError(pathPrefix: String, msg: String = "API error") {
        coEvery { client.post(match { it.startsWith(pathPrefix) }, any(), any()) } returns
            result.failure(Exception(msg))
    }

    /**
     * Mock client.patch() ReturnFailed
     */
    protected fun mockPatchError(pathPrefix: String, msg: String = "API error") {
        coEvery { client.patch(match { it.startsWith(pathPrefix) }, any()) } returns
            result.failure(Exception(msg))
    }

    /**
     * Mock client.delete() ReturnFailed
     */
    protected fun mockDeleteError(pathPrefix: String, msg: String = "API error") {
        coEvery { client.delete(match { it.startsWith(pathPrefix) }) } returns
            result.failure(Exception(msg))
    }

    /**
     * Mock client.downloadRaw() ReturnSuccess
     */
    protected fun mockDownloadRaw(pathPrefix: String, bytes: ByteArray = ByteArray(10)) {
        coEvery { client.downloadRaw(match { it.startsWith(pathPrefix) }) } returns
            result.success(bytes)
    }

    /**
     * Mock client.downloadRawWithHeaders()
     */
    protected fun mockDownloadRawWithHeaders(
        pathPrefix: String,
        bytes: ByteArray = ByteArray(10),
        headers: Map<String, String> = mapOf("Content-Type" to "application/octet-stream")
    ) {
        coEvery { client.downloadRawWithHeaders(match { it.startsWith(pathPrefix) }) } returns
            result.success(Pair(bytes, headers))
    }

    // ─── JSON helpers ───────────────────────────────────────────

    /**
     * 将 data Package装为飞书 API 标准Response结构 {"code":0,"data":{...}}
     */
    protected fun wrapData(data: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("code", 0)
            addProperty("msg", "success")
            add("data", data)
        }
    }

    /**
     * Fast构造 JsonObject
     */
    protected fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject {
        return JsonObject().apply {
            pairs.forEach { (key, value) ->
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is JsonObject -> add(key, value)
                    is JsonArray -> add(key, value)
                    null -> add(key, null)
                }
            }
        }
    }

    /**
     * Fast构造 JsonArray
     */
    protected fun jsonArr(vararg items: JsonObject): JsonArray {
        return JsonArray().apply { items.forEach { add(it) } }
    }

    /**
     * 从 JSON StringParse
     */
    protected fun parseJson(json: String): JsonObject {
        return JsonParser.parseString(json).asJsonObject
    }
}
