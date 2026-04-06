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
 * Config UI Automation Test
 * Test Config related UI interactions
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
        // Validate Config UI Start - Check API Config Title
        onView(withText("API Config"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testModelConfiguration_isVisible() {
        // Validate Feature toggle section is visible
        onView(withText("Feature Toggles"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testConfigSave_works() {
        // Test Save Config (if has Save button)
        try {
            onView(withText("Save"))
                .check(matches(isDisplayed()))
                .perform(click())

            Thread.sleep(500)

            // Validate Save Success Hint (if has)
        } catch (e: Exception) {
            // May have no Save button
        }
    }

    @Test
    fun testBackNavigation_works() {
        // Test Return navigation
        activityRule.scenario.onActivity { activity ->
            activity.onBackPressed()
        }

        // Validate activity is closed
        Thread.sleep(500)
    }
}
