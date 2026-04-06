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
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.unit.dp
import com.xiaomo.weixin.Weixinchannel
import com.xiaomo.weixin.Weixinconfig
import com.xiaomo.weixin.auth.QRCodeGenerator
import com.xiaomo.weixin.storage.WeixinAccountStore
import kotlinx.coroutines.launch

class WeixinchannelActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WeixinchannelActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WeixinchannelScreen(onback = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeixinchannelScreen(onback: () -> Unit) {
    val context = Localcontext.current
    val scope = rememberCoroutineScope()

    val configLoader = remember { com.xiaomo.androidforclaw.config.configLoader(context) }
    val openClawconfig = remember { configLoader.loadOpenClawconfig() }
    val weixinCfg = openClawconfig.channels.weixin

    var enabled by remember { mutableStateOf(weixinCfg?.enabled ?: false) }
    var statusText by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var accountInfo by remember { mutableStateOf("") }

    // Check existing account on load
    LaunchedEffect(Unit) {
        val account = WeixinAccountStore.loadAccount()
        if (account != null && !account.token.isNullorBlank()) {
            isLoggedIn = true
            accountInfo = "Account: ${account.accountId ?: "Unknown"}\nuser: ${account.userId ?: "Unknown"}"
            statusText = "[OK] alreadyLogin"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("微信 (Weixin)") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "through微信 ClawBot 插件接入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Enable开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paing(16.dp),
                    horizontalArrangement = Arrangement.Spacebetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable微信 channel",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "openbackwill receive微信Message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            // Savetoconfig
                            val currentconfig = configLoader.loadOpenClawconfig()
                            val updatedWeixin = (currentconfig.channels.weixin ?: com.xiaomo.androidforclaw.config.Weixinchannelconfig())
                                .copy(enabled = newValue)
                            val updatedconfig = currentconfig.copy(
                                channels = currentconfig.channels.copy(weixin = updatedWeixin)
                            )
                            configLoader.saveOpenClawconfig(updatedconfig)

                            if (newValue) {
                                // Enable: TryStartchannel
                                (context.applicationcontext as? com.xiaomo.androidforclaw.core.MyApplication)
                                    ?.restartWeixinchannel()
                                statusText = "[OK] alreadyEnable"
                            } else {
                                // Disabled: Stopchannel
                                com.xiaomo.androidforclaw.core.MyApplication.getWeixinchannel()?.stop()
                                statusText = "alreadyDisabled"
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
                    Column(modifier = Modifier.paing(16.dp)) {
                        Text("[OK] alreadyConnect微信", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(accountInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Logout button
                Outlinedbutton(
                    onClick = {
                        // Stop微信Message监听
                        com.xiaomo.androidforclaw.core.MyApplication.getWeixinchannel()?.stop()
                        WeixinAccountStore.clearAccount()
                        isLoggedIn = false
                        accountInfo = ""
                        statusText = "alreadyexitLogin"
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
                            modifier = Modifier.paing(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("use微信扫描two维码", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "微信Logintwo维码",
                                modifier = Modifier.size(250.dp),
                            )
                        }
                    }
                }

                // Login button
                button(
                    onClick = {
                        isLoggingIn = true
                        statusText = "currentlyGettwo维码..."
                        qrBitmap = null

                        scope.launch {
                            try {
                                val baseUrl = weixinCfg?.baseUrl
                                    ?.takeif { it.isnotBlank() }
                                    ?: Weixinconfig.DEFAULT_BASE_URL

                                val channel = Weixinchannel(
                                    Weixinconfig(baseUrl = baseUrl, routeTag = weixinCfg?.routeTag)
                                )
                                val qrLogin = channel.createQRLogin()

                                // Fetch QR code
                                val qrresult = qrLogin.fetchQRCode()
                                if (qrresult == null) {
                                    statusText = "[ERROR] Gettwo维码Failed"
                                    isLoggingIn = false
                                    return@launch
                                }

                                val (qrcodeUrl, qrcode) = qrresult

                                // Generate QR image locally from qrcode_img_content (URL for WeChat scanning)
                                statusText = "currently生成two维码..."
                                Log.i("WeixinLogin", "QR bitmap content: ${qrcodeUrl.take(80)}")
                                Log.i("WeixinLogin", "Poll qrcode: ${qrcode.take(30)}")
                                val bitmap = QRCodeGenerator.generate(qrcodeUrl, 512)
                                if (bitmap != null) {
                                    qrBitmap = bitmap
                                    statusText = "pleaseuse微信扫描two维码"
                                } else {
                                    statusText = "[WARN] two维码生成Failed, pleaseretry"
                                    isLoggingIn = false
                                    return@launch
                                }

                                // Wait for login
                                val loginresult = qrLogin.waitforLogin(
                                    qrcode = qrcode,
                                    onStatusUpdate = { status ->
                                        statusText = when (status) {
                                            "wait" -> "Wait扫码..."
                                            "scaned" -> "👀 already扫码, pleasein微信UpConfirm"
                                            "expired" -> "two维码alreadyover期, currentlyRefresh..."
                                            "confirmed" -> "[OK] LoginSuccess!"
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
                                    accountInfo = "Account: ${loginresult.accountId ?: "Unknown"}\nuser: ${loginresult.userId ?: "Unknown"}"
                                    statusText = loginresult.message
                                    qrBitmap = null

                                    // notification MyApplication reStart微信Message监听
                                    (context.applicationcontext as? com.xiaomo.androidforclaw.core.MyApplication)
                                        ?.restartWeixinchannel()
                                } else {
                                    statusText = "[ERROR] ${loginresult.message}"
                                }
                            } catch (e: exception) {
                                Log.e("WeixinLogin", "Login error", e)
                                statusText = "[ERROR] LoginFailed: ${e.message}"
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
            if (statusText.isnotBlank()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (statusText.startswith("[OK]")) {
                        MaterialTheme.colorScheme.primary
                    } else if (statusText.startswith("[ERROR]")) {
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
                Column(modifier = Modifier.paing(12.dp)) {
                    Text("illustrate", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "基于微信 ClawBot 插件Protocol, 扫码backcan via WeChat AI Conversation. \n" +
                                "• 仅Support私聊Message\n" +
                                "• Support文字、image、语音、files\n" +
                                "• Login凭证Savein本地",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


