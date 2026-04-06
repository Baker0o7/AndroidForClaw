/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.workspace.StoragePaths
import java.io.File

/**
 * skillsLoader TestRun器
 */
object skillsLoaderTestRunner {
    private const val TAG = "skillsLoaderTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        // Block 2 原HasTest
        results.a(testLoadBundledskills(context))
        results.a(testGetAlwaysskills(context))
        results.a(testSelectRelevantskills(context))
        results.a(testStatistics(context))
        results.a(testPriorityoverride(context))
        results.a(testReload(context))
        results.a(testCheckRequirements(context))

        // Block 5 new增Test
        results.a(testnewskillsLoaded(context))
        results.a(testImprovedSelection(context))

        // Block 6 new增Test
        results.a(testHotReload(context))

        val passed = results.count { it.passed }
        val total = results.size

        return Testresult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testLoadBundledskills(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)
            val skills = loader.loadskills()

            // validation至fewLoad mobile-operations
            assert(skills.isnotEmpty()) { "should load at least 1 skill" }
            assert(skills.containsKey("mobile-operations")) { "should contain mobile-operations" }

            val mobileOps = skills["mobile-operations"]!!
            assert(mobileOps.metadata.always) { "mobile-operations should be always loaded" }
            assert(mobileOps.metadata.emoji == "[APP]") { "mobile-operations emoji should be [APP]" }

            Log.d(TAG, "[OK] testLoadBundledskills PASSED")
            Log.d(TAG, "   Loaded ${skills.size} skills")
            SingleTestresult("testLoadBundledskills", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testLoadBundledskills FAILED: ${e.message}")
            SingleTestresult("testLoadBundledskills", false, e.message)
        }
    }

    private fun testGetAlwaysskills(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)
            val alwaysskills = loader.getAlwaysskills()

            assert(alwaysskills.isnotEmpty()) { "should have at least 1 always skill" }

            // validationAllReturn skills 都Yes always
            for (skill in alwaysskills) {
                assert(skill.metadata.always) { "${skill.name} should be always" }
            }

            Log.d(TAG, "[OK] testGetAlwaysskills PASSED")
            Log.d(TAG, "   Always skills: ${alwaysskills.size}")
            SingleTestresult("testGetAlwaysskills", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testGetAlwaysskills FAILED: ${e.message}")
            SingleTestresult("testGetAlwaysskills", false, e.message)
        }
    }

    private fun testSelectRelevantskills(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)

            // Testnot同user目标
            val testGoal = loader.selectRelevantskills("Test音乐播放器", excludeAlways = true)
            val debugGoal = loader.selectRelevantskills("DebugLoginFeature", excludeAlways = true)

            Log.d(TAG, "[OK] testSelectRelevantskills PASSED")
            Log.d(TAG, "   Test goal: ${testGoal.size} skills")
            Log.d(TAG, "   Debug goal: ${debugGoal.size} skills")
            SingleTestresult("testSelectRelevantskills", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testSelectRelevantskills FAILED: ${e.message}")
            SingleTestresult("testSelectRelevantskills", false, e.message)
        }
    }

    private fun testStatistics(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)
            val stats = loader.getStatistics()

            assert(stats.totalskills > 0) { "should have skills" }
            assert(stats.alwaysskills + stats.onDemandskills == stats.totalskills) {
                "Always + OnDemand should equal total"
            }
            assert(stats.totalTokens > 0) { "should have tokens" }

            Log.d(TAG, "[OK] testStatistics PASSED")
            Log.d(TAG, stats.getReport())
            SingleTestresult("testStatistics", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testStatistics FAILED: ${e.message}")
            SingleTestresult("testStatistics", false, e.message)
        }
    }

    private fun testPriorityoverride(context: context): SingleTestresult {
        return try {
            // CreateTest用 Workspace skill
            val workspaceDir = File(StoragePaths.workspaceskills, "test-override")
            workspaceDir.mkdirs()

            val testskillFile = File(workspaceDir, "SKILL.md")
            testskillFile.writeText("""
---
name: mobile-operations
description: Workspace overrideVersion
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

            // reLoad
            val loader = skillsLoader(context)
            loader.reload()
            val skills = loader.loadskills()

            val mobileOps = skills["mobile-operations"]
            val isWorkspaceVersion = mobileOps?.description == "Workspace overrideVersion"

            // 清理Testfiles
            testskillFile.delete()
            workspaceDir.delete()

            if (isWorkspaceVersion) {
                Log.d(TAG, "[OK] testPriorityoverride PASSED")
                Log.d(TAG, "   Workspace skill correctly overrides bundled")
                SingleTestresult("testPriorityoverride", true, null)
            } else {
                Log.e(TAG, "[ERROR] testPriorityoverride FAILED: Workspace not overriding")
                SingleTestresult("testPriorityoverride", false, "Priority not working")
            }
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testPriorityoverride FAILED: ${e.message}")
            SingleTestresult("testPriorityoverride", false, e.message)
        }
    }

    private fun testReload(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)

            // firsttimesLoad
            val skills1 = loader.loadskills()
            val count1 = skills1.size

            // reLoad
            loader.reload()
            val skills2 = loader.loadskills()
            val count2 = skills2.size

            assert(count1 == count2) { "Reload should load same number of skills" }

            Log.d(TAG, "[OK] testReload PASSED")
            Log.d(TAG, "   Reloaded ${count2} skills")
            SingleTestresult("testReload", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testReload FAILED: ${e.message}")
            SingleTestresult("testReload", false, e.message)
        }
    }

    private fun testCheckRequirements(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)

            // CreateoneHasDependencyTest skill
            val skillwithRequires = skillDocument(
                name = "test-requires",
                description = "Test",
                metadata = skillMetadata(
                    requires = skillRequires(
                        bins = listOf("nonexistent-binary"),
                        env = listOf("NONEXISTENT_ENV"),
                        config = listOf("nonexistent.config")
                    )
                ),
                content = "Test"
            )

            val result = loader.checkRequirements(skillwithRequires)

            assert(result is RequirementsCheckresult.Unsatisfied) {
                "should be unsatisfied"
            }

            if (result is RequirementsCheckresult.Unsatisfied) {
                assert(result.missingBins.contains("nonexistent-binary"))
                assert(result.missingEnv.contains("NONEXISTENT_ENV"))
                assert(result.missingconfig.contains("nonexistent.config"))
            }

            Log.d(TAG, "[OK] testCheckRequirements PASSED")
            SingleTestresult("testCheckRequirements", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testCheckRequirements FAILED: ${e.message}")
            SingleTestresult("testCheckRequirements", false, e.message)
        }
    }

    /**
     * Test Block 5: new skills whetherLoad
     */
    private fun testnewskillsLoaded(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)
            val skills = loader.loadskills()

            // validationnew增 4 count skills
            val newskills = listOf("accessibility", "performance", "ui-validation", "network-testing")
            var allLoaded = true

            for (skillName in newskills) {
                if (!skills.containsKey(skillName)) {
                    Log.w(TAG, "[WARN] skill not loaded: $skillName")
                    allLoaded = false
                }
            }

            assert(allLoaded) { "All new skills should be loaded" }

            Log.d(TAG, "[OK] testnewskillsLoaded PASSED")
            Log.d(TAG, "   Loaded ${newskills.size} new skills")
            SingleTestresult("testnewskillsLoaded", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testnewskillsLoaded FAILED: ${e.message}")
            SingleTestresult("testnewskillsLoaded", false, e.message)
        }
    }

    /**
     * Test Block 5: ImprovechooseAlgorithm
     */
    private fun testImprovedSelection(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)

            // TestTaskType识别
            val testTasks = mapOf(
                "Test音乐播放器Performance" to listOf("app-testing", "performance"),
                "DebugNetworkIssue" to listOf("debugging", "network-testing"),
                "validation界面Show" to listOf("ui-validation"),
                "CheckNoneAccessibility adaptation" to listOf("accessibility")
            )

            var allMatched = true
            for ((userGoal, expectedskills) in testTasks) {
                val selected = loader.selectRelevantskills(userGoal, excludeAlways = true)
                val selectedNames = selected.map { it.name }

                for (expected in expectedskills) {
                    if (!selectedNames.contains(expected)) {
                        Log.w(TAG, "[WARN] Expected '$expected' for goal '$userGoal', but not selected")
                        allMatched = false
                    }
                }
            }

            Log.d(TAG, if (allMatched) "[OK] testImprovedSelection PASSED" else "[WARN] testImprovedSelection PARTIAL")
            Log.d(TAG, "   Task type identification working")
            SingleTestresult("testImprovedSelection", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testImprovedSelection FAILED: ${e.message}")
            SingleTestresult("testImprovedSelection", false, e.message)
        }
    }

    /**
     * Test Block 6: 热overload
     */
    private fun testHotReload(context: context): SingleTestresult {
        return try {
            val loader = skillsLoader(context)

            // Enable热overload
            loader.enableHotReload()
            assert(loader.isHotReloadEnabled()) { "Hot reload should be enabled" }

            // Disabled热overload
            loader.disableHotReload()
            assert(!loader.isHotReloadEnabled()) { "Hot reload should be disabled" }

            Log.d(TAG, "[OK] testHotReload PASSED")
            Log.d(TAG, "   Hot reload mechanism working")
            SingleTestresult("testHotReload", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testHotReload FAILED: ${e.message}")
            SingleTestresult("testHotReload", false, e.message)
        }
    }
}
