/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.ConfigLoader
import kotlinx.coroutines.launch

/**
 * 飞书 Channel Config页面
 * 对齐 clawdbot-feishu Config项
 */
class FeishuChannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 禁止截屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                FeishuChannelScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeishuChannelScreen(onBack: () -> Unit, context: android.content.Context = androidx.compose.ui.platform.LocalContext.current) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { ConfigLoader(context) }

    // LoadConfig
    val openClawConfig = remember { configLoader.loadOpenClawConfig() }
    val savedConfig = remember { openClawConfig.channels.feishu }

    // StatusVariable(对齐 clawdbot-feishu Config)
    var enabled by remember { mutableStateOf(savedConfig.enabled) }
    var appId by remember { mutableStateOf(savedConfig.appId) }
    var appSecret by remember { mutableStateOf(savedConfig.appSecret) }
    var dmPolicy by remember { mutableStateOf(savedConfig.dmPolicy) }
    var groupPolicy by remember { mutableStateOf(savedConfig.groupPolicy) }
    var requireMention by remember { mutableStateOf(savedConfig.requireMention ?: savedConfig.groupPolicy != "open") }
    var groupAllowFrom by remember { mutableStateOf(savedConfig.groupAllowFrom.joinToString("\n")) }

    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feishu_channel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Return")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                // Read当Front完整Config
                                val currentConfig = configLoader.loadOpenClawConfig()

                                // Update feishu Config
                                val updatedFeishuConfig = currentConfig.channels.feishu.copy(
                                    enabled = enabled,
                                    appId = appId,
                                    appSecret = appSecret,
                                    connectionMode = currentConfig.channels.feishu.connectionMode,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention,
                                    groupAllowFrom = groupAllowFrom.split("\n").filter { it.isNotBlank() },
                                    historyLimit = currentConfig.channels.feishu.historyLimit,
                                    dmHistoryLimit = currentConfig.channels.feishu.dmHistoryLimit
                                )

                                // Update完整Config
                                val updatedChannelsConfig = currentConfig.channels.copy(
                                    feishu = updatedFeishuConfig
                                )
                                val updatedConfig = currentConfig.copy(
                                    channels = updatedChannelsConfig
                                )

                                // Save到 openclaw.json
                                configLoader.saveOpenClawConfig(updatedConfig)

                                showSaveSuccess = true
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }
            )
        },
        snackbarHost = {
            if (showSaveSuccess) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSaveSuccess = false
                }
                Snackbar(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Config已Save")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Enabledd开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enabledd Feishu Channel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "开启BackWill receive飞书Message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }
            }

            // 基础Config
            Text(
                text = "基础Config",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("App ID") },
                placeholder = { Text("cli_xxxxxx") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("App Secret") },
                placeholder = { Text("Input App Secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // DM Policy
            Text(
                text = "私聊Policy (DM Policy)",
                style = MaterialTheme.typography.titleMedium
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "pairing", "allowlist").forEach { policy ->
                    FilterChip(
                        selected = dmPolicy == policy,
                        onClick = { dmPolicy = policy },
                        label = {
                            Column {
                                Text(policy.replaceFirstChar { it.uppercase() })
                                Text(
                                    text = when (policy) {
                                        "open" -> "acceptAll私聊"
                                        "pairing" -> "Need配对Back才能use"
                                        "allowlist" -> "仅白名单User"
                                        else -> "Its他Policy"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 群聊Policy
            Text(
                text = "群聊Policy (Group Policy)",
                style = MaterialTheme.typography.titleMedium
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "allowlist", "disabled").forEach { policy ->
                    FilterChip(
                        selected = groupPolicy == policy,
                        onClick = { groupPolicy = policy },
                        label = {
                            Column {
                                Text(policy.replaceFirstChar { it.uppercase() })
                                Text(
                                    text = when (policy) {
                                        "open" -> "Accept all group chats"
                                        "allowlist" -> "仅白名单群聊"
                                        "disabled" -> "Disabled群聊"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Group chat whitelist
            if (groupPolicy == "allowlist") {
                OutlinedTextField(
                    value = groupAllowFrom,
                    onValueChange = { groupAllowFrom = it },
                    label = { Text("Group chat whitelist") },
                    placeholder = { Text("每Row一个群聊ID\noc_xxxxxx") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }

            // 群聊 @ 提及
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "群聊Need @ 提及",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "开启Back仅Response @ 机器人的Message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requireMention,
                        onCheckedChange = { requireMention = it }
                    )
                }
            }

            // ConfigFile pathHint
            Text(
                text = "ConfigSave在:\n/sdcard/.androidforclaw/openclaw.json (channels.feishu)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
