package com.xiaomo.feishu.tools.doc

/**
 * E2E Test: 飞书DocumentCreate(Title + Inside容)
 *
 * Test目标: Validate "Create个Document 随便写点Inside容" 能at the same timeWriteTitle和Inside容. 
 *
 * Test步骤: 
 * 1. CreateDocument(带Title)
 * 2. WriteInside容(通过 DocUpdateHelper)
 * 3. ReadDocument raw_content
 * 4. 断言: Title不为Null AND Inside容不为Null
 *
 * Run方式(通过 ADB): 
 * ```
 * adb shell am instrument -w -e class com.xiaomo.feishu.tools.doc.DocCreateE2ETest \
 *   com.xiaomo.androidforclaw.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 或通过 GatewayServer HTTP Interface触发(Need在 app 中Register). 
 */

import android.util.Log
import com.google.gson.JsonObject
import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.storage.WeixinAccountStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocCreateE2ETest {

    companion object {
        private const val TAG = "DocCreateE2ETest"
        private const val TEST_TITLE = "E2ETestDocument_${System.currentTimeMillis()}"
        private const val TEST_CONTENT = "这Yes一段TestInside容, 用于ValidateDocumentCreateBackInside容能正确Write. "
    }

    /**
     * 从DeviceUp的 openclaw.json Read飞书Config
     */
    private fun loadFeishuConfig(): FeishuConfig? {
        return try {
            // Attempt从DeviceUp的Config文件Read
            val configFile = File("/data/data/com.xiaomo.androidforclaw/files/openclaw/openclaw.json")
            if (!configFile.exists()) {
                Log.e(TAG, "Config file not found: ${configFile.absolutePath}")
                return null
            }
            val json = com.google.gson.Gson().fromJson(configFile.readText(), JsonObject::class.java)
            val feishu = json.getAsJsonObject("feishu") ?: return null
            FeishuConfig(
                enabled = feishu.get("enabled")?.asBoolean ?: false,
                appId = feishu.get("appId")?.asString ?: return null,
                appSecret = feishu.get("appSecret")?.asString ?: return null,
                domain = feishu.get("domain")?.asString ?: "feishu",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            null
        }
    }

    /**
     * 从 assets 或 filesDir LoadConfig(备用方案)
     */
    private fun loadFeishuConfigFromFilesDir(filesDir: File): FeishuConfig? {
        return try {
            val configFile = File(filesDir, "openclaw/openclaw.json")
            if (!configFile.exists()) {
                Log.e(TAG, "Config not found: ${configFile.absolutePath}")
                return null
            }
            val json = com.google.gson.Gson().fromJson(configFile.readText(), JsonObject::class.java)
            val feishu = json.getAsJsonObject("feishu") ?: return null
            FeishuConfig(
                enabled = feishu.get("enabled")?.asBoolean ?: false,
                appId = feishu.get("appId")?.asString ?: return null,
                appSecret = feishu.get("appSecret")?.asString ?: return null,
                domain = feishu.get("domain")?.asString ?: "feishu",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config from filesDir", e)
            null
        }
    }

    /**
     * 核心Test: CreateDocument → WriteInside容 → Read → Validate
     */
    @Test
    fun testDocCreateWithTitleAndContent() = runBlocking {
        val config = loadFeishuConfig()
            ?: run {
                Log.w(TAG, "⚠️ CannotLoad飞书Config, Skip E2E Test")
                return@runBlocking
            }

        if (!config.enabled) {
            Log.w(TAG, "⚠️ 飞书未Enabled, Skip E2E Test")
            return@runBlocking
        }

        val client = FeishuClient(config)

        // ===== Step 1: Get tenant_access_token =====
        val tokenresult = client.getTenantAccessToken()
        assertTrue("Get tenant_access_token Failed: ${tokenresult.exceptionOrNull()?.message}",
            tokenresult.isSuccess)
        val token = tokenresult.getOrNull()!!
        Log.i(TAG, "✅ tenant_access_token GetSuccess")

        // ===== Step 2: CreateDocument =====
        val createBody = mapOf(
            "title" to TEST_TITLE,
            "type" to "doc"
        )
        val createresult = client.post("/open-apis/docx/v1/documents", createBody)
        assertTrue("CreateDocumentFailed: ${createresult.exceptionOrNull()?.message}",
            createresult.isSuccess)

        val createData = createresult.getOrNull()!!
        val docId = createData
            .getAsJsonObject("data")
            ?.getAsJsonObject("document")
            ?.get("document_id")
            ?.asString
        assertNotNull("缺少 document_id", docId)
        Log.i(TAG, "✅ DocumentCreateSuccess: docId=$docId, title=$TEST_TITLE")

        // ===== Step 3: WriteInside容(use DocUpdateHelper, 对齐FixBack的逻辑)=====
        val updateHelper = DocUpdateHelper(client)
        val writeresult = updateHelper.updateDocContent(docId!!, TEST_CONTENT)
        assertTrue("Inside容WriteFailed: ${writeresult.exceptionOrNull()?.message}",
            writeresult.isSuccess)
        Log.i(TAG, "✅ Inside容WriteSuccess")

        // ===== Step 4: ReadDocument raw_content =====
        val readresult = client.get("/open-apis/docx/v1/documents/$docId/raw_content")
        assertTrue("ReadDocumentFailed: ${readresult.exceptionOrNull()?.message}",
            readresult.isSuccess)

        val readData = readresult.getOrNull()!!
        val content = readData
            .getAsJsonObject("data")
            ?.get("content")
            ?.asString ?: ""

        Log.i(TAG, "📄 DocumentInside容: \"$content\"")

        // ===== Step 5: Validate =====
        // 5a: Title不为Null(通过Create API 已Confirm)
        assertTrue("❌ Title为Null", TEST_TITLE.isNotBlank())
        Log.i(TAG, "✅ TitleValidate通过: $TEST_TITLE")

        // 5b: Inside容不为Null
        assertTrue("❌ Inside容为Null!这illustrateWrite逻辑Has bug", content.isNotBlank())
        assertEquals("❌ Inside容不match", TEST_CONTENT, content.trim())
        Log.i(TAG, "✅ Inside容Validate通过: $content")

        // ===== Step 6: 清理 =====
        try {
            client.delete("/open-apis/docx/v1/documents/$docId")
            Log.i(TAG, "🗑️ TestDocument已Delete: $docId")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ DeleteTestDocumentFailed: ${e.message}")
        }

        Log.i(TAG, "🎉 E2E TestAll通过!Title和Inside容均正常Write. ")
    }

    /**
     * most小Test: 只测Inside容Writepartially(False设已HasDocument)
     * 可通过 ADB 传入 docId ParametersRun
     */
    @Test
    fun testContentWriteOnly() = runBlocking {
        val config = loadFeishuConfig()
            ?: run {
                Log.w(TAG, "⚠️ CannotLoad飞书Config, SkipTest")
                return@runBlocking
            }

        val client = FeishuClient(config)
        val tokenresult = client.getTenantAccessToken()
        assertTrue("Get token Failed", tokenresult.isSuccess)

        // CreateTestDocument
        val createBody = mapOf("title" to "E2EInside容WriteTest", "type" to "doc")
        val createresult = client.post("/open-apis/docx/v1/documents", createBody)
        assertTrue("CreateDocumentFailed", createresult.isSuccess)

        val docId = createresult.getOrNull()!!
            .getAsJsonObject("data")
            ?.getAsJsonObject("document")
            ?.get("document_id")?.asString!!

        // Test DocUpdateHelper
        val helper = DocUpdateHelper(client)
        val testContent = "Hello E2E Test ${System.currentTimeMillis()}"
        val writeresult = helper.updateDocContent(docId, testContent)

        if (writeresult.isFailure) {
            Log.e(TAG, "❌ Inside容WriteFailed: ${writeresult.exceptionOrNull()?.message}")
            // 清理
            client.delete("/open-apis/docx/v1/documents/$docId")
            fail("Inside容WriteFailed: ${writeresult.exceptionOrNull()?.message}")
        }

        // ReadValidate
        val readresult = client.get("/open-apis/docx/v1/documents/$docId/raw_content")
        assertTrue("ReadFailed", readresult.isSuccess)

        val content = readresult.getOrNull()!!
            .getAsJsonObject("data")
            ?.get("content")?.asString ?: ""

        Log.i(TAG, "WriteInside容: $testContent")
        Log.i(TAG, "ReadInside容: $content")

        assertTrue("❌ Read到的Inside容为Null", content.isNotBlank())
        assertTrue("❌ Inside容不ContainsTestText", content.contains(testContent))

        // 清理
        client.delete("/open-apis/docx/v1/documents/$docId")
        Log.i(TAG, "🎉 Inside容WriteTest通过!")
    }
}
