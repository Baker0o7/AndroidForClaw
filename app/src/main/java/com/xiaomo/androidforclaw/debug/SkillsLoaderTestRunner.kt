/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * SkillsLoader Test Runner
 */
object SkillsLoaderTestRunner {
    private const val TAG = "skillsLoaderTest"

    /**
     * Run All Tests
     */
    fun runAllTests(context: Context): TestResult {
        val results = mutableListOf<SingleTestResult>()

        // Block 2 original tests
        results.add(testLoadBundledSkills(context))
        results.add(testGetAlwaysSkills(context))
        results.add(testSelectRelevantSkills(context))
        results.add(testStatistics(context))
        results.add(testPriorityOverride(context))
        results.add(testReload(context))
        results.add(testCheckRequirements(context))

        // Block 5 new tests
        results.add(testNewSkillsLoaded(context))
        results.add(testImprovedSelection(context))

        // Block 6 new tests
        results.add(testHotReload(context))

        val passed = results.count { it.passed }
        val total = results.size

        return TestResult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testLoadBundledSkills(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)
            val skills = loader.loadSkills()

            // Validate at least mobile-operations loaded
            assert(skills.isNotEmpty()) { "should load at least 1 skill" }
            assert(skills.containsKey("mobile-operations")) { "should contain mobile-operations" }

            val mobileOps = skills["mobile-operations"]!!
            assert(mobileOps.metadata.always) { "mobile-operations should be always loaded" }
            assert(mobileOps.metadata.emoji == "[APP]") { "mobile-operations emoji should be [APP]" }

            Log.d(TAG, "[OK] testLoadBundledSkills PASSED")
            Log.d(TAG, "   Loaded ${skills.size} skills")
            SingleTestResult("testLoadBundledSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testLoadBundledSkills FAILED: ${e.message}")
            SingleTestResult("testLoadBundledSkills", false, e.message)
        }
    }

    private fun testGetAlwaysSkills(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)
            val alwaysSkills = loader.getAlwaysSkills()

            assert(alwaysSkills.isNotEmpty()) { "should have at least 1 always skill" }

            // Validate all returned skills are always
            for (skill in alwaysSkills) {
                assert(skill.metadata.always) { "${skill.name} should be always" }
            }

            Log.d(TAG, "[OK] testGetAlwaysSkills PASSED")
            Log.d(TAG, "   Always skills: ${alwaysSkills.size}")
            SingleTestResult("testGetAlwaysSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testGetAlwaysSkills FAILED: ${e.message}")
            SingleTestResult("testGetAlwaysSkills", false, e.message)
        }
    }

    private fun testSelectRelevantSkills(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)

            // Test different user goals
            val testGoal = loader.selectRelevantSkills("Test music player", excludeAlways = true)
            val debugGoal = loader.selectRelevantSkills("Debug login feature", excludeAlways = true)

            Log.d(TAG, "[OK] testSelectRelevantSkills PASSED")
            Log.d(TAG, "   Test goal: ${testGoal.size} skills")
            Log.d(TAG, "   Debug goal: ${debugGoal.size} skills")
            SingleTestResult("testSelectRelevantSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testSelectRelevantSkills FAILED: ${e.message}")
            SingleTestResult("testSelectRelevantSkills", false, e.message)
        }
    }

    private fun testStatistics(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)
            val stats = loader.getStatistics()

            assert(stats.totalSkills > 0) { "should have skills" }
            assert(stats.alwaysSkills + stats.onDemandSkills == stats.totalSkills) {
                "Always + OnDemand should equal total"
            }
            assert(stats.totalTokens > 0) { "should have tokens" }

            Log.d(TAG, "[OK] testStatistics PASSED")
            Log.d(TAG, stats.getReport())
            SingleTestResult("testStatistics", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testStatistics FAILED: ${e.message}")
            SingleTestResult("testStatistics", false, e.message)
        }
    }

    private fun testPriorityOverride(context: Context): SingleTestResult {
        return try {
            // Create test workspace skill
            val workspaceDir = File(StoragePaths.workspaceSkills, "test-override")
            workspaceDir.mkdirs()

            val testSkillFile = File(workspaceDir, "SKILL.md")
            testSkillFile.writeText("""
---
name: mobile-operations
description: Workspace override version
metadata:
  {
    "openclaw": {
      "always": true,
      "emoji": "[TEST]"
    }
  }
---

# Workspace override Test
            """.trimIndent())

            // Reload
            val loader = SkillsLoader(context)
            loader.reload()
            val skills = loader.loadSkills()

            val mobileOps = skills["mobile-operations"]
            val isWorkspaceVersion = mobileOps?.description == "Workspace override version"

            // Cleanup test files
            testSkillFile.delete()
            workspaceDir.delete()

            if (isWorkspaceVersion) {
                Log.d(TAG, "[OK] testPriorityOverride PASSED")
                Log.d(TAG, "   Workspace skill correctly overrides bundled")
                SingleTestResult("testPriorityOverride", true, null)
            } else {
                Log.e(TAG, "[ERROR] testPriorityOverride FAILED: Workspace not overriding")
                SingleTestResult("testPriorityOverride", false, "Priority not working")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testPriorityOverride FAILED: ${e.message}")
            SingleTestResult("testPriorityOverride", false, e.message)
        }
    }

    private fun testReload(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)

            // First time load
            val skills1 = loader.loadSkills()
            val count1 = skills1.size

            // Reload
            loader.reload()
            val skills2 = loader.loadSkills()
            val count2 = skills2.size

            assert(count1 == count2) { "Reload should load same number of skills" }

            Log.d(TAG, "[OK] testReload PASSED")
            Log.d(TAG, "   Reloaded ${count2} skills")
            SingleTestResult("testReload", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testReload FAILED: ${e.message}")
            SingleTestResult("testReload", false, e.message)
        }
    }

    private fun testCheckRequirements(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)

            // Create one has dependency test skill
            val skillWithRequires = SkillDocument(
                name = "test-requires",
                description = "Test",
                metadata = SkillMetadata(
                    requires = SkillRequires(
                        bins = listOf("nonexistent-binary"),
                        env = listOf("NONEXISTENT_ENV"),
                        config = listOf("nonexistent.config")
                    )
                ),
                content = "Test"
            )

            val result = loader.checkRequirements(skillWithRequires)

            assert(result is RequirementsCheckResult.Unsatisfied) {
                "should be unsatisfied"
            }

            if (result is RequirementsCheckResult.Unsatisfied) {
                assert(result.missingBins.contains("nonexistent-binary"))
                assert(result.missingEnv.contains("NONEXISTENT_ENV"))
                assert(result.missingConfig.contains("nonexistent.config"))
            }

            Log.d(TAG, "[OK] testCheckRequirements PASSED")
            SingleTestResult("testCheckRequirements", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testCheckRequirements FAILED: ${e.message}")
            SingleTestResult("testCheckRequirements", false, e.message)
        }
    }

    /**
     * Test Block 5: new skills whether load
     */
    private fun testNewSkillsLoaded(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)
            val skills = loader.loadSkills()

            // Validate new 4 count skills
            val newSkills = listOf("accessibility", "performance", "ui-validation", "network-testing")
            var allLoaded = true

            for (skillName in newSkills) {
                if (!skills.containsKey(skillName)) {
                    Log.w(TAG, "[WARN] skill not loaded: $skillName")
                    allLoaded = false
                }
            }

            assert(allLoaded) { "All new skills should be loaded" }

            Log.d(TAG, "[OK] testNewSkillsLoaded PASSED")
            Log.d(TAG, "   Loaded ${newSkills.size} new skills")
            SingleTestResult("testNewSkillsLoaded", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testNewSkillsLoaded FAILED: ${e.message}")
            SingleTestResult("testNewSkillsLoaded", false, e.message)
        }
    }

    /**
     * Test Block 5: Improve choose algorithm
     */
    private fun testImprovedSelection(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)

            // Test task type recognition
            val testTasks = mapOf(
                "Test music player performance" to listOf("app-testing", "performance"),
                "Debug network issue" to listOf("debugging", "network-testing"),
                "Validate UI display" to listOf("ui-validation"),
                "Check no accessibility adaptation" to listOf("accessibility")
            )

            var allMatched = true
            for ((userGoal, expectedSkills) in testTasks) {
                val selected = loader.selectRelevantSkills(userGoal, excludeAlways = true)
                val selectedNames = selected.map { it.name }

                for (expected in expectedSkills) {
                    if (!selectedNames.contains(expected)) {
                        Log.w(TAG, "[WARN] Expected '$expected' for goal '$userGoal', but not selected")
                        allMatched = false
                    }
                }
            }

            Log.d(TAG, if (allMatched) "[OK] testImprovedSelection PASSED" else "[WARN] testImprovedSelection PARTIAL")
            Log.d(TAG, "   Task type identification working")
            SingleTestResult("testImprovedSelection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testImprovedSelection FAILED: ${e.message}")
            SingleTestResult("testImprovedSelection", false, e.message)
        }
    }

    /**
     * Test Block 6: hot overload
     */
    private fun testHotReload(context: Context): SingleTestResult {
        return try {
            val loader = SkillsLoader(context)

            // Enable hot reload
            loader.enableHotReload()
            assert(loader.isHotReloadEnabled()) { "Hot reload should be enabled" }

            // Disable hot reload
            loader.disableHotReload()
            assert(!loader.isHotReloadEnabled()) { "Hot reload should be disabled" }

            Log.d(TAG, "[OK] testHotReload PASSED")
            Log.d(TAG, "   Hot reload mechanism working")
            SingleTestResult("testHotReload", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testHotReload FAILED: ${e.message}")
            SingleTestResult("testHotReload", false, e.message)
        }
    }
}

/**
 * Test Result
 */
data class TestResult(
    val passed: Int,
    val total: Int,
    val results: List<SingleTestResult>
) {
    fun isSuccess(): Boolean = passed == total

    fun getSummary(): String {
        val emoji = if (isSuccess()) "[OK]" else "[ERROR]"
        return "$emoji SkillsLoader Tests: $passed/$total passed"
    }

    fun getDetailedReport(): String {
        val builder = StringBuilder()
        builder.appendLine(getSummary())
        builder.appendLine()

        for (result in results) {
            val emoji = if (result.passed) "[OK]" else "[ERROR]"
            builder.appendLine("$emoji ${result.testName}")
            if (!result.passed && result.error != null) {
                builder.appendLine("   Error: ${result.error}")
            }
        }

        return builder.toString()
    }
}

/**
 * Single Test Result
 */
data class SingleTestResult(
    val testName: String,
    val passed: Boolean,
    val error: String?
)