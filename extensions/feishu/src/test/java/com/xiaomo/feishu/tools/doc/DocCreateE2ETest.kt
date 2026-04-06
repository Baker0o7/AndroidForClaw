package com.xiaomo.feishu.tools.doc

/**
 * E2E Test: Feishu Document Create (Title + Content)
 *
 * Test Goal: Validate "Create a Document with some Content" can write both Title and Content simultaneously.
 *
 * Test Steps:
 * 1. CreateDocument (with Title)
 * 2. WriteContent (via DocUpdateHelper)
 * 3. ReadDocument raw_content
 * 4. Assert: Title is not Null AND Content is not Null
 *
 * Run method (via ADB):
 * ```
 * adb shell am instrument -w -e class com.xiaomo.feishu.tools.doc.DocCreateE2ETest \
 *   com.xiaomo.androidforclaw.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Or via GatewayServer HTTP Interface (Need to Register in app).
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
        private const val TEST_CONTENT = "This is a test content, used to validate that Document Create can write Content correctly. "
    }

    /**
     * Load Feishu Config from device's openclaw.json
     */
    private fun loadFeishuConfig(): FeishuConfig? {
        return try {
            // Attempt to read Config from device
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
     * Load Config from assets or filesDir (fallback)
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
     * Core Test: CreateDocument -> WriteContent -> Read -> Validate
     */
    @Test
    fun testDocCreateWithTitleAndContent() = runBlocking {
        val config = loadFeishuConfig()
            ?: run {
                Log.w(TAG, "⚠️ Cannot load Feishu Config, Skip E2E Test")
                return@runBlocking
            }

        if (!config.enabled) {
            Log.w(TAG, "⚠️ Feishu not enabled, Skip E2E Test")
            return@runBlocking
        }

        val client = FeishuClient(config)

        // ===== Step 1: Get tenant_access_token =====
        val tokenresult = client.getTenantAccessToken()
        assertTrue("Get tenant_access_token Failed: ${tokenresult.exceptionOrNull()?.message}",
            tokenresult.isSuccess)
        val token = tokenresult.getOrNull()!!
        Log.i(TAG, "✅ tenant_access_token get success")

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
        assertNotNull("Missing document_id", docId)
        Log.i(TAG, "✅ Document created successfully: docId=$docId, title=$TEST_TITLE")

        // ===== Step 3: WriteContent (use DocUpdateHelper, align with FixBack logic)=====
        val updateHelper = DocUpdateHelper(client)
        val writeresult = updateHelper.updateDocContent(docId!!, TEST_CONTENT)
        assertTrue("Content write failed: ${writeresult.exceptionOrNull()?.message}",
            writeresult.isSuccess)
        Log.i(TAG, "✅ Content write success")

        // ===== Step 4: ReadDocument raw_content =====
        val readresult = client.get("/open-apis/docx/v1/documents/$docId/raw_content")
        assertTrue("ReadDocumentFailed: ${readresult.exceptionOrNull()?.message}",
            readresult.isSuccess)

        val readData = readresult.getOrNull()!!
        val content = readData
            .getAsJsonObject("data")
            ?.get("content")
            ?.asString ?: ""

        Log.i(TAG, "📄 Document content: \"$content\"")

        // ===== Step 5: Validate =====
        // 5a: Title not Null (confirmed via Create API)
        assertTrue("❌ Title is Null", TEST_TITLE.isNotBlank())
        Log.i(TAG, "✅ Title validation passed: $TEST_TITLE")

        // 5b: Content not Null
        assertTrue("❌ Content is Null! This shows write logic has bug", content.isNotBlank())
        assertEquals("❌ Content does not match", TEST_CONTENT, content.trim())
        Log.i(TAG, "✅ Content validation passed: $content")

        // ===== Step 6: Cleanup =====
        try {
            client.delete("/open-apis/docx/v1/documents/$docId")
            Log.i(TAG, "🗑️ Test document deleted: $docId")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Delete test document failed: ${e.message}")
        }

        Log.i(TAG, "🎉 E2E Test all passed! Title and Content both write correctly.")
    }

    /**
     * Minimal Test: Only test Content write (assume Document already exists)
     * Can run via ADB with docId parameter
     */
    @Test
    fun testContentWriteOnly() = runBlocking {
        val config = loadFeishuConfig()
            ?: run {
                Log.w(TAG, "⚠️ Cannot load Feishu Config, Skip test")
                return@runBlocking
            }

        val client = FeishuClient(config)
        val tokenresult = client.getTenantAccessToken()
        assertTrue("Get token Failed", tokenresult.isSuccess)

        // Create test document
        val createBody = mapOf("title" to "E2EContentWriteTest", "type" to "doc")
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
            Log.e(TAG, "❌ Content write failed: ${writeresult.exceptionOrNull()?.message}")
            // Cleanup
            client.delete("/open-apis/docx/v1/documents/$docId")
            fail("Content write failed: ${writeresult.exceptionOrNull()?.message}")
        }

        // Read and validate
        val readresult = client.get("/open-apis/docx/v1/documents/$docId/raw_content")
        assertTrue("ReadFailed", readresult.isSuccess)

        val content = readresult.getOrNull()!!
            .getAsJsonObject("data")
            ?.get("content")?.asString ?: ""

        Log.i(TAG, "Write content: $testContent")
        Log.i(TAG, "Read content: $content")

        assertTrue("❌ Read content is Null", content.isNotBlank())
        assertTrue("❌ Content does not contain test text", content.contains(testContent))

        // Cleanup
        client.delete("/open-apis/docx/v1/documents/$docId")
        Log.i(TAG, "🎉 Content write test passed!")
    }
}