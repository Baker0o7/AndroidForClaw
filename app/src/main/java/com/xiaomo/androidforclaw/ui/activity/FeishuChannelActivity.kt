/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import android.view.Windowmanager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.configLoader
import kotlinx.coroutines.launch

/**
 * Feishu channel config page
 * Configures clawbot-feishu config items
 */
class FeishuChannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable screenshot
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

    // Load config
    val openClawConfig = remember { configLoader.loadOpenClawConfig() }
    val savedConfig = remember { openClawConfig.channels.feishu }

    // Status variables (match clawbot-feishu config)
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
                                // Read current full config
                                val currentConfig = configLoader.loadOpenClawConfig()

                                // Update feishu config
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

                                // Update full config
                                val updatedChannelsConfig = currentConfig.channels.copy(
                                    feishu = updatedFeishuConfig
                                )
                                val updatedConfig = currentConfig.copy(
                                    channels = updatedChannelsConfig
                                )

                                // Save to openclaw.json
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
                    Text("Config saved")
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
            // Enable switch
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
                            text = "Enable Feishu channel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Open back will receive Feishu messages",
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

            // Basic config
            Text(
                text = "Basic Config",
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
                text = "Private Chat Policy (DM Policy)",
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
                                        "open" -> "Accept all DMs"
                                        "pairing" -> "Need pairing to use"
                                        "allowlist" -> "Only whitelisted users"
                                        else -> "Other policies"
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

            // Group chat policy
            Text(
                text = "Group Chat Policy",
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
                                        "allowlist" -> "Only whitelisted groups"
                                        "disabled" -> "Disabled for group chats"
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
                    placeholder = { Text("One group ID per line\noc_xxxxxx") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }

            // Group chat @ mentions
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
                            text = "Group chats need @ mentions",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Open back will only respond to @ mentions",
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

            // Config file path hint
            Text(
                text = "Config saved at:\n/sdcard/.androidforclaw/openclaw.json (channels.feishu)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
