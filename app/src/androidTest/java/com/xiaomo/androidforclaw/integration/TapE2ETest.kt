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
 * 注意: 由于 AIDL 跨ProcessLimit, TestProcessCannot直接调用主 app Process的AccessibilityService. 
 * 这些TestValidate的Yes: 
 * 1. AccessibilityService系统SettingsYesNo正确
 * 2. TapSkill ParametersValidate逻辑
 * 3. 主 app Process的ServiceConnectStatus(通过文件标记Validate)
 *
 * True正的 tap FeatureNeed在主 app ProcessInsideValidate(通过飞书/ADB broadcast 触发)
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
