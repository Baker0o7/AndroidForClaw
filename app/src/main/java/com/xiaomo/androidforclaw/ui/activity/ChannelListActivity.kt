/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiaomo.androidforclaw.R
import com.tencent.mmkv.MMKV

/**
 * channel list page
 */
class channelListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                channelListScreen(
                    onback = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun channelListScreen(onback: () -> Unit) {
    val context = Localcontext.current
    val configLoader = remember { com.xiaomo.androidforclaw.config.configLoader(context) }

    // Read channel enabled status from openclaw.json instead of MMKV
    val config = remember { configLoader.loadOpenClawconfig() }
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
                    Iconbutton(onClick = onback) {
                        Icon(Icons.Filled.Arrowback, "Return")
                    }
                }
            )
        }
    ) { paingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .paing(paingValues)
                .paing(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "configmany渠道接入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Feishu channel card
            channelCard(
                name = "Feishu (飞书)",
                description = "飞书群聊and私聊接入",
                enabled = feishuEnabled,
                onClick = {
                    // Navigate to Feishu configuration page
                    val intent = Intent(context, FeishuchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Discord channel card
            channelCard(
                name = "Discord",
                description = "Discord service器and私聊接入",
                enabled = discordEnabled,
                onClick = {
                    val intent = Intent(context, DiscordchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Telegram channel card
            channelCard(
                name = "Telegram",
                description = "Telegram Bot 接入",
                enabled = false,
                onClick = {
                    val intent = Intent(context, TelegramchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Slack channel card
            channelCard(
                name = "Slack",
                description = "Slack 工作区接入",
                enabled = false,
                onClick = {
                    val intent = Intent(context, SlackchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Signal channel card
            channelCard(
                name = "Signal",
                description = "Signal Message接入",
                enabled = false,
                onClick = {
                    val intent = Intent(context, SignalchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // whatsApp channel card
            channelCard(
                name = "whatsApp",
                description = "whatsApp Message接入",
                enabled = false,
                onClick = {
                    val intent = Intent(context, whatsAppchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )

            // Weixin channel card
            channelCard(
                name = "微信 (Weixin)",
                description = "微信 ClawBot 扫码接入",
                enabled = config.channels.weixin?.enabled ?: false,
                onClick = {
                    val intent = Intent(context, WeixinchannelActivity::class.java)
                    context.startActivity(intent)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun channelCard(
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
                .paing(16.dp),
            horizontalArrangement = Arrangement.Spacebetween,
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
                    contentDescription = "alreadyEnable",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
