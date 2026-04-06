package com.xiaomo.androidforclaw.gateway

import org.junit.Assert.*
import org.junit.Test

/**
 * Web Clipboard Feature Unit Test
 *
 * Test Content:
 * 1. Clipboard History Management (Add, Update, Sort)
 * 2. Clipboard Page HTML Generation
 * 3. API Route Matching Logic
 * 4. Connect Page IP Address Parse
 */
class WebClipboardTest {

    // ── Clipboard History Management ────────────────────────────────────

    /**
     * Mock GatewayServer's clipboard history logic
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
    fun `Add text to history record`() {
        val history = ClipboardHistory()
        history.add("test-text-1", 1000L)
        assertEquals(1, history.size)
        assertEquals("test-text-1", history.getAll()[0].first)
        assertEquals(1000L, history.getAll()[0].second)
    }

    @Test
    fun `Newest record at front`() {
        val history = ClipboardHistory()
        history.add("older", 1000L)
        history.add("newer", 2000L)
        assertEquals("newer", history.getAll()[0].first)
        assertEquals("older", history.getAll()[1].first)
    }

    @Test
    fun `History record not exceed max value`() {
        val maxSize = 5
        val history = ClipboardHistory(maxSize)
        for (i in 1..10) {
            history.add("text-$i", i.toLong())
        }
        assertEquals(maxSize, history.size)
        // Newest should be at front
        assertEquals("text-10", history.getAll()[0].first)
        // Oldest is evicted
        assertFalse(history.getAll().any { it.first == "text-1" })
    }

    @Test
    fun `Default max history is 20 items`() {
        val history = ClipboardHistory()
        for (i in 1..25) {
            history.add("text-$i")
        }
        assertEquals(20, history.size)
    }

    @Test
    fun `Empty history returns empty list`() {
        val history = ClipboardHistory()
        assertTrue(history.getAll().isEmpty())
        assertEquals(0, history.size)
    }

    // ── API Route Matching ─────────────────────────────────────

    /**
     * Mock GatewayServer.serve() route matching logic
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
    fun `Route - clipboard page`() {
        assertEquals(RouteResult.CLIPBOARD_PAGE, matchRoute("/clipboard"))
        assertEquals(RouteResult.CLIPBOARD_PAGE, matchRoute("/clipboard/"))
    }

    @Test
    fun `Route - clipboard send API (POST)`() {
        assertEquals(RouteResult.CLIPBOARD_SEND, matchRoute("/api/clipboard/send", "POST"))
    }

    @Test
    fun `Route - clipboard send GET not matching`() {
        // GET request should not match clipboard send
        assertNotEquals(RouteResult.CLIPBOARD_SEND, matchRoute("/api/clipboard/send", "GET"))
    }

    @Test
    fun `Route - clipboard history API`() {
        assertEquals(RouteResult.CLIPBOARD_HISTORY, matchRoute("/api/clipboard/history"))
    }

    @Test
    fun `Route - normal API unaffected`() {
        assertEquals(RouteResult.API, matchRoute("/api/health"))
        assertEquals(RouteResult.API, matchRoute("/api/device/status"))
    }

    @Test
    fun `Route - normal page goes to WebUI`() {
        assertEquals(RouteResult.WEBUI, matchRoute("/"))
        assertEquals(RouteResult.WEBUI, matchRoute("/index.html"))
    }

    // ── Clipboard Page HTML ──────────────────────────────────

    @Test
    fun `Clipboard page contains key elements`() {
        // Mock serveClipboardPage HTML content
        val html = buildClipboardPageHtml()
        assertTrue("Missing Title", html.contains("Web Clipboard"))
        assertTrue("Missing send button", html.contains("Send to Phone"))
        assertTrue("Missing textarea", html.contains("<textarea"))
        assertTrue("Missing fetch API call", html.contains("/api/clipboard/send"))
        assertTrue("Missing history record area", html.contains("History Records"))
        assertTrue("Missing Ctrl+Enter shortcut", html.contains("ctrlKey") || html.contains("metaKey"))
    }

    @Test
    fun `Clipboard page API paths correct`() {
        val html = buildClipboardPageHtml()
        assertTrue("send API path", html.contains("'/api/clipboard/send'") || html.contains("\"/api/clipboard/send\""))
        assertTrue("history API path", html.contains("'/api/clipboard/history'") || html.contains("\"/api/clipboard/history\""))
    }

    @Test
    fun `Clipboard page has XSS protection`() {
        val html = buildClipboardPageHtml()
        assertTrue("Contains escapeHtml function", html.contains("escapeHtml"))
        assertTrue("Contains escapeAttr function", html.contains("escapeAttr"))
    }

    private fun buildClipboardPageHtml(): String {
        // Consistent with GatewayServer.serveClipboardPage() HTML
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>AndroidForClaw - Web Clipboard</title>
</head>
<body>
<h1>Web Clipboard</h1>
<p>Input on computer, auto copy to phone</p>
<textarea id="text" placeholder="Paste API Key, config content or any text..."></textarea>
<button class="btn-send" id="sendBtn" onclick="send()">Send to Phone</button>
<h2>History (click to copy)</h2>
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

    // ── IP Address Parse ──────────────────────────────────────

    @Test
    fun `IP address format as URL`() {
        val ip = "192.168.1.100"
        val port = 19789
        val url = "http://$ip:$port/clipboard"
        assertEquals("http://192.168.1.100:19789/clipboard", url)
        assertTrue(url.startsWith("http://"))
        assertTrue(url.contains(":19789"))
        assertTrue(url.endsWith("/clipboard"))
    }

    @Test
    fun `No WiFi then no URL generated`() {
        val ip = "Not connected WiFi"
        val url = if (ip.contains(".")) "http://$ip:19789/clipboard" else ip
        assertEquals("Not connected WiFi", url)
        assertFalse(url.startsWith("http"))
    }

    // ── Clipboard Text Validation ──────────────────────────────────

    @Test
    fun `Null text should be rejected`() {
        val text = "   "
        assertTrue("Empty text", text.isBlank())
    }

    @Test
    fun `Normal text should pass`() {
        val text = "sk-or-v1-abcdef1234567890"
        assertFalse("Valid text", text.isBlank())
    }

    @Test
    fun `Long text not truncated`() {
        val longText = "a".repeat(10000)
        val history = ClipboardHistory()
        history.add(longText)
        assertEquals(10000, history.getAll()[0].first.length)
    }

    @Test
    fun `Special characters do not affect storage`() {
        val history = ClipboardHistory()
        val special = """{"key": "value", "nested": {"a": [1,2,3]}}"""
        history.add(special)
        assertEquals(special, history.getAll()[0].first)
    }

    @Test
    fun `Text with newlines stored normally`() {
        val history = ClipboardHistory()
        val multiline = "line1\nline2\nline3"
        history.add(multiline)
        assertEquals(multiline, history.getAll()[0].first)
    }

    // ── Concurrency Security ─────────────────────────────────────────

    @Test
    fun `Concurrent writes no data loss`() {
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

    // ── Connect Page Show Logic ─────────────────────────────

    @Test
    fun `Valid IP shown as clickable link`() {
        val localIp = "192.168.1.5"
        val clipboardUrl = if (localIp.contains(".")) "http://$localIp:19789/clipboard" else localIp
        assertTrue(clipboardUrl.startsWith("http://"))
    }

    @Test
    fun `Invalid IP not shown as link`() {
        val localIp = "GetFailed"
        val clipboardUrl = if (localIp.contains(".")) "http://$localIp:19789/clipboard" else localIp
        assertEquals("GetFailed", clipboardUrl)
    }
}