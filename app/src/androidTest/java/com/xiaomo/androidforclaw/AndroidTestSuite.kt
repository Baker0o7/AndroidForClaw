package com.xiaomo.androidforclaw

import com.xiaomo.androidforclaw.e2e.CameraE2ETest
import com.xiaomo.androidforclaw.integration.AgentIntegrationTest
import com.xiaomo.androidforclaw.ui.*
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * AndroidForClaw UI Auto化Test套件
 *
 * ContainsAll UI 和集成Test
 *
 * RunAllTest:
 * ./gradlew connectedDebugAndroidTest
 *
 * Run此Test套件:
 * ./gradlew connectedDebugAndroidTest --tests "AndroidTestSuite"
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    // UI Test
    PermissionUITest::class,
    ConfigActivityUITest::class,
    FloatingWindowUITest::class,
    ForClawMainTabsUITest::class,

    // E2E Test
    CameraE2ETest::class,

    // 集成Test
    AgentIntegrationTest::class
)
class AndroidTestSuite
