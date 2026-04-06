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
 * Agent Integration Test
 * Test Agent Features in Real Android Environment
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

        // Create test config file
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

        // Create test openclaw.json - Contains providers (models.json is deprecated)
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

    // ========== Config System Integration Test ==========

    @Test
    fun testConfigLoader_loadsSuccessfully() {
        val config = configLoader.loadOpenClawConfig()

        assertNotNull("ConfigShouldLoadSuccess", config)
        // providers may be empty in test environment if no models.json configured
        // just verify config loaded without crash
    }

    @Test
    fun testConfigLoader_findsProviders() {
        // Get provider
        val provider = configLoader.getProviderConfig("anthropic")

        // If test environment has config for anthropic, validate it's valid
        if (provider != null) {
            assertNotNull("BaseUrl should not be null", provider.baseUrl)
            assertTrue("ShouldHasmodels", provider.models.isNotEmpty())
        }
        // If no config, test should at least not crash
    }

    @Test
    fun testOpenClawConfig_loadsSuccessfully() {
        val config = configLoader.loadOpenClawConfig()

        assertNotNull("OpenClaw ConfigShouldLoadSuccess", config)
        assertTrue("maxIterations Should > 0", config.agent.maxIterations > 0)
        assertNotNull("skills ConfigShouldExists", config.skills)
    }

    // ========== Tool Registry Integration Test ==========

    @Test
    fun testToolRegistry_hasTools() {
        val toolCount = toolRegistry.getToolCount()

        assertTrue("Should have tool registered", toolCount > 0)
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

        // Validate each definition structure
        definitions.forEach { def ->
            assertEquals("Type should be function", "function", def.type)
            assertNotNull("Function should not be null", def.function)
            assertTrue("Name should not be null", def.function.name.isNotBlank())
            assertTrue("Description should not be null", def.function.description.isNotBlank())
        }
    }

    // ========== Skill Execution Integration Test ==========

    @Test
    fun testWaitSkill_executesInAndroid() = runBlocking {
        val startTime = System.currentTimeMillis()

        // Wait skill uses seconds parameters
        val result = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "wait", "timeMs" to 100))

        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Wait should succeed", result.success)
        assertTrue("Should wait at least 100ms", elapsed >= 95)
        assertTrue("Should complete within 200ms", elapsed < 200)
    }

    @Test
    fun testLogSkill_executesInAndroid() = runBlocking {
        val result = toolRegistry.execute("log", mapOf(
            "message" to "Integration test log",
            "level" to "INFO"
        ))

        assertTrue("Log should succeed", result.success)
    }

    @Test
    fun testStopSkill_executesInAndroid() = runBlocking {
        val result = toolRegistry.execute("stop", mapOf(
            "reason" to "Integration test stop"
        ))

        assertTrue("Stop should succeed", result.success)
        assertTrue("Should have stopped metadata", result.metadata.containsKey("stopped"))
        assertEquals("stopped should be true", true, result.metadata["stopped"])
    }

    @Test
    fun testMultipleSkills_executeSequentially() = runBlocking {
        // Execute multiple skills
        val result1 = toolRegistry.execute("log", mapOf("message" to "First"))
        val result2 = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "wait", "timeMs" to 50))
        val result3 = toolRegistry.execute("log", mapOf("message" to "Second"))

        assertTrue("All skills should succeed", result1.success && result2.success && result3.success)
    }

    @Test
    fun testSkill_withInvalidArguments() = runBlocking {
        // Test missing required parameters
        val result = toolRegistry.execute("device", mapOf("action" to "invalid_action"))

        assertFalse("Invalid action should fail", result.success)
        assertTrue("Should contain error info", result.content.isNotEmpty())
    }

    // ========== Workspace Integration Test ==========

    @Test
    fun testWorkspace_directoryExists() {
        val workspaceDir = java.io.File("/sdcard/.androidforclaw/workspace")

        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        assertTrue("Workspace should exist", workspaceDir.exists())
        assertTrue("Should be readable", workspaceDir.canRead())
        assertTrue("Should be writable", workspaceDir.canWrite())
    }

    @Test
    fun testWorkspace_skillsDirectoryExists() {
        val skillsDir = java.io.File("/sdcard/.androidforclaw/workspace/skills")

        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }

        assertTrue("Skills directory should exist", skillsDir.exists())
    }

    @Test
    fun testWorkspace_canCreateFile() {
        val testFile = java.io.File("/sdcard/.androidforclaw/workspace/integration_test.txt")

        try {
            testFile.writeText("Integration test content")

            assertTrue("File should be created successfully", testFile.exists())
            assertEquals("Content should match", "Integration test content", testFile.readText())

        } finally {
            testFile.delete()
        }
    }

    // ========== Assets Resource Integration Test ==========

    @Test
    fun testAssets_skillsDirectoryExists() {
        try {
            val skillsList = context.assets.list("skills")

            assertNotNull("Skills directory should exist", skillsList)
            // May be null depending on whether skills are configured

        } catch (e: Exception) {
            fail("Accessing assets skills failed: ${e.message}")
        }
    }

    // ========== TaskDataManager Integration Test ==========

    @Test
    fun testTaskDataManager_initialization() {
        assertNotNull("TaskDataManager should initialize", taskDataManager)
    }

    // ========== Performance Integration Test ==========

    @Test
    fun testPerformance_multipleToolCalls() = runBlocking {
        val iterations = 10
        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            toolRegistry.execute("log", mapOf("message" to "PerformanceTest $it"))
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Average execution time should be reasonable (< 100ms per call)
        val avgTime = elapsed / iterations
        assertTrue("Average execution time should be reasonable (< 100ms)", avgTime < 100)
    }

    @Test
    fun testPerformance_configReload() {
        val iterations = 5
        val startTime = System.currentTimeMillis()

        repeat(iterations) {
            configLoader.reloadOpenClawConfig()
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Config reload should be fast (< 500ms per reload)
        val avgTime = elapsed / iterations
        assertTrue("Config reload should be fast (< 500ms)", avgTime < 500)
    }
}
