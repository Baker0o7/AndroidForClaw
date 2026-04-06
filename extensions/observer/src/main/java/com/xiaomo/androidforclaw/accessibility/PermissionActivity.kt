package com.xiaomo.androidforclaw.accessibility

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: observer permission and projection flow.
 */


import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import com.xiaomo.androidforclaw.accessibility.databinding.ActivityObserverPermissionsBinding
import com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderService
import kotlinx.coroutines.*
import java.io.File

/**
 * Permission Request Activity (Refactored Version)
 *
 * Main Improvements:
 * 1. Async permission check (non-blocking main thread)
 * 2. Reduced check frequency (1s -> 2s)
 * 3. Event-driven UI updates
 * 4. Detailed status descriptions
 * 5. Optimized user experience
 */
class PermissionActivity : Activity() {
    companion object {
        private const val TAG = "PermissionActivity"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 10086
        private const val REQUEST_CODE_ACCESSIBILITY = 1001
        private const val REQUEST_CODE_MANAGE_STORAGE = 1002
        private const val STATUS_CHECK_INTERVAL = 2000L  // Check every 2 seconds (reduced frequency)
    }

    private lateinit var binding: ActivityObserverPermissionsBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coroutine scope for this activity
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Status cache (to avoid frequent checks)
    private var cachedAccessibilityEnabled = false
    private var cachedMediaProjectionAuthorized = false
    private var cachedStorageGranted = false
    private var lastCheckTime = 0L

    // Status check job
    private var statusCheckJob: Job? = null

    private data class PermissionCheckSnapshot(
        val settingsEnabled: Boolean,
        val serviceInstancePresent: Boolean,
        val rootPresent: Boolean,
        val accessibilityEnabled: Boolean,
        val mediaProjectionAuthorized: Boolean,
        val storageGranted: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // Initialize MediaProjectionHelper
        val workspace = File("/sdcard/.androidforclaw/workspace")
        val screenshotDir = File(workspace, "screenshots")
        MediaProjectionHelper.initialize(this, screenshotDir)

        binding = ActivityObserverPermissionsBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        setupViews()

        // Initial check
        checkPermissionsAsync("onCreate")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "lifecycle onResume")
        // Broadcast permission status change to refresh LiveData in app module
        sendBroadcast(android.content.Intent("com.xiaomo.androidforclaw.PERMISSION_CHANGED"))
        // Refresh immediately to cover the case where user returns from system settings/floating button but onActivityResult is not called
        checkPermissionsAsync("onResume")
        // Start periodic check
        startStatusCheck()
        // Some ROMs have delays in writing back accessibility settings, add one more delayed refresh
        mainHandler.postDelayed({ checkPermissionsAsync("onResume-delayed-800ms") }, 800)
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "lifecycle onRestart")
        checkPermissionsAsync("onRestart")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "lifecycle onWindowFocusChanged hasFocus=$hasFocus")
        if (hasFocus) {
            checkPermissionsAsync("onWindowFocusChanged")
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop periodic check
        stopStatusCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all coroutines
        activityScope.cancel()
        Log.d(TAG, "onDestroy called")
    }

    private fun setupViews() {
        binding.apply {
            // Back button
            btnBack.setOnClickListener { finish() }

            // Accessibility service button
            btnAccessibility.setOnClickListener {
                Log.d(TAG, "btnAccessibility clicked")
                requestAccessibilityPermission()
            }

            // Storage permission button
            btnStorage.setOnClickListener {
                Log.d(TAG, "btnStorage clicked")
                requestStoragePermission()
            }

            // Screen recording permission button
            btnScreenCapture.setOnClickListener {
                Log.d(TAG, "btnScreenCapture clicked")
                requestMediaProjectionPermission()
            }

            // Grant all button
            btnGrantAll.setOnClickListener {
                Log.d(TAG, "btnGrantAll clicked")
                grantAllPermissions()
            }

            // Reset button (hidden, for debugging)
            tvAllStatus.setOnLongClickListener {
                showResetDialog()
                true
            }
        }
    }

    /**
     * Async check permission status
     */
    private fun checkPermissionsAsync(reason: String = "unknown") {
        activityScope.launch {
            try {
                Log.d(TAG, "checkPermissionsAsync start, reason=$reason")

                // Check on background thread
                val result = withContext(Dispatchers.IO) {
                    val settingsEnabled = isAccessibilityServiceEnabled()
                    val serviceInstancePresent = AccessibilityBinderService.serviceInstance != null
                    val rootPresent = AccessibilityBinderService.serviceInstance?.rootInActiveWindow != null
                    val accessibility = resolveAccessibilityEnabled()
                    val mediaProjection = MediaProjectionHelper.isAuthorized()
                    val storage = isStoragePermissionGranted()
                    PermissionCheckSnapshot(
                        settingsEnabled = settingsEnabled,
                        serviceInstancePresent = serviceInstancePresent,
                        rootPresent = rootPresent,
                        accessibilityEnabled = accessibility,
                        mediaProjectionAuthorized = mediaProjection,
                        storageGranted = storage
                    )
                }

                Log.d(
                    TAG,
                    "checkPermissionsAsync result, reason=$reason, " +
                        "settingsEnabled=${result.settingsEnabled}, " +
                        "serviceInstancePresent=${result.serviceInstancePresent}, " +
                        "rootPresent=${result.rootPresent}, " +
                        "accessibilityEnabled=${result.accessibilityEnabled}, " +
                        "mediaProjectionAuthorized=${result.mediaProjectionAuthorized}, " +
                        "storageGranted=${result.storageGranted}"
                )

                // Update cache
                cachedAccessibilityEnabled = result.accessibilityEnabled
                cachedMediaProjectionAuthorized = result.mediaProjectionAuthorized
                cachedStorageGranted = result.storageGranted
                lastCheckTime = System.currentTimeMillis()

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    updateAccessibilityUI(result.accessibilityEnabled)
                    updateMediaProjectionUI(result.mediaProjectionAuthorized)
                    updateStorageUI(result.storageGranted)
                    updateAllPermissionsUI(
                        result.accessibilityEnabled,
                        result.mediaProjectionAuthorized,
                        result.storageGranted
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking permissions, reason=$reason", e)
            }
        }
    }

    /**
     * Check if storage permission is granted
     */
    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and below check WRITE_EXTERNAL_STORAGE
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Start periodic status check
     */
    private fun startStatusCheck() {
        stopStatusCheck()

        statusCheckJob = activityScope.launch {
            while (isActive) {
                checkPermissionsAsync("periodic")
                delay(STATUS_CHECK_INTERVAL)
            }
        }

        Log.d(TAG, "Started permission status check (interval: ${STATUS_CHECK_INTERVAL}ms)")
    }

    /**
     * Stop periodic status check
     */
    private fun stopStatusCheck() {
        statusCheckJob?.cancel()
        statusCheckJob = null
        Log.d(TAG, "Stopped permission status check")
    }

    /**
     * Update accessibility service UI
     */
    private fun updateAccessibilityUI(isEnabled: Boolean) {
        binding.apply {
            if (isEnabled) {
                tvAccessibilityStatus.text = "✅ Enabled"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnAccessibility.isEnabled = false
                btnAccessibility.text = "Enabled"
                btnAccessibility.alpha = 0.5f

                tvAccessibilityDesc.text = """
                    ✅ Accessibility service enabled

                    Features:
                    • Click, swipe, long press
                    • Input text
                    • Get UI info
                    • Navigation (Home/Back)
                """.trimIndent()
            } else {
                tvAccessibilityStatus.text = "❌ Not enabled"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnAccessibility.isEnabled = true
                btnAccessibility.text = "Go to Settings"
                btnAccessibility.alpha = 1.0f

                tvAccessibilityDesc.text = """
                    ⚠️ Accessibility service needs to be enabled

                    Steps:
                    1. Click "Go to Settings" button
                    2. Find "S4Claw" or "Accessibility services"
                    3. Turn on the service switch
                    4. Grant permissions
                """.trimIndent()
            }
        }
    }

    /**
     * Update screen recording permission UI
     */
    private fun updateMediaProjectionUI(isAuthorized: Boolean) {
        val statusDetails = MediaProjectionHelper.getDetailedStatus()

        binding.apply {
            if (isAuthorized) {
                tvScreenCaptureStatus.text = "✅ Authorized"
                tvScreenCaptureStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnScreenCapture.isEnabled = false
                btnScreenCapture.text = "Authorized"
                btnScreenCapture.alpha = 0.5f

                tvScreenCaptureDesc.text = """
                    ✅ Screen recording permission authorized

                    Status: $statusDetails

                    Features:
                    • Capture screen
                    • Analyze UI elements
                    • Assist Agent observation
                """.trimIndent()
            } else {
                tvScreenCaptureStatus.text = "❌ Not authorized"
                tvScreenCaptureStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnScreenCapture.isEnabled = true
                btnScreenCapture.text = "Grant Permission"
                btnScreenCapture.alpha = 1.0f

                tvScreenCaptureDesc.text = """
                    ⚠️ Screen recording permission needed

                    Status: $statusDetails

                    Note:
                    • Click "Grant Permission" button
                    • Click "Start now" in the popup
                    • Foreground service will start automatically

                    Note: Screen recording permission requires foreground service to maintain
                """.trimIndent()
            }
        }
    }

    /**
     * Update storage permission UI
     */
    private fun updateStorageUI(isGranted: Boolean) {
        binding.apply {
            if (isGranted) {
                tvStorageStatus.text = "✅ Authorized"
                tvStorageStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnStorage.isEnabled = false
                btnStorage.text = "Authorized"
                btnStorage.alpha = 0.5f

                tvStorageDesc.text = """
                    ✅ Storage permission authorized

                    Features:
                    • Save screenshot files
                    • Access workspace
                    • Read/write config files
                """.trimIndent()
            } else {
                tvStorageStatus.text = "❌ Not authorized"
                tvStorageStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnStorage.isEnabled = true
                btnStorage.text = "Grant Permission"
                btnStorage.alpha = 1.0f

                tvStorageDesc.text = """
                    ⚠️ Storage permission needed

                    Note:
                    • Android 11+ requires "All files access"
                    • Click "Grant Permission" button
                    • Enable permission in settings

                    Note: Storage permission is used to save screenshots
                """.trimIndent()
            }
        }
    }

    /**
     * Update overall status UI
     */
    private fun updateAllPermissionsUI(accessibilityEnabled: Boolean, mediaProjectionAuthorized: Boolean, storageGranted: Boolean) {
        val allGranted = accessibilityEnabled && mediaProjectionAuthorized && storageGranted
        val grantedCount = listOf(accessibilityEnabled, mediaProjectionAuthorized, storageGranted).count { it }

        binding.apply {
            if (allGranted) {
                tvAllStatus.text = "✅ All permissions granted (3/3)"
                tvAllStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnGrantAll.isEnabled = false
                btnGrantAll.text = "All Authorized"
                btnGrantAll.alpha = 0.5f
            } else {
                tvAllStatus.text = "⚠️ $grantedCount/3 permissions granted"
                tvAllStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                btnGrantAll.isEnabled = true
                btnGrantAll.text = "Grant All (${grantedCount}/3)"
                btnGrantAll.alpha = 1.0f
            }
        }
    }

    /**
     * Request accessibility permission — Show prominent disclosure first (Google Play compliance)
     */
    private fun requestAccessibilityPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.disclosure_accessibility_title))
            .setMessage(getString(R.string.disclosure_accessibility_message))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.disclosure_agree)) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Open system accessibility settings page
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY)
            Toast.makeText(this, "Please find and enable S4Claw accessibility service", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            Toast.makeText(this, "Cannot open accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Request screen recording permission — Show prominent disclosure first (Google Play compliance)
     */
    private fun requestMediaProjectionPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.disclosure_screen_capture_title))
            .setMessage(getString(R.string.disclosure_screen_capture_message))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.disclosure_agree)) { _, _ ->
                startMediaProjectionRequest()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Initiate system screen recording permission request
     */
    private fun startMediaProjectionRequest() {
        try {
            val needsPermission = !MediaProjectionHelper.requestPermission(this)

            if (!needsPermission) {
                Toast.makeText(this, "Screen recording permission already granted", Toast.LENGTH_SHORT).show()
                checkPermissionsAsync("requestMediaProjectionPermission-alreadyGranted")
            } else {
                Toast.makeText(this, "Please click \"Start now\" in the popup", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request media projection", e)
            Toast.makeText(this, "Failed to request screen recording permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Request storage permission — Show prominent disclosure first (Google Play compliance)
     */
    private fun requestStoragePermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.disclosure_storage_title))
            .setMessage(getString(R.string.disclosure_storage_message))
            .setCancelable(true)
            .setPositiveButton(getString(R.string.disclosure_agree)) { _, _ ->
                openStorageSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Open system storage permission settings
     */
    private fun openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
                Toast.makeText(this, "Please enable \"Allow access to all files\" permission", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CODE_MANAGE_STORAGE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request storage permission", e)
            Toast.makeText(this, "Failed to request storage permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Grant all permissions with one click
     */
    private fun grantAllPermissions() {
        if (!cachedAccessibilityEnabled) {
            requestAccessibilityPermission()
        } else if (!cachedStorageGranted) {
            requestStoragePermission()
        } else if (!cachedMediaProjectionAuthorized) {
            requestMediaProjectionPermission()
        }
    }

    /**
     * Show reset dialog (triggered by long press)
     */
    private fun showResetDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Permissions")
            .setMessage("Are you sure you want to reset all permissions?\n\nThis will:\n• Stop foreground service\n• Clear screen recording permission\n• Require re-authorization")
            .setPositiveButton("Reset") { _, _ ->
                MediaProjectionHelper.releaseCompletely(this)
                Toast.makeText(this, "Permissions reset", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ checkPermissionsAsync() }, 500)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Read accessibility switch status in system settings.
     * Note: When transitioning from "no permission -> has permission", system settings usually complete earlier than
     * when service truly connects, so final UI judgment should not rely solely on this.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            val serviceShort = "${packageName}/.accessibility.service.PhoneAccessibilityService"
            val serviceFull = "${packageName}/com.xiaomo.androidforclaw.accessibility.service.PhoneAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            val serviceEnabled = enabledServices?.let {
                it.contains(serviceShort) || it.contains(serviceFull)
            } ?: false

            accessibilityEnabled && serviceEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service", e)
            false
        }
    }

    /**
     * Final accessibility "authorized" status determination:
     * - Only check if system settings enabled + serviceInstance established
     * - No longer rely on rootInActiveWindow; that is more suitable as a runtime indicator for "can immediately capture UI"
     */
    private suspend fun resolveAccessibilityEnabled(): Boolean {
        val settingsEnabled = isAccessibilityServiceEnabled()
        if (!settingsEnabled) return false

        repeat(8) { attempt ->
            val serviceConnected = AccessibilityBinderService.serviceInstance != null
            if (serviceConnected) {
                if (attempt > 0) {
                    Log.d(TAG, "Accessibility service connected after ${attempt + 1} checks")
                }
                return true
            }
            delay(250)
        }

        Log.w(TAG, "Accessibility settings enabled, but serviceInstance is still null")
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            REQUEST_CODE_MEDIA_PROJECTION -> {
                val granted = MediaProjectionHelper.handlePermissionResult(this, requestCode, resultCode, data)

                if (granted) {
                    Toast.makeText(this, "✅ Screen recording permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Screen recording permission denied", Toast.LENGTH_SHORT).show()
                }

                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-mediaProjection") }, 500)
            }

            REQUEST_CODE_ACCESSIBILITY -> {
                Toast.makeText(this, "Checking accessibility service status...", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-accessibility") }, 1000)
            }

            REQUEST_CODE_MANAGE_STORAGE -> {
                val granted = isStoragePermissionGranted()
                if (granted) {
                    Toast.makeText(this, "✅ Storage permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Storage permission denied", Toast.LENGTH_SHORT).show()
                }
                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-storage") }, 500)
            }
        }
    }
}
