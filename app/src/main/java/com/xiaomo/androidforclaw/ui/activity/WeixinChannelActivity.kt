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
            statusText = "✅ 已Login"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("微信 (Weixin)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Return")
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
                text = "通过微信 ClawBot 插件接入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Enabledd开关
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
                            text = "Enabledd微信 Channel",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "开启BackWill receive微信Message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            // Save到Config
                            val currentConfig = configLoader.loadOpenClawConfig()
                            val updatedWeixin = (currentConfig.channels.weixin ?: com.xiaomo.androidforclaw.config.WeixinChannelConfig())
                                .copy(enabled = newValue)
                            val updatedConfig = currentConfig.copy(
                                channels = currentConfig.channels.copy(weixin = updatedWeixin)
                            )
                            configLoader.saveOpenClawConfig(updatedConfig)

                            if (newValue) {
                                // Enabledd: TryStart通道
                                (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                    ?.restartWeixinChannel()
                                statusText = "✅ 已Enabledd"
                            } else {
                                // Disabled: Stop通道
                                com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                                statusText = "已Disabled"
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
                        Text("✅ 已Connect微信", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(accountInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Logout button
                OutlinedButton(
                    onClick = {
                        // Stop微信Message监听
                        com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                        WeixinAccountStore.clearAccount()
                        isLoggedIn = false
                        accountInfo = ""
                        statusText = "已exitLogin"
                        qrBitmap = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("exitLogin")
                }
            } else {
                // QR code display
                qrBitmap?.let { bmp ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("use微信扫描二维码", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "微信Login二维码",
                                modifier = Modifier.size(250.dp),
                            )
                        }
                    }
                }

                // Login button
                Button(
                    onClick = {
                        isLoggingIn = true
                        statusText = "正在Get二维码..."
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
                                val qrresult = qrLogin.fetchQRCode()
                                if (qrresult == null) {
                                    statusText = "❌ Get二维码Failed"
                                    isLoggingIn = false
                                    return@launch
                                }

                                val (qrcodeUrl, qrcode) = qrresult

                                // Generate QR image locally from qrcode_img_content (URL for WeChat scanning)
                                statusText = "正在生成二维码..."
                                Log.i("WeixinLogin", "QR bitmap content: ${qrcodeUrl.take(80)}")
                                Log.i("WeixinLogin", "Poll qrcode: ${qrcode.take(30)}")
                                val bitmap = QRCodeGenerator.generate(qrcodeUrl, 512)
                                if (bitmap != null) {
                                    qrBitmap = bitmap
                                    statusText = "请use微信扫描二维码"
                                } else {
                                    statusText = "⚠️ 二维码生成Failed, 请Retry"
                                    isLoggingIn = false
                                    return@launch
                                }

                                // Wait for login
                                val loginresult = qrLogin.waitForLogin(
                                    qrcode = qrcode,
                                    onStatusUpdate = { status ->
                                        statusText = when (status) {
                                            "wait" -> "Wait扫码..."
                                            "scaned" -> "👀 已扫码, 请在微信UpConfirm"
                                            "expired" -> "二维码已过期, 正在Refresh..."
                                            "confirmed" -> "✅ LoginSuccess!"
                                            else -> status
                                        }
                                    },
                                    onQRRefreshed = { newUrl, newQrcode ->
                                        val newBitmap = QRCodeGenerator.generate(newUrl, 512)
                                        if (newBitmap != null) {
                                            qrBitmap = newBitmap
                                        }
                                    }
                                )

                                if (loginresult.connected) {
                                    isLoggedIn = true
                                    accountInfo = "Account: ${loginresult.accountId ?: "Unknown"}\nUser: ${loginresult.userId ?: "Unknown"}"
                                    statusText = loginresult.message
                                    qrBitmap = null

                                    // Notification MyApplication 重NewStart微信Message监听
                                    (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                        ?.restartWeixinChannel()
                                } else {
                                    statusText = "❌ ${loginresult.message}"
                                }
                            } catch (e: Exception) {
                                Log.e("WeixinLogin", "Login error", e)
                                statusText = "❌ LoginFailed: ${e.message}"
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
                    Text(if (isLoggingIn) "Login中..." else "扫码Login")
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
                    Text("illustrate", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "基于微信 ClawBot 插件Protocol, 扫码BackCan via WeChat AI Conversation. \n" +
                                "• 仅Support私聊Message\n" +
                                "• Support文字、Graph片、语音、文件\n" +
                                "• Login凭证Save在本地",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


