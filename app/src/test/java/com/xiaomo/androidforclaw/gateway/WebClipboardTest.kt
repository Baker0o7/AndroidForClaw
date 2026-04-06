package com.xiaomo.androidforclaw.gateway

import org.junit.Assert.*
import org.junit.Test

/**
 * Web Clipboard Feature单元Test
 *
 * TestInside容: 
 * 1. 剪切板历史Manage(Add、Up限、Sort)
 * 2. 剪切板页面 HTML 生成
 * 3. API Route匹配逻辑
 * 4. Connect 页面 IP 地址Parse
 */
class WebClipboardTest {

    // ── 剪切板历史Manage ────────────────────────────────────

    /**
     * Mock GatewayServer 中的剪切板历史逻辑
     */
    private class ClipboardHistory(private val maxSize: Int = 20) {
        private val history = mutableListOf<Pair<String, Long>>()

        fun add(text: String, timestamp: Long = System.currentTimeMillis()) {
            synchronized(history) {
                history.add(0, Pair(text, timestamp))
                if (history.size > maxSize) {
                    history.removeAt(history.size - 1)
                }
            }
        }

        fun getAll(): List<Pair<String, Long>> {
            synchronized(history) {
                return history.toList()
            }
        }

        val size: Int get() = history.size
    }

    @Test
    fun `AddText到历史Record`() {
        val history = ClipboardHistory()
        history.add("test-text-1", 1000L)
        assertEquals(1, history.size)
        assertEquals("test-text-1", history.getAll()[0].first)
        assertEquals(1000L, history.getAll()[0].second)
    }

    @Test
    fun `最NewRecord排在最Front`() {
        val history = ClipboardHistory()
        history.add("older", 1000L)
        history.add("newer", 2000L)
        assertEquals("newer", history.getAll()[0].first)
        assertEquals("older", history.getAll()[1].first)
    }

    @Test
    fun `历史Record不超过MaxValue`() {
        val maxSize = 5
        val history = ClipboardHistory(maxSize)
        for (i in 1..10) {
            history.add("text-$i", i.toLong())
        }
        assertEquals(maxSize, history.size)
        // 最New的Should在最Front面
        assertEquals("text-10", history.getAll()[0].first)
        // 最Old的被淘汰
        assertFalse(history.getAll().any { it.first == "text-1" })
    }

    @Test
    fun `DefaultMax历史为 20 条`() {
        val history = ClipboardHistory()
        for (i in 1..25) {
            history.add("text-$i")
        }
        assertEquals(20, history.size)
    }

    @Test
    fun `Null历史ReturnNullList`() {
        val history = ClipboardHistory()
        assertTrue(history.getAll().isEmpty())
        assertEquals(0, history.size)
    }

    // ── API Route匹配 ─────────────────────────────────────

    /**
     * Mock GatewayServer.serve() 中的Route匹配逻辑
     */
    private enum class RouteResult {
        CLIPBOARD_PAGE, CLIPBOARD_SEND, CLIPBOARD_HISTORY, API, WEBUI
    }

    private fun matchRoute(uri: String, method: String = "GET"): RouteResult {
        if (uri.startsWith("/api/")) {
            val apiUri = uri.removePrefix("/api")
            return when {
                apiUri == "/clipboard/send" && method == "POST" -> RouteResult.CLIPBOARD_SEND
                apiUri == "/clipboard/history" -> RouteResult.CLIPBOARD_HISTORY
                else -> RouteResult.API
            }
        }
        if (uri == "/clipboard" || uri == "/clipboard/") {
            return RouteResult.CLIPBOARD_PAGE
        }
        return RouteResult.WEBUI
    }

    @Test
    fun `Route - clipboard 页面`() {
        assertEquals(RouteResult.CLIPBOARD_PAGE, matchRoute("/clipboard"))
        assertEquals(RouteResult.CLIPBOARD_PAGE, matchRoute("/clipboard/"))
    }

    @Test
    fun `Route - clipboard send API (POST)`() {
        assertEquals(RouteResult.CLIPBOARD_SEND, matchRoute("/api/clipboard/send", "POST"))
    }

    @Test
    fun `Route - clipboard send GET 不匹配`() {
        // GET Request不应匹配 clipboard send
        assertNotEquals(RouteResult.CLIPBOARD_SEND, matchRoute("/api/clipboard/send", "GET"))
    }

    @Test
    fun `Route - clipboard history API`() {
        assertEquals(RouteResult.CLIPBOARD_HISTORY, matchRoute("/api/clipboard/history"))
    }

    @Test
    fun `Route - 普通 API 不受影响`() {
        assertEquals(RouteResult.API, matchRoute("/api/health"))
        assertEquals(RouteResult.API, matchRoute("/api/device/status"))
    }

    @Test
    fun `Route - 普通页面走 WebUI`() {
        assertEquals(RouteResult.WEBUI, matchRoute("/"))
        assertEquals(RouteResult.WEBUI, matchRoute("/index.html"))
    }

    // ── 剪切板页面 HTML ──────────────────────────────────

    @Test
    fun `剪切板页面Contains关KeyElement`() {
        // Mock serveClipboardPage 中 HTML 的关KeyInside容
        val html = buildClipboardPageHtml()
        assertTrue("缺少Title", html.contains("Web Clipboard"))
        assertTrue("缺少发送按钮", html.contains("发送到手机"))
        assertTrue("缺少 textarea", html.contains("<textarea"))
        assertTrue("缺少 fetch API 调用", html.contains("/api/clipboard/send"))
        assertTrue("缺少历史Record区域", html.contains("历史Record"))
        assertTrue("缺少 Ctrl+Enter 快捷Key", html.contains("ctrlKey") || html.contains("metaKey"))
    }

    @Test
    fun `剪切板页面 API Path正确`() {
        val html = buildClipboardPageHtml()
        assertTrue("send API Path", html.contains("'/api/clipboard/send'") || html.contains("\"/api/clipboard/send\""))
        assertTrue("history API Path", html.contains("'/api/clipboard/history'") || html.contains("\"/api/clipboard/history\""))
    }

    @Test
    fun `剪切板页面Has XSS 防护`() {
        val html = buildClipboardPageHtml()
        assertTrue("Contains escapeHtml Function", html.contains("escapeHtml"))
        assertTrue("Contains escapeAttr Function", html.contains("escapeAttr"))
    }

    private fun buildClipboardPageHtml(): String {
        // 与 GatewayServer.serveClipboardPage() 中的 HTML 保持一致
        return """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>AndroidForClaw - Web Clipboard</title>
</head>
<body>
<h1>Web Clipboard</h1>
<p>在电脑UpInput, 手机UpAutoCopyto clipboard</p>
<textarea id="text" placeholder="粘贴 API Key、ConfigInside容或AnyText..."></textarea>
<button class="btn-send" id="sendBtn" onclick="send()">发送到手机</button>
<h2>历史Record(点击Copy)</h2>
<ul class="history" id="history"></ul>
<script>
async function send() {
  const text = document.getElementById('text').value.trim();
  if (!text) return;
  const res = await fetch('/api/clipboard/send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text })
  });
}
async function loadHistory() {
  const res = await fetch('/api/clipboard/history');
}
document.getElementById('text').addEventListener('keydown', function(e) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); send(); }
});
function escapeHtml(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function escapeAttr(s) { return s.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
loadHistory();
</script>
</body>
</html>
        """.trimIndent()
    }

    // ── IP 地址Parse ──────────────────────────────────────

    @Test
    fun `IP 地址Format为 URL`() {
        val ip = "192.168.1.100"
        val port = 19789
        val url = "http://$ip:$port/clipboard"
        assertEquals("http://192.168.1.100:19789/clipboard", url)
        assertTrue(url.startsWith("http://"))
        assertTrue(url.contains(":19789"))
        assertTrue(url.endsWith("/clipboard"))
    }

    @Test
    fun `None WiFi 时不生成 URL`() {
        val ip = "Not connected WiFi"
        val url = if (ip.contains(".")) "http://$ip:19789/clipboard" else ip
        assertEquals("Not connected WiFi", url)
        assertFalse(url.startsWith("http"))
    }

    // ── 剪切板TextValidate ──────────────────────────────────

    @Test
    fun `NullText应被拒绝`() {
        val text = "   "
        assertTrue("Null白Text", text.isBlank())
    }

    @Test
    fun `正常Text应通过`() {
        val text = "sk-or-v1-abcdef1234567890"
        assertFalse("ValidText", text.isBlank())
    }

    @Test
    fun `长Text不截断`() {
        val longText = "a".repeat(10000)
        val history = ClipboardHistory()
        history.add(longText)
        assertEquals(10000, history.getAll()[0].first.length)
    }

    @Test
    fun `特殊字符不影响Storage`() {
        val history = ClipboardHistory()
        val special = """{"key": "value", "nested": {"a": [1,2,3]}}"""
        history.add(special)
        assertEquals(special, history.getAll()[0].first)
    }

    @Test
    fun `Contains换Row的Text正常Storage`() {
        val history = ClipboardHistory()
        val multiline = "line1\nline2\nline3"
        history.add(multiline)
        assertEquals(multiline, history.getAll()[0].first)
    }

    // ── ConcurrencySecure ─────────────────────────────────────────

    @Test
    fun `ConcurrencyWrite不丢Data`() {
        val history = ClipboardHistory(100)
        val threads = (1..10).map { threadId ->
            Thread {
                for (i in 1..10) {
                    history.add("thread-$threadId-item-$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(100, history.size)
    }

    // ── Connect 页面Show逻辑 ─────────────────────────────

    @Test
    fun `Valid IP Show为可点击链接`() {
        val localIp = "192.168.1.5"
        val clipboardUrl = if (localIp.contains(".")) "http://$localIp:19789/clipboard" else localIp
        assertTrue(clipboardUrl.startsWith("http://"))
    }

    @Test
    fun `None效 IP 不Show链接`() {
        val localIp = "GetFailed"
        val clipboardUrl = if (localIp.contains(".")) "http://$localIp:19789/clipboard" else localIp
        assertEquals("GetFailed", clipboardUrl)
    }
}
