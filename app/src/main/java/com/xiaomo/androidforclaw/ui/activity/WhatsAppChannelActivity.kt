/**
 * OpenClaw Source Reference:
 * - 无 OpenClaw 对应 (Android 平台独有)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.config.ConfigLoader
import com.xiaomo.androidforclaw.config.WhatsAppChannelConfig
import kotlinx.coroutines.launch

class WhatsAppChannelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WhatsAppChannelScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppChannelScreen(
    onBack: () -> Unit,
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current
) {
    val scope = rememberCoroutineScope()
    val configLoader = remember { ConfigLoader(context) }

    val savedConfig = remember { configLoader.loadOpenClawConfig().channels.whatsapp }
    var enabled by remember { mutableStateOf(savedConfig?.enabled ?: false) }
    var phoneNumber by remember { mutableStateOf(savedConfig?.phoneNumber ?: "") }
    var dmPolicy by remember { mutableStateOf(savedConfig?.dmPolicy ?: "open") }
    var groupPolicy by remember { mutableStateOf(savedConfig?.groupPolicy ?: "open") }
    var requireMention by remember { mutableStateOf(savedConfig?.requireMention ?: true) }
    var showSaveSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp Channel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val currentConfig = configLoader.loadOpenClawConfig()
                                val updatedChannelConfig = (currentConfig.channels.whatsapp ?: WhatsAppChannelConfig()).copy(
                                    enabled = enabled,
                                    phoneNumber = phoneNumber,
                                    dmPolicy = dmPolicy,
                                    groupPolicy = groupPolicy,
                                    requireMention = requireMention
                                )
                                val updatedConfig = currentConfig.copy(
                                    channels = currentConfig.channels.copy(whatsapp = updatedChannelConfig)
                                )
                                configLoader.saveOpenClawConfig(updatedConfig)
                                showSaveSuccess = true
                            }
                        }
                    ) {
                        Text("保存")
                    }
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
            // Enable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用 WhatsApp", style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Divider()

            // Phone Number
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                placeholder = { Text("WhatsApp registered phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // DM Policy
            Text("DM Policy", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "pairing", "allowlist").forEach { policy ->
                    FilterChip(
                        selected = dmPolicy == policy,
                        onClick = { dmPolicy = policy },
                        label = { Text(policy) }
                    )
                }
            }

            // Group Policy
            Text("Group Policy", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("open", "allowlist", "disabled").forEach { policy ->
                    FilterChip(
                        selected = groupPolicy == policy,
                        onClick = { groupPolicy = policy },
                        label = { Text(policy) }
                    )
                }
            }

            // Require Mention
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("群聊需要 @提及")
                Switch(checked = requireMention, onCheckedChange = { requireMention = it })
            }

            // Save success
            if (showSaveSuccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("✅ 配置已保存", color = MaterialTheme.colorScheme.primary)
            }

            // Status info
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "📖 WhatsApp 消息接入\n\n配置保存后需要重启应用生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
