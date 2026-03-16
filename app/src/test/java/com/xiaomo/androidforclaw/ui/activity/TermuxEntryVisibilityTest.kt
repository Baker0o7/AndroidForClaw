package com.xiaomo.androidforclaw.ui.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxEntryVisibilityTest {
    @Test
    fun mainActivityComposeSource_containsTermuxEntry() {
        val source = File("src/main/java/com/xiaomo/androidforclaw/ui/activity/MainActivityCompose.kt").readText()
        assertTrue(source.contains("onNavigateToTermux"))
        assertTrue(source.contains("text = \"Termux 配置\""))
        assertTrue(source.contains("TermuxSetupActivity::class.java"))
    }
}
