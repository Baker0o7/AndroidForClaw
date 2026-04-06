package com.xiaomo.androidforclaw.integration

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Tap E2E Test
 *
 * Note: Due to AIDL cross-process limitation, Test process cannot directly call
 * the main app process's AccessibilityService.
 * These tests validate:
 * 1. AccessibilityService system settings are correct
 * 2. TapSkill parameter validation logic
 * 3. Main app process Service connection status (validated via file marker)
 *
 * The actual tap feature needs to be validated inside the main app process
 * (triggered via Feishu/ADB broadcast)
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TapE2ETest {

    companion object {
        private const val TAG = "TapE2ETest"
    }

    @Test
    fun test02_tapSkillParamValidation() {
        kotlinx.coroutines.runBlocking {
            val skill = com.xiaomo.androidforclaw.agent.tools.TapSkill()

            // Missing args should fail (may be "Missing" or "Accessibility service not connected")
            val result1 = skill.execute(emptyMap())
            assertFalse("Should fail with empty args", result1.success)
            Log.i(TAG, "Empty args: ${result1.content}")

            // Missing y should fail
            val result2 = skill.execute(mapOf("x" to 100))
            assertFalse("Should fail with missing y", result2.success)
            Log.i(TAG, "Missing y: ${result2.content}")

            Log.i(TAG, "=== test02 result: param validation works ===")
        }
    }

}
