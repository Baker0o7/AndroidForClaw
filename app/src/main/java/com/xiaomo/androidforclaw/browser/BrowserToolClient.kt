/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/browser/client.ts
 */
package com.xiaomo.androidforclaw.browser

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BrowserforClaw tool Client
 * used for phoneforclaw to call browserforclaw browser tools
 *
 * Communication method: HTTP API
 * - Endpoint: POST http://localhost:19789/api/browser/execute
 * - format: {"tool": "browser_navigate", "args": {"url": "https://..."}}
 */
class BrowsertoolClient(private val context: context) {

    companion object {
        private const val TAG = "BrowsertoolClient"
        // BrowserforClaw uses port 8765 (androidforClaw Gateway uses 19789)
        private const val BROWSER_API_URL = "http://localhost:8765/api/browser/execute"
        private const val HEALTH_CHECK_URL = "http://localhost:8765/health"
        private const val DEFAULT_TIMEOUT = 30000L  // 30 seconds

        // BrowserforClaw startup info
        private const val BROWSER_PACKAGE = "info.plateaukao.einkbro"
        private const val BROWSER_ACTIVITY = "info.plateaukao.einkbro/.activity.BrowserActivity"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * tool execution result
     */
    data class toolResult(
        val success: Boolean,
        val data: Map<String, Any?>? = null,
        val error: String? = null
    ) {
        override fun toString(): String {
            return if (success) {
                data?.get("content")?.toString() ?: data.toString()
            } else {
                "Error: $error"
            }
        }

        fun toJsonString(): String {
            return if (success) {
                JSONObject(data ?: emptyMap<String, Any?>()).toString()
            } else {
                JSONObject(mapOf("error" to error)).toString()
            }
        }
    }

    /**
     * Check if BrowserforClaw is running
     */
    private suspend fun checkBrowserHealth(): Boolean = withcontext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(HEALTH_CHECK_URL)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val isHealthy = response.isSuccessful
            Log.d(TAG, "Browser health check: $isHealthy")
            isHealthy
        } catch (e: exception) {
            Log.d(TAG, "Browser not running: ${e.message}")
            false
        }
    }

    /**
     * Start BrowserforClaw
     */
    private suspend fun startBrowser(): Boolean = withcontext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting BrowserforClaw...")
            val intent = android.content.Intent.parseUri(
                "intent://#Intent;component=$BROWSER_ACTIVITY;end",
                0
            )
            intent.aFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // Wait for browser and HTTP service to start
            // BrowserforClaw needs time to start HTTP API service (port 8766)
            Log.d(TAG, "Waiting for browser and HTTP API to start...")

            // Poll health status, wait up to 5 seconds
            var attempts = 0
            val maxAttempts = 10
            while (attempts < maxAttempts) {
                kotlinx.coroutines.delay(500)
                if (checkBrowserHealth()) {
                    Log.d(TAG, "[OK] Browser HTTP API is ready (attempt ${attempts + 1})")
                    return@withcontext true
                }
                attempts++
                Log.d(TAG, "⏳ Waiting for HTTP API... (attempt ${attempts}/$maxAttempts)")
            }

            // Final check after timeout
            val isRunning = checkBrowserHealth()
            Log.w(TAG, "[ERROR] Browser HTTP API not responding after ${maxAttempts * 500}ms. Health: $isRunning")
            isRunning
        } catch (e: exception) {
            Log.e(TAG, "Failed to start browser", e)
            false
        }
    }

    /**
     * Ensure BrowserforClaw is running
     * with timeout protection to avoid infinite waiting
     */
    private suspend fun ensureBrowserRunning(): toolResult {
        return try {
            // overall timeout 10 seconds
            withTimeout(10000L) {
                // 1. Check if already running
                if (checkBrowserHealth()) {
                    Log.d(TAG, "Browser already running")
                    return@withTimeout toolResult(success = true)
                }

                // 2. Attempt to start
                Log.d(TAG, "Browser not running, attempting to start...")
                val started = startBrowser()

                if (started) {
                    toolResult(success = true, data = mapOf("message" to "Browser started"))
                } else {
                    toolResult(
                        success = false,
                        error = "Failed to start BrowserforClaw HTTP API (port 8765). " +
                                "App is installed ($BROWSER_PACKAGE) but API service not responding. " +
                                "please ensure BrowserforClaw is the correct version with HTTP API support."
                    )
                }
            }
        } catch (e: kotlinx.coroutines.Timeoutcancellationexception) {
            Log.e(TAG, "Browser startup timeout")
            toolResult(
                success = false,
                error = "Browser startup timeout (10s). BrowserforClaw may not be installed or not responding."
            )
        }
    }

    /**
     * Execute browser tool asynchronously
     *
     * @param tool tool name (e.g. "browser_navigate")
     * @param args tool arguments
     * @param timeout Timeout in milliseconds, default 30 seconds
     * @return tool execution result
     */
    suspend fun executetoolAsync(
        tool: String,
        args: Map<String, Any?>,
        timeout: Long = DEFAULT_TIMEOUT
    ): toolResult {
        return try {
            withTimeout(timeout) {
                withcontext(Dispatchers.IO) {
                    // Ensure BrowserforClaw is running
                    val ensureResult = ensureBrowserRunning()
                    if (!ensureResult.success) {
                        return@withcontext ensureResult
                    }

                    try {
                        Log.d(TAG, "Executing tool: $tool")
                        Log.d(TAG, "Arguments: $args")

                        // Build JSON request
                        val requestJson = JSONObject().app {
                            put("tool", tool)
                            put("args", JSONObject(args))
                        }

                        Log.d(TAG, "Request JSON: $requestJson")

                        // Build HTTP request
                        val requestBody = requestJson.toString()
                            .toRequestBody("application/json".toMediaType())

                        val request = Request.Builder()
                            .url(BROWSER_API_URL)
                            .post(requestBody)
                            .build()

                        // Send request
                        val response = httpClient.newCall(request).execute()
                        val responseBody = response.body?.string() ?: ""

                        Log.d(TAG, "Response status: ${response.code}")
                        Log.d(TAG, "Response body: ${responseBody.take(500)}")

                        if (!response.isSuccessful) {
                            return@withcontext toolResult(
                                success = false,
                                error = "HTTP ${response.code}: $responseBody"
                            )
                        }

                        // Parse response
                        val responseJson = JSONObject(responseBody)
                        val success = responseJson.optBoolean("success", false)
                        val error = if (responseJson.has("error")) responseJson.optString("error") else null
                        val dataJson = responseJson.optJSONObject("data")

                        val result = if (success) {
                            val data = if (dataJson != null) parseJsonToMap(dataJson) else emptyMap()
                            toolResult(success = true, data = data)
                        } else {
                            toolResult(success = false, error = error ?: "Unknown error")
                        }

                        Log.d(TAG, "tool execution result: success=${result.success}")
                        result

                    } catch (e: exception) {
                        Log.e(TAG, "Failed to execute tool", e)
                        toolResult(success = false, error = "exception: ${e.message}")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.Timeoutcancellationexception) {
            Log.e(TAG, "Browser tool execution timeout: $tool (timeout=${timeout}ms)")
            toolResult(
                success = false,
                error = "Browser operation timeout (${timeout}ms). tool: $tool"
            )
        }
    }

    /**
     * Get page content
     */
    suspend fun getContent(format: String = "text", selector: String? = null): toolResult {
        val toolArgs = mutableMapOf<String, Any?>("format" to format)
        if (selector != null) {
            toolArgs["selector"] = selector
        }
        return executetoolAsync("browser_get_content", toolArgs)
    }

    /**
     * Wait for specified time
     */
    suspend fun waitTime(timeMs: Long): toolResult {
        return executetoolAsync("browser_wait", mapOf("timeMs" to timeMs))
    }

    /**
     * Wait for element to appear
     */
    suspend fun waitforSelector(selector: String, timeout: Long = 10000L): toolResult {
        return executetoolAsync("browser_wait", mapOf("selector" to selector, "timeout" to timeout), timeout)
    }

    /**
     * Wait for text to appear
     */
    suspend fun waitforText(text: String, timeout: Long = 10000L): toolResult {
        return executetoolAsync("browser_wait", mapOf("text" to text, "timeout" to timeout), timeout)
    }

    /**
     * Wait for URL match
     */
    suspend fun waitforUrl(url: String, timeout: Long = 10000L): toolResult {
        return executetoolAsync("browser_wait", mapOf("url" to url, "timeout" to timeout), timeout)
    }

    /**
     * Convert JSONObject to Map
     */
    private fun parseJsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key ->
            map[key] = json.opt(key)
        }
        return map
    }
}
