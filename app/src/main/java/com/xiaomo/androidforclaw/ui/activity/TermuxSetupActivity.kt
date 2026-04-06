/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.ClipData
import android.content.Clipboardmanager
import android.content.context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomo.androidforclaw.agent.tools.TermuxBridgetool
import com.xiaomo.androidforclaw.core.TermuxSshdLauncher
import com.xiaomo.androidforclaw.agent.tools.TermuxSetupStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withcontext

class TermuxSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TermuxSetupScreen(onback = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxSetupScreen(onback: () -> Unit) {
    val context = Localcontext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { TermuxBridgetool(context) }

    // Status
    var termuxInstalled by remember { mutableStateOf(false) }
    var keypairReady by remember { mutableStateOf(false) }
    var sshReachable by remember { mutableStateOf(false) }
    var sshAuthOk by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var launchingSshd by remember { mutableStateOf(false) }
    var sshdLaunchMessage by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        scope.launch {
            val status = withcontext(Dispatchers.IO) { bridge.getStatus() }
            termuxInstalled = status.termuxInstalled
            keypairReady = status.keypairPresent
            sshReachable = status.sshReachable
            sshAuthOk = status.sshAuthOk
            publicKey = withcontext(Dispatchers.IO) { bridge.getPublicKey() }
            checking = false
        }
    }

    // Initial check + auto-connect if keypair ready but sshd not running
    LaunchedEffect(Unit) {
        // Ensure BouncyCastle
        try {
            val bc = org.bouncycastle.jce.provider.BouncyCastleprovider()
            java.security.Security.removeprovider(bc.name)
            java.security.Security.insertproviderAt(bc, 1)
        } catch (_: exception) {}
        refreshStatus()

        // into config page hour: If connected then skip, otherwise start on-demand
        val initStatus = withContext(Dispatchers.IO) { bridge.getStatus() }
        val alreadyConnected = com.xiaomo.androidforclaw.agent.tools.TermuxSSHPool.isConnected
        if (!alreadyConnected && initStatus.keypairPresent && !initStatus.sshReachable && initStatus.termuxInstalled) {
            launchingSshd = true
            sshdLaunchMessage = "Auto starting sshd..."
            try {
                withContext(Dispatchers.IO) {
                    com.xiaomo.androidforclaw.core.TermuxSshdLauncher.ensureAndLaunch(context)
                }
                // Termux was launched, return when front activity is shown
                val backIntent = android.content.Intent(context, TermuxSetupActivity::class.java)
                backIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(backIntent)
                // Poll and auto inject public key
                var keyInjected = false
                for (attempt in 1..15) {
                    delay(1000)
                    val s = withContext(Dispatchers.IO) { bridge.getStatus() }
                    refreshStatus()
                    if (s.ready) {
                        sshdLaunchMessage = "[OK] sshd Ready"
                        com.xiaomo.androidforclaw.agent.tools.TermuxSSHPool.warmUp(context)
                        break
                    }
                    if (s.sshReachable && !s.sshAuthOk && !keyInjected) {
                        val pubKey = withContext(Dispatchers.IO) { bridge.getPublicKey() }
                        if (pubKey != null) {
                            com.xiaomo.androidforclaw.core.TermuxSshdLauncher.injectPublicKey(context, pubKey)
                            keyInjected = true
                            sshdLaunchMessage = "Configuring SSH Key..."
                        }
                    }
                }
            } catch (e: Exception) {
                sshdLaunchMessage = "Auto Start Failed: ${e.message}"
            } finally {
                launchingSshd = false
                refreshStatus()
            }
        } else if (alreadyConnected) {
            sshdLaunchMessage = null // Already connected, don't show any hint
        }

        // Keep refreshing status
        while (true) {
            delay(3000)
            refreshStatus()
        }
    }

    // Build the key setup command (only when public key is available)
    val keySetupCommand = remember(publicKey) {
        if (publicKey != null) {
            "mkdir -p ~/.ssh && echo '${publicKey}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Termux Setup") },
                navigationIcon = {
                    IconButton(onClick = onback) {
                        Icon(Icons.Filled.Arrowback, "Back")
                    }
                }
            )
        }
    ) { paingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .paing(paingValues)
                .paing(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Header
            Text(
                "Follow the steps below to set up Termux so AI can execute commands on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ============ Step 1: Install Termux ============
            SetupStepCard(
                step = 1,
                title = "Install Termux",
                done = termuxInstalled,
            ) {
                Text(
                    "Download and install Termux from F-Droid (do not use Play Store version)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!termuxInstalled) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://f-droid.org/packages/com.termux/")))
                        } catch (_: Exception) {
                            Toast.makeText(context, "Please manually search for Termux", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Download")
                    }
                }
            }

            // ============ Step 2: Generate keypair ============
            SetupStepCard(
                step = 2,
                title = "Generate SSH Key",
                done = keypairReady,
            ) {
                Text(
                    "Click the button below to automatically generate a key pair (only needed once)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!keypairReady) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { bridge.ensureKeypair() }
                            refreshStatus()
                        }
                    }) {
                        Text("Generate Key")
                    }
                }
            }

            // ============ Step 3: termux-setup-storage ============
            SetupStepCard(
                step = 3,
                title = "Grant Termux Storage Access",
                done = sshAuthOk, // we know storage works if auth succeeded
            ) {
                Text(
                    "Open Termux, enter the following command and tap \"Allow\":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                CommandBox(
                    command = "termux-setup-storage",
                    context = context
                )
            }

            // ============ Step 4: Install openssh ============
            SetupStepCard(
                step = 4,
                title = "Install SSH Service",
                done = sshAuthOk,
            ) {
                Text(
                    "Run in Termux (wait for installation to complete):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                CommandBox(
                    command = "pkg install -y openssh",
                    context = context
                )
            }

            // ============ Step 5: Configure Key ============
            SetupStepCard(
                step = 5,
                title = "Configure SSH Key",
                done = sshAuthOk,
            ) {
                if (keySetupCommand != null) {
                    Text(
                        "Run the following command in Termux to add the key to SSH:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    CommandBox(
                        command = keySetupCommand,
                        context = context
                    )
                } else {
                    Text(
                        "Please complete Step 2 to generate a key first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ============ Step 6: Start sshd ============
            SetupStepCard(
                step = 6,
                title = "Start SSH Service",
                done = sshReachable,
            ) {
                Text(
                    "Run in Termux:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                CommandBox(
                    command = "sshd",
                    context = context
                )
                Spacer(Modifier.height(8.dp))
                // One-click start sshd button
                Button(
                    onClick = {
                        launchingSshd = true
                        sshdLaunchMessage = null
                        scope.launch {
                            try {
                                TermuxSshdLauncher.ensureAndLaunch(context)
                                sshdLaunchMessage = "Start command sent, waiting for sshd to start..."
                                // Wait for sshd to start then refresh status
                                delay(3000)
                                refreshStatus()
                            } catch (e: Exception) {
                                sshdLaunchMessage = "Start failed: ${e.message}\nPlease manually run sshd in Termux"
                            } finally {
                                launchingSshd = false
                            }
                        }
                    },
                    enabled = !launchingSshd && termuxInstalled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (launchingSshd) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Starting...")
                    } else {
                        Text("One-click Start sshd")
                    }
                }
                if (sshdLaunchMessage != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = sshdLaunchMessage!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sshdLaunchMessage!!.startsWith("Start failed"))
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Note: sshd needs to be run again after each Termux restart",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ============ Step 7: Verify ============
            SetupStepCard(
                step = 7,
                title = "Verify Connection",
                done = sshAuthOk,
            ) {
                if (sshAuthOk) {
                    Text(
                        "SSH connection successful, setup complete!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                } else if (sshReachable) {
                    Text(
                        "SSH port reachable but authentication failed. Please verify Step 5 key command was executed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Waiting for connection... Make sure Termux is open and sshd is running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        checking = true
                        refreshStatus()
                    },
                    enabled = !checking
                ) {
                    Text("Retry")
                }
            }
                Spacer(Modifier.height(8.dp))
                Outlinedbutton(
                    onClick = {
                        checking = true
                        refreshStatus()
                    },
                    enabled = !checking
                ) {
                    Text("\u91cd\u65b0\u68c0\u6d4b")
                }
            }

            // ============ Success banner ============
            if (sshAuthOk) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            "Complete",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Termux Setup Complete!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "AI can now execute shell scripts via exec commands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onback) {
                            Text("Done")
                        }
                    }
                }
            }
                    }
                }
            }

            // Loading
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).paing(8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SetupStepCard(
    step: Int,
    title: String,
    done: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.paing(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Step indicator
            if (done) {
                Icon(
                    Icons.Default.CheckCircle,
                    "Complete",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$step",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun CommandBox(command: String, context: context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E)
    ) {
        Row(
            modifier = Modifier.paing(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = command,
                color = Color(0xFF4EC9B0),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    "Copy",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
