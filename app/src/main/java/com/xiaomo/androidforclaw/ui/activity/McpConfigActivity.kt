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
 * MCP Server config页面
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  [WARN]  this页面Manage MCP Server Yes给【External agent】用           │
 * │     (Claude Desktop、Cursor、Its他 MCP Client等)             │
 * │                                                              │
 * │     and androidforClaw 自身 AI FeaturecompletelyNone关.                  │
 * │     androidforClaw throughInternal Devicetool 直接Action手机,           │
 * │     not经overthis MCP Server.                                      │
 * │                                                              │
 * │     this MCP Server 作用Yes让同one局域网nextIts他 AI agent        │
 * │     can远程操控this台手机(ViewScreen、click、swipe等).             │
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
                    text = "thisservice用于External agent(Claude Desktop、Cursor 等)远程操控本手机, and androidforClaw 自身FeatureNone关. ",
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
                            Text("serviceStatus", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (serverRunning) "Run中 · port $port" else "alreadyStop",
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
                        text = "willbynextconfigpasteto Claude Desktop / Cursor  MCP Settings中: ",
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
                    Text("Available工具", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    val tools = listOf(
                        "get_view_tree" to "Get UI Tree",
                        "screenshot" to "截屏 (base64 PNG)",
                        "tap" to "click坐标 (x, y)",
                        "long_press" to "long press坐标 (x, y)",
                        "swipe" to "swipe手势",
                        "input_text" to "Input文字",
                        "press_home" to "按 Home Key",
                        "press_back" to "按ReturnKey",
                        "get_current_app" to "GetforegroundappPackage name",
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
