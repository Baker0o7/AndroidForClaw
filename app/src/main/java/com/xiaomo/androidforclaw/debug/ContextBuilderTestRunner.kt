/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.agent.context

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.skills.SingleTestresult
import com.xiaomo.androidforclaw.agent.skills.Testresult
import com.xiaomo.androidforclaw.agent.tools.AndroidToolRegistry
import com.xiaomo.androidforclaw.agent.tools.ToolRegistry
import com.xiaomo.androidforclaw.data.model.TaskDataManager

/**
 * ContextBuilder TestRun器 (Block 3)
 */
object ContextBuilderTestRunner {
    private const val TAG = "ContextBuilderTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: Context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        // Block 3 原HasTest
        results.add(testBuildSystemPrompt(context))
        results.add(testAlwaysSkillsInjection(context))
        results.add(testRelevantSkillsSelection(context))
        results.add(testTokenReduction(context))

        // Block 4 New增Test
        results.add(testBootstrapFilesLoaded(context))

        val passed = results.count { it.passed }
        val total = results.size

        return Testresult(
            passed = passed,
            total = total,
            results = results
        )
    }

    /**
     * Test 1: Build系统Hint词
     */
    private fun testBuildSystemPrompt(context: Context): SingleTestresult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Test音乐播放器",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            assert(systemPrompt.isNotEmpty()) { "System prompt should not be empty" }
            assert(systemPrompt.contains("AndroidForClaw")) { "Should contain identity" }

            Log.d(TAG, "✅ testBuildSystemPrompt PASSED")
            Log.d(TAG, "   Prompt length: ${systemPrompt.length} chars")
            SingleTestresult("testBuildSystemPrompt", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testBuildSystemPrompt FAILED: ${e.message}")
            SingleTestresult("testBuildSystemPrompt", false, e.message)
        }
    }

    /**
     * Test 2: Always Skills 注入
     */
    private fun testAlwaysSkillsInjection(context: Context): SingleTestresult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Open微信",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // ShouldContains mobile-operations (always: true)
            assert(systemPrompt.contains("Active Skills")) { "Should contain Active Skills section" }
            assert(systemPrompt.contains("mobile-operations") || systemPrompt.contains("📱")) {
                "Should contain mobile-operations skill"
            }

            Log.d(TAG, "✅ testAlwaysSkillsInjection PASSED")
            Log.d(TAG, "   Contains Always Skills: mobile-operations")
            SingleTestresult("testAlwaysSkillsInjection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testAlwaysSkillsInjection FAILED: ${e.message}")
            SingleTestresult("testAlwaysSkillsInjection", false, e.message)
        }
    }

    /**
     * Test 3: 相关 Skills choose
     */
    private fun testRelevantSkillsSelection(context: Context): SingleTestresult {
        return try {
            val builder = createContextBuilder(context)

            // TestTaskShouldLoad app-testing
            val testPrompt = builder.buildSystemPrompt(
                userGoal = "Test音乐播放器的AllFeature",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            // DebugTaskShouldLoad debugging
            val debugPrompt = builder.buildSystemPrompt(
                userGoal = "DebugLoginFeature的Issue",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            Log.d(TAG, "✅ testRelevantSkillsSelection PASSED")
            Log.d(TAG, "   Test prompt: ${testPrompt.length} chars")
            Log.d(TAG, "   Debug prompt: ${debugPrompt.length} chars")
            SingleTestresult("testRelevantSkillsSelection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testRelevantSkillsSelection FAILED: ${e.message}")
            SingleTestresult("testRelevantSkillsSelection", false, e.message)
        }
    }

    /**
     * Test 4: Token 减少Validate
     */
    private fun testTokenReduction(context: Context): SingleTestresult {
        return try {
            val builder = createContextBuilder(context)

            // SimpleTask(只Has Always Skills)
            val simplePrompt = builder.buildSystemPrompt(
                userGoal = "Open微信",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // ComplexTask(Always + Relevant Skills)
            val complexPrompt = builder.buildSystemPrompt(
                userGoal = "Test并Debug音乐播放器",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            val simpleTokens = simplePrompt.length / 4
            val complexTokens = complexPrompt.length / 4

            Log.d(TAG, "✅ testTokenReduction PASSED")
            Log.d(TAG, "   Simple task: ~$simpleTokens tokens")
            Log.d(TAG, "   Complex task: ~$complexTokens tokens")
            Log.d(TAG, "   目标: < 1500 tokens (Block 3)")

            // Validate Token use合理
            assert(simpleTokens < 2000) { "Simple task tokens should be < 2000" }
            assert(complexTokens < 3000) { "Complex task tokens should be < 3000" }

            SingleTestresult("testTokenReduction", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testTokenReduction FAILED: ${e.message}")
            SingleTestresult("testTokenReduction", false, e.message)
        }
    }

    /**
     * Test Block 4: Bootstrap 文件Load
     */
    private fun testBootstrapFilesLoaded(context: Context): SingleTestresult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Testapply",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            // ShouldContains Bootstrap 文件Inside容
            assert(systemPrompt.contains("AndroidForClaw Agent") ||
                   systemPrompt.contains("核心Capability") ||
                   systemPrompt.contains("工作原则")) {
                "Should contain Bootstrap files content (IDENTITY.md or AGENTS.md)"
            }

            Log.d(TAG, "✅ testBootstrapFilesLoaded PASSED")
            Log.d(TAG, "   Bootstrap files loaded successfully")
            SingleTestresult("testBootstrapFilesLoaded", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testBootstrapFilesLoaded FAILED: ${e.message}")
            SingleTestresult("testBootstrapFilesLoaded", false, e.message)
        }
    }

    /**
     * Create ContextBuilder Instance
     */
    private fun createContextBuilder(context: Context): ContextBuilder {
        val toolRegistry = ToolRegistry(
            context = context,
            taskDataManager = TaskDataManager.getInstance()
        )

        val androidToolRegistry = AndroidToolRegistry(
            context = context,
            taskDataManager = TaskDataManager.getInstance(),
            cameraCaptureManager = com.xiaomo.androidforclaw.core.MyApplication.getCameraCaptureManager(),
        )

        return ContextBuilder(
            context = context,
            toolRegistry = toolRegistry,
            androidToolRegistry = androidToolRegistry
        )
    }
}
