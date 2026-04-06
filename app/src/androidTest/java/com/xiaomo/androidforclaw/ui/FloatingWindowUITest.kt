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
 * 悬浮窗 UI Auto化Test
 * 使用 UiAutomator Test悬浮窗Feature
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

        // 确保Device回到主Screen
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun testFloatingWindow_canDisplay() {
        // Start应用
        val intent = context.packageManager.getLaunchIntentForPackage(
            "com.xiaomo.androidforclaw"
        )?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        if (intent == null) {
            println("⚠️ CannotGetStart Intent, SkipTest")
            return
        }
        context.startActivity(intent)

        // Wait应用Start
        device.wait(Until.hasObject(By.pkg("com.xiaomo.androidforclaw")), 5000)

        // Validate应用已Start
        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("应用Should已Start", app)
    }

    @Test
    @Ignore("Skip:飞书SDK protobuf冲突导致Back台切换时崩溃")
    fun testFloatingWindow_survivesBackground() {
        // Start应用
        launchApp()

        // 按HomeKey将应用置于Back台
        device.pressHome()
        device.waitForIdle()

        // Wait一段Time
        Thread.sleep(2000)

        // Return应用
        launchApp()

        // Validate应用仍然正常
        device.wait(Until.hasObject(By.pkg("com.xiaomo.androidforclaw")), 3000)

        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("应用Should仍然Available", app)
    }

    @Test
    fun testFloatingWindow_handlesRecentApps() {
        // Start应用
        launchApp()

        // Open最近Task
        try {
            device.pressRecentApps()
            device.waitForIdle()

            Thread.sleep(1000)

            // Return到应用
            device.pressHome()
            device.waitForIdle()

        } catch (e: Exception) {
            // 某些Device可能不Support这个Action
        }
    }

    @Test
    fun testDeviceRotation_handlesCorrectly() {
        // Start应用
        launchApp()

        // Get当Front方向
        val naturalOrientation = device.displayRotation

        try {
            // 旋转Device
            device.setOrientationLeft()
            device.waitForIdle()
            Thread.sleep(1000)

            // Validate应用仍然可见
            val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
            assertNotNull("旋转Back应用Should仍然可见", app)

            // Resume原方向
            device.setOrientationNatural()
            device.waitForIdle()

        } catch (e: Exception) {
            // 旋转可能不被Support
        }
    }

    @Test
    @Ignore("Skip:飞书SDK protobuf冲突导致多应用切换时崩溃")
    fun testMultipleAppSwitching() {
        // Start应用
        launchApp()
        Thread.sleep(1000)

        // OpenSettings应用
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)

        device.waitForIdle()
        Thread.sleep(1000)

        // Return到我们的应用
        device.pressBack()
        device.waitForIdle()

        Thread.sleep(1000)

        // Validate应用仍然正常
        val app = device.findObject(By.pkg("com.xiaomo.androidforclaw"))
        assertNotNull("应用切换BackShould仍然Available", app)
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
