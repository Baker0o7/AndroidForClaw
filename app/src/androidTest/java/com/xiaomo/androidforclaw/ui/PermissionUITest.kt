package com.xiaomo.androidforclaw.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaomo.androidforclaw.core.MyApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * PermissionTest
 * Test AndroidForClaw 的PermissionManage
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PermissionUITest {

    @Before
    fun grantStoragePermission() {
        // API 30+ needs MANAGE_EXTERNAL_STORAGE for /sdcard/ access
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            .close()
    }

    /**
     * Test1: 应用HasStoragePermission
     * API 30+ uses MANAGE_EXTERNAL_STORAGE (granted via appops in @Before)
     * API 29- uses WRITE_EXTERNAL_STORAGE
     */
    @Test
    fun testStoragePermission_granted() {
        val context = ApplicationProvider.getApplicationContext<MyApplication>()

        val hasPermission = if (android.os.Build.VERSION.SDK_INT >= 30) {
            // On API 30+, MANAGE_EXTERNAL_STORAGE is granted via appops
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        assertTrue("ShouldHasStoragePermission", hasPermission)
    }

    /**
     * Test6: CanRead assets 中的 skills
     */
    @Test
    fun testAssetsSkills_accessible() {
        val context = ApplicationProvider.getApplicationContext<MyApplication>()

        try {
            val skillsDir = context.assets.list("skills")

            assertNotNull("Skills目录ShouldExists", skillsDir)
            assertTrue("ShouldHasbundled skills", skillsDir!!.isNotEmpty())

        } catch (e: Exception) {
            fail("Cannot访问assets中的skills: ${e.message}")
        }
    }

    /**
     * Test7: 应用Package name正确
     */
    @Test
    fun testPackageName_correct() {
        val context = ApplicationProvider.getApplicationContext<MyApplication>()

        // Debug 和 Release 统一使用相同Package name
        assertEquals(
            "Package nameShould正确",
            "com.xiaomo.androidforclaw",
            context.packageName
        )
    }

    /**
     * Test8: 应用Version可Get
     */
    @Test
    fun testAppVersion_retrievable() {
        val context = ApplicationProvider.getApplicationContext<MyApplication>()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        assertNotNull("Version name不应为Null", packageInfo.versionName)
        assertTrue("Version numberShouldGreater than0", packageInfo.versionCode > 0)
    }

    /**
     * Test9: MMKV Initialize
     */
    @Test
    fun testMMKV_initialized() {
        val context = ApplicationProvider.getApplicationContext<MyApplication>()

        try {
            val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()

            assertNotNull("MMKVShouldInitialize", mmkv)

            // TestWriteRead
            mmkv.putString("test_key", "test_value")
            assertEquals("Should能Read", "test_value", mmkv.getString("test_key", ""))

            // 清理
            mmkv.remove("test_key")

        } catch (e: Exception) {
            fail("MMKV未正确Initialize: ${e.message}")
        }
    }

    /**
     * Test10: ExternalStorageAvailable
     */
    @Test
    fun testExternalStorage_available() {
        val state = android.os.Environment.getExternalStorageState()

        assertEquals(
            "ExternalStorageShouldAvailable",
            android.os.Environment.MEDIA_MOUNTED,
            state
        )

        val externalDir = android.os.Environment.getExternalStorageDirectory()
        assertTrue("ExternalStorage目录ShouldExists", externalDir.exists())
        assertTrue("Should可读", externalDir.canRead())
    }
}
