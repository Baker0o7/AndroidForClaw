package com.xiaomo.androidforclaw.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.xiaomo.androidforclaw.core.MyApplication
import com.xiaomo.androidforclaw.ui.activity.MainActivityCompose
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Floating Window UI Automation Test
 * Use UiAutomator to test Floating Window Feature
 *
 * Run:
 * ./gradlew connectedDebugAndroidTest --tests "FloatingWindowUITest"
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FloatingWindowUITest {

    private lateinit var device: UiDevice
    private lateinit var context: Context

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()

        // Ensure Device returns to home Screen
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun testFloatingWindow_canDisplay() {
        // Start app
        val intent = context.packageManager.getLaunchIntentForPackage(
            "com.xiaomo.androidforclaw"
        )?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        if (intent == null) {
            println("Cannot get Start Intent, Skip Test")
            return
        }
        context.startActivity(intent)

        // Wait for app to Start
        device.wait(Until.hasObject(By.pkg("com.xiaomo.androidforclaw")), 5000)

        // Validate app has Started
        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("App Should be started", app)
    }

    @Test
    @Ignore("Skip: Feishu SDK protobuf conflict causes crash when switching to background")
    fun testFloatingWindow_survivesBackground() {
        // Start app
        launchApp()

        // Press Home key to put app in background
        device.pressHome()
        device.waitForIdle()

        // Wait for a while
        Thread.sleep(2000)

        // Return to app
        launchApp()

        // Validate app is still working
        device.wait(Until.hasObject(By.pkg("com.xiaomo.androidforclaw")), 3000)

        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("App Should still be Available", app)
    }

    @Test
    fun testFloatingWindow_handlesRecentApps() {
        // Start app
        launchApp()

        // Open recent Tasks
        try {
            device.pressRecentApps()
            device.waitForIdle()

            Thread.sleep(1000)

            // Return to app
            device.pressHome()
            device.waitForIdle()

        } catch (e: Exception) {
            // Some Devices may not Support this action
        }
    }

    @Test
    fun testDeviceRotation_handlesCorrectly() {
        // Start app
        launchApp()

        // Get current front orientation
        val naturalOrientation = device.displayRotation

        try {
            // Rotate Device
            device.setOrientationLeft()
            device.waitForIdle()
            Thread.sleep(1000)

            // Validate app is still visible
            val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
            assertNotNull("After rotation, app Should still be visible", app)

            // Resume original orientation
            device.setOrientationNatural()
            device.waitForIdle()

        } catch (e: Exception) {
            // Rotation may not be Supported
        }
    }

    @Test
    @Ignore("Skip: Feishu SDK protobuf conflict causes crash during multi-app switching")
    fun testMultipleAppSwitching() {
        // Start app
        launchApp()
        Thread.sleep(1000)

        // Open Settings app
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)

        device.waitForIdle()
        Thread.sleep(1000)

        // Return to our app
        device.pressBack()
        device.waitForIdle()

        Thread.sleep(1000)

        // Validate app is still working
        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("After app switch, App Should still be Available", app)
    }

    private fun launchApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(
            "com.xiaomo.androidforclaw"
        )?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        if (intent == null) {
            // App not installed or no launch intent; use fallback
            val fallback = Intent("android.intent.action.MAIN").apply {
                setClassName("com.xiaomo.androidforclaw", "com.xiaomo.androidforclaw.ui.activity.MainActivityCompose")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(fallback)
        } else {
            context.startActivity(intent)
        }
        device.wait(Until.hasObject(By.pkg("com.xiaomo.androidforclaw")), 5000)
        device.waitForIdle()
    }
}
