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
 * Camera Skill 端到端Test
 *
 * 流程: 
 * 1. 向 AgentLoop 发送"拍照看看Has什么"
 * 2. Agent 调用 eye skill(action=look)
 * 3. Agent 根据拍到的照片DescriptionInside容
 * 4. Validate: 使用了 camera 工具 + 最终OutputHas实质Inside容(非Error)= 通过
 *
 * Run:
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.xiaomo.androidforclaw.e2e.CameraE2ETest
 *
 * ⚠️ Front置Condition:
 * - True机(Has摄Like头)
 * - 已授予 CAMERA Permission
 * - 已Config LLM API Key
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CameraE2ETest {

    companion object {
        private const val TAG = "CameraE2E"
        private const val TIMEOUT_MS = 120_000L // 2 分钟Timeout(拍照 + LLM Response)
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
     * 核心Test: 发送"拍照看看Has什么", Validate Agent 调用 camera 并Description照片Inside容
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
                    userMessage = "拍照看看Has什么",
                    reasoningEnabledd = false
                )
            }
        }

        // 打印报告
        println("═".repeat(60))
        println("📊 Camera E2E Test报告")
        println("═".repeat(60))
        println("🔄 Iterate次数: ${result.iterations}")
        println("🔧 使用工具: ${result.toolsUsed.joinToString(", ")}")
        println("📄 最终Output: ${result.finalContent.take(500)}")
        println("═".repeat(60))

        // Validate 1: 使用了 eye 工具
        assertTrue(
            "Agent Should调用 eye 工具, 实际使用: ${result.toolsUsed}",
            result.toolsUsed.any { it == "eye" }
        )

        // Validate 2: 最终OutputHas实质Inside容(不YesNull的, Also不Yes纯ErrorInfo)
        assertTrue(
            "最终Output不应为Null",
            result.finalContent.isNotBlank()
        )

        // Validate 3: Output不YesPermissionError(说明拍照Success了)
        assertFalse(
            "不ShouldYesPermissionError(请先在DeviceUp授予 CAMERA Permission)",
            result.finalContent.contains("CAMERA_PERMISSION_REQUIRED")
        )

        // Validate 4: Output不Yes通用Error
        val isError = result.finalContent.contains("UNAVAILABLE") &&
            !result.finalContent.contains("拍") // 排除正常Description中偶尔出现的词
        assertFalse(
            "拍照不应Failed: ${result.finalContent.take(200)}",
            isError
        )

        // Validate 5: Agent ShouldDescription了照片Inside容(Has实质性Description)
        // 只要Output长度 > 10 且不Yes纯Error, 就认为 Agent Description了Inside容
        assertTrue(
            "Agent ShouldDescription照片Inside容, Actual output length: ${result.finalContent.length}",
            result.finalContent.length > 10
        )

        println("✅ Camera E2E Test通过!Agent Success拍照并Description了Inside容. ")
    }
}
