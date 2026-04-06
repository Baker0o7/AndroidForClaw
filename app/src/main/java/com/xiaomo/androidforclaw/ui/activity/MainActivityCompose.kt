/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import com.xiaomo.androidforclaw.ui.compose.ForClawSettingsTab
import com.xiaomo.androidforclaw.util.ChatBroadcastReceiver
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.ui.RootScreen
import ai.openclaw.app.ui.OpenClawTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaomo.androidforclaw.accessibility.MediaProjectionHelper
import com.xiaomo.androidforclaw.ui.float.SessionFloatWindow
import com.tencent.mmkv.MMKV
import com.xiaomo.androidforclaw.util.MMKVKeys
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Check if S4Claw (observer extension) accessibility service is enabled
 *
 * Note: This method only checks system settings without blocking the thread
 */
suspend fun isS4ClawAccessibilityEnableddd(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            // Check system settings
            val accessibilityEnableddd = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!accessibilityEnableddd) {
                Log.d("MainActivityCompose", "System accessibility not enabled")
                return@withContext false
            }

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@withContext false

            // S4Claw accessibility service package name
            val s4clawServiceName = "com.xiaomo.androidforclaw.accessibility/com.xiaomo.androidforclaw.accessibility.service.PhoneAccessibilityService"

            val isEnableddd = enabledServices.contains(s4clawServiceName)
            Log.d("MainActivityCompose", "S4Claw accessibility service system status: $isEnableddd")

            // If system shows enabled, verify service is actually available
            if (isEnableddd) {
                try {
                    val ready = com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isServiceReadyAsync()
                    Log.d("MainActivityCompose", "S4Claw accessibility service availability: $ready")
                    return@withContext ready
                } catch (e: Exception) {
                    Log.w("MainActivityCompose", "Service check failed, using system settings result", e)
                    return@withContext isEnableddd
                }
            }

            isEnableddd
        } catch (e: Exception) {
            Log.e("MainActivityCompose", "Failed to check S4Claw accessibility service", e)
            false
        }
    }
}

/**
 * MainActivity - Compose version
 *
 * Contains three tabs:
 * 1. Chat - AI assistant chat interface
 * 2. Status - System status cards
 * 3. Settings - Configuration and test entries
 */
class MainActivityCompose : ComponentActivity() {

    private val openClawViewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /**
     * Workaround for Compose 1.4.x hover event crash on some MIUI devices.
     * See: https://issuetracker.google.com/issues/286991266
     */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent?): Boolean {
        return try {
            super.dispatchGenericMotionEvent(ev)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HOVER_EXIT") == true) {
                Log.w("MainActivityCompose", "Suppressed Compose hover crash: ${e.message}")
                true
            } else throw e
        }
    }

    private fun launchObserverPermissionActivity() {
        try {
            startActivity(Intent().apply {
                component = android.content.ComponentName(
                    "com.xiaomo.androidforclaw",
                    "com.xiaomo.androidforclaw.accessibility.PermissionActivity"
                )
            })
        } catch (e: Exception) {
            Log.w(TAG, "Observer PermissionActivity unavailable, fallback to local PermissionsActivity", e)
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    companion object {
        private const val TAG = "MainActivityCompose"
        private const val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    }

    private var chatBroadcastReceiver: ChatBroadcastReceiver? = null
    private var permissionChangedReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Storage permission: request after legal consent is accepted.
        // For returning users who already accepted, check immediately.
        val legalAlreadyAccepted = getSharedPreferences("forclaw_legal", MODE_PRIVATE)
            .getBoolean("legal.accepted", false)
        if (legalAlreadyAccepted) {
            checkAndRequestStoragePermission()
        }

        // Model setup: only check after storage permission is granted,
        // otherwise the wizard cannot read/write /sdcard/.androidforclaw/.
        if (legalAlreadyAccepted && hasStoragePermission()) {
            launchModelSetupIfNeeded()
            // Start channels if they were skipped in MyApplication.onCreate due to missing permission
            (application as? com.xiaomo.androidforclaw.core.MyApplication)?.startAllChannels()
        }

        // Termux RUN_COMMAND permission: dangerous level, must request at runtime
        requestTermuxPermissionIfNeeded()

        // Restore floating avatar if it was previously enabled (Rive takes priority)
        if (android.provider.Settings.canDrawOverlays(this)) {
            val riveAvatarEnableddd = getSharedPreferences("forclaw_rive_avatar", MODE_PRIVATE)
                .getBoolean("enabled", false)
            val avatarEnableddd = getSharedPreferences("forclaw_avatar", MODE_PRIVATE)
                .getBoolean("enabled", false)
            if (riveAvatarEnableddd && !ai.openclaw.app.rive.FloatingRiveService.isRunning) {
                ai.openclaw.app.rive.FloatingRiveService.start(this)
            } else if (avatarEnableddd && !ai.openclaw.app.avatar.FloatingAvatarService.isRunning) {
                ai.openclaw.app.avatar.FloatingAvatarService.start(this)
            }
        }

        // Auto-prune old sessions (>30 days) on startup
        lifecycleScope.launch {
            try {
                val sessionManager = com.xiaomo.androidforclaw.core.MainEntryNew.getSessionManager()
                sessionManager?.pruneOldSessions()
            } catch (_: Exception) {}
        }

        // Mark onboarding as completed so RootScreen always shows PostOnboardingTabs.
        getSharedPreferences("openclaw.node", MODE_PRIVATE).edit()
            .putBoolean("onboarding.completed", true)
            .apply()

        setContent {
            val legalPrefs = remember {
                getSharedPreferences("forclaw_legal", MODE_PRIVATE)
            }
            var legalAccepted by remember {
                mutableStateOf(legalPrefs.getBoolean("legal.accepted", false))
            }

            // 本地直连: 同Process, None需 WebSocket 握手
            LaunchedEffect(Unit) {
                openClawViewModel.connectLocal()
                // 让 CanvasTool 走 Screen tab Inside嵌 WebView 而不Yes独立 Activity
                com.xiaomo.androidforclaw.canvas.CanvasManager.screenTabController =
                    openClawViewModel.canvas
            }

            OpenClawTheme {
                if (!legalAccepted) {
                    LegalConsentDialog(
                        onAccept = {
                            legalPrefs.edit().putBoolean("legal.accepted", true).apply()
                            legalAccepted = true
                            // Request storage permission after legal consent
                            checkAndRequestStoragePermission()
                            // For Android 10 and below (no MANAGE_EXTERNAL_STORAGE needed),
                            // launch model setup immediately since permission is already granted.
                            if (hasStoragePermission()) {
                                launchModelSetupIfNeeded()
                                (application as? com.xiaomo.androidforclaw.core.MyApplication)?.startAllChannels()
                            }
                        },
                        onDecline = { finishAffinity() },
                        onOpenPrivacy = { LegalActivity.start(this@MainActivityCompose, LegalActivity.TYPE_PRIVACY) },
                        onOpenTerms = { LegalActivity.start(this@MainActivityCompose, LegalActivity.TYPE_TERMS) },
                    )
                }

                RootScreen(
                    viewModel = openClawViewModel,
                    settingsTabSlot = { ForClawSettingsTab() },
                    skillActions = remember { com.xiaomo.androidforclaw.agent.skills.SkillActionsImpl() },
                )
            }
        }

        // Register ADB test interface
        registerChatBroadcastReceiver()

        // Register permission change receiver (from PermissionActivity in observer module)
        permissionChangedReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.refreshPermissions(ctx)
            }
        }
        val permFilter = android.content.IntentFilter("com.xiaomo.androidforclaw.PERMISSION_CHANGED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionChangedReceiver, permFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionChangedReceiver, permFilter)
        }
    }

    override fun onStart() {
        super.onStart()
        openClawViewModel.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission LiveData on every resume
        com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.refreshPermissions(this)
        // Note: SessionFloatWindow lifecycle is now managed by ActivityLifecycleCallbacks
        // Silent update check on every resume (cold + warm start)
        silentUpdateCheck()
    }

    override fun onStop() {
        openClawViewModel.setForeground(false)
        super.onStop()
    }

    /**
     * Check file server for updates.
     * - 已Download: 直接弹窗询问Install
     * - 未Download: Back台AutoDownload, DownloadComplete弹窗
     * - 频次Limit: 同Version numberdailymost多弹一次
     */
    fun silentUpdateCheck() {
        lifecycleScope.launch {
            try {
                val updater = com.xiaomo.androidforclaw.updater.AppUpdater(this@MainActivityCompose)
                val info = updater.checkForUpdate()
                if (!info.hasUpdate || info.downloadUrl == null) return@launch

                val prefs = getSharedPreferences("update_check", MODE_PRIVATE)
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val key = "dismissed_${info.latestVersion}_$today"

                // 频次Limit: 同Version number当天被Close就不弹
                if (prefs.getBoolean(key, false)) return@launch

                // 已Download该Version → 直接弹InstallConfirm
                if (updater.hasDownloadedUpdate() && updater.getDownloadedVersion() == info.latestVersion) {
                    showInstallDialog(updater, info, prefs, key)
                    return@launch
                }

                // 没Download过 → Back台AutoDownload
                val sizeStr = if (info.fileSize > 0) "%.1f MB".format(info.fileSize / 1024.0 / 1024.0) else ""
                val message = buildString {
                    append("discoverNewVersion v${info.latestVersion}")
                    if (sizeStr.isNotEmpty()) append("($sizeStr)")
                    append("\n当FrontVersion v${info.currentVersion}\n")
                    if (!info.releaseNotes.isNullOrEmpty()) {
                        append("\n${info.releaseNotes.take(200)}")
                    }
                }

                androidx.appcompat.app.AlertDialog.Builder(this@MainActivityCompose)
                    .setTitle("discoverNewVersion")
                    .setMessage(message)
                    .setPositiveButton("Back台Download") { _, _ ->
                        // Back台Download, Download完弹Install
                        lifecycleScope.launch {
                            val success = updater.downloadUpdate(info.downloadUrl, info.latestVersion)
                            if (success) {
                                showInstallDialog(updater, info, prefs, key)
                            }
                        }
                    }
                    .setNegativeButton("稍Back再说") { _, _ ->
                        prefs.edit().putBoolean(key, true).apply()
                    }
                    .setOnCancelListener {
                        prefs.edit().putBoolean(key, true).apply()
                    }
                    .show()
            } catch (_: Exception) {
                // Silent — don't interrupt user
            }
        }
    }

    /** 弹窗ConfirmInstall已Download的 APK */
    private fun showInstallDialog(
        updater: com.xiaomo.androidforclaw.updater.AppUpdater,
        info: com.xiaomo.androidforclaw.updater.AppUpdater.UpdateInfo,
        prefs: android.content.SharedPreferences,
        key: String
    ) {
        if (isFinishing) return
        androidx.appcompat.app.AlertDialog.Builder(this@MainActivityCompose)
            .setTitle("Update已Ready")
            .setMessage("v${info.latestVersion} 已DownloadComplete, YesNoInstall?")
            .setPositiveButton("Install") { _, _ ->
                updater.installUpdate()
            }
            .setNegativeButton("稍Back") { _, _ ->
                prefs.edit().putBoolean(key, true).apply()
            }
            .setOnCancelListener {
                prefs.edit().putBoolean(key, true).apply()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Note: SessionFloatWindow lifecycle is now managed by ActivityLifecycleCallbacks
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterChatBroadcastReceiver()
        permissionChangedReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
    }

    /**
     * Register Chat Broadcast Receiver
     *
     * Note: Uses RECEIVER_EXPORTED to support ADB testing
     */
    private fun registerChatBroadcastReceiver() {
        chatBroadcastReceiver = ChatBroadcastReceiver { message ->
            Log.d(TAG, "📨 [BroadcastReceiver] Received message: $message")
            // Send to agent via ViewModel
            openClawViewModel.sendChat(message = message, thinking = "", attachments = emptyList())
            Log.d(TAG, "✅ [BroadcastReceiver] Message sent to agent: $message")
        }

        val filter = ChatBroadcastReceiver.createIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "✅ Register ChatBroadcastReceiver (EXPORTED, SDK >= 33)")
            registerReceiver(chatBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            Log.i(TAG, "✅ Register ChatBroadcastReceiver (SDK < 33)")
            registerReceiver(chatBroadcastReceiver, filter)
        }
    }

    /**
     * Unregister Chat Broadcast Receiver
     */
    private fun unregisterChatBroadcastReceiver() {
        chatBroadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }


    /**
     * Whether the app already has file management permission.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Android 10 and below use scoped storage or legacy permissions
        }
    }

    /**
     * Request Termux RUN_COMMAND permission if Termux is installed and permission not yet granted.
     * Shows an explanation dialog before requesting.
     */
    private fun requestTermuxPermissionIfNeeded() {
        val termuxPermission = "com.termux.permission.RUN_COMMAND"
        val termuxInstalled = try {
            packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) {
            false
        }
        if (!termuxInstalled) return
        if (checkSelfPermission(termuxPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return

        // 先弹illustrateConversation框, 再applyPermission
        android.app.AlertDialog.Builder(this)
            .setTitle("Termux 命令执RowPermission")
            .setMessage(
                "ForClaw Need「Termux 命令执Row」Permission来Implementation以DownFeature: \n\n" +
                "• 让 AI 在手机终端中执Row命令(such asInstall软件、Run脚本)\n" +
                "• AutoStart Termux SSH Service, None需ManualAction\n" +
                "• 当 SSH Keylose时AutoFixConnect\n\n" +
                "此Permission仅用于与 Termux 通信, 不会访问您的Its他Data. \n\n" +
                "click「agree」Back, 系统会弹出PermissionRequest, 请choose「允许」. "
            )
            .setCancelable(true)
            .setPositiveButton("agree") { _, _ ->
                Log.i(TAG, "Requesting Termux RUN_COMMAND permission...")
                requestPermissions(arrayOf(termuxPermission), 1002)
            }
            .setNegativeButton("暂不") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Launch ModelSetupActivity if first run and no API key configured.
     * Must be called only after storage permission is granted.
     */
    private fun launchModelSetupIfNeeded() {
        if (ModelSetupActivity.isNeeded(this)) {
            Log.i(TAG, "🔧 首次Start, Open模型Config引导...")
            startActivity(Intent(this, ModelSetupActivity::class.java))
        }
    }

    /**
     * Check and request file management permission.
     * Shows an in-app dialog if permission is not granted.
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.i(TAG, "File management permission not granted, showing dialog...")
                showStoragePermissionDialog()
            } else {
                Log.i(TAG, "✅ File management permission granted")
            }
        } else {
            Log.i(TAG, "Android 10 and below, using traditional storage permissions")
        }
    }

    private fun showStoragePermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(com.xiaomo.androidforclaw.R.string.disclosure_storage_title))
            .setMessage(getString(com.xiaomo.androidforclaw.R.string.disclosure_storage_message))
            .setCancelable(false)
            .setPositiveButton(getString(com.xiaomo.androidforclaw.R.string.disclosure_agree)) { _, _ ->
                openStoragePermissionSettings()
            }
            .setNegativeButton(getString(com.xiaomo.androidforclaw.R.string.disclosure_exit)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun openStoragePermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForresult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file management permission settings page", e)
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForresult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open file management permission settings", e2)
                Toast.makeText(this, "CannotOpenPermissionSettings, 请ManualAuthorize", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityresult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityresult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.i(TAG, "✅ File management permission granted")
                    // Launch model setup wizard before recreate if needed
                    launchModelSetupIfNeeded()
                    // Restart to apply — recreate so config/sessions init properly
                    recreate()
                } else {
                    Log.w(TAG, "⚠️ File management permission still not granted")
                    showStoragePermissionDialog()
                }
            }
        }
    }
}

@Composable
private fun LegalConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    Dialog(
        onDismissRequest = { /* non-dismissable */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Service条款和隐私政策",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "欢迎use ForClaw!在Start之Front, 请阅读并agree我们的Service条款和隐私政策. ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Clickable links
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenPrivacy) {
                        Text("View隐私政策 →", fontSize = 14.sp)
                    }
                    TextButton(onClick = onOpenTerms) {
                        Text("ViewUserProtocol →", fontSize = 14.sp)
                    }
                }

                Text(
                    text = "click「agree」that isTable示您已阅读并agree以Up条款. ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDecline) {
                        Text("不agree")
                    }
                    Button(onClick = onAccept) {
                        Text("agree")
                    }
                }
            }
        }
    }
}
