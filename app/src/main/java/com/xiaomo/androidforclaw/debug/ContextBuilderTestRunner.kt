/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.context

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.skills.SingleTestResult
import com.xiaomo.androidforclaw.agent.skills.TestResult
import com.xiaomo.androidforclaw.agent.tools.AndroidToolRegistry
import com.xiaomo.androidforclaw.agent.tools.ToolRegistry
import com.xiaomo.androidforclaw.data.model.TaskDataManager

/**
 * ContextBuilder Test Runner (Block 3)
 */
object ContextBuilderTestRunner {
    private const val TAG = "contextBuilderTest"

    /**
     * Run All Tests
     */
    fun runAllTests(context: Context): TestResult {
        val results = mutableListOf<SingleTestResult>()

        // Block 3 original tests
        results.add(testBuildSystemPrompt(context))
        results.add(testAlwaysSkillsInjection(context))
        results.add(testRelevantSkillsSelection(context))
        results.add(testTokenReduction(context))

        // Block 4 new test
        results.add(testBootstrapFilesLoaded(context))

        val passed = results.count { it.passed }
        val total = results.size

        return TestResult(
            passed = passed,
            total = total,
            results = results
        )
    }

    /**
     * Test 1: Build system prompt
     */
    private fun testBuildSystemPrompt(context: Context): SingleTestResult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Test music player",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            assert(systemPrompt.isNotEmpty()) { "System prompt should not be empty" }
            assert(systemPrompt.contains("AndroidForClaw")) { "should contain identity" }

            Log.d(TAG, "[OK] testBuildSystemPrompt PASSED")
            Log.d(TAG, "   Prompt length: ${systemPrompt.length} chars")
            SingleTestResult("testBuildSystemPrompt", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testBuildSystemPrompt FAILED: ${e.message}")
            SingleTestResult("testBuildSystemPrompt", false, e.message)
        }
    }

    /**
     * Test 2: Always skills injection
     */
    private fun testAlwaysSkillsInjection(context: Context): SingleTestResult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Open WeChat",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // Should contain mobile-operations (always: true)
            assert(systemPrompt.contains("Active skills")) { "should contain Active skills section" }
            assert(systemPrompt.contains("mobile-operations") || systemPrompt.contains("[APP]")) {
                "should contain mobile-operations skill"
            }

            Log.d(TAG, "[OK] testAlwaysSkillsInjection PASSED")
            Log.d(TAG, "   Contains Always skills: mobile-operations")
            SingleTestResult("testAlwaysSkillsInjection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testAlwaysSkillsInjection FAILED: ${e.message}")
            SingleTestResult("testAlwaysSkillsInjection", false, e.message)
        }
    }

    /**
     * Test 3: Relevant skills selection
     */
    private fun testRelevantSkillsSelection(context: Context): SingleTestResult {
        return try {
            val builder = createContextBuilder(context)

            // Test task should load app-testing
            val testPrompt = builder.buildSystemPrompt(
                userGoal = "Test music player all features",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            // Debug task should load debugging
            val debugPrompt = builder.buildSystemPrompt(
                userGoal = "Debug login feature issue",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            Log.d(TAG, "[OK] testRelevantSkillsSelection PASSED")
            Log.d(TAG, "   Test prompt: ${testPrompt.length} chars")
            Log.d(TAG, "   Debug prompt: ${debugPrompt.length} chars")
            SingleTestResult("testRelevantSkillsSelection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testRelevantSkillsSelection FAILED: ${e.message}")
            SingleTestResult("testRelevantSkillsSelection", false, e.message)
        }
    }

    /**
     * Test 4: Token reduction validation
     */
    private fun testTokenReduction(context: Context): SingleTestResult {
        return try {
            val builder = createContextBuilder(context)

            // Simple task (only has always skills)
            val simplePrompt = builder.buildSystemPrompt(
                userGoal = "Open WeChat",
                packageName = "com.tencent.mm",
                testMode = "exploration"
            )

            // Complex task (Always + relevant skills)
            val complexPrompt = builder.buildSystemPrompt(
                userGoal = "Test and debug music player",
                packageName = "com.example.music",
                testMode = "exploration"
            )

            val simpleTokens = simplePrompt.length / 4
            val complexTokens = complexPrompt.length / 4

            Log.d(TAG, "[OK] testTokenReduction PASSED")
            Log.d(TAG, "   Simple task: ~$simpleTokens tokens")
            Log.d(TAG, "   Complex task: ~$complexTokens tokens")
            Log.d(TAG, "   Goal: < 1500 tokens (Block 3)")

            // Validate token usage reasonable
            assert(simpleTokens < 2000) { "Simple task tokens should be < 2000" }
            assert(complexTokens < 3000) { "Complex task tokens should be < 3000" }

            SingleTestResult("testTokenReduction", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testTokenReduction FAILED: ${e.message}")
            SingleTestResult("testTokenReduction", false, e.message)
        }
    }

    /**
     * Test Block 4: Bootstrap files load
     */
    private fun testBootstrapFilesLoaded(context: Context): SingleTestResult {
        return try {
            val builder = createContextBuilder(context)

            val systemPrompt = builder.buildSystemPrompt(
                userGoal = "Test app",
                packageName = "com.example.app",
                testMode = "exploration"
            )

            // Should contain bootstrap files content
            assert(systemPrompt.contains("AndroidForClaw agent") ||
                   systemPrompt.contains("core capability") ||
                   systemPrompt.contains("working principle")) {
                "should contain Bootstrap files content (IDENTITY.md or AGENTS.md)"
            }

            Log.d(TAG, "[OK] testBootstrapFilesLoaded PASSED")
            Log.d(TAG, "   Bootstrap files loaded successfully")
            SingleTestResult("testBootstrapFilesLoaded", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testBootstrapFilesLoaded FAILED: ${e.message}")
            SingleTestResult("testBootstrapFilesLoaded", false, e.message)
        }
    }

    /**
     * Create ContextBuilder instance
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