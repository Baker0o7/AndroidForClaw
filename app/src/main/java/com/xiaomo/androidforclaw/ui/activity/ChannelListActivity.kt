/**
 * OpenClaw Source Reference:
 * - 无 OpenClaw 对应 (Android 平台独有)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.tencent.mmkv.MMKV

/**
 * Channel list page
 */
class ChannelListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ChannelListScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configLoader = remember { com.xiaomo.androidforclaw.config.ConfigLoader(context) }

    // Read channel enabled status from openclaw.json instead of MMKV
    val config = remember { configLoader.loadOpenClawConfig() }
    var feishuEnabled by remember {
        mutableStateOf(config.channels.feishu.enabled)
    }

    var discordEnabled by remember {
        mutableStateOf(config.channels.discord?.enabled ?: false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channels_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Configure multi-channel access",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Feishu Channel card
            ChannelCard(
                name = "Feishu (Lark)",
                description = "Feishu group chat and direct message access",
                enabled = feishuEnabled,
                onClick = {
                    // Navigate to Feishu configuration page
                    val intent = Intent(context, FeishuChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Discord Channel card
            ChannelCard(
                name = "Discord",
                description = "Discord server and direct message access",
                enabled = discordEnabled,
                onClick = {
                    val intent = Intent(context, DiscordChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Telegram Channel card
            ChannelCard(
                name = "Telegram",
                description = "Telegram Bot access",
                enabled = false,
                onClick = {
                    val intent = Intent(context, TelegramChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Slack Channel card
            ChannelCard(
                name = "Slack",
                description = "Slack workspace access",
                enabled = false,
                onClick = {
                    val intent = Intent(context, SlackChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Signal Channel card
            ChannelCard(
                name = "Signal",
                description = "Signal messaging access",
                enabled = false,
                onClick = {
                    val intent = Intent(context, SignalChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // WhatsApp Channel card
            ChannelCard(
                name = "WhatsApp",
                description = "WhatsApp messaging access",
                enabled = false,
                onClick = {
                    val intent = Intent(context, WhatsAppChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Weixin Channel card
            ChannelCard(
                name = "WeChat (Weixin)",
                description = "WeChat ClawBot QR code access",
                enabled = config.channels.weixin?.enabled ?: false,
                onClick = {
                    val intent = Intent(context, WeixinChannelActivity::class.java)
                    context.startActivity(intent)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelCard(
    name: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (enabled) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Enabled",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
