/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.config.configLoader
import kotlinx.coroutines.launch

/**
 * Discord channel config页面
 */
class DiscordchannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DiscordchannelScreen(
                    onback = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordchannelScreen(
    onback: () -> Unit,
    context: android.content.context = androidx.compose.ui.platform.Localcontext.current
) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { configLoader(context) }

    // Loadconfig
    val openClawconfig = remember { configLoader.loadOpenClawconfig() }
    val savedconfig = remember { openClawconfig.channels.discord }

    // StatusVariable(correct齐 Discord config)
    var enabled by remember { mutableStateOf(savedconfig?.enabled ?: false) }
    var token by remember { mutableStateOf(savedconfig?.token ?: "") }
    var name by remember { mutableStateOf(savedconfig?.name ?: "androidforClaw Bot") }
    var dmPolicy by remember { mutableStateOf(savedconfig?.dm?.policy ?: "pairing") }
    var groupPolicy by remember { mutableStateOf(savedconfig?.groupPolicy ?: "allowlist") }
    var replyToMode by remember { mutableStateOf(savedconfig?.replyToMode ?: "off") }
    var allowfrom by remember { mutableStateOf(savedconfig?.dm?.allowfrom?.joinToString("\n") ?: "") }

    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discord_channel_title)) },
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

                                // Build Discord config
                                val updatedDiscordconfig = com.xiaomo.androidforclaw.config.Discordchannelconfig(
                                    enabled = enabled,
                                    token = token.takeif { it.isnotBlank() },
                                    name = name.takeif { it.isnotBlank() },
                                    dm = com.xiaomo.androidforclaw.config.DmPolicyconfig(
                                        policy = dmPolicy,
                                        allowfrom = allowfrom.split("\n").filter { it.isnotBlank() }.takeif { it.isnotEmpty() }
                                    ),
                                    groupPolicy = groupPolicy,
                                    replyToMode = replyToMode
                                )

                                // Update完整config
                                val updatedchannelsconfig = currentconfig.channels.copy(
                                    discord = updatedDiscordconfig
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
                    Text("configalreadySave, needRestartapp生效")
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
                            text = "Enable Discord channel",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "openbackwill receive Discord Message",
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
                value = token,
                onValueChange = { token = it },
                label = { Text("Bot Token") },
                placeholder = { Text("MTxxxxxxxx.Gxxxx.xxxxxxxxxxxxxxx") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Bot Name (Optional)") },
                placeholder = { Text("androidforClaw Bot") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // DM Policy
            Text(
                text = "DM (私聊) Policy",
                style = MaterialTheme.typography.titleLarge
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = dmPolicy == "open",
                    onClick = { dmPolicy = "open" },
                    label = { Text("Open - acceptAll DM") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = dmPolicy == "pairing",
                    onClick = { dmPolicy = "pairing" },
                    label = { Text("Pairing - needManage员审批 (recommend)") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = dmPolicy == "allowlist",
                    onClick = { dmPolicy = "allowlist" },
                    label = { Text("Allowlist - 仅允许白名单user") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (dmPolicy == "allowlist") {
                OutlinedTextField(
                    value = allowfrom,
                    onValueChange = { allowfrom = it },
                    label = { Text("白名单user ID (每Rowone)") },
                    placeholder = { Text("123456789012345678\n987654321098765432") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }

            // Guild (service器) Policy
            Text(
                text = "Guild (service器) Policy",
                style = MaterialTheme.typography.titleLarge
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = groupPolicy == "open",
                    onClick = { groupPolicy = "open" },
                    label = { Text("Open - acceptAllchannel (need @mentions)") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = groupPolicy == "allowlist",
                    onClick = { groupPolicy = "allowlist" },
                    label = { Text("Allowlist - 仅允许configchannel (recommend)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // return复schema
            Text(
                text = "return复schema",
                style = MaterialTheme.typography.titleLarge
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = replyToMode == "off",
                    onClick = { replyToMode = "off" },
                    label = { Text("Off - notusereturn复 (recommend)") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = replyToMode == "always",
                    onClick = { replyToMode = "always" },
                    label = { Text("Always - 总Yesusereturn复") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = replyToMode == "threads",
                    onClick = { replyToMode = "threads" },
                    label = { Text("Threads - inThread中usereturn复") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // configHint
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.paing(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "[WARN] configillustrate",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = """
                            1. need先in Discord Developer Portal Create Bot
                            2. Enable MESSAGE CONTENT INTENT (特权 Intent)
                            3. Get Bot Token 并填入Up方
                            4. will Bot 邀pleasetoYourservice器
                            5. 详细config参见: extensions/discord/SETUP_GUIDE.md
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
