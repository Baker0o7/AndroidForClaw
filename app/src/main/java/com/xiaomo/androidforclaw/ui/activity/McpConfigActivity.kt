/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.ui.activity

import android.content.ClipData
import android.content.Clipboardmanager
import android.content.context
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
import androidx.compose.material.icons.filled.Arrowback
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Localcontext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomo.androidforclaw.R
import com.xiaomo.androidforclaw.mcp.ObserverMcpServer
import java.net.Inet4Aress
import java.net.NetworkInterface

/**
 * MCP Server Config Page
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  [WARN]  This page manages MCP Server for 【External agent】 use           │
 * │     (Claude Desktop、Cursor、Other MCP Clients, etc.)             │
 * │                                                              │
 * │     And AndroidForClaw's own AI Feature is completely unrelated.                  │
 * │     AndroidForClaw controls the phone directly via Internal Device Tools,           │
 * │     NOT through this MCP Server.                                      │
 * │                                                              │
 * │     This MCP Server allows other AI agents on the same LAN        │
 * │     to remotely control this phone (View Screen, click, swipe, etc).             │
 * └──────────────────────────────────────────────────────────────┘
 */
class McpconfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                McpconfigScreen(onback = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpconfigScreen(onback: () -> Unit) {
    val context = Localcontext.current
    var serverRunning by remember { mutableStateOf(ObserverMcpServer.isRunning()) }
    val deviceIp = remember { getDeviceIp() }
    val port = ObserverMcpServer.DEFAULT_PORT
    val serverUrl = "http://$deviceIp:$port/mcp"

    val mcpconfig = remember(deviceIp) {
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
                    Iconbutton(onClick = onback) {
                        Icon(Icons.Filled.Arrowback, "Return")
                    }
                }
            )
        }
    ) { paing ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .paing(paing)
                .verticalScroll(rememberScrollState())
                .paing(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Banner ──────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 0.dp
            ) {
                Text(
                    text = "This service is for External agents (Claude Desktop, Cursor, etc.) to remotely control this phone, and is unrelated to AndroidForClaw's own features. ",
                    modifier = Modifier.paing(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            // ── Server control ──────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.paing(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server Status", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (serverRunning) "Running · port $port" else "Stopped",
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
                                    } catch (e: exception) {
                                        Toast.makeText(context, "StartFailed: ${e.message}", Toast.LENGTH_SHORT).show()
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

            // ── MCP config JSON ─────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.paing(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "MCP config",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Textbutton(
                            onClick = {
                                val cb = context.getSystemservice(context.CLIPBOARD_SERVICE) as Clipboardmanager
                                cb.setPrimaryClip(ClipData.newPlainText("mcp-config", mcpconfig))
                                Toast.makeText(context, "alreadyCopy", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Will be used in next config pasted to Claude Desktop / Cursor MCP Settings: ",
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
                            text = mcpconfig,
                            modifier = Modifier
                                .paing(12.dp)
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
                Column(modifier = Modifier.paing(16.dp)) {
                    Text("Available Tools", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    val tools = listOf(
                        "get_view_tree" to "Get UI Tree",
                        "screenshot" to "Screenshot (base64 PNG)",
                        "tap" to "Tap at coordinates (x, y)",
                        "long_press" to "Long press at coordinates (x, y)",
                        "swipe" to "Swipe gesture",
                        "input_text" to "Input text",
                        "press_home" to "Press Home Key",
                        "press_back" to "Press Back Key",
                        "get_current_app" to "Get foreground app package name",
                    )

                    tools.forEach { (name, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .paing(vertical = 4.dp),
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
            if (intf.isloopback || !intf.isUp) return@forEach
            intf.inetAresses?.toList()?.forEach { ar ->
                if (ar is Inet4Aress && !ar.isloopbackAress) {
                    return ar.hostAress ?: "0.0.0.0"
                }
            }
        }
    } catch (_: exception) {}
    return "0.0.0.0"
}
