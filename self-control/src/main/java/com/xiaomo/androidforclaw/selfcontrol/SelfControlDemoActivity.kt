package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Self-Control Demo Activity
 *
 * Example Activity demonstrating how to use Self-Control Skills.
 *
 * This Activity demonstrates:
 * 1. How to initialize SelfControlRegistry
 * 2. How to call various Self-Control Skills
 * 3. Code examples for typical usage scenarios
 *
 * Note: This is an optional demo class, the main app doesn't need to depend on it.
 */
class SelfControlDemoActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SelfControlDemo"
    }

    private lateinit var registry: SelfControlRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Registry
        registry = SelfControlRegistry(this)

        // Demonstrate various features
        demonstrateSelfControl()
    }

    private fun demonstrateSelfControl() {
        lifecycleScope.launch {
            Log.d(TAG, "=== Self-Control Demo Start ===")
            Log.d(TAG, registry.getSummary())

            // Example 1: Page Navigation
            demoNavigation()

            // Example 2: Config Management
            demoConfigManagement()

            // Example 3: Service Control
            demoServiceControl()

            // Example 4: Log Query
            demoLogQuery()

            Log.d(TAG, "=== Self-Control Demo End ===")
        }
    }

    /**
     * Example 1: Page Navigation
     */
    private suspend fun demoNavigation() {
        Log.d(TAG, "\n--- Demo 1: Navigation ---")

        // Open config page
        val result = registry.execute(
            "navigate_app",
            mapOf("page" to "config")
        )

        Log.d(TAG, "Navigate to config: ${result?.content}")
        showToast("Navigate to config page: ${result?.success}")
    }

    /**
     * Example 2: Config Management
     */
    private suspend fun demoConfigManagement() {
        Log.d(TAG, "\n--- Demo 2: Config Management ---")

        // 2.1 List all feature configs
        val listResult = registry.execute(
            "manage_config",
            mapOf(
                "operation" to "list",
                "category" to "feature"
            )
        )
        Log.d(TAG, "List config:\n${listResult?.content}")

        // 2.2 Read exploration_mode
        val getResult = registry.execute(
            "manage_config",
            mapOf(
                "operation" to "get",
                "key" to "exploration_mode"
            )
        )
        Log.d(TAG, "Get exploration_mode: ${getResult?.content}")

        // 2.3 Update config
        val setResult = registry.execute(
            "manage_config",
            mapOf(
                "operation" to "set",
                "key" to "self_control_demo",
                "value" to "true"
            )
        )
        Log.d(TAG, "Set config: ${setResult?.content}")

        // 2.4 Verify update
        val verifyResult = registry.execute(
            "manage_config",
            mapOf(
                "operation" to "get",
                "key" to "self_control_demo"
            )
        )
        Log.d(TAG, "Verify config: ${verifyResult?.content}")

        showToast("Config management demo complete")
    }

    /**
     * Example 3: Service Control
     */
    private suspend fun demoServiceControl() {
        Log.d(TAG, "\n--- Demo 3: Service Control ---")

        // 3.1 Check service status
        val statusResult = registry.execute(
            "control_service",
            mapOf("operation" to "check_status")
        )
        Log.d(TAG, "Service status:\n${statusResult?.content}")

        // 3.2 Hide floating window (simulate pre-screenshot operation)
        val hideResult = registry.execute(
            "control_service",
            mapOf("operation" to "hide_float")
        )
        Log.d(TAG, "Hide float: ${hideResult?.content}")

        // 3.3 Wait 1 second
        kotlinx.coroutines.delay(1000)

        // 3.4 Show floating window (simulate post-screenshot operation)
        val showResult = registry.execute(
            "control_service",
            mapOf("operation" to "show_float")
        )
        Log.d(TAG, "Show float: ${showResult?.content}")

        showToast("Service control demo complete")
    }

    /**
     * Example 4: Log Query
     */
    private suspend fun demoLogQuery() {
        Log.d(TAG, "\n--- Demo 4: Log Query ---")

        // 4.1 Query error logs
        val errorResult = registry.execute(
            "query_logs",
            mapOf(
                "level" to "E",
                "lines" to 20
            )
        )
        Log.d(TAG, "Error logs:\n${errorResult?.content}")

        // 4.2 Search specific TAG
        val filterResult = registry.execute(
            "query_logs",
            mapOf(
                "level" to "D",
                "filter" to "SelfControl",
                "lines" to 50
            )
        )
        Log.d(TAG, "Filtered logs:\n${filterResult?.content}")

        showToast("Log query demo complete")
    }

    /**
     * Example 5: Combined Usage (Complete Workflow)
     */
    private suspend fun demoCompleteWorkflow() {
        Log.d(TAG, "\n--- Demo 5: Complete Workflow ---")
        Log.d(TAG, "Scenario: AI Agent self-diagnosis and tuning")

        // Step 1: Check service status
        Log.d(TAG, "Step 1: Check service status")
        val status = registry.execute(
            "control_service",
            mapOf("operation" to "check_status")
        )
        Log.d(TAG, status?.content ?: "Failed")

        // Step 2: Query error logs
        Log.d(TAG, "Step 2: Query error logs")
        val errors = registry.execute(
            "query_logs",
            mapOf("level" to "E", "lines" to 50)
        )

        // Simulate finding issue: screenshot_delay too short
        if (errors?.content?.contains("screenshot", ignoreCase = true) == true) {
            Log.d(TAG, "Step 3: Found issue - screenshot delay too short")

            // Step 3: Adjust config
            Log.d(TAG, "Step 4: Increase screenshot_delay")
            registry.execute(
                "manage_config",
                mapOf(
                    "operation" to "set",
                    "key" to "screenshot_delay",
                    "value" to "200"
                )
            )

            // Step 4: Verify config
            Log.d(TAG, "Step 5: Verify config change")
            val verify = registry.execute(
                "manage_config",
                mapOf(
                    "operation" to "get",
                    "key" to "screenshot_delay"
                )
            )
            Log.d(TAG, verify?.content ?: "Failed")
        }

        // Step 5: Open config page for user confirmation
        Log.d(TAG, "Step 6: Open config page for user confirmation")
        registry.execute(
            "navigate_app",
            mapOf("page" to "config")
        )

        showToast("Complete workflow demo complete")
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
