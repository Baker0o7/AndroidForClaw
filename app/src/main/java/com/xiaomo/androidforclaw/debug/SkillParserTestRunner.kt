/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log

/**
 * SkillParser Test Runner
 * Used in android environment to test skill parser
 */
object SkillParserTestRunner {
    private const val TAG = "skillParserTest"

    /**
     * Run All Tests
     */
    fun runAllTests(context: Context): TestResult {
        val results = mutableListOf<SingleTestResult>()

        results.add(testSimpleSkill())
        results.add(testSkillWithRequires())
        results.add(testSkillWithoutMetadata())
        results.add(testMobileOperationsSkill(context))
        results.add(testInvalidFormat())

        val passed = results.count { it.passed }
        val total = results.size

        return TestResult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testSimpleSkill(): SingleTestResult {
        return try {
            val content = """
---
name: test-skill
description: A test skill
metadata:
  {
    "openclaw": {
      "always": true,
      "emoji": "[TEST]"
    }
  }
---

# Test skill
This is a test skill.
            """.trimIndent()

            val skill = SkillParser.parse(content)

            assert(skill.name == "test-skill") { "Name mismatch" }
            assert(skill.description == "A test skill") { "Description mismatch" }
            assert(skill.metadata.always) { "Always should be true" }
            assert(skill.metadata.emoji == "[TEST]") { "Emoji mismatch" }
            assert(skill.content.contains("This is a test skill")) { "Content mismatch" }

            Log.d(TAG, "[OK] testSimpleSkill PASSED")
            SingleTestResult("testSimpleSkill", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testSimpleSkill FAILED: ${e.message}")
            SingleTestResult("testSimpleSkill", false, e.message)
        }
    }

    private fun testSkillWithRequires(): SingleTestResult {
        return try {
            val content = """
---
name: advanced-skill
description: Skill with requirements
metadata:
  {
    "openclaw": {
      "always": false,
      "requires": {
        "bins": ["adb"],
        "env": ["ANDROID_HOME"],
        "config": ["api.key"]
      }
    }
  }
---

# Advanced
            """.trimIndent()

            val skill = SkillParser.parse(content)

            assert(skill.name == "advanced-skill") { "Name mismatch" }
            assert(!skill.metadata.always) { "Always should be false" }
            assert(skill.metadata.requires != null) { "Requires should not be null" }
            assert(skill.metadata.requires?.bins == listOf("adb")) { "Bins mismatch" }
            assert(skill.metadata.requires?.hasRequirements() == true) { "should have requirements" }

            Log.d(TAG, "[OK] testSkillWithRequires PASSED")
            SingleTestResult("testSkillWithRequires", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testSkillWithRequires FAILED: ${e.message}")
            SingleTestResult("testSkillWithRequires", false, e.message)
        }
    }

    private fun testSkillWithoutMetadata(): SingleTestResult {
        return try {
            val content = """
---
name: simple-skill
description: Simple skill
---

# Simple
            """.trimIndent()

            val skill = SkillParser.parse(content)

            assert(skill.name == "simple-skill") { "Name mismatch" }
            assert(!skill.metadata.always) { "Always should default to false" }
            assert(skill.metadata.emoji == null) { "Emoji should be null" }

            Log.d(TAG, "[OK] testSkillWithoutMetadata PASSED")
            SingleTestResult("testSkillWithoutMetadata", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testSkillWithoutMetadata FAILED: ${e.message}")
            SingleTestResult("testSkillWithoutMetadata", false, e.message)
        }
    }

    private fun testMobileOperationsSkill(context: Context): SingleTestResult {
        return try {
            // Try load actual mobile-operations skill
            val content = context.assets.open("skills/mobile-operations/SKILL.md")
                .bufferedReader().use { it.readText() }

            val skill = SkillParser.parse(content)

            assert(skill.name == "mobile-operations") { "Name should be mobile-operations" }
            assert(skill.metadata.always) { "should be always loaded" }
            assert(skill.metadata.emoji == "[APP]") { "Emoji should be [APP]" }
            assert(skill.content.contains("观察 → think → 行动 → 验证")) { "should contain core loop" }

            Log.d(TAG, "[OK] testMobileOperationsSkill PASSED")
            Log.d(TAG, "  - skill name: ${skill.name}")
            Log.d(TAG, "  - Token estimate: ${skill.estimateTokens()} tokens")
            SingleTestResult("testMobileOperationsSkill", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testMobileOperationsSkill FAILED: ${e.message}")
            SingleTestResult("testMobileOperationsSkill", false, e.message)
        }
    }

    private fun testInvalidFormat(): SingleTestResult {
        return try {
            val content = """
# Just Content
No frontmatter
            """.trimIndent()

            try {
                SkillParser.parse(content)
                // if no exception, test failed
                Log.e(TAG, "[ERROR] testInvalidFormat FAILED: should throw exception")
                SingleTestResult("testInvalidFormat", false, "should throw exception")
            } catch (e: IllegalArgumentException) {
                // Expected exception
                Log.d(TAG, "[OK] testInvalidFormat PASSED (correctly threw exception)")
                SingleTestResult("testInvalidFormat", true, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] testInvalidFormat FAILED: ${e.message}")
            SingleTestResult("testInvalidFormat", false, e.message)
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
        return "$emoji SkillParser Tests: $passed/$total passed"
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