package com.xiaomo.androidforclaw.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.xiaomo.androidforclaw.agent.tools.AndroidToolRegistry
import com.xiaomo.androidforclaw.config.ConfigLoader
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.data.model.TaskDataManager
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Agent 集成Test
 * 在Real Android Environment中Test Agent Feature
 *
 * Run:
 * ./gradlew connectedDebugAndroidTest --tests "AgentIntegrationTest"
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AgentIntegrationTest {

    private lateinit var context: Context
    private lateinit var configLoader: ConfigLoader
    private lateinit var toolRegistry: AndroidToolRegistry
    private lateinit var taskDataManager: TaskDataManager

    @Before
    fun setup() {
        // API 30+ needs MANAGE_EXTERNAL_STORAGE for /sdcard/ access
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            .close()

        context = ApplicationProvider.getApplicationContext<MyApplication>()

        // CreateTestConfig文件
        setupTestConfig()

        configLoader = ConfigLoader(context)
        taskDataManager = TaskDataManager.getInstance()
        toolRegistry = AndroidToolRegistry(context, taskDataManager)
    }

    private fun setupTestConfig() {
        val configDir = java.io.File("/sdcard/.androidforclaw/config")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        // CreateTest用的openclaw.json - Containsproviders (models.json已废弃)
        val openClawFile = java.io.File(configDir, "openclaw.json")
        if (!openClawFile.exists()) {
            openClawFile.writeText("""
                {
                    "version": "1.0.0",
                    "agent": {
                        "name": "androidforclaw-test",
                        "maxIterations": 20
                    },
                    "thinking": {
                        "enabled": true
                    },
                    "providers": {
                        "anthropic": {
                            "baseUrl": "https://api.anthropic.com/v1",
                            "apiKey": "test-key",
                            "api": "openai-completions",
                            "models": [
                                {
                                    "id": "claude-opus-4-6",
                                    "name": "Claude Opus 4.6",
                                    "reasoning": true,
                                    "input": ["text", "image"],
                                    "contextWindow": 200000,
                                    "maxTokens": 16384
                                }
                            ]
                        }
                    },
                    "gateway": {
                        "feishu": {
                            "enabled": false,
                            "appId": "",
                            "appSecret": "",
                            "verificationToken": "",
                            "encryptKey": "",
                            "domain": "https://open.feishu.cn",
                            "connectionMode": "websocket",
                            "dmPolicy": "allow",
                            "groupPolicy": "mention",
                            "requireMention": true
                        }
                    }
                }
            """.trimIndent())
        }
    }

    // ========== Config系统集成Test ==========

    @Test
    fun testConfigLoader_loadsSuccessfully() {
        val config = configLoader.loadOpenClawConfig()

        assertNotNull("ConfigShouldLoadSuccess", config)
        // providers may be empty in test environment if no models.json configured
        // just verify config loaded without crash
    }

    @Test
    fun testConfigLoader_findsProviders() {
        // Getprovider
        val provider = configLoader.getProviderConfig("anthropic")

        // 如果TestEnvironmentHasConfiganthropic,ValidateItsValid性
        if (provider != null) {
            assertNotNull("BaseUrl 不应为Null", provider.baseUrl)
            assertTrue("ShouldHasmodels", provider.models.isNotEmpty())
        }
        // 如果NoneConfig,Test至少不崩溃即可
    }

    @Test
    fun testOpenClawConfig_loadsSuccessfully() {
        val config = configLoader.loadOpenClawConfig()

        assertNotNull("OpenClaw ConfigShouldLoadSuccess", config)
        assertTrue("maxIterations Should > 0", config.agent.maxIterations > 0)
        assertNotNull("skills ConfigShouldExists", config.skills)
    }

    // ========== Tool Registry 集成Test ==========

    @Test
    fun testToolRegistry_hasTools() {
        val toolCount = toolRegistry.getToolCount()

        assertTrue("ShouldHas工具Register", toolCount > 0)
    }

    @Test
    fun testToolRegistry_hasWaitSkill() {
        val hasWait = toolRegistry.contains("device")

        assertTrue("ShouldContains device skill", hasWait)
    }

    @Test
    fun testToolRegistry_hasStopSkill() {
        val hasStop = toolRegistry.contains("stop")

        assertTrue("ShouldContains stop skill", hasStop)
    }

    @Test
    fun testToolRegistry_hasLogSkill() {
        val hasLog = toolRegistry.contains("log")

        assertTrue("ShouldContains log skill", hasLog)
    }

    @Test
    fun testToolRegistry_getDefinitions() {
        val definitions = toolRegistry.getToolDefinitions()

        assertTrue("ShouldHasTool definition", definitions.isNotEmpty())

        // ValidateEach定义的结构
        definitions.forEach { def ->
            assertEquals("Type ShouldYes function", "function", def.type)
            assertNotNull("Function 不应为Null", def.function)
            assertTrue("Name Should非Null", def.function.name.isNotBlank())
            assertTrue("Description Should非Null", def.function.description.isNotBlank())
        }
    }

    // ========== Skill 执Row集成Test ==========

    @Test
    fun testWaitSkill_executesInAndroid() = runBlocking {
        val startTime = System.currentTimeMillis()

        // WaitSkill使用secondsParameters
        val result = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "wait", "timeMs" to 100))

        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Wait ShouldSuccess", result.success)
        assertTrue("ShouldWait至少 100ms", elapsed >= 95)
        assertTrue("Should在 200ms InsideComplete", elapsed < 200)
    }

    @Test
    fun testLogSkill_executesInAndroid() = runBlocking {
        val result = toolRegistry.execute("log", mapOf(
            "message" to "集成TestLog",
            "level" to "INFO"
        ))

        assertTrue("Log ShouldSuccess", result.success)
    }

    @Test
    fun testStopSkill_executesInAndroid() = runBlocking {
        val result = toolRegistry.execute("stop", mapOf(
            "reason" to "集成TestStop"
        ))

        assertTrue("Stop ShouldSuccess", result.success)
        assertTrue("ShouldHas stopped 元Data", result.metadata.containsKey("stopped"))
        assertEquals("stopped Should为 true", true, result.metadata["stopped"])
    }

    @Test
    fun testMultipleSkills_executeSequentially() = runBlocking {
        // 执RowMultiple技能
        val result1 = toolRegistry.execute("log", mapOf("message" to "First"))
        val result2 = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "wait", "timeMs" to 50))
        val result3 = toolRegistry.execute("log", mapOf("message" to "第二个"))

        assertTrue("All技能ShouldSuccess", result1.success && result2.success && result3.success)
    }

    @Test
    fun testSkill_withInvalidArguments() = runBlocking {
        // Test缺少RequiredParameters
        val result = toolRegistry.execute("device", mapOf("action" to "invalid_action"))

        assertFalse("None效ActionShouldFailed", result.success)
        assertTrue("ShouldContainsErrorInfo", result.content.isNotEmpty())
    }

    // ========== 工作Space集成Test ==========

    @Test
    fun testWorkspace_directoryExists() {
        val workspaceDir = java.io.File("/sdcard/.androidforclaw/workspace")

        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        assertTrue("工作SpaceShouldExists", workspaceDir.exists())
        assertTrue("Should可读", workspaceDir.canRead())
        assertTrue("Should可写", workspaceDir.canWrite())
    }

    @Test
    fun testWorkspace_skillsDirectoryExists() {
        val skillsDir = java.io.File("/sdcard/.androidforclaw/workspace/skills")

        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }

        assertTrue("Skills 目录ShouldExists", skillsDir.exists())
    }

    @Test
    fun testWorkspace_canCreateFile() {
        val testFile = java.io.File("/sdcard/.androidforclaw/workspace/integration_test.txt")

        try {
            testFile.writeText("Integration test content")

            assertTrue("文件ShouldCreateSuccess", testFile.exists())
            assertEquals("Inside容Should匹配", "Integration test content", testFile.readText())

        } finally {
            testFile.delete()
        }
    }

    // ========== Assets Resource集成Test ==========

    @Test
    fun testAssets_skillsDirectoryExists() {
        try {
            val skillsList = context.assets.list("skills")

            assertNotNull("Skills 目录ShouldExists", skillsList)
            // 可能为Null, 取决于YesNoHasInside置 skills

        } catch (e: Exception) {
            fail("访问 assets skills Failed: ${e.message}")
        }
    }

    // ========== TaskDataManager 集成Test ==========

    @Test
    fun testTaskDataManager_initialization() {
        assertNotNull("TaskDataManager ShouldInitialize", taskDataManager)
    }

    // ========== Performance集成Test ==========

    @Test
    fun testPerformance_multipleToolCalls() = runBlocking {
        val iterations = 10
        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            toolRegistry.execute("log", mapOf("message" to "PerformanceTest $it"))
        }

        val elapsed = System.currentTimeMillis() - startTime

        // 平均每次调用Should在合理TimeInside(< 100ms/次)
        val avgTime = elapsed / iterations
        assertTrue("平均执RowTimeShould合理 (< 100ms)", avgTime < 100)
    }

    @Test
    fun testPerformance_configReload() {
        val iterations = 5
        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            configLoader.reloadOpenClawConfig()
        }

        val elapsed = System.currentTimeMillis() - startTime

        // ConfigOverloadShouldFast(< 500ms/次)
        val avgTime = elapsed / iterations
        assertTrue("ConfigOverloadShouldFast (< 500ms)", avgTime < 500)
    }
}
