/**
 * OpenClaw Source Reference:
 * - 无 OpenClaw 对应 (Android 平台独有)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.mcp.ObserverMcpServer
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * MCP Server 配置页面
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  ⚠️  此页面管理的 MCP Server 是给【外部 Agent】用的           │
 * │     （Claude Desktop、Cursor、其他 MCP 客户端等）             │
 * │                                                              │
 * │     与 AndroidForClaw 自身的 AI 功能完全无关。                 │
 * │     AndroidForClaw 通过内部 DeviceTool 直接操作手机，          │
 * │     不经过此 MCP Server。                                     │
 * │                                                              │
 * │     此 MCP Server 的作用是让同一局域网下的其他 AI Agent        │
 * │     能够远程操控这台手机（查看屏幕、点击、滑动等）。            │
 * └──────────────────────────────────────────────────────────────┘
 */
class McpConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                McpConfigScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var serverRunning by remember { mutableStateOf(ObserverMcpServer.isRunning()) }
    val deviceIp = remember { getDeviceIp() }
    val port = ObserverMcpServer.DEFAULT_PORT
    val serverUrl = "http://$deviceIp:$port/mcp"

    val mcpConfig = remember(deviceIp) {
        """
        |{
        |  "mcpServers": {
        |    "android-phone": {
        |      "url": "$serverUrl"
        |    }
        |  }
        |}
        """.trimMargin()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Banner ──────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 0.dp
            ) {
                Text(
                    text = "This service allows external agents (Claude Desktop, Cursor, etc.) to remotely control this phone. It is unrelated to AndroidForClaw's own features.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            // ── Server control ──────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Service Status", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (serverRunning) "Running · Port $port" else "Stopped",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (serverRunning)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = serverRunning,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    try {
                                        val server = ObserverMcpServer.getInstance(port)
                                        server.start()
                                        serverRunning = true
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    ObserverMcpServer.stopServer()
                                    serverRunning = false
                                }
                            }
                        )
                    }

                    if (serverRunning) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "IP: $deviceIp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── MCP Config JSON ─────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "MCP Configuration",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("mcp-config", mcpConfig))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Paste the following configuration into Claude Desktop / Cursor MCP settings:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    // Code block
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = mcpConfig,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Available tools ─────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Available Tools", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    val tools = listOf(
                        "get_view_tree" to "Get UI tree",
                        "screenshot" to "Screenshot (base64 PNG)",
                        "tap" to "Tap at coordinates (x, y)",
                        "long_press" to "Long press at coordinates (x, y)",
                        "swipe" to "Swipe gesture",
                        "input_text" to "Input text",
                        "press_home" to "Press Home",
                        "press_back" to "Press Back",
                        "get_current_app" to "Get foreground app package name",
                    )

                    tools.forEach { (name, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(130.dp)
                            )
                            Text(
                                text = desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun getDeviceIp(): String {
    try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
            if (intf.isLoopback || !intf.isUp) return@forEach
            intf.inetAddresses?.toList()?.forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "0.0.0.0"
                }
            }
        }
    } catch (_: Exception) {}
    return "0.0.0.0"
}
