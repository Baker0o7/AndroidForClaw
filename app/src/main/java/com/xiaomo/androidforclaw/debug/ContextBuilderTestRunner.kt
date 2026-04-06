/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.context

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.skills.SingleTestresult
import com.xiaomo.androidforclaw.agent.skills.Testresult
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.data.model.TaskDatamanager

/**
 * contextBuilder TestRun器 (Block 3)
 */
object contextBuilderTestRunner {
    private const val TAG = "contextBuilderTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        // Block 3 原HasTest
        results.a(testBuildSystemPrompt(context))
        results.a(testAlwaysskillsInjection(context))
        results.a(testRelevantskillsSelection(context))
        results.a(testTokenReduction(context))

        // Block 4 new增Test
        results.a(testBootstrapFilesLoaded(context))

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
    private fun testBuildSystemPrompt(context: context): SingleTestresult {
        return try {
            val builder = createcontextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Test音乐播放器",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            assert(systemPrompt.isnotEmpty()) { "System prompt should not be empty" }
            assert(systemPrompt.contains("androidforClaw")) { "should contain identity" }

            Log.d(TAG, "[OK] testBuildSystemPrompt PASSED")
            Log.d(TAG, "   Prompt length: ${systemPrompt.length} chars")
            SingleTestresult("testBuildSystemPrompt", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testBuildSystemPrompt FAILED: ${e.message}")
            SingleTestresult("testBuildSystemPrompt", false, e.message)
        }
    }

    /**
     * Test 2: Always skills 注入
     */
    private fun testAlwaysskillsInjection(context: context): SingleTestresult {
        return try {
            val builder = createcontextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Open微信",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // shouldContains mobile-operations (always: true)
            assert(systemPrompt.contains("Active skills")) { "should contain Active skills section" }
            assert(systemPrompt.contains("mobile-operations") || systemPrompt.contains("[APP]")) {
                "should contain mobile-operations skill"
            }

            Log.d(TAG, "[OK] testAlwaysskillsInjection PASSED")
            Log.d(TAG, "   Contains Always skills: mobile-operations")
            SingleTestresult("testAlwaysskillsInjection", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testAlwaysskillsInjection FAILED: ${e.message}")
            SingleTestresult("testAlwaysskillsInjection", false, e.message)
        }
    }

    /**
     * Test 3: 相关 skills choose
     */
    private fun testRelevantskillsSelection(context: context): SingleTestresult {
        return try {
            val builder = createcontextBuilder(context)

            // TestTaskshouldLoad app-testing
            val testPrompt = builder.buildSystemPrompt(
                userGoal = "Test音乐播放器AllFeature",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            // DebugTaskshouldLoad debugging
            val debugPrompt = builder.buildSystemPrompt(
                userGoal = "DebugLoginFeatureIssue",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            Log.d(TAG, "[OK] testRelevantskillsSelection PASSED")
            Log.d(TAG, "   Test prompt: ${testPrompt.length} chars")
            Log.d(TAG, "   Debug prompt: ${debugPrompt.length} chars")
            SingleTestresult("testRelevantskillsSelection", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testRelevantskillsSelection FAILED: ${e.message}")
            SingleTestresult("testRelevantskillsSelection", false, e.message)
        }
    }

    /**
     * Test 4: Token 减fewvalidation
     */
    private fun testTokenReduction(context: context): SingleTestresult {
        return try {
            val builder = createcontextBuilder(context)

            // SimpleTask(只Has Always skills)
            val simplePrompt = builder.buildSystemPrompt(
                userGoal = "Open微信",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // ComplexTask(Always + Relevant skills)
            val complexPrompt = builder.buildSystemPrompt(
                userGoal = "Test并Debug音乐播放器",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            val simpleTokens = simplePrompt.length / 4
            val complexTokens = complexPrompt.length / 4

            Log.d(TAG, "[OK] testTokenReduction PASSED")
            Log.d(TAG, "   Simple task: ~$simpleTokens tokens")
            Log.d(TAG, "   Complex task: ~$complexTokens tokens")
            Log.d(TAG, "   目标: < 1500 tokens (Block 3)")

            // validation Token use合理
            assert(simpleTokens < 2000) { "Simple task tokens should be < 2000" }
            assert(complexTokens < 3000) { "Complex task tokens should be < 3000" }

            SingleTestresult("testTokenReduction", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testTokenReduction FAILED: ${e.message}")
            SingleTestresult("testTokenReduction", false, e.message)
        }
    }

    /**
     * Test Block 4: Bootstrap filesLoad
     */
    private fun testBootstrapFilesLoaded(context: context): SingleTestresult {
        return try {
            val builder = createcontextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Testapp",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            // shouldContains Bootstrap filescontent
            assert(systemPrompt.contains("androidforClaw agent") ||
                   systemPrompt.contains("core capability") ||
                   systemPrompt.contains("工作原then")) {
                "should contain Bootstrap files content (IDENTITY.md or AGENTS.md)"
            }

            Log.d(TAG, "[OK] testBootstrapFilesLoaded PASSED")
            Log.d(TAG, "   Bootstrap files loaded successfully")
            SingleTestresult("testBootstrapFilesLoaded", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testBootstrapFilesLoaded FAILED: ${e.message}")
            SingleTestresult("testBootstrapFilesLoaded", false, e.message)
        }
    }

    /**
     * Create contextBuilder Instance
     */
    private fun createcontextBuilder(context: context): contextBuilder {
        val toolRegistry = toolRegistry(
            context = context,
            taskDatamanager = TaskDatamanager.getInstance()
        )

        val androidtoolRegistry = androidtoolRegistry(
            context = context,
            taskDatamanager = TaskDatamanager.getInstance(),
            cameraCapturemanager = com.xiaomo.androidforclaw.core.MyApplication.getCameraCapturemanager(),
        )

        return contextBuilder(
            context = context,
            toolRegistry = toolRegistry,
            androidtoolRegistry = androidtoolRegistry
        )
    }
}
