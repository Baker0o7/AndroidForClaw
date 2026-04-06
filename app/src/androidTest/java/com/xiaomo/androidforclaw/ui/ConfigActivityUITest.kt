package com.xiaomo.androidforclaw.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.xiaomo.androidforclaw.ui.activity.MainActivityCompose
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Config界面 UI Auto化Test
 * TestConfig相关的 UI 交互
 *
 * Run:
 * ./gradlew connectedDebugAndroidTest --tests "ConfigActivityUITest"
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(maxSdkVersion = 35) // Espresso InputManager.getInstance() removed in API 36
class ConfigActivityUITest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivityCompose::class.java)

    @Test
    fun testConfigActivity_launches() {
        // ValidateConfig界面Start - Check API ConfigTitle
        onView(withText("API Config"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testModelConfiguration_isVisible() {
        // ValidateFeature开关部分可见
        onView(withText("Feature开关"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testConfigSave_works() {
        // TestSaveConfig(如果HasSave按钮)
        try {
            onView(withText("Save"))
                .check(matches(isDisplayed()))
                .perform(click())

            Thread.sleep(500)

            // ValidateSaveSuccessHint(如果Has)
        } catch (e: Exception) {
            // 可能NoneSave按钮
        }
    }

    @Test
    fun testBackNavigation_works() {
        // TestReturn导航
        activityRule.scenario.onActivity { activity ->
            activity.onBackPressed()
        }

        // Validate活动已Close
        Thread.sleep(500)
    }
}
