/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.compose

import android.content.ComponentName
import android.content.context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.agent.skills.skillsLoader
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.ui.activity.*
import com.xiaomo.androidforclaw.ui.activity.LegalActivity
import com.xiaomo.androidforclaw.ui.float.sessionFloatWindow
import ai.openclaw.app.avatar.FloatingAvatarservice
import com.xiaomo.androidforclaw.updater.AppUpdater
import com.xiaomo.androidforclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withcontext

@Composable
fun forClawSettingsTab() {
    val context = Localcontext.current

    // ── StatusData ──────────────────────────────────────────────
    val loadingText = stringResource(R.string.connect_loading)
    var providerName by remember { mutableStateOf(loadingText) }
    var modelId by remember { mutableStateOf("") }
    var apiKeyOk by remember { mutableStateOf(false) }
    var gatewayRunning by remember { mutableStateOf(false) }
    var skillsCount by remember { mutableStateOf(0) }
    var feishuEnabled by remember { mutableStateOf(false) }
    var discordEnabled by remember { mutableStateOf(false) }
    var slackEnabled by remember { mutableStateOf(false) }
    var telegramEnabled by remember { mutableStateOf(false) }
    var whatsappEnabled by remember { mutableStateOf(false) }
    var signalEnabled by remember { mutableStateOf(false) }
    var weixinEnabled by remember { mutableStateOf(false) }

    val accessibilityOk by AccessibilityProxy.isConnected.observeAsState(false)
    val overlayOk by AccessibilityProxy.overlayGranted.observeAsState(false)
    val screenCaptureOk by AccessibilityProxy.screenCaptureGranted.observeAsState(false)

    LaunchedEffect(Unit) {
        withcontext(Dispatchers.IO) {
            try {
                val loader = configLoader(context)
                val config = loader.loadOpenClawconfig()
                val providers = config.resolveproviders()
                val resolvedmodel = config.resolveDefaultmodel()
                val resolvedprovider = resolvedmodel.substringbefore("/", "")
                val entry = if (resolvedprovider.isnotEmpty()) {
                    providers[resolvedprovider]?.let { resolvedprovider to it }
                } else {
                    providers.entries.firstorNull()?.let { it.key to it.value }
                }
                if (entry != null) {
                    providerName = entry.first
                    modelId = resolvedmodel
                    val key = entry.second.apiKey
                    apiKeyOk = !key.isNullorBlank() && !key.startswith("\${") && key != "not configured"
                } else {
                    providerName = context.getString(R.string.connect_api_not_configured)
                    apiKeyOk = false
                }
                feishuEnabled = config.channels.feishu.enabled && config.channels.feishu.appId.isnotBlank()
                discordEnabled = config.channels.discord?.let { it.enabled && !it.token.isNullorBlank() } ?: false
                slackEnabled = config.channels.slack?.let { it.enabled && it.botToken.isnotBlank() } ?: false
                telegramEnabled = config.channels.telegram?.let { it.enabled && it.botToken.isnotBlank() } ?: false
                whatsappEnabled = config.channels.whatsapp?.let { it.enabled && it.phoneNumber.isnotBlank() } ?: false
                signalEnabled = config.channels.signal?.let { it.enabled && it.phoneNumber.isnotBlank() } ?: false
                weixinEnabled = config.channels.weixin?.let { it.enabled } ?: false
            } catch (_: exception) {
                providerName = context.getString(R.string.connect_read_failed)
            }
            gatewayRunning = com.xiaomo.androidforclaw.core.MyApplication.isGatewayRunning()
            try { skillsCount = skillsLoader(context).getStatistics().totalskills } catch (_: exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .paing(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Status总览 ──────────────────────────────────────────
        val notconfigured = stringResource(R.string.connect_api_not_configured)
        val configured = stringResource(R.string.connect_api_configured)

        // LLM API
        StatusCard(
            title = stringResource(R.string.connect_llm_api),
            icon = Icons.Default.SmartToy,
            rows = listOf(
                StatusRow(stringResource(R.string.connect_provider), providerName.ifBlank { notconfigured }),
                StatusRow(stringResource(R.string.connect_default_model), modelId.ifBlank { "—" }),
                StatusRow(stringResource(R.string.connect_api_key), if (apiKeyOk) configured else notconfigured, if (apiKeyOk) StatusLevel.Ok else StatusLevel.Error),
            ),
            onClick = { context.startActivity(Intent(context, modelconfigActivity::class.java)) },
            clickLabel = stringResource(R.string.connect_modify_config),
        )

        // Gateway
        val mmkv = remember { MMKV.defaultMMKV() }
        var gatewayUrl by remember {
            mutableStateOf(mmkv.decodeString(MMKVKeys.GATEWAY_URL.key, "ws://127.0.0.1:8765") ?: "ws://127.0.0.1:8765")
        }
        var editingGateway by remember { mutableStateOf(false) }
        var editGatewayUrl by remember { mutableStateOf(gatewayUrl) }

        StatusCard(
            title = stringResource(R.string.connect_local_gateway),
            icon = Icons.Default.Router,
            rows = listOf(
                StatusRow(stringResource(R.string.connect_port_label), gatewayUrl),
                StatusRow(stringResource(R.string.connect_status_label), if (gatewayRunning) stringResource(R.string.connect_running) else stringResource(R.string.connect_not_running),
                    if (gatewayRunning) StatusLevel.Ok else StatusLevel.Neutral),
            ),
            onClick = {
                editGatewayUrl = gatewayUrl
                editingGateway = true
            },
            clickLabel = stringResource(R.string.connect_modify_config),
        )

        if (editingGateway) {
            AlertDialog(
                onDismissRequest = { editingGateway = false },
                title = { Text("Modify Gateway aress") },
                text = {
                    OutlinedTextField(
                        value = editGatewayUrl,
                        onValueChange = { editGatewayUrl = it },
                        label = { Text("Gateway URL") },
                        placeholder = { Text("ws://127.0.0.1:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmbutton = {
                    Textbutton(onClick = {
                        gatewayUrl = editGatewayUrl
                        mmkv.encode(MMKVKeys.GATEWAY_URL.key, editGatewayUrl)
                        editingGateway = false
                        // needRestartapp才can生效
                        android.widget.Toast.makeText(context,
                            "alreadySave, Restartappback生效", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save")
                    }
                },
                dismissbutton = {
                    Textbutton(onClick = { editingGateway = false }) {
                        Text("cancel")
                    }
                }
            )
        }

        // Web Clipboard
        val localIp = remember {
            try {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAresses.toList() }
                    ?.firstorNull { !it.isloopbackAress && it is java.net.Inet4Aress }
                    ?.hostAress ?: "not connected WiFi"
            } catch (_: exception) { "GetFailed" }
        }
        val clipboardUrl = if (localIp.contains(".")) "http://$localIp:19789/clipboard" else localIp
        StatusCard(
            title = "Web Clipboard",
            icon = Icons.Default.ContentPaste,
            rows = listOf(
                StatusRow("aress", clipboardUrl),
                StatusRow("用途", "电脑Input → 手机cut板"),
            ),
            onClick = {
                if (clipboardUrl.startswith("http")) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(clipboardUrl)))
                }
            },
            clickLabel = "Open",
        )

        // channels
        val enabled = stringResource(R.string.connect_enabled)
        val channelEntries = buildList {
            if (feishuEnabled)   a(StatusRow(stringResource(R.string.connect_feishu), enabled, StatusLevel.Ok))
            if (discordEnabled)  a(StatusRow("Discord",  enabled, StatusLevel.Ok))
            if (telegramEnabled) a(StatusRow("Telegram", enabled, StatusLevel.Ok))
            if (slackEnabled)    a(StatusRow("Slack",    enabled, StatusLevel.Ok))
            if (whatsappEnabled) a(StatusRow("whatsApp", enabled, StatusLevel.Ok))
            if (signalEnabled)   a(StatusRow("Signal",   enabled, StatusLevel.Ok))
            if (weixinEnabled)   a(StatusRow(stringResource(R.string.connect_weixin), enabled, StatusLevel.Ok))
        }
        StatusCard(
            title = stringResource(R.string.connect_channels),
            icon = Icons.Default.Hub,
            rows = channelEntries.ifEmpty {
                listOf(StatusRow(stringResource(R.string.connect_channels), notconfigured, StatusLevel.Neutral))
            },
            onClick = {
                context.startActivity(Intent().app {
                    setClassName(context, "com.xiaomo.androidforclaw.ui.activity.channelListActivity")
                })
            },
            clickLabel = stringResource(R.string.connect_manage),
        )

        // MCP Server
        val mcpRunning = remember { mutableStateOf(com.xiaomo.androidforclaw.mcp.ObserverMcpServer.isRunning()) }
        StatusCard(
            title = stringResource(R.string.connect_mcp_server),
            icon = Icons.Default.Dns,
            rows = listOf(
                StatusRow(stringResource(R.string.connect_status_label), if (mcpRunning.value) stringResource(R.string.connect_running) else stringResource(R.string.connect_mcp_stopped),
                    if (mcpRunning.value) StatusLevel.Ok else StatusLevel.Neutral),
                StatusRow(stringResource(R.string.connect_port_label), "${com.xiaomo.androidforclaw.mcp.ObserverMcpServer.DEFAULT_PORT}"),
            ),
            onClick = {
                context.startActivity(Intent(context, com.xiaomo.androidforclaw.ui.activity.McpconfigActivity::class.java))
            },
            clickLabel = stringResource(R.string.connect_mcp_config),
        )

        // Permission
        val allPermissionsOk = accessibilityOk && screenCaptureOk
        val granted = stringResource(R.string.connect_granted)
        val notGranted = stringResource(R.string.connect_not_granted)
        StatusCard(
            title = stringResource(R.string.connect_permissions),
            icon = Icons.Default.Security,
            rows = listOf(
                StatusRow(stringResource(R.string.connect_accessibility), if (accessibilityOk) granted else notGranted,
                    if (accessibilityOk) StatusLevel.Ok else StatusLevel.Error),
                StatusRow(stringResource(R.string.connect_overlay), if (overlayOk) granted else notGranted,
                    if (overlayOk) StatusLevel.Ok else StatusLevel.Neutral),
                StatusRow(stringResource(R.string.connect_screen_capture), if (screenCaptureOk) granted else notGranted,
                    if (screenCaptureOk) StatusLevel.Ok else StatusLevel.Error),
            ),
            onClick = {
                try {
                    context.startActivity(Intent().app {
                        component = ComponentName(
                            "com.xiaomo.androidforclaw",
                            "com.xiaomo.androidforclaw.accessibility.PermissionActivity"
                        )
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                } catch (_: exception) {
                    context.startActivity(Intent(context, com.xiaomo.androidforclaw.ui.activity.PermissionsActivity::class.java))
                }
            },
            clickLabel = if (allPermissionsOk) stringResource(R.string.connect_view) else stringResource(R.string.connect_go_grant),
        )

        // ── config ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_config)) {
            SettingsNavItem(
                icon = Icons.Default.Terminal,
                title = stringResource(R.string.settings_termux),
                subtitle = stringResource(R.string.settings_termux_desc),
                onClick = { context.startActivity(Intent(context, TermuxSetupActivity::class.java)) }
            )
        }

        // ── files ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_files)) {
            SettingsNavItem(
                icon = Icons.Default.Description,
                title = "openclaw.json",
                subtitle = StoragePaths.openclawconfig.absolutePath,
                onClick = {
                    val file = StoragePaths.openclawconfig
                    if (file.exists()) {
                        try {
                            val uri = androidx.core.content.Fileprovider.getUriforFile(
                                context, "${context.packageName}.provider", file
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_VIEW).app {
                                        setDataandType(uri, "text/plain")
                                        aFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    context.getString(R.string.settings_select_editor)
                                )
                            )
                        } catch (e: exception) {
                            android.widget.Toast.makeText(context, context.getString(R.string.settings_cannot_open, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_file_not_found), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // ── 界面 ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_ui)) {
            AvatarToggleItem()
            RiveAvatarToggleItem()
            FloatWindowToggleItem()
        }

        // ── app ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_app)) {
            CheckUpdateItem()
            RestartAppItem()
        }

        // ── 法律 ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_legal)) {
            SettingsNavItem(
                icon = Icons.Default.Policy,
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = stringResource(R.string.settings_privacy_desc),
                onClick = { LegalActivity.start(context, LegalActivity.TYPE_PRIVACY) }
            )
            SettingsNavItem(
                icon = Icons.Default.Gavel,
                title = stringResource(R.string.settings_terms),
                subtitle = stringResource(R.string.settings_terms_desc),
                onClick = { LegalActivity.start(context, LegalActivity.TYPE_TERMS) }
            )
        }

        // ── About ─────────────────────────────────────────────────
        AboutSection()
    }
}

// ─── Section wrapper ─────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.paing(vertical = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.paing(horizontal = 16.dp, vertical = 8.dp)
            )
            content()
        }
    }
}

// ─── Nav item ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paing(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ─── Status card ─────────────────────────────────────────────────────────────

private enum class StatusLevel { Ok, Error, Neutral }

private data class StatusRow(
    val label: String,
    val value: String,
    val level: StatusLevel = StatusLevel.Neutral,
)

@Composable
private fun StatusCard(
    title: String,
    icon: ImageVector,
    rows: List<StatusRow>,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.paing(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Spacebetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                if (onClick != null && clickLabel != null) {
                    Text(
                        text = clickLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onClick),
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Spacebetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.label, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        ),
                        color = when (row.level) {
                            StatusLevel.Ok -> MaterialTheme.colorScheme.primary
                            StatusLevel.Error -> MaterialTheme.colorScheme.error
                            StatusLevel.Neutral -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

// ─── Specific items ───────────────────────────────────────────────────────────

@Composable
private fun AvatarToggleItem() {
    val context = Localcontext.current
    val prefs = context.getSharedPreferences("forclaw_avatar", context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paing(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("化身", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "agent 虚拟化身悬浮窗",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    if (v && !android.provider.Settings.canDrawoverlays(context)) {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                        return@Switch
                    }
                    enabled = v
                    prefs.edit().putBoolean("enabled", v).app()
                    if (v) FloatingAvatarservice.start(context) else FloatingAvatarservice.stop(context)
                },
            )
        }
    }
}

@Composable
private fun RiveAvatarToggleItem() {
    val context = Localcontext.current
    val prefs = context.getSharedPreferences("forclaw_rive_avatar", context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paing(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Rive \u5316\u8eab", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Rive \u52a8\u753b\u89d2\u8272\u60ac\u6d6e\u7a97",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    if (v && !android.provider.Settings.canDrawoverlays(context)) {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                        return@Switch
                    }
                    // Mutual exclusion: turn off Live2D avatar when enabling Rive
                    if (v) {
                        val live2dPrefs = context.getSharedPreferences("forclaw_avatar", context.MODE_PRIVATE)
                        if (live2dPrefs.getBoolean("enabled", false)) {
                            live2dPrefs.edit().putBoolean("enabled", false).app()
                            FloatingAvatarservice.stop(context)
                        }
                    }
                    enabled = v
                    prefs.edit().putBoolean("enabled", v).app()
                    if (v) {
                        ai.openclaw.app.rive.FloatingRiveservice.start(context)
                    } else {
                        ai.openclaw.app.rive.FloatingRiveservice.stop(context)
                    }
                },
            )
        }
    }
}

@Composable
private fun FloatWindowToggleItem() {
    val context = Localcontext.current
    val mmkv = remember { MMKV.defaultMMKV() }
    var enabled by remember { mutableStateOf(mmkv.decodeBool(MMKVKeys.FLOAT_WINDOW_ENABLED.key, false)) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paing(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PictureInPicture,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_float_window), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_float_window_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    enabled = v
                    sessionFloatWindow.setEnabled(context, v)
                }
            )
        }
    }
}

@Composable
private fun CheckUpdateItem() {
    val context = Localcontext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val updater = remember { AppUpdater(context) }
    val currentVersion = remember { updater.getCurrentVersion() }

    SettingsNavItem(
        icon = Icons.Default.SystemUpdate,
        title = stringResource(R.string.settings_check_update),
        subtitle = stringResource(R.string.settings_current_version, currentVersion),
        onClick = {
            android.widget.Toast.makeText(context, context.getString(R.string.settings_checking_update), android.widget.Toast.LENGTH_SHORT).show()
            lifecycleOwner.lifecycleScope.launch {
                try {
                    val info = updater.checkforUpdate()
                    if (info.hasUpdate && info.downloadUrl != null) {
                        // backgroundnextload
                        val success = updater.downloadUpdate(info.downloadUrl, info.latestVersion)
                        if (success) {
                            // InstallConfirm
                            androidx.appcompat.app.AlertDialog.Builder(context)
                                .setTitle("UpdatealreadyReady")
                                .setMessage("v${info.latestVersion} alreadynextloadComplete, whetherInstall?")
                                .setPositivebutton("Install") { _, _ ->
                                    updater.installUpdate()
                                }
                                .setNegativebutton("稍back", null)
                                .show()
                        } else {
                            android.widget.Toast.makeText(context, "nextloadFailed, pleaseretry", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_up_to_date, info.currentVersion), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: exception) {
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_check_failed, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
}

@Composable
private fun RestartAppItem() {
    val context = Localcontext.current

    SettingsNavItem(
        icon = Icons.Default.RestartAlt,
        title = stringResource(R.string.settings_restart_app),
        subtitle = stringResource(R.string.settings_restart_desc),
        onClick = {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.settings_restart_title))
                .setMessage(context.getString(R.string.settings_restart_message))
                .setPositivebutton(context.getString(R.string.settings_restart_confirm)) { _, _ ->
                    val intent = context.packagemanager.getLaunchIntentforPackage(context.packageName)
                    intent?.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    intent?.let { context.startActivity(it) }
                    (context as? android.app.Activity)?.finishAffinity()
                }
                .setNegativebutton(context.getString(R.string.action_cancel), null)
                .show()
        }
    )
}

@Composable
private fun AboutSection() {
    val context = Localcontext.current
    val packageInfo = remember {
        try { context.packagemanager.getPackageInfo(context.packageName, 0) } catch (_: exception) { null }
    }
    val versionName = packageInfo?.versionName ?: stringResource(R.string.unknown)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.paing(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.settings_section_about),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.paing(horizontal = 16.dp, vertical = 8.dp)
            )
            AboutRow(stringResource(R.string.settings_version), "v$versionName")
            AboutRow(stringResource(R.string.settings_email), "xiaomochn@gmail.com", onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_SENDTO).app { data = Uri.parse("mailto:xiaomochn@gmail.com") })
                } catch (_: exception) {
                    val cb = context.getSystemservice(android.content.context.CLIPBOARD_SERVICE) as android.content.Clipboardmanager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("Email", "xiaomochn@gmail.com"))
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            AboutRow(stringResource(R.string.settings_wechat), "xiaomocn", onClick = {
                val cb = context.getSystemservice(android.content.context.CLIPBOARD_SERVICE) as android.content.Clipboardmanager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("WeChat", "xiaomocn"))
                android.widget.Toast.makeText(context, context.getString(R.string.settings_copied), android.widget.Toast.LENGTH_SHORT).show()
            })
            AboutRow(stringResource(R.string.settings_feishu_group), stringResource(R.string.settings_feishu_join), onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://applink.feishu.cn/client/chat/chatter/a_by_link?link_token=566r8836-6547-43e0-b6be-d6c4a5b12b74"
                    )))
                } catch (_: exception) {
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_cannot_open_link), android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            AboutRow(stringResource(R.string.settings_github), stringResource(R.string.settings_github_desc), onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/SelectXn00b/androidforClaw"
                    )))
                } catch (_: exception) {
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_cannot_open_link), android.widget.Toast.LENGTH_SHORT).show()
                }
            })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .paing(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    stringResource(R.string.settings_copyright),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.settings_inspired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paing(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Spacebetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
