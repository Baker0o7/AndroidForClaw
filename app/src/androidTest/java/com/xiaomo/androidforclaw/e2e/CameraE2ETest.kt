package com.xiaomo.androidforclaw.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.xiaomo.androidforclaw.agent.context.ContextBuilder
import com.xiaomo.androidforclaw.agent.loop.AgentLoop
import com.xiaomo.androidforclaw.agent.tools.AndroidToolRegistry
import com.xiaomo.androidforclaw.agent.tools.ToolRegistry
import com.xiaomo.androidforclaw.config.ConfigLoader
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.data.model.TaskDataManager
import com.xiaomo.androidforclaw.providers.UnifiedLLMProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Camera Skill End-to-End Test
 *
 * Flow:
 * 1. Send "take a picture and see what's there" to AgentLoop
 * 2. Agent calls eye skill (action=look)
 * 3. Agent describes the content of the captured photo
 * 4. Validate: used camera tool + final output has substantial content (not error) = pass
 *
 * Run:
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.xiaomo.androidforclaw.e2e.CameraE2ETest
 *
 * ⚠️ Setup Requirements:
 * - Real device (with camera)
 * - CAMERA permission granted
 * - LLM API Key configured
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CameraE2ETest {

    companion object {
        private const val TAG = "CameraE2E"
        private const val TIMEOUT_MS = 120_000L // 2 minute timeout (photo + LLM response)
    }

    private lateinit var context: Context
    private lateinit var llmProvider: UnifiedLLMProvider
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var androidToolRegistry: AndroidToolRegistry
    private lateinit var configLoader: ConfigLoader
    private lateinit var contextBuilder: ContextBuilder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<MyApplication>()
        configLoader = ConfigLoader(context)
        llmProvider = UnifiedLLMProvider(context)
        val taskDataManager = TaskDataManager.getInstance()
        toolRegistry = ToolRegistry(context, taskDataManager)
        androidToolRegistry = AndroidToolRegistry(
            context = context,
            taskDataManager = taskDataManager,
            cameraCaptureManager = MyApplication.getCameraCaptureManager(),
        )
        contextBuilder = ContextBuilder(context, toolRegistry, androidToolRegistry, configLoader)
    }

    /**
     * Core Test: Send "take a picture and see what's there", validate Agent calls camera and describes photo content
     */
    @Test
    fun test_cameraSnap_describeContent() {
        val agentLoop = AgentLoop(
            llmProvider = llmProvider,
            toolRegistry = toolRegistry,
            androidToolRegistry = androidToolRegistry,
            maxIterations = 10,
            configLoader = configLoader
        )

        val systemPrompt = contextBuilder.buildSystemPrompt(
            promptMode = ContextBuilder.Companion.PromptMode.FULL
        )

        val result = runBlocking {
            withTimeout(TIMEOUT_MS) {
                agentLoop.run(
                    systemPrompt = systemPrompt,
                    userMessage = "take a picture and see what's there",
                    reasoningEnabledd = false
                )
            }
        }

        // Print report
        println("═".repeat(60))
        println("📊 Camera E2E Test Report")
        println("═".repeat(60))
        println("🔄 Iterations: ${result.iterations}")
        println("🔧 Tools used: ${result.toolsUsed.joinToString(", ")}")
        println("📄 Final output: ${result.finalContent.take(500)}")
        println("═".repeat(60))

        // Validate 1: used eye tool
        assertTrue(
            "Agent should call eye tool, actually used: ${result.toolsUsed}",
            result.toolsUsed.any { it == "eye" }
        )

        // Validate 2: final output has substantial content (not null, and not pure error info)
        assertTrue(
            "Final output should not be null",
            result.finalContent.isNotBlank()
        )

        // Validate 3: output does not contain permission error (meaning photo was taken successfully)
        assertFalse(
            "Should not have permission error (please grant CAMERA permission on device first)",
            result.finalContent.contains("CAMERA_PERMISSION_REQUIRED")
        )

        // Validate 4: output does not contain generic error
        val isError = result.finalContent.contains("UNAVAILABLE") &&
            !result.finalContent.contains("photo") // exclude normal description that may occasionally contain this word
        assertFalse(
            "Photo taking should not fail: ${result.finalContent.take(200)}",
            isError
        )

        // Validate 5: Agent should describe the photo content (has substantial description)
        // As long as output length > 10 and not pure error, consider that Agent described the content
        assertTrue(
            "Agent should describe photo content, actual output length: ${result.finalContent.length}",
            result.finalContent.length > 10
        )

        println("✅ Camera E2E Test passed! Agent successfully took photo and described content.")
    }
}
