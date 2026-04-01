/**
 * Weixin channel setup page — QR code login + status display.
 */
package com.xiaomo.androidforclaw.ui.activity

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaomo.weixin.WeixinChannel
import com.xiaomo.weixin.WeixinConfig
import com.xiaomo.weixin.auth.QRCodeGenerator
import com.xiaomo.weixin.storage.WeixinAccountStore
import kotlinx.coroutines.launch

class WeixinChannelActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WeixinChannelActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WeixinChannelScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeixinChannelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val configLoader = remember { com.xiaomo.androidforclaw.config.ConfigLoader(context) }
    val openClawConfig = remember { configLoader.loadOpenClawConfig() }
    val weixinCfg = openClawConfig.channels.weixin

    var enabled by remember { mutableStateOf(weixinCfg?.enabled ?: false) }
    var statusText by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var accountInfo by remember { mutableStateOf("") }

    // Check existing account on load
    LaunchedEffect(Unit) {
        val account = WeixinAccountStore.loadAccount()
        if (account != null && !account.token.isNullOrBlank()) {
            isLoggedIn = true
            accountInfo = "Account: ${account.accountId ?: "Unknown"}\nUser: ${account.userId ?: "Unknown"}"
            statusText = "✅ Logged in"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WeChat") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Connect via WeChat ClawBot plugin",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 启用开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable WeChat Channel",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Receive WeChat messages when enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            // 保存到配置
                            val currentConfig = configLoader.loadOpenClawConfig()
                            val updatedWeixin = (currentConfig.channels.weixin ?: com.xiaomo.androidforclaw.config.WeixinChannelConfig())
                                .copy(enabled = newValue)
                            val updatedConfig = currentConfig.copy(
                                channels = currentConfig.channels.copy(weixin = updatedWeixin)
                            )
                            configLoader.saveOpenClawConfig(updatedConfig)

                            if (newValue) {
                                // 启用：尝试启动通道
                                (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                    ?.restartWeixinChannel()
                                statusText = "✅ Enabled"
                            } else {
                                // 禁用：停止通道
                                com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                                statusText = "Disabled"
                            }
                        },
                    )
                }
            }

            if (isLoggedIn) {
                // Show logged-in state
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ Connected to WeChat", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(accountInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Logout button
                OutlinedButton(
                    onClick = {
                        // 停止微信消息监听
                        com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                        WeixinAccountStore.clearAccount()
                        isLoggedIn = false
                        accountInfo = ""
                        statusText = "Logged out"
                        qrBitmap = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Log out")
                }
            } else {
                // QR code display
                qrBitmap?.let { bmp ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Scan QR code with WeChat", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "WeChat login QR code",
                                modifier = Modifier.size(250.dp),
                            )
                        }
                    }
                }

                // Login button
                Button(
                    onClick = {
                        isLoggingIn = true
                        statusText = "Fetching QR code..."
                        qrBitmap = null

                        scope.launch {
                            try {
                                val baseUrl = weixinCfg?.baseUrl
                                    ?.takeIf { it.isNotBlank() }
                                    ?: WeixinConfig.DEFAULT_BASE_URL

                                val channel = WeixinChannel(
                                    WeixinConfig(baseUrl = baseUrl, routeTag = weixinCfg?.routeTag)
                                )
                                val qrLogin = channel.createQRLogin()

                                // Fetch QR code
                                val qrResult = qrLogin.fetchQRCode()
                                if (qrResult == null) {
                                    statusText = "❌ Failed to fetch QR code"
                                    isLoggingIn = false
                                    return@launch
                                }

                                val (qrcodeUrl, qrcode) = qrResult

                                // Generate QR image locally
                                // qrcodeUrl is a web page link, not an image — use it as QR content
                                statusText = "Generating QR code..."
                                val bitmap = QRCodeGenerator.generate(qrcodeUrl, 512)
                                if (bitmap != null) {
                                    qrBitmap = bitmap
                                    statusText = "Please scan QR code with WeChat"
                                } else {
                                    statusText = "⚠️ QR code generation failed, please retry"
                                    isLoggingIn = false
                                    return@launch
                                }

                                // Wait for login
                                val loginResult = qrLogin.waitForLogin(
                                    qrcode = qrcode,
                                    onStatusUpdate = { status ->
                                        statusText = when (status) {
                                            "wait" -> "Waiting for scan..."
                                            "scaned" -> "👀 Scanned, please confirm in WeChat"
                                            "expired" -> "QR code expired, refreshing..."
                                            "confirmed" -> "✅ Login successful!"
                                            else -> status
                                        }
                                    },
                                    onQRRefreshed = { newUrl ->
                                        val newBitmap = QRCodeGenerator.generate(newUrl, 512)
                                        if (newBitmap != null) {
                                            qrBitmap = newBitmap
                                        }
                                    }
                                )

                                if (loginResult.connected) {
                                    isLoggedIn = true
                                    accountInfo = "Account: ${loginResult.accountId ?: "Unknown"}\nUser: ${loginResult.userId ?: "Unknown"}"
                                    statusText = loginResult.message
                                    qrBitmap = null

                                    // 通知 MyApplication 重新启动微信消息监听
                                    (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                        ?.restartWeixinChannel()
                                } else {
                                    statusText = "❌ ${loginResult.message}"
                                }
                            } catch (e: Exception) {
                                Log.e("WeixinLogin", "Login error", e)
                                statusText = "❌ Login failed: ${e.message}"
                            } finally {
                                isLoggingIn = false
                            }
                        }
                    },
                    enabled = !isLoggingIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoggingIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isLoggingIn) "Logging in..." else "Scan to Login")
                }
            }

            // Status text
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusText.startsWith("✅")) {
                        MaterialTheme.colorScheme.primary
                    } else if (statusText.startsWith("❌")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Notes", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Based on the WeChat ClawBot plugin protocol, you can chat with AI via WeChat after scanning.\n" +
                                "• Only direct messages are supported\n" +
                                "• Supports text, images, voice, and files\n" +
                                "• Login credentials are stored locally",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


