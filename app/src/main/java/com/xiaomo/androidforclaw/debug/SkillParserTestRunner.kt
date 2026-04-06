/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.context
import com.xiaomo.androidforclaw.logging.Log

/**
 * skillParser TestRun器
 * 用于in android Environment中Test skillParser
 */
object skillParserTestRunner {
    private const val TAG = "skillParserTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        results.a(testSimpleskill())
        results.a(testskillwithRequires())
        results.a(testskillwithoutMetadata())
        results.a(testMobileOperationsskill(context))
        results.a(testInvalidformat())

        val passed = results.count { it.passed }
        val total = results.size

        return Testresult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testSimpleskill(): SingleTestresult {
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

            val skill = skillParser.parse(content)

            assert(skill.name == "test-skill") { "Name mismatch" }
            assert(skill.description == "A test skill") { "Description mismatch" }
            assert(skill.metadata.always) { "Always should be true" }
            assert(skill.metadata.emoji == "[TEST]") { "Emoji mismatch" }
            assert(skill.content.contains("This is a test skill")) { "Content mismatch" }

            Log.d(TAG, "[OK] testSimpleskill PASSED")
            SingleTestresult("testSimpleskill", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testSimpleskill FAILED: ${e.message}")
            SingleTestresult("testSimpleskill", false, e.message)
        }
    }

    private fun testskillwithRequires(): SingleTestresult {
        return try {
            val content = """
---
name: advanced-skill
description: skill with requirements
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

            val skill = skillParser.parse(content)

            assert(skill.name == "advanced-skill") { "Name mismatch" }
            assert(!skill.metadata.always) { "Always should be false" }
            assert(skill.metadata.requires != null) { "Requires should not be null" }
            assert(skill.metadata.requires?.bins == listOf("adb")) { "Bins mismatch" }
            assert(skill.metadata.requires?.hasRequirements() == true) { "should have requirements" }

            Log.d(TAG, "[OK] testskillwithRequires PASSED")
            SingleTestresult("testskillwithRequires", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testskillwithRequires FAILED: ${e.message}")
            SingleTestresult("testskillwithRequires", false, e.message)
        }
    }

    private fun testskillwithoutMetadata(): SingleTestresult {
        return try {
            val content = """
---
name: simple-skill
description: Simple skill
---

# Simple
            """.trimIndent()

            val skill = skillParser.parse(content)

            assert(skill.name == "simple-skill") { "Name mismatch" }
            assert(!skill.metadata.always) { "Always should default to false" }
            assert(skill.metadata.emoji == null) { "Emoji should be null" }

            Log.d(TAG, "[OK] testskillwithoutMetadata PASSED")
            SingleTestresult("testskillwithoutMetadata", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testskillwithoutMetadata FAILED: ${e.message}")
            SingleTestresult("testskillwithoutMetadata", false, e.message)
        }
    }

    private fun testMobileOperationsskill(context: context): SingleTestresult {
        return try {
            // TryLoad实际 mobile-operations skill
            val content = context.assets.open("skills/mobile-operations/SKILL.md")
                .bufferedReader().use { it.readText() }

            val skill = skillParser.parse(content)

            assert(skill.name == "mobile-operations") { "Name should be mobile-operations" }
            assert(skill.metadata.always) { "should be always loaded" }
            assert(skill.metadata.emoji == "[APP]") { "Emoji should be [APP]" }
            assert(skill.content.contains("观察 → think → Row动 → validation")) { "should contain core loop" }

            Log.d(TAG, "[OK] testMobileOperationsskill PASSED")
            Log.d(TAG, "  - skill name: ${skill.name}")
            Log.d(TAG, "  - Token estimate: ${skill.estimateTokens()} tokens")
            SingleTestresult("testMobileOperationsskill", true, null)
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testMobileOperationsskill FAILED: ${e.message}")
            SingleTestresult("testMobileOperationsskill", false, e.message)
        }
    }

    private fun testInvalidformat(): SingleTestresult {
        return try {
            val content = """
# Just Content
No frontmatter
            """.trimIndent()

            try {
                skillParser.parse(content)
                // if没抛exception, TestFailed
                Log.e(TAG, "[ERROR] testInvalidformat FAILED: should throw exception")
                SingleTestresult("testInvalidformat", false, "should throw exception")
            } catch (e: IllegalArgumentexception) {
                // 预期exception
                Log.d(TAG, "[OK] testInvalidformat PASSED (correctly threw exception)")
                SingleTestresult("testInvalidformat", true, null)
            }
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] testInvalidformat FAILED: ${e.message}")
            SingleTestresult("testInvalidformat", false, e.message)
        }
    }
}

/**
 * Testresult
 */
data class Testresult(
    val passed: Int,
    val total: Int,
    val results: List<SingleTestresult>
) {
    fun isSuccess(): Boolean = passed == total

    fun getSummary(): String {
        val emoji = if (isSuccess()) "[OK]" else "[ERROR]"
        return "$emoji skillParser Tests: $passed/$total passed"
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
 * SingleTestresult
 */
data class SingleTestresult(
    val testName: String,
    val passed: Boolean,
    val error: String?
)
