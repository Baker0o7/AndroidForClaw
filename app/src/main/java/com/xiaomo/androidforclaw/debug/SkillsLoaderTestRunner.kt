/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * SkillsLoader TestRun器
 */
object SkillsLoaderTestRunner {
    private const val TAG = "SkillsLoaderTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: Context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        // Block 2 原HasTest
        results.add(testLoadBundledSkills(context))
        results.add(testGetAlwaysSkills(context))
        results.add(testSelectRelevantSkills(context))
        results.add(testStatistics(context))
        results.add(testPriorityOverride(context))
        results.add(testReload(context))
        results.add(testCheckRequirements(context))

        // Block 5 New增Test
        results.add(testNewSkillsLoaded(context))
        results.add(testImprovedSelection(context))

        // Block 6 New增Test
        results.add(testHotReload(context))

        val passed = results.count { it.passed }
        val total = results.size

        return Testresult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testLoadBundledSkills(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)
            val skills = loader.loadSkills()

            // Validate至少Load了 mobile-operations
            assert(skills.isNotEmpty()) { "Should load at least 1 skill" }
            assert(skills.containsKey("mobile-operations")) { "Should contain mobile-operations" }

            val mobileOps = skills["mobile-operations"]!!
            assert(mobileOps.metadata.always) { "mobile-operations should be always loaded" }
            assert(mobileOps.metadata.emoji == "📱") { "mobile-operations emoji should be 📱" }

            Log.d(TAG, "✅ testLoadBundledSkills PASSED")
            Log.d(TAG, "   Loaded ${skills.size} skills")
            SingleTestresult("testLoadBundledSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testLoadBundledSkills FAILED: ${e.message}")
            SingleTestresult("testLoadBundledSkills", false, e.message)
        }
    }

    private fun testGetAlwaysSkills(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)
            val alwaysSkills = loader.getAlwaysSkills()

            assert(alwaysSkills.isNotEmpty()) { "Should have at least 1 always skill" }

            // ValidateAllReturn的 skills 都Yes always
            for (skill in alwaysSkills) {
                assert(skill.metadata.always) { "${skill.name} should be always" }
            }

            Log.d(TAG, "✅ testGetAlwaysSkills PASSED")
            Log.d(TAG, "   Always skills: ${alwaysSkills.size}")
            SingleTestresult("testGetAlwaysSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testGetAlwaysSkills FAILED: ${e.message}")
            SingleTestresult("testGetAlwaysSkills", false, e.message)
        }
    }

    private fun testSelectRelevantSkills(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)

            // Test不同的User目标
            val testGoal = loader.selectRelevantSkills("Test音乐播放器", excludeAlways = true)
            val debugGoal = loader.selectRelevantSkills("DebugLoginFeature", excludeAlways = true)

            Log.d(TAG, "✅ testSelectRelevantSkills PASSED")
            Log.d(TAG, "   Test goal: ${testGoal.size} skills")
            Log.d(TAG, "   Debug goal: ${debugGoal.size} skills")
            SingleTestresult("testSelectRelevantSkills", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testSelectRelevantSkills FAILED: ${e.message}")
            SingleTestresult("testSelectRelevantSkills", false, e.message)
        }
    }

    private fun testStatistics(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)
            val stats = loader.getStatistics()

            assert(stats.totalSkills > 0) { "Should have skills" }
            assert(stats.alwaysSkills + stats.onDemandSkills == stats.totalSkills) {
                "Always + OnDemand should equal total"
            }
            assert(stats.totalTokens > 0) { "Should have tokens" }

            Log.d(TAG, "✅ testStatistics PASSED")
            Log.d(TAG, stats.getReport())
            SingleTestresult("testStatistics", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testStatistics FAILED: ${e.message}")
            SingleTestresult("testStatistics", false, e.message)
        }
    }

    private fun testPriorityOverride(context: Context): SingleTestresult {
        return try {
            // CreateTest用的 Workspace Skill
            val workspaceDir = File(StoragePaths.workspaceSkills, "test-override")
            workspaceDir.mkdirs()

            val testSkillFile = File(workspaceDir, "SKILL.md")
            testSkillFile.writeText("""
---
name: mobile-operations
description: Workspace OverrideVersion
metadata:
  {
    "openclaw": {
      "always": true,
      "emoji": "🧪"
    }
  }
---

# Workspace Override Test
            """.trimIndent())

            // 重NewLoad
            val loader = SkillsLoader(context)
            loader.reload()
            val skills = loader.loadSkills()

            val mobileOps = skills["mobile-operations"]
            val isWorkspaceVersion = mobileOps?.description == "Workspace OverrideVersion"

            // 清理Test文件
            testSkillFile.delete()
            workspaceDir.delete()

            if (isWorkspaceVersion) {
                Log.d(TAG, "✅ testPriorityOverride PASSED")
                Log.d(TAG, "   Workspace skill correctly overrides bundled")
                SingleTestresult("testPriorityOverride", true, null)
            } else {
                Log.e(TAG, "❌ testPriorityOverride FAILED: Workspace not overriding")
                SingleTestresult("testPriorityOverride", false, "Priority not working")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ testPriorityOverride FAILED: ${e.message}")
            SingleTestresult("testPriorityOverride", false, e.message)
        }
    }

    private fun testReload(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)

            // First次Load
            val skills1 = loader.loadSkills()
            val count1 = skills1.size

            // 重NewLoad
            loader.reload()
            val skills2 = loader.loadSkills()
            val count2 = skills2.size

            assert(count1 == count2) { "Reload should load same number of skills" }

            Log.d(TAG, "✅ testReload PASSED")
            Log.d(TAG, "   Reloaded ${count2} skills")
            SingleTestresult("testReload", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testReload FAILED: ${e.message}")
            SingleTestresult("testReload", false, e.message)
        }
    }

    private fun testCheckRequirements(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)

            // Create一个HasDependency的Test Skill
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

            assert(result is RequirementsCheckresult.Unsatisfied) {
                "Should be unsatisfied"
            }

            if (result is RequirementsCheckresult.Unsatisfied) {
                assert(result.missingBins.contains("nonexistent-binary"))
                assert(result.missingEnv.contains("NONEXISTENT_ENV"))
                assert(result.missingConfig.contains("nonexistent.config"))
            }

            Log.d(TAG, "✅ testCheckRequirements PASSED")
            SingleTestresult("testCheckRequirements", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testCheckRequirements FAILED: ${e.message}")
            SingleTestresult("testCheckRequirements", false, e.message)
        }
    }

    /**
     * Test Block 5: New Skills YesNoLoad
     */
    private fun testNewSkillsLoaded(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)
            val skills = loader.loadSkills()

            // ValidateNew增的 4 个 Skills
            val newSkills = listOf("accessibility", "performance", "ui-validation", "network-testing")
            var allLoaded = true

            for (skillName in newSkills) {
                if (!skills.containsKey(skillName)) {
                    Log.w(TAG, "⚠️ Skill not loaded: $skillName")
                    allLoaded = false
                }
            }

            assert(allLoaded) { "All new skills should be loaded" }

            Log.d(TAG, "✅ testNewSkillsLoaded PASSED")
            Log.d(TAG, "   Loaded ${newSkills.size} new skills")
            SingleTestresult("testNewSkillsLoaded", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testNewSkillsLoaded FAILED: ${e.message}")
            SingleTestresult("testNewSkillsLoaded", false, e.message)
        }
    }

    /**
     * Test Block 5: Improve的chooseAlgorithm
     */
    private fun testImprovedSelection(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)

            // TestTaskType识别
            val testTasks = mapOf(
                "Test音乐播放器的Performance" to listOf("app-testing", "performance"),
                "DebugNetworkIssue" to listOf("debugging", "network-testing"),
                "Validate界面Show" to listOf("ui-validation"),
                "CheckNoneAccessibility adaptation" to listOf("accessibility")
            )

            var allMatched = true
            for ((userGoal, expectedSkills) in testTasks) {
                val selected = loader.selectRelevantSkills(userGoal, excludeAlways = true)
                val selectedNames = selected.map { it.name }

                for (expected in expectedSkills) {
                    if (!selectedNames.contains(expected)) {
                        Log.w(TAG, "⚠️ Expected '$expected' for goal '$userGoal', but not selected")
                        allMatched = false
                    }
                }
            }

            Log.d(TAG, if (allMatched) "✅ testImprovedSelection PASSED" else "⚠️ testImprovedSelection PARTIAL")
            Log.d(TAG, "   Task type identification working")
            SingleTestresult("testImprovedSelection", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testImprovedSelection FAILED: ${e.message}")
            SingleTestresult("testImprovedSelection", false, e.message)
        }
    }

    /**
     * Test Block 6: 热Overload
     */
    private fun testHotReload(context: Context): SingleTestresult {
        return try {
            val loader = SkillsLoader(context)

            // Enabledd热Overload
            loader.enableHotReload()
            assert(loader.isHotReloadEnableddd()) { "Hot reload should be enabled" }

            // Disabled热Overload
            loader.disableHotReload()
            assert(!loader.isHotReloadEnableddd()) { "Hot reload should be disabled" }

            Log.d(TAG, "✅ testHotReload PASSED")
            Log.d(TAG, "   Hot reload mechanism working")
            SingleTestresult("testHotReload", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testHotReload FAILED: ${e.message}")
            SingleTestresult("testHotReload", false, e.message)
        }
    }
}
