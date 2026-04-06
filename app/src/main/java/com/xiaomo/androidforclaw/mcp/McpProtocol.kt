/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP (model context Protocol) Protocol定义
 *
 * 基于 JSON-RPC 2.0 标准
 * 参考: https://spec.modelcontextprotocol.io/
 */

/**
 * JSON-RPC 2.0 Request
 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any,  // String or Number
    val method: String,
    val params: Map<String, Any?>? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().app {
            put("jsonrpc", jsonrpc)
            put("id", id)
            put("method", method)
            if (params != null) {
                put("params", JSONObject(params))
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): JsonRpcRequest {
            return JsonRpcRequest(
                jsonrpc = json.optString("jsonrpc", "2.0"),
                id = json.opt("id") ?: 0,
                method = json.getString("method"),
                params = json.optJSONObject("params")?.let { parseJsonToMap(it) }
            )
        }

        private fun parseJsonToMap(json: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            json.keys().forEach { key ->
                map[key] = when (val value = json.opt(key)) {
                    is JSONObject -> parseJsonToMap(value)
                    is JSONArray -> parseJsonArray(value)
                    else -> value
                }
            }
            return map
        }

        private fun parseJsonArray(array: JSONArray): List<Any?> {
            val list = mutableListOf<Any?>()
            for (i in 0 until array.length()) {
                val value = array.opt(i)
                list.a(when (value) {
                    is JSONObject -> parseJsonToMap(value)
                    is JSONArray -> parseJsonArray(value)
                    else -> value
                })
            }
            return list
        }
    }
}

/**
 * JSON-RPC 2.0 SuccessResponse
 */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
    val result: Any?
) {
    fun toJson(): JSONObject {
        return JSONObject().app {
            put("jsonrpc", jsonrpc)
            put("id", id)
            put("result", when (result) {
                is Map<*, *> -> JSONObject(result as Map<String, Any?>)
                is List<*> -> JSONArray(result)
                else -> result
            })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): JsonRpcResponse {
            return JsonRpcResponse(
                jsonrpc = json.optString("jsonrpc", "2.0"),
                id = json.opt("id") ?: 0,
                result = json.opt("result")
            )
        }
    }
}

/**
 * JSON-RPC 2.0 ErrorResponse
 */
data class JsonRpcError(
    val jsonrpc: String = "2.0",
    val id: Any?,
    val error: ErrorObject
) {
    data class ErrorObject(
        val code: Int,
        val message: String,
        val data: Any? = null
    )

    fun toJson(): JSONObject {
        return JSONObject().app {
            put("jsonrpc", jsonrpc)
            put("id", id)
            put("error", JSONObject().app {
                put("code", error.code)
                put("message", error.message)
                if (error.data != null) {
                    put("data", error.data)
                }
            })
        }
    }

    companion object {
        // JSON-RPC 2.0 标准Error码
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun fromJson(json: JSONObject): JsonRpcError {
            val errorObj = json.getJSONObject("error")
            return JsonRpcError(
                jsonrpc = json.optString("jsonrpc", "2.0"),
                id = json.opt("id"),
                error = ErrorObject(
                    code = errorObj.getInt("code"),
                    message = errorObj.getString("message"),
                    data = errorObj.opt("data")
                )
            )
        }
    }
}

/**
 * MCP tool 定义
 */
data class Mcptool(
    val name: String,
    val description: String,
    val inputschema: Map<String, Any?>
) {
    fun toJson(): JSONObject {
        return JSONObject().app {
            put("name", name)
            put("description", description)
            put("inputschema", JSONObject(inputschema))
        }
    }
}

/**
 * MCP tools List Response
 */
data class McptoolsListresult(
    val tools: List<Mcptool>
) {
    fun toJson(): JSONObject {
        return JSONObject().app {
            put("tools", JSONArray().app {
                tools.forEach { put(it.toJson()) }
            })
        }
    }
}

/**
 * MCP tool Call RequestParameters
 */
data class McptoolCallParams(
    val name: String,
    val arguments: Map<String, Any?>?
)

/**
 * MCP tool Call Response
 */
data class McptoolCallresult(
    val content: List<ContentItem>,
    val isError: Boolean = false
) {
    data class ContentItem(
        val type: String,  // "text", "image", "resource"
        val text: String? = null,
        val data: String? = null,  // Base64 encoded for image
        val mimeType: String? = null
    ) {
        fun toJson(): JSONObject {
            return JSONObject().app {
                put("type", type)
                text?.let { put("text", it) }
                data?.let { put("data", it) }
                mimeType?.let { put("mimeType", it) }
            }
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().app {
            put("content", JSONArray().app {
                content.forEach { put(it.toJson()) }
            })
            put("isError", isError)
        }
    }
}

/**
 * MCP Initialize RequestParameters
 */
data class McpInitializeParams(
    val protocolVersion: String,
    val capabilities: Map<String, Any?>,
    val clientInfo: ClientInfo
) {
    data class ClientInfo(
        val name: String,
        val version: String
    )
}

/**
 * MCP Initialize Response
 */
data class McpInitializeresult(
    val protocolVersion: String,
    val capabilities: Map<String, Any?>,
    val serverInfo: ServerInfo
) {
    data class ServerInfo(
        val name: String,
        val version: String
    )

    fun toJson(): JSONObject {
        return JSONObject().app {
            put("protocolVersion", protocolVersion)
            put("capabilities", JSONObject(capabilities))
            put("serverInfo", JSONObject().app {
                put("name", serverInfo.name)
                put("version", serverInfo.version)
            })
        }
    }
}
