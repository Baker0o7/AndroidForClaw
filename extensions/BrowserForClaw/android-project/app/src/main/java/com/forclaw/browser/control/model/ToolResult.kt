/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tools/browser/(all)
 *
 * AndroidForClaw adaptation: browser tool client.
 */
package com.forclaw.browser.control.model

/**
 * Tool execution result
 *
 * @property success Success or not
 * @property data Return data
 * @property error Error info (only when success = false)
 */
data class Toolresult(
    val success: Boolean,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null
) {
    companion object {
        /**
         * Create success result
         */
        fun success(data: Map<String, Any?> = emptyMap()): Toolresult {
            return Toolresult(success = true, data = data, error = null)
        }

        /**
         * Create success result (convenience method)
         */
        fun success(vararg pairs: Pair<String, Any?>): Toolresult {
            return success(mapOf(*pairs))
        }

        /**
         * Create error result
         */
        fun error(message: String): Toolresult {
            return Toolresult(success = false, data = emptyMap(), error = message)
        }
    }

    /**
     * Convert to JSON String (for Broadcast transmission)
     */
    fun toJson(): String {
        val dataJson = data.entries.joinToString(",") { (key, value) ->
            """"$key":${valueToJson(value)}"""
        }
        return """{"success":$success,"data":{$dataJson}${if (error != null) ""","error":"${error.escape()}"""" else ""}}"""
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> """"${value.escape()}""""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> """"${value.toString().escape()}""""
    }

    private fun String.escape(): String {
        return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
