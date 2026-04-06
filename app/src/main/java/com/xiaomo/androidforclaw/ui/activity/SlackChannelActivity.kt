/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/types.slack.ts  (SlackAccountconfig, Slackconfig)
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
import com.xiaomo.androidforclaw.config.Slackchannelconfig
import com.xiaomo.androidforclaw.ui.compose.channelmodelPicker
import kotlinx.coroutines.launch

class SlackchannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SlackchannelScreen(onback = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlackchannelScreen(
    onback: () -> Unit,
    context: android.content.context = androidx.compose.ui.platform.Localcontext.current
) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { configLoader(context) }

    val openClawconfig = remember { configLoader.loadOpenClawconfig() }
    val savedconfig = remember { openClawconfig.channels.slack }

    var enabled by remember { mutableStateOf(savedconfig?.enabled ?: false) }
    var botToken by remember { mutableStateOf(savedconfig?.botToken ?: "") }
    var appToken by remember { mutableStateOf(savedconfig?.appToken ?: "") }
    var signingSecret by remember { mutableStateOf(savedconfig?.signingSecret ?: "") }
    var mode by remember { mutableStateOf(savedconfig?.mode ?: "socket") }
    var dmPolicy by remember { mutableStateOf(savedconfig?.dmPolicy ?: "open") }
    var groupPolicy by remember { mutableStateOf(savedconfig?.groupPolicy ?: "open") }
    var requireMention by remember { mutableStateOf(savedconfig?.requireMention ?: true) }
    var historyLimitText by remember { mutableStateOf(savedconfig?.historyLimit?.toString() ?: "") }
    var streaming by remember { mutableStateOf(savedconfig?.streaming ?: "partial") }
    var model by remember { mutableStateOf(savedconfig?.model) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.slack_channel_title)) },
                navigationIcon = {
                    Iconbutton(onClick = onback) {
                        Icon(Icons.Filled.Arrowback, "Return")
                    }
                },
                actions = {
                    Textbutton(
                        onClick = {
                            scope.launch {
                                val currentconfig = configLoader.loadOpenClawconfig()
                                val updated = (currentconfig.channels.slack ?: Slackchannelconfig()).copy(
                                    enabled = enabled,
                                    botToken = botToken,
                                    appToken = appToken.takeif { it.isnotBlank() },
                                    signingSecret = signingSecret.takeif { it.isnotBlank() },
                                    mode = mode,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention,
                                    historyLimit = historyLimitText.tointorNull(),
                                    streaming = streaming,
                                    model = model?.takeif { it.isnotBlank() }
                                )
                                configLoader.saveOpenClawconfig(
                                    currentconfig.copy(channels = currentconfig.channels.copy(slack = updated))
                                )
                                showSaveSuccess = true
                            }
                        }
                    ) { Text("Save") }
                }
            )
        }
    ) { paingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .paing(paingValues)
                .paing(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Enable ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Spacebetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Slack", style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Divider()

            // ── Connectschema ──
            Text("Connectschema", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("socket" to "Socket Mode(recommend)", "http" to "HTTP Mode").forEach { (value, label) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = { mode = value },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                text = if (mode == "socket")
                    "Socket Mode: need Bot Token + App-Level Token, Noneneed公网 IP"
                else
                    "HTTP Mode: need Bot Token + Signing Secret + 公网 Webhook URL",
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
                    placeholder = { Text("from Slack App → Basic Info → App-Level Token Get") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = signingSecret,
                    onValueChange = { signingSecret = it },
                    label = { Text("Signing Secret") },
                    placeholder = { Text("from Slack App → Basic Info → Signing Secret Get") },
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
                horizontalArrangement = Arrangement.Spacebetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("群聊need @mentions")
                Switch(checked = requireMention, onCheckedChange = { requireMention = it })
            }

            Divider()

            // ── History Limit ──
            OutlinedTextField(
                value = historyLimitText,
                onValueChange = { historyLimitText = it.filter { c -> c.isDigit() } },
                label = { Text("历史Messagecount数Limit(Optional)") },
                placeholder = { Text("留Null = notLimit, such as 50") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ── Streaming ──
            Text("流式return复schema", style = MaterialTheme.typography.titleSmall)
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

            // ── model Picker ──
            channelmodelPicker(
                config = openClawconfig,
                selected = model,
                onSelected = { model = it },
                modifier = Modifier.fillMaxWidth()
            )

            if (showSaveSuccess) {
                Spacer(Modifier.height(4.dp))
                Text("[OK] configalreadySave", color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "configSavebackneedRestartapp生效. \n详细Document参见 OpenClaw Slack 接入指南. ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
