/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/config/types.signal.ts  (SignalAccountconfig, Signalconfig)
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
import com.xiaomo.androidforclaw.config.Signalchannelconfig
import com.xiaomo.androidforclaw.ui.compose.channelmodelPicker
import kotlinx.coroutines.launch

class SignalchannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SignalchannelScreen(onback = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalchannelScreen(
    onback: () -> Unit,
    context: android.content.context = androidx.compose.ui.platform.Localcontext.current
) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { configLoader(context) }

    val openClawconfig = remember { configLoader.loadOpenClawconfig() }
    val savedconfig = remember { openClawconfig.channels.signal }

    var enabled by remember { mutableStateOf(savedconfig?.enabled ?: false) }
    var phoneNumber by remember { mutableStateOf(savedconfig?.phoneNumber ?: "") }
    var httpUrl by remember { mutableStateOf(savedconfig?.httpUrl ?: "") }
    var httpPortText by remember { mutableStateOf(savedconfig?.httpPort?.toString() ?: "8080") }
    var dmPolicy by remember { mutableStateOf(savedconfig?.dmPolicy ?: "open") }
    var groupPolicy by remember { mutableStateOf(savedconfig?.groupPolicy ?: "open") }
    var requireMention by remember { mutableStateOf(savedconfig?.requireMention ?: true) }
    var historyLimitText by remember { mutableStateOf(savedconfig?.historyLimit?.toString() ?: "") }
    var model by remember { mutableStateOf(savedconfig?.model) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.signal_channel_title)) },
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
                                val updated = (currentconfig.channels.signal ?: Signalchannelconfig()).copy(
                                    enabled = enabled,
                                    phoneNumber = phoneNumber,
                                    httpUrl = httpUrl.takeif { it.isnotBlank() },
                                    httpPort = httpPortText.tointorNull() ?: 8080,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention,
                                    historyLimit = historyLimitText.tointorNull(),
                                    model = model?.takeif { it.isnotBlank() }
                                )
                                configLoader.saveOpenClawconfig(
                                    currentconfig.copy(channels = currentconfig.channels.copy(signal = updated))
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
                Text("Enable Signal", style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Divider()

            // ── Account (E.164 phone) ──
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                                 label = { Text("Phone Number (E.164 format, correct for signal-cli account)") },
                placeholder = { Text("+8613800138000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )

            // ── signal-cli daemon ──
            Text("signal-cli Daemon Connect", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = httpUrl,
                onValueChange = { httpUrl = it },
                label = { Text("Daemon URL(Optional, 优先use)") },
                placeholder = { Text("http://127.0.0.1:8080  Leave empty to use host+port below") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = httpPortText,
                onValueChange = { httpPortText = it.filter { c -> c.isDigit() } },
                label = { Text("Daemon Port(Default 8080)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

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
                 Text("Group chat requires @mentions")
                Switch(checked = requireMention, onCheckedChange = { requireMention = it })
            }

            Divider()

            // ── History Limit ──
            OutlinedTextField(
                value = historyLimitText,
                onValueChange = { historyLimitText = it.filter { c -> c.isDigit() } },
                                 label = { Text("History Message Count Limit (Optional)") },
                                 placeholder = { Text("Set Null = no limit, such as 50") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

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
                text = "configSavebackneedRestartapp生效. \nSignal 接入needinmain机UpRun signal-cli daemon. ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
