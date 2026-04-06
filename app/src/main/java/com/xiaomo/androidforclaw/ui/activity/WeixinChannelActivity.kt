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
            accountInfo = "Account: ${account.accountId ?: "Unknown"}\nUser: ${account.userId ?: "Unknown"}"
            statusText = "[OK] Already Logged In"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WeChat") },
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
                text = "Connect via WeChat ClawBot plugin",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Enable switch
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
                            text = "Enable WeChat Channel",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Enable to receive WeChat messages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newValue ->
                            enabled = newValue
                            // Save to config
                            val currentconfig = configLoader.loadOpenClawconfig()
                            val updatedWeixin = (currentconfig.channels.weixin ?: com.xiaomo.androidforclaw.config.WeixinChannelConfig())
                                .copy(enabled = newValue)
                            val updatedconfig = currentconfig.copy(
                                channels = currentconfig.channels.copy(weixin = updatedWeixin)
                            )
                            configLoader.saveOpenClawConfig(updatedconfig)

                            if (newValue) {
                                // Enable: Try Start Channel
                                (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                    ?.restartWeixinChannel()
                                statusText = "[OK] Already Enabled"
                            } else {
                                // Disabled: Stop Channel
                                com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                                statusText = "Already Disabled"
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
                        Text("[OK] Connected to WeChat", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(accountInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Logout button
                OutlinedButton(
                    onClick = {
                        // Stop WeChat message listener
                        com.xiaomo.androidforclaw.core.MyApplication.getWeixinChannel()?.stop()
                        WeixinAccountStore.clearAccount()
                        isLoggedIn = false
                        accountInfo = ""
                        statusText = "Logged Out"
                        qrBitmap = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Log Out")
                }
                }

                // Login button
                qrBitmap?.let { bmp ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Scan with WeChat to login", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "WeChat Login QR Code",
                                modifier = Modifier.size(250.dp),
                            )
                        }
                    }
                }
                    }
                }

                // Login button
                Button(
                    onClick = {
                        isLoggingIn = true
                        statusText = "Getting QR Code..."
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
                                    statusText = "[ERROR] Failed to get QR Code"
                                    isLoggingIn = false
                                    return@launch
                                }

                                val (qrCodeUrl, qrCode) = qrResult

                                // Generate QR image locally from qrcode_img_content (URL for WeChat scanning)
                                statusText = "Generating QR Code..."
                                Log.i("WeixinLogin", "QR bitmap content: ${qrCodeUrl.take(80)}")
                                Log.i("WeixinLogin", "Poll qrcode: ${qrCode.take(30)}")
                                val bitmap = QRCodeGenerator.generate(qrCodeUrl, 512)
                                if (bitmap != null) {
                                    qrBitmap = bitmap
                                    statusText = "Please scan with WeChat"
                                } else {
                                    statusText = "[WARN] QR Code generation failed, please retry"
                                    isLoggingIn = false
                                    return@launch
                                }

                                // Wait for login
                                val loginResult = qrLogin.waitForLogin(
                                    qrCode = qrCode,
                                    onStatusUpdate = { status ->
                                        statusText = when (status) {
                                            "wait" -> "Waiting for scan..."
                                            "scaned" -> "Scanned, please confirm in WeChat"
                                            "expired" -> "QR Code expired, refreshing..."
                                            "confirmed" -> "[OK] Login Success!"
                                            else -> status
                                        }
                                    },
                                    onQRRefreshed = { newUrl, newQrCode ->
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

                                    // Notify MyApplication to restart WeChat message listener
                                    (context.applicationContext as? com.xiaomo.androidforclaw.core.MyApplication)
                                        ?.restartWeixinChannel()
                                } else {
                                    statusText = "[ERROR] ${loginResult.message}"
                                }
                            } catch (e: Exception) {
                                Log.e("WeixinLogin", "Login error", e)
                                statusText = "[ERROR] Login Failed: ${e.message}"
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
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Instructions", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Based on WeChat ClawBot plugin protocol, scan to login to enable AI conversation via WeChat.\n" +
                                "• Only supports private chat messages\n" +
                                "• Supports text, images, voice, files\n" +
                                "• Login credentials stored locally",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            }
        }
    }
}


