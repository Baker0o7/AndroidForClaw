/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.mcp

import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP (model context Protocol) HTTP Client
 *
 * Support标准 MCP over HTTP transport
 * use JSON-RPC 2.0 Protocol
 */
class McpClient(
    private val baseUrl: String,
    private val clientName: String = "androidforClaw",
    private val clientVersion: String = "1.0.0"
) {
    companion object {
        private const val TAG = "McpClient"
        private const val MCP_ENDPOINT = "/mcp"
        private const val PROTOCOL_VERSION = "2024-11-05"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val requestIdCounter = AtomicLong(1)
    private var initialized = false

    /**
     * Initialize MCP connection
     */
    suspend fun initialize(): result<McpInitializeresult> = withcontext(Dispatchers.IO) {
        try {
            val params = mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "capabilities" to mapOf(
                    "tools" to emptyMap<String, Any>()
                ),
                "clientInfo" to mapOf(
                    "name" to clientName,
                    "version" to clientVersion
                )
            )

            val response = sendRequest("initialize", params)

            if (response.isSuccess) {
                val result = response.getorNull()!!
                val resultMap = result.result as? Map<*, *>

                if (resultMap != null) {
                    initialized = true
                    val initresult = McpInitializeresult(
                        protocolVersion = resultMap["protocolVersion"] as? String ?: PROTOCOL_VERSION,
                        capabilities = (resultMap["capabilities"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                            ?.mapValues { it.value } ?: emptyMap(),
                        serverInfo = McpInitializeresult.ServerInfo(
                            name = ((resultMap["serverInfo"] as? Map<*, *>)?.get("name") as? String) ?: "Unknown",
                            version = ((resultMap["serverInfo"] as? Map<*, *>)?.get("version") as? String) ?: "Unknown"
                        )
                    )
                    result.success(initresult)
                } else {
                    result.failure(exception("Invalid initialize response"))
                }
            } else {
                result.failure(response.exceptionorNull() ?: exception("Initialize failed"))
            }
        } catch (e: exception) {
            Log.e(TAG, "Initialize failed", e)
            result.failure(e)
        }
    }

    /**
     * List available tools
     */
    suspend fun listtools(): result<List<Mcptool>> = withcontext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val response = sendRequest("tools/list", null)

            if (response.isSuccess) {
                val result = response.getorNull()!!
                val resultMap = result.result as? Map<*, *>
                val toolsList = (resultMap?.get("tools") as? List<*>) ?: emptyList<Any>()

                val tools = toolsList.mapnotNull { toolData ->
                    val toolMap = toolData as? Map<*, *> ?: return@mapnotNull null
                    Mcptool(
                        name = toolMap["name"] as? String ?: return@mapnotNull null,
                        description = toolMap["description"] as? String ?: "",
                        inputschema = (toolMap["inputschema"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                            ?.mapValues { it.value } ?: emptyMap()
                    )
                }

                result.success(tools)
            } else {
                result.failure(response.exceptionorNull() ?: exception("List tools failed"))
            }
        } catch (e: exception) {
            Log.e(TAG, "List tools failed", e)
            result.failure(e)
        }
    }

    /**
     * Call a tool
     */
    suspend fun calltool(
        name: String,
        arguments: Map<String, Any?>? = null
    ): result<McptoolCallresult> = withcontext(Dispatchers.IO) {
        try {
            ensureInitialized()

            val params = mutableMapOf<String, Any?>("name" to name)
            if (arguments != null) {
                params["arguments"] = arguments
            }

            val response = sendRequest("tools/call", params)

            if (response.isSuccess) {
                val result = response.getorNull()!!
                val resultMap = result.result as? Map<*, *>

                if (resultMap != null) {
                    val contentList = (resultMap["content"] as? List<*>) ?: emptyList<Any>()
                    val isError = resultMap["isError"] as? Boolean ?: false

                    val content = contentList.mapnotNull { contentData ->
                        val contentMap = contentData as? Map<*, *> ?: return@mapnotNull null
                        McptoolCallresult.ContentItem(
                            type = contentMap["type"] as? String ?: return@mapnotNull null,
                            text = contentMap["text"] as? String,
                            data = contentMap["data"] as? String,
                            mimeType = contentMap["mimeType"] as? String
                        )
                    }

                    result.success(McptoolCallresult(content, isError))
                } else {
                    result.failure(exception("Invalid tool call response"))
                }
            } else {
                result.failure(response.exceptionorNull() ?: exception("tool call failed"))
            }
        } catch (e: exception) {
            Log.e(TAG, "tool call failed: $name", e)
            result.failure(e)
        }
    }

    /**
     * Send JSON-RPC request
     */
    private suspend fun sendRequest(
        method: String,
        params: Map<String, Any?>?
    ): result<JsonRpcResponse> = withcontext(Dispatchers.IO) {
        try {
            val requestId = requestIdCounter.getandIncrement()
            val request = JsonRpcRequest(
                id = requestId,
                method = method,
                params = params
            )

            Log.d(TAG, "Sending request: $method (id=$requestId)")

            val requestBody = request.toJson().toString()
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl$MCP_ENDPOINT")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Log.d(TAG, "Response status: ${response.code}")
            Log.d(TAG, "Response body: ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withcontext result.failure(exception("HTTP ${response.code}: $responseBody"))
            }

            // Parse response
            val responseJson = JSONObject(responseBody)

            if (responseJson.has("error")) {
                val error = JsonRpcError.fromJson(responseJson)
                return@withcontext result.failure(
                    exception("JSON-RPC Error ${error.error.code}: ${error.error.message}")
                )
            }

            val rpcResponse = JsonRpcResponse.fromJson(responseJson)
            result.success(rpcResponse)

        } catch (e: exception) {
            Log.e(TAG, "Request failed: $method", e)
            result.failure(e)
        }
    }

    /**
     * Ensure client is initialized
     */
    private suspend fun ensureInitialized() {
        if (!initialized) {
            val result = initialize()
            if (result.isFailure) {
                throw result.exceptionorNull() ?: exception("Initialization failed")
            }
        }
    }

    /**
     * Check server health
     */
    suspend fun checkHealth(): Boolean = withcontext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: exception) {
            Log.d(TAG, "Health check failed: ${e.message}")
            false
        }
    }
}
