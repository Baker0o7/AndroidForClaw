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
 * 飞书 channel config页面
 * correct齐 clawdbot-feishu config项
 */
class FeishuchannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 禁止截屏
        window.setFlags(
            Windowmanager.LayoutParams.FLAG_SECURE,
            Windowmanager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                FeishuchannelScreen(
                    onback = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeishuchannelScreen(onback: () -> Unit, context: android.content.context = androidx.compose.ui.platform.Localcontext.current) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { configLoader(context) }

    // Loadconfig
    val openClawconfig = remember { configLoader.loadOpenClawconfig() }
    val savedconfig = remember { openClawconfig.channels.feishu }

    // StatusVariable(correct齐 clawdbot-feishu config)
    var enabled by remember { mutableStateOf(savedconfig.enabled) }
    var appId by remember { mutableStateOf(savedconfig.appId) }
    var appSecret by remember { mutableStateOf(savedconfig.appSecret) }
    var dmPolicy by remember { mutableStateOf(savedconfig.dmPolicy) }
    var groupPolicy by remember { mutableStateOf(savedconfig.groupPolicy) }
    var requireMention by remember { mutableStateOf(savedconfig.requireMention ?: savedconfig.groupPolicy != "open") }
    var groupAllowfrom by remember { mutableStateOf(savedconfig.groupAllowfrom.joinToString("\n")) }

    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feishu_channel_title)) },
                navigationIcon = {
                    Iconbutton(onClick = onback) {
                        Icon(Icons.Filled.Arrowback, "Return")
                    }
                },
                actions = {
                    Textbutton(
                        onClick = {
                            scope.launch {
                                // ReadwhenFront完整config
                                val currentconfig = configLoader.loadOpenClawconfig()

                                // Update feishu config
                                val updatedFeishuconfig = currentconfig.channels.feishu.copy(
                                    enabled = enabled,
                                    appId = appId,
                                    appSecret = appSecret,
                                    connectionMode = currentconfig.channels.feishu.connectionMode,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention,
                                    groupAllowfrom = groupAllowfrom.split("\n").filter { it.isnotBlank() },
                                    historyLimit = currentconfig.channels.feishu.historyLimit,
                                    dmHistoryLimit = currentconfig.channels.feishu.dmHistoryLimit
                                )

                                // Update完整config
                                val updatedchannelsconfig = currentconfig.channels.copy(
                                    feishu = updatedFeishuconfig
                                )
                                val updatedconfig = currentconfig.copy(
                                    channels = updatedchannelsconfig
                                )

                                // Saveto openclaw.json
                                configLoader.saveOpenClawconfig(updatedconfig)

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
                    modifier = Modifier.paing(16.dp)
                ) {
                    Text("configalreadySave")
                }
            }
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
            // Enable开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paing(16.dp),
                    horizontalArrangement = Arrangement.Spacebetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Feishu channel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "openbackwill receive飞书Message",
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

            // 基础config
            Text(
                text = "基础config",
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
                                Text(policy.replacefirstChar { it.uppercase() })
                                Text(
                                    text = when (policy) {
                                        "open" -> "acceptAll私聊"
                                        "pairing" -> "need配correctback才canuse"
                                        "allowlist" -> "仅白名单user"
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
                                Text(policy.replacefirstChar { it.uppercase() })
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
                    value = groupAllowfrom,
                    onValueChange = { groupAllowfrom = it },
                    label = { Text("Group chat whitelist") },
                    placeholder = { Text("每Rowone群聊ID\noc_xxxxxx") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }

            // 群聊 @ mentions
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paing(16.dp),
                    horizontalArrangement = Arrangement.Spacebetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "群聊need @ mentions",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "openback仅Response @ 机器人Message",
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

            // configFile pathHint
            Text(
                text = "configSavein:\n/sdcard/.androidforclaw/openclaw.json (channels.feishu)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.paing(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
