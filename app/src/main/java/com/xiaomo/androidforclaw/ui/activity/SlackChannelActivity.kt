/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/types.slack.ts  (SlackAccountConfig, SlackConfig)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
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
import com.xiaomo.androidforclaw.config.SlackChannelConfig
import com.xiaomo.androidforclaw.ui.compose.ChannelModelPicker
import kotlinx.coroutines.launch

class SlackChannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SlackChannelScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlackChannelScreen(
    onBack: () -> Unit,
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current
) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { ConfigLoader(context) }

    val openClawConfig = remember { configLoader.loadOpenClawConfig() }
    val savedConfig = remember { openClawConfig.channels.slack }

    var enabled by remember { mutableStateOf(savedConfig?.enabled ?: false) }
    var botToken by remember { mutableStateOf(savedConfig?.botToken ?: "") }
    var appToken by remember { mutableStateOf(savedConfig?.appToken ?: "") }
    var signingSecret by remember { mutableStateOf(savedConfig?.signingSecret ?: "") }
    var mode by remember { mutableStateOf(savedConfig?.mode ?: "socket") }
    var dmPolicy by remember { mutableStateOf(savedConfig?.dmPolicy ?: "open") }
    var groupPolicy by remember { mutableStateOf(savedConfig?.groupPolicy ?: "open") }
    var requireMention by remember { mutableStateOf(savedConfig?.requireMention ?: true) }
    var historyLimitText by remember { mutableStateOf(savedConfig?.historyLimit?.toString() ?: "") }
    var streaming by remember { mutableStateOf(savedConfig?.streaming ?: "partial") }
    var model by remember { mutableStateOf(savedConfig?.model) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.slack_channel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val currentConfig = configLoader.loadOpenClawConfig()
                                val updated = (currentConfig.channels.slack ?: SlackChannelConfig()).copy(
                                    enabled = enabled,
                                    botToken = botToken,
                                    appToken = appToken.takeIf { it.isNotBlank() },
                                    signingSecret = signingSecret.takeIf { it.isNotBlank() },
                                    mode = mode,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention,
                                    historyLimit = historyLimitText.toIntOrNull(),
                                    streaming = streaming,
                                    model = model?.takeIf { it.isNotBlank() }
                                )
                                configLoader.saveOpenClawConfig(
                                    currentConfig.copy(channels = currentConfig.channels.copy(slack = updated))
                                )
                                showSaveSuccess = true
                            }
                        }
                    ) { Text("Save") }
                }
            )
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
            // ── 启用 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Slack", style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Divider()

            // ── 连接模式 ──
            Text("Connection Mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("socket" to "Socket Mode (recommended)", "http" to "HTTP Mode").forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = { mode = value },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                text = if (mode == "socket")
                    "Socket Mode: Requires Bot Token + App-Level Token, no public IP needed"
                else
                    "HTTP Mode: Requires Bot Token + Signing Secret + public Webhook URL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── Tokens ──
            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it },
                label = { Text("Bot Token (xoxb-...)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (mode == "socket") {
                OutlinedTextField(
                    value = appToken,
                    onValueChange = { appToken = it },
                    label = { Text("App-Level Token (xapp-...)") },
                    placeholder = { Text("Get from Slack App → Basic Info → App-Level Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = signingSecret,
                    onValueChange = { signingSecret = it },
                    label = { Text("Signing Secret") },
                    placeholder = { Text("Get from Slack App → Basic Info → Signing Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Divider()

            // ── DM Policy ──
            Text(stringResource(R.string.dm_policy), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "pairing", "allowlist").forEach { policy ->
                    FilterChip(
                        selected = dmPolicy == policy,
                        onClick = { dmPolicy = policy },
                        label = { Text(policy) }
                    )
                }
            }

            // ── Group Policy ──
            Text(stringResource(R.string.group_policy), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "allowlist", "disabled").forEach { policy ->
                    FilterChip(
                        selected = groupPolicy == policy,
                        onClick = { groupPolicy = policy },
                        label = { Text(policy) }
                    )
                }
            }

            // ── Require Mention ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Require @mention in groups")
                Switch(checked = requireMention, onCheckedChange = { requireMention = it })
            }

            Divider()

            // ── History Limit ──
            OutlinedTextField(
                value = historyLimitText,
                onValueChange = { historyLimitText = it.filter { c -> c.isDigit() } },
                label = { Text("History message limit (optional)") },
                placeholder = { Text("Leave empty = unlimited, e.g. 50") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ── Streaming ──
            Text("Streaming reply mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("off", "partial", "block", "progress").forEach { value ->
                    FilterChip(
                        selected = streaming == value,
                        onClick = { streaming = value },
                        label = { Text(value) }
                    )
                }
            }

            Divider()

            // ── Model Picker ──
            ChannelModelPicker(
                config = openClawConfig,
                selected = model,
                onSelected = { model = it },
                modifier = Modifier.fillMaxWidth()
            )

            if (showSaveSuccess) {
                Spacer(Modifier.height(4.dp))
                Text("✅ Configuration saved", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Restart app after saving for changes to take effect.\nSee OpenClaw Slack integration guide for details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
