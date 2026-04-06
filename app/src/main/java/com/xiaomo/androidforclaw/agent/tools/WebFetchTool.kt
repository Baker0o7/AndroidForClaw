package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/web-fetch.ts
 *
 * androidforClaw adaptation: web fetch tool.
 */


import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Web Fetch tool - Fetch web page content
 * Reference: nanobot's WebFetchtool
 */
class WebFetchtool(
    private val maxChars: Int = 50000
) : tool {
    companion object {
        private const val TAG = "WebFetchtool"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; android 10) AppleWebKit/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "web_fetch"
    override val description = "Fetch and extract content from a URL"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "url" to Propertyschema("string", "needGet URL"),
                        "max_chars" to Propertyschema("integer", "MaxReturncharacters, Default 50000")
                    ),
                    required = listOf("url")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val url = args["url"] as? String
        val maxCharsParam = (args["max_chars"] as? Number)?.toInt() ?: maxChars

        if (url == null) {
            return toolresult.error("Missing required parameter: url")
        }

        // URL validation
        if (!url.startswith("http://") && !url.startswith("https://")) {
            return toolresult.error("URL must start with http:// or https://")
        }

        Log.d(TAG, "Fetching URL: $url")
        return withcontext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("user-agent", USER_AGENT)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withcontext toolresult.error("HTTP ${response.code}: ${response.message}")
                }

                val contentType = response.header("Content-Type") ?: ""
                val body = response.body?.string() ?: ""

                // Simple content extraction (strip HTML tags)
                val content = when {
                    contentType.contains("application/json", ignoreCase = true) -> {
                        // Return JSON content directly
                        body
                    }
                    contentType.contains("text/html", ignoreCase = true) -> {
                        // Simple HTML cleanup
                        stripHtmlTags(body)
                    }
                    else -> {
                        // Other text content
                        body
                    }
                }

                // Truncate overly long content
                val finalContent = if (content.length > maxCharsParam) {
                    content.take(maxCharsParam) + "\n... (truncated, ${content.length - maxCharsParam} more chars)"
                } else {
                    content
                }

                toolresult.success(finalContent, mapOf("url" to url, "length" to content.length))
            } catch (e: exception) {
                Log.e(TAG, "Web fetch failed", e)
                toolresult.error("Web fetch failed: ${e.message}")
            }
        }
    }

    /**
     * Simple HTML tag cleanup
     */
    private fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
