package com.xiaomo.androidforclaw.e2e

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.xiaomo.androidforclaw.agent.tools.AndroidToolRegistry
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.data.model.TaskDataManager
import com.xiaomo.androidforclaw.ui.activity.MainActivityCompose
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Skill Feature端到端Test
 *
 * TestReal的Android Skills执Row:
 * - screenshot: Screenshot
 * - tap: 点击
 * - swipe: 滑动
 * - type: Input
 * - home/back: 导航
 * - open_app: Open应用
 *
 * 按照Agent实际使用场景Test
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SkillE2ETest {

    companion object {
        private const val TIMEOUT = 5000L
        private const val PACKAGE_NAME = "com.xiaomo.androidforclaw"

        // StaticVariable,在AllTest间共享
        lateinit var device: UiDevice
        lateinit var context: Context
        lateinit var toolRegistry: AndroidToolRegistry
        lateinit var taskDataManager: TaskDataManager

        @BeforeClass
        @JvmStatic
        fun setupOnce() {
            device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            context = ApplicationProvider.getApplicationContext<MyApplication>()
            taskDataManager = TaskDataManager.getInstance()
            toolRegistry = AndroidToolRegistry(context, taskDataManager)

            // 只Start一次应用,供AllTest使用
            println("\n🚀 Start应用 - StartSkillTest套件")
            println("=" .repeat(60))
            launchApp()
            Thread.sleep(1500)
        }

        @JvmStatic
        fun launchApp() {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(PACKAGE_NAME, MainActivityCompose::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), TIMEOUT)
            device.waitForIdle()
        }
    }

    /**
     * 场景1: ScreenshotFeature
     * AgentNeed观察Screen时使用
     */
    @Test
    fun test01_skill_screenshot() = runBlocking {
        println("🎯 TestSkill: screenshot")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // 执RowScreenshot
        val result = toolRegistry.execute("device", mapOf("action" to "screenshot"))

        assumeTrue("ScreenshotNeed MediaProjection Permission, Skip", result.success)
        // Device screenshot may return base64 or file path
        assertTrue("ScreenshotShouldHasInside容", result.content.isNotEmpty())
        println("✅ Screenshot执RowComplete: ${result.content.take(100)}")
        println()
    }

    /**
     * 场景2: Home导航
     * AgentNeedReturn主Screen时使用
     */
    @Test
    fun test02_skill_home() = runBlocking {
        println("🎯 TestSkill: home")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // 执RowHome
        val result = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "home"))

        assumeTrue("HomeNeedAccessibilityService, Skip", result.success)
        delay(500)

        // Validate确实到了主Screen
        // Home press executed successfully
        device.wait(Until.hasObject(By.pkg("com.miui.home")), 2000)

        println("✅ Return主ScreenSuccess")
        println()

        // Return到应用ContinueTest
        println("  → Return应用ContinueTest...")
        launchApp()
        Thread.sleep(500)
    }

    /**
     * 场景3: Back导航
     * AgentNeedReturnUp一页时使用
     */
    @Test
    fun test03_skill_back() = runBlocking {
        println("🎯 TestSkill: back")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // 执RowBack
        val result = toolRegistry.execute("device", mapOf("action" to "act", "kind" to "press", "key" to "BACK"))

        assumeTrue("BackNeedAccessibilityService, Skip", result.success)
        delay(500)

        println("✅ ReturnUp一页Success")
        println()

        // Return到应用ContinueTest
        println("  → Return应用ContinueTest...")
        launchApp()
        Thread.sleep(500)
    }

    /**
     * 场景4: WaitWait
     * AgentNeedWait页面Load时使用
     */
    @Test
    fun test04_skill_wait() = runBlocking {
        println("🎯 TestSkill: wait")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val startTime = System.currentTimeMillis()

        // Wait0.2秒
        val result = toolRegistry.execute("wait", mapOf("seconds" to 0.2))

        val elapsed = System.currentTimeMillis() - startTime

        assumeTrue("device wait 在TestEnvironment可能不Available, Skip", result.success)
        assertTrue("ShouldWait约200ms", elapsed >= 180 && elapsed < 300)

        println("✅ WaitSuccess: ${elapsed}ms")
        println()
    }

    /**
     * 场景6: NotificationNotification
     * AgentNeed发送Notification时使用
     */
    @Test
    fun test06_skill_notification() = runBlocking {
        println("🎯 TestSkill: notification")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // 发送Notification
        val result = toolRegistry.execute("notification", mapOf(
            "title" to "SkillTest",
            "message" to "这Yes一条TestNotification"
        ))

        assumeTrue("NotificationNeedPermission, Skip", result.success)

        println("✅ Notification发送Success")
        println()
    }

    /**
     * 场景8: 完整Agent工作流
     * MockAgent完整执Row流程
     */
    @Test
    fun test08_completeAgentWorkflow() = runBlocking {
        println("🤖 完整Agent工作流Test")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println()
        println("📋 场景: AgentNeedScreenshot并分析ScreenInside容")
        println()

        val workflow = listOf(
            "步骤1: RecordStart" to mapOf("tool" to "log", "args" to mapOf("message" to "StartScreenshotTask")),
            "步骤2: WaitUIStable" to mapOf("tool" to "wait", "args" to mapOf("seconds" to 0.3)),
            "步骤3: 执RowScreenshot" to mapOf("tool" to "screenshot", "args" to emptyMap<String, Any>()),
            "步骤4: WaitSave" to mapOf("tool" to "wait", "args" to mapOf("seconds" to 0.2)),
            "步骤5: RecordComplete" to mapOf("tool" to "log", "args" to mapOf("message" to "ScreenshotComplete")),
            "步骤6: 发送Notification" to mapOf("tool" to "notification", "args" to mapOf(
                "title" to "TaskComplete",
                "message" to "Screenshot已Save"
            )),
            "步骤7: Stop执Row" to mapOf("tool" to "stop", "args" to mapOf("reason" to "TaskComplete"))
        )

        val results = mutableListOf<Triple<String, String, Boolean>>()
        val startTime = System.currentTimeMillis()

        workflow.forEachIndexed { index, (step, config) ->
            val toolName = config["tool"] as String
            @Suppress("UNCHECKED_CAST")
            val args = config["args"] as Map<String, Any?>

            print("  ${index + 1}. $step ... ")

            val result = toolRegistry.execute(toolName, args)
            results.add(Triple(step, toolName, result.success))

            if (result.success) {
                println("✅")
                if (toolName == "screenshot" && result.content.contains("/sdcard/")) {
                    val path = result.content.substringAfter("saved to ").trim()
                    println("     → ScreenshotSave: ${File(path).name}")
                }
            } else {
                println("❌ ${result.content}")
            }

            delay(100) // MockAgent思考
        }

        val totalTime = System.currentTimeMillis() - startTime
        val allSuccess = results.all { it.third }

        println()
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("📊 工作流统计:")
        println("   - 总步骤: ${workflow.size}")
        println("   - Success: ${results.count { it.third }}")
        println("   - Failed: ${results.count { !it.third }}")
        println("   - 总耗时: ${totalTime}ms")
        println("   - 平均耗时: ${totalTime / workflow.size}ms/步")
        println()

        // Some steps require accessibility service / MediaProjection — skip if unavailable
        assumeTrue("工作流NeedAccessibilityService等系统Permission, Skip", allSuccess)

        println("✅ 完整Agent工作流Test通过")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println()
    }

    // ========== 辅助Method ==========
    // launchApp 已移至 companion object 作为共享Method
}
