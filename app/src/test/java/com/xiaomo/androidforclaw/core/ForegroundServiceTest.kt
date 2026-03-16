package com.xiaomo.androidforclaw.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTest {
    @Test
    fun actionStartActivity_constantStable() {
        assertEquals("START_ACTIVITY", ForegroundService.ACTION_START_ACTIVITY)
    }
}
