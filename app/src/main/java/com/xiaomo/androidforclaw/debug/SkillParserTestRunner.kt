/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.agent.skills

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log

/**
 * SkillParser TestRun器
 * 用于在 Android Environment中Test SkillParser
 */
object SkillParserTestRunner {
    private const val TAG = "SkillParserTest"

    /**
     * RunAllTest
     */
    fun runAllTests(context: Context): Testresult {
        val results = mutableListOf<SingleTestresult>()

        results.add(testSimpleSkill())
        results.add(testSkillWithRequires())
        results.add(testSkillWithoutMetadata())
        results.add(testMobileOperationsSkill(context))
        results.add(testInvalidFormat())

        val passed = results.count { it.passed }
        val total = results.size

        return Testresult(
            passed = passed,
            total = total,
            results = results
        )
    }

    private fun testSimpleSkill(): SingleTestresult {
        return try {
            val content = """
---
name: test-skill
description: A test skill
metadata:
  {
    "openclaw": {
      "always": true,
      "emoji": "🧪"
    }
  }
---

# Test Skill
This is a test skill.
            """.trimIndent()

            val skill = SkillParser.parse(content)

            assert(skill.name == "test-skill") { "Name mismatch" }
            assert(skill.description == "A test skill") { "Description mismatch" }
            assert(skill.metadata.always) { "Always should be true" }
            assert(skill.metadata.emoji == "🧪") { "Emoji mismatch" }
            assert(skill.content.contains("This is a test skill")) { "Content mismatch" }

            Log.d(TAG, "✅ testSimpleSkill PASSED")
            SingleTestresult("testSimpleSkill", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testSimpleSkill FAILED: ${e.message}")
            SingleTestresult("testSimpleSkill", false, e.message)
        }
    }

    private fun testSkillWithRequires(): SingleTestresult {
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
            assert(skill.metadata.requires?.hasRequirements() == true) { "Should have requirements" }

            Log.d(TAG, "✅ testSkillWithRequires PASSED")
            SingleTestresult("testSkillWithRequires", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testSkillWithRequires FAILED: ${e.message}")
            SingleTestresult("testSkillWithRequires", false, e.message)
        }
    }

    private fun testSkillWithoutMetadata(): SingleTestresult {
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

            Log.d(TAG, "✅ testSkillWithoutMetadata PASSED")
            SingleTestresult("testSkillWithoutMetadata", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testSkillWithoutMetadata FAILED: ${e.message}")
            SingleTestresult("testSkillWithoutMetadata", false, e.message)
        }
    }

    private fun testMobileOperationsSkill(context: Context): SingleTestresult {
        return try {
            // TryLoad实际的 mobile-operations Skill
            val content = context.assets.open("skills/mobile-operations/SKILL.md")
                .bufferedReader().use { it.readText() }

            val skill = SkillParser.parse(content)

            assert(skill.name == "mobile-operations") { "Name should be mobile-operations" }
            assert(skill.metadata.always) { "Should be always loaded" }
            assert(skill.metadata.emoji == "📱") { "Emoji should be 📱" }
            assert(skill.content.contains("观察 → think → Row动 → Validate")) { "Should contain core loop" }

            Log.d(TAG, "✅ testMobileOperationsSkill PASSED")
            Log.d(TAG, "  - Skill name: ${skill.name}")
            Log.d(TAG, "  - Token estimate: ${skill.estimateTokens()} tokens")
            SingleTestresult("testMobileOperationsSkill", true, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ testMobileOperationsSkill FAILED: ${e.message}")
            SingleTestresult("testMobileOperationsSkill", false, e.message)
        }
    }

    private fun testInvalidFormat(): SingleTestresult {
        return try {
            val content = """
# Just Content
No frontmatter
            """.trimIndent()

            try {
                SkillParser.parse(content)
                // if没抛Exception, TestFailed
                Log.e(TAG, "❌ testInvalidFormat FAILED: Should throw exception")
                SingleTestresult("testInvalidFormat", false, "Should throw exception")
            } catch (e: IllegalArgumentException) {
                // 预期的Exception
                Log.d(TAG, "✅ testInvalidFormat PASSED (correctly threw exception)")
                SingleTestresult("testInvalidFormat", true, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ testInvalidFormat FAILED: ${e.message}")
            SingleTestresult("testInvalidFormat", false, e.message)
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
        val emoji = if (isSuccess()) "✅" else "❌"
        return "$emoji SkillParser Tests: $passed/$total passed"
    }

    fun getDetailedReport(): String {
        val builder = StringBuilder()
        builder.appendLine(getSummary())
        builder.appendLine()

        for (result in results) {
            val emoji = if (result.passed) "✅" else "❌"
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
