package com.xiaomo.androidforclaw.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.xiaomo.androidforclaw.ui.activity.MainActivityCompose
import com.xiaomo.androidforclaw.R
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that the main app's PermissionIngress directly enters the observer Permission page,
 * instead of staying on the main app's intermediate page.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PermissionsEntryFlowUITest {

    private lateinit var device: UiDevice

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val permissionsLabel get() = res.getString(R.string.connect_permissions)

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Pre-accept legal consent so the app skips the first-run dialog
        context.getSharedPreferences("forclaw_legal", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("legal.accepted", true).apply()

        val intent = Intent(context, MainActivityCompose::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        device.waitForIdle()
        Thread.sleep(2000) // Wait for Compose to settle
    }

    @Test
    fun testPermissionEntry_redirectsDirectlyToObserverPermissionPage() {
        // Permission card is rendered via StatusCard in Compose, need to switch to Settings tab first
        val settingsTab = device.wait(Until.findObject(By.desc("Settings")), 5000)
            ?: device.wait(Until.findObject(By.textContains("Settings")), 2000)
            ?: device.wait(Until.findObject(By.descContains("Settings")), 2000)
        settingsTab?.click()
        device.waitForIdle()
        Thread.sleep(1000)

        val permissionCard = device.wait(Until.findObject(By.text(permissionsLabel)), 5000)
            ?: device.wait(Until.findObject(By.textContains(permissionsLabel)), 2000)
        assertNotNull("Permission card should exist on settings tab", permissionCard)
        permissionCard!!.click()

        // Observer page uniquely has: StoragePermission item / OneKeyAuthorize button
        val accessibilityLabel = res.getString(R.string.connect_accessibility)
        val grantLabel = res.getString(R.string.connect_go_grant)
        val viewLabel = res.getString(R.string.connect_view)
        val found = device.wait(Until.findObject(By.textContains(accessibilityLabel)), 5000)
            ?: device.wait(Until.findObject(By.text(grantLabel)), 2000)
            ?: device.wait(Until.findObject(By.text(viewLabel)), 2000)
        assertNotNull("Observer permission page should open directly", found)
    }
}