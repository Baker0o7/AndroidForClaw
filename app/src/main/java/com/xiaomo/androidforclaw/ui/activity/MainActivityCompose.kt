/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.content.context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.database.ContentObserver
import android.os.Handler
import android.os.looper
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.Viewmodelprovider
import com.xiaomo.androidforclaw.ui.compose.forClawSettingsTab
import com.xiaomo.androidforclaw.util.ChatBroadcastReceiver
import ai.openclaw.app.MainViewmodel
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
import com.xiaomo.androidforclaw.accessibility.MediaProjectionhelper
import com.xiaomo.androidforclaw.ui.float.sessionFloatWindow
import com.tencent.mmkv.MMKV
import com.xiaomo.androidforclaw.util.MMKVKeys
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Check if S4Claw (observer extension) accessibility service is enabled
 *
 * note: This method only checks system settings without blocking the thread
 */
suspend fun isS4ClawAccessibilityEnabled(context: context): Boolean {
    return withcontext(Dispatchers.IO) {
        try {
            // Check system settings
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!accessibilityEnabled) {
                Log.d("MainActivityCompose", "System accessibility not enabled")
                return@withcontext false
            }

            val enabledservices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@withcontext false

            // S4Claw accessibility service package name
            val s4clawserviceName = "com.xiaomo.androidforclaw.accessibility/com.xiaomo.androidforclaw.accessibility.service.PhoneAccessibilityservice"

            val isEnabled = enabledservices.contains(s4clawserviceName)
            Log.d("MainActivityCompose", "S4Claw accessibility service system status: $isEnabled")

            // if system shows enabled, verify service is actually available
            if (isEnabled) {
                try {
                    val ready = com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.isserviceReadyAsync()
                    Log.d("MainActivityCompose", "S4Claw accessibility service availability: $ready")
                    return@withcontext ready
                } catch (e: exception) {
                    Log.w("MainActivityCompose", "service check failed, using system settings result", e)
                    return@withcontext isEnabled
                }
            }

            isEnabled
        } catch (e: exception) {
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
 * 3. Settings - configuration and test entries
 */
class MainActivityCompose : ComponentActivity() {

    private val openClawViewmodel: MainViewmodel by lazy {
        Viewmodelprovider(this)[MainViewmodel::class.java]
    }

    /**
     * Workaround for Compose 1.4.x hover event crash on some MIUI devices.
     * See: https://issuetracker.google.com/issues/286991266
     */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent?): Boolean {
        return try {
            super.dispatchGenericMotionEvent(ev)
        } catch (e: IllegalStateexception) {
            if (e.message?.contains("HOVER_EXIT") == true) {
                Log.w("MainActivityCompose", "Suppressed Compose hover crash: ${e.message}")
                true
            } else throw e
        }
    }

    private fun launchObserverPermissionActivity() {
        try {
            startActivity(Intent().app {
                component = android.content.ComponentName(
                    "com.xiaomo.androidforclaw",
                    "com.xiaomo.androidforclaw.accessibility.PermissionActivity"
                )
            })
        } catch (e: exception) {
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
        // for returning users who already accepted, check immediately.
        val legalAlreadyAccepted = getSharedPreferences("forclaw_legal", MODE_PRIVATE)
            .getBoolean("legal.accepted", false)
        if (legalAlreadyAccepted) {
            checkandRequestStoragePermission()
        }

        // model setup: only check after storage permission is granted,
        // otherwise the wizard cannot read/write /sdcard/.androidforclaw/.
        if (legalAlreadyAccepted && hasStoragePermission()) {
            launchmodelSetupifneeded()
            // Start channels if they were skipped in MyApplication.onCreate due to missing permission
            (application as? com.xiaomo.androidforclaw.core.MyApplication)?.startAllchannels()
        }

        // Termux RUN_COMMAND permission: dangerous level, must request at runtime
        requestTermuxPermissionifneeded()

        // Restore floating avatar if it was previously enabled (Rive takes priority)
        if (android.provider.Settings.canDrawoverlays(this)) {
            val riveAvatarEnabled = getSharedPreferences("forclaw_rive_avatar", MODE_PRIVATE)
                .getBoolean("enabled", false)
            val avatarEnabled = getSharedPreferences("forclaw_avatar", MODE_PRIVATE)
                .getBoolean("enabled", false)
            if (riveAvatarEnabled && !ai.openclaw.app.rive.FloatingRiveservice.isRunning) {
                ai.openclaw.app.rive.FloatingRiveservice.start(this)
            } else if (avatarEnabled && !ai.openclaw.app.avatar.FloatingAvatarservice.isRunning) {
                ai.openclaw.app.avatar.FloatingAvatarservice.start(this)
            }
        }

        // Auto-prune old sessions (>30 days) on startup
        lifecycleScope.launch {
            try {
                val sessionmanager = com.xiaomo.androidforclaw.core.MainEntrynew.getsessionmanager()
                sessionmanager?.pruneoldsessions()
            } catch (_: exception) {}
        }

        // Mark onboarding as completed so RootScreen always shows PostOnboardingTabs.
        getSharedPreferences("openclaw.node", MODE_PRIVATE).edit()
            .putBoolean("onboarding.completed", true)
            .app()

        setContent {
            val legalPrefs = remember {
                getSharedPreferences("forclaw_legal", MODE_PRIVATE)
            }
            var legalAccepted by remember {
                mutableStateOf(legalPrefs.getBoolean("legal.accepted", false))
            }

            // 本地直连: 同Process, Noneneed WebSocket 握手
            LaunchedEffect(Unit) {
                openClawViewmodel.connectLocal()
                // 让 canvastool 走 Screen tab inside嵌 WebView 而notYes独立 Activity
                com.xiaomo.androidforclaw.canvas.canvasmanager.screenTabController =
                    openClawViewmodel.canvas
            }

            OpenClawTheme {
                if (!legalAccepted) {
                    LegalConsentDialog(
                        onAccept = {
                            legalPrefs.edit().putBoolean("legal.accepted", true).app()
                            legalAccepted = true
                            // Request storage permission after legal consent
                            checkandRequestStoragePermission()
                            // for android 10 and below (no MANAGE_EXTERNAL_STORAGE needed),
                            // launch model setup immediately since permission is already granted.
                            if (hasStoragePermission()) {
                                launchmodelSetupifneeded()
                                (application as? com.xiaomo.androidforclaw.core.MyApplication)?.startAllchannels()
                            }
                        },
                        onDecline = { finishAffinity() },
                        onOpenPrivacy = { LegalActivity.start(this@MainActivityCompose, LegalActivity.TYPE_PRIVACY) },
                        onOpenTerms = { LegalActivity.start(this@MainActivityCompose, LegalActivity.TYPE_TERMS) },
                    )
                }

                RootScreen(
                    viewmodel = openClawViewmodel,
                    settingsTabSlot = { forClawSettingsTab() },
                    skillActions = remember { com.xiaomo.androidforclaw.agent.skills.skillActionsImpl() },
                )
            }
        }

        // Register ADB test interface
        registerChatBroadcastReceiver()

        // Register permission change receiver (from PermissionActivity in observer module)
        permissionChangedReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.context, intent: android.content.Intent) {
                com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.refreshPermissions(ctx)
            }
        }
        val permFilter = android.content.IntentFilter("com.xiaomo.androidforclaw.PERMISSION_CHANGED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionChangedReceiver, permFilter, android.content.context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionChangedReceiver, permFilter)
        }
    }

    override fun onStart() {
        super.onStart()
        openClawViewmodel.setforeground(true)
    }

    override fun onresume() {
        super.onresume()
        // Refresh permission LiveData on every resume
        com.xiaomo.androidforclaw.accessibility.AccessibilityProxy.refreshPermissions(this)
        // note: sessionFloatWindow lifecycle is now managed by ActivityLifecycleCallbacks
        // Silent update check on every resume (cold + warm start)
        silentUpdateCheck()
    }

    override fun onStop() {
        openClawViewmodel.setforeground(false)
        super.onStop()
    }

    /**
     * Check file server for updates.
     * - alreadynextload: 直接popup询问Install
     * - notnextload: backgroundAutonextload, nextloadCompletepopup
     * - 频timesLimit: 同Version numberdailymostmany弹onetimes
     */
    fun silentUpdateCheck() {
        lifecycleScope.launch {
            try {
                val updater = com.xiaomo.androidforclaw.updater.AppUpdater(this@MainActivityCompose)
                val info = updater.checkforUpdate()
                if (!info.hasUpdate || info.downloadUrl == null) return@launch

                val prefs = getSharedPreferences("update_check", MODE_PRIVATE)
                val today = java.text.SimpleDateformat("yyyy-MM-", java.util.Locale.US).format(java.util.Date())
                val key = "dismissed_${info.latestVersion}_$today"

                // 频timesLimit: 同Version numberwhen天被Close就not弹
                if (prefs.getBoolean(key, false)) return@launch

                // alreadynextloadshouldVersion → 直接弹InstallConfirm
                if (updater.hasnextloadedUpdate() && updater.getnextloadedVersion() == info.latestVersion) {
                    showInstallDialog(updater, info, prefs, key)
                    return@launch
                }

                // 没nextloadover → backgroundAutonextload
                val sizeStr = if (info.fileSize > 0) "%.1f MB".format(info.fileSize / 1024.0 / 1024.0) else ""
                val message = buildString {
                    append("discovernewVersion v${info.latestVersion}")
                    if (sizeStr.isnotEmpty()) append("($sizeStr)")
                    append("\nwhenFrontVersion v${info.currentVersion}\n")
                    if (!info.releasenotes.isNullorEmpty()) {
                        append("\n${info.releasenotes.take(200)}")
                    }
                }

                androidx.appcompat.app.AlertDialog.Builder(this@MainActivityCompose)
                    .setTitle("discovernewVersion")
                    .setMessage(message)
                    .setPositivebutton("backgroundnextload") { _, _ ->
                        // backgroundnextload, nextload完弹Install
                        lifecycleScope.launch {
                            val success = updater.downloadUpdate(info.downloadUrl, info.latestVersion)
                            if (success) {
                                showInstallDialog(updater, info, prefs, key)
                            }
                        }
                    }
                    .setNegativebutton("稍back再说") { _, _ ->
                        prefs.edit().putBoolean(key, true).app()
                    }
                    .setOncancelListener {
                        prefs.edit().putBoolean(key, true).app()
                    }
                    .show()
            } catch (_: exception) {
                // Silent — don't interrupt user
            }
        }
    }

    /** popupConfirmInstallalreadynextload APK */
    private fun showInstallDialog(
        updater: com.xiaomo.androidforclaw.updater.AppUpdater,
        info: com.xiaomo.androidforclaw.updater.AppUpdater.UpdateInfo,
        prefs: android.content.SharedPreferences,
        key: String
    ) {
        if (isFinishing) return
        androidx.appcompat.app.AlertDialog.Builder(this@MainActivityCompose)
            .setTitle("UpdatealreadyReady")
            .setMessage("v${info.latestVersion} alreadynextloadComplete, whetherInstall?")
            .setPositivebutton("Install") { _, _ ->
                updater.installUpdate()
            }
            .setNegativebutton("稍back") { _, _ ->
                prefs.edit().putBoolean(key, true).app()
            }
            .setOncancelListener {
                prefs.edit().putBoolean(key, true).app()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        // note: sessionFloatWindow lifecycle is now managed by ActivityLifecycleCallbacks
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterChatBroadcastReceiver()
        permissionChangedReceiver?.let { try { unregisterReceiver(it) } catch (_: exception) {} }
    }

    /**
     * Register Chat Broadcast Receiver
     *
     * note: uses RECEIVER_EXPORTED to support ADB testing
     */
    private fun registerChatBroadcastReceiver() {
        chatBroadcastReceiver = ChatBroadcastReceiver { message ->
            Log.d(TAG, "[MSG] [BroadcastReceiver] Received message: $message")
            // Send to agent via Viewmodel
            openClawViewmodel.sendChat(message = message, thinking = "", attachments = emptyList())
            Log.d(TAG, "[OK] [BroadcastReceiver] Message sent to agent: $message")
        }

        val filter = ChatBroadcastReceiver.createIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "[OK] Register ChatBroadcastReceiver (EXPORTED, SDK >= 33)")
            registerReceiver(chatBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            Log.i(TAG, "[OK] Register ChatBroadcastReceiver (SDK < 33)")
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
            } catch (e: exception) {
                // Ignore
            }
        }
    }


    /**
     * Whether the app already has file management permission.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStoragemanager()
        } else {
            true // android 10 and below use scoped storage or legacy permissions
        }
    }

    /**
     * Request Termux RUN_COMMAND permission if Termux is installed and permission not yet granted.
     * Shows an explanation dialog before requesting.
     */
    private fun requestTermuxPermissionifneeded() {
        val termuxPermission = "com.termux.permission.RUN_COMMAND"
        val termuxInstalled = try {
            packagemanager.getPackageInfo("com.termux", 0)
            true
        } catch (_: exception) {
            false
        }
        if (!termuxInstalled) return
        if (checkSelfPermission(termuxPermission) == android.content.pm.Packagemanager.PERMISSION_GRANTED) return

        // 先弹illustrateConversation框, 再appPermission
        android.app.AlertDialog.Builder(this)
            .setTitle("Termux 命令executionPermission")
            .setMessage(
                "forClaw need「Termux 命令execution」PermissioncomeimplementationbynextFeature: \n\n" +
                "• 让 AI in手机终端中execution命令(such asInstall软件、Run脚本)\n" +
                "• AutoStart Termux SSH service, NoneneedManualAction\n" +
                "• when SSH KeylosehourAutoFixConnect\n\n" +
                "thisPermission仅用于and Termux 通信, notwillaccess您Its他Data. \n\n" +
                "click「agree」back, 系统will弹出PermissionRequest, pleasechoose「允许」. "
            )
            .setcancelable(true)
            .setPositivebutton("agree") { _, _ ->
                Log.i(TAG, "Requesting Termux RUN_COMMAND permission...")
                requestPermissions(arrayOf(termuxPermission), 1002)
            }
            .setNegativebutton("暂not") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Launch modelSetupActivity if first run and no API key configured.
     * must be called only after storage permission is granted.
     */
    private fun launchmodelSetupifneeded() {
        if (modelSetupActivity.isneeded(this)) {
            Log.i(TAG, "[WRENCH] first start, Open模型configsteer...")
            startActivity(Intent(this, modelSetupActivity::class.java))
        }
    }

    /**
     * Check and request file management permission.
     * Shows an in-app dialog if permission is not granted.
     */
    private fun checkandRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStoragemanager()) {
                Log.i(TAG, "File management permission not granted, showing dialog...")
                showStoragePermissionDialog()
            } else {
                Log.i(TAG, "[OK] File management permission granted")
            }
        } else {
            Log.i(TAG, "android 10 and below, using traditional storage permissions")
        }
    }

    private fun showStoragePermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(com.xiaomo.androidforclaw.R.string.disclosure_storage_title))
            .setMessage(getString(com.xiaomo.androidforclaw.R.string.disclosure_storage_message))
            .setcancelable(false)
            .setPositivebutton(getString(com.xiaomo.androidforclaw.R.string.disclosure_agree)) { _, _ ->
                openStoragePermissionSettings()
            }
            .setNegativebutton(getString(com.xiaomo.androidforclaw.R.string.disclosure_exit)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun openStoragePermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityforresult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
        } catch (e: exception) {
            Log.e(TAG, "cannot open file management permission settings page", e)
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityforresult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
            } catch (e2: exception) {
                Log.e(TAG, "cannot open file management permission settings", e2)
                Toast.makeText(this, "cannotOpenPermissionSettings, pleaseManualAuthorize", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityresult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityresult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStoragemanager()) {
                    Log.i(TAG, "[OK] File management permission granted")
                    // Launch model setup wizard before recreate if needed
                    launchmodelSetupifneeded()
                    // Restart to app — recreate so config/sessions init properly
                    recreate()
                } else {
                    Log.w(TAG, "[WARN] File management permission still not granted")
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
        properties = DialogProperties(dismissOnbackPress = false, dismissOnClickoutside = false),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.paing(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "servicecount款and隐私政策",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = "欢迎use forClaw!inStart之Front, please阅读并agree我们servicecount款and隐私政策. ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Clickable links
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Textbutton(onClick = onOpenPrivacy) {
                        Text("View隐私政策 →", fontSize = 14.sp)
                    }
                    Textbutton(onClick = onOpenTerms) {
                        Text("ViewuserProtocol →", fontSize = 14.sp)
                    }
                }

                Text(
                    text = "click「agree」that isTable示您already阅读并agreebyUpcount款. ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    Textbutton(onClick = onDecline) {
                        Text("notagree")
                    }
                    button(onClick = onAccept) {
                        Text("agree")
                    }
                }
            }
        }
    }
}
