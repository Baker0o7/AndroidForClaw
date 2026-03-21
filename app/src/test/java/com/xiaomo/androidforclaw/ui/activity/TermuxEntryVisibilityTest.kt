package com.xiaomo.androidforclaw.ui.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxEntryVisibilityTest {
    @Test
    fun mainActivityComposeSource_containsTermuxEntry() {
        val candidates = listOf(
            "src/main/java/com/xiaomo/androidforclaw/ui/activity/MainActivityCompose.kt",
            "app/src/main/java/com/xiaomo/androidforclaw/ui/activity/MainActivityCompose.kt"
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw AssertionError("MainActivityCompose.kt not found, cwd=${System.getProperty("user.dir")}")
        val source = file.readText()
        assertTrue("onNavigateToTermux missing", source.contains("onNavigateToTermux"))
        assertTrue("Termux 配置 text missing", source.contains("Termux 配置"))
        assertTrue("onNavigateToTermux callback missing", source.contains("onNavigateToTermux"))
    }
}
