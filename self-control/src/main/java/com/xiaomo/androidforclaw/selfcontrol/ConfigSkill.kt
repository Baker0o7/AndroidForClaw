package com.xiaomo.androidforclaw.selfcontrol

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import com.xiaomo.androidforclaw.selfcontrol.Skill
import com.xiaomo.androidforclaw.selfcontrol.SkillResult
import com.xiaomo.androidforclaw.selfcontrol.FunctionDefinition
import com.xiaomo.androidforclaw.selfcontrol.ParametersSchema
import com.xiaomo.androidforclaw.selfcontrol.PropertySchema
import com.xiaomo.androidforclaw.selfcontrol.ToolDefinition

/**
 * Self-Control Config Skill
 *
 * Read and modify PhoneForClaw configuration parameters, allowing AI Agent to:
 * - View current configuration
 * - Modify runtime parameters
 * - Toggle feature switches
 * - Adjust performance parameters
 *
 * Use cases:
 * - AI self-optimization (adjust timeouts, retry counts, etc.)
 * - Feature switch management
 * - Remote configuration updates
 * - A/B testing
 */
class ConfigSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ConfigSkill"

        // Operation types
        object Operations {
            const val GET = "get"              // Read config
            const val SET = "set"              // Set config
            const val LIST = "list"            // List all configs
            const val DELETE = "delete"        // Delete config
        }

        // Config categories
        object Categories {
            const val AGENT = "agent"          // Agent config
            const val API = "api"              // API config
            const val UI = "ui"                // UI config
            const val FEATURE = "feature"      // Feature switches
            const val PERFORMANCE = "perf"     // Performance parameters
        }
    }

    override val name = "manage_config"

    override val description = """
        Manage PhoneForClaw application configuration.

        Supported operations:
        - get: Read specified config item
        - set: Set config item value
        - list: List all configs in a category
        - delete: Delete config item

        Config categories:
        - agent: Agent runtime parameters (max_iterations, timeout, etc.)
        - api: API settings (base_url, api_key, model, etc.)
        - ui: UI preferences (theme, language, etc.)
        - feature: Feature switches (exploration_mode, reasoning_enabled, etc.)
        - perf: Performance parameters (screenshot_delay, ui_tree_enabled, etc.)

        Examples:
        - Read: {"operation": "get", "key": "exploration_mode"}
        - Set: {"operation": "set", "key": "exploration_mode", "value": true}
        - List: {"operation": "list", "category": "feature"}

        Note: Modifying some configs may require restarting the app to take effect.
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "operation" to PropertySchema(
                            type = "string",
                            description = "Operation type",
                            enum = listOf(
                                Operations.GET,
                                Operations.SET,
                                Operations.LIST,
                                Operations.DELETE
                            )
                        ),
                        "key" to PropertySchema(
                            type = "string",
                            description = "Config key (required for get/set/delete)"
                        ),
                        "value" to PropertySchema(
                            type = "string",
                            description = "Config value (required for set, supports string/number/boolean)"
                        ),
                        "category" to PropertySchema(
                            type = "string",
                            description = "Config category (optional for list)",
                            enum = listOf(
                                Categories.AGENT,
                                Categories.API,
                                Categories.UI,
                                Categories.FEATURE,
                                Categories.PERFORMANCE
                            )
                        )
                    ),
                    required = listOf("operation")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val operation = args["operation"] as? String
            ?: return SkillResult.error("Missing required parameter: operation")

        val mmkv = MMKV.defaultMMKV()

        return try {
            when (operation) {
                Operations.GET -> handleGet(mmkv, args)
                Operations.SET -> handleSet(mmkv, args)
                Operations.LIST -> handleList(mmkv, args)
                Operations.DELETE -> handleDelete(mmkv, args)
                else -> SkillResult.error("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config operation failed: $operation", e)
            SkillResult.error("Config operation failed: ${e.message}")
        }
    }

    private fun handleGet(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        if (!mmkv.contains(key)) {
            return SkillResult.error("Config item not found: $key")
        }

        // Try different types
        val value: Any? = when {
            mmkv.decodeInt(key, Int.MIN_VALUE) != Int.MIN_VALUE -> mmkv.decodeInt(key)
            mmkv.decodeLong(key, Long.MIN_VALUE) != Long.MIN_VALUE -> mmkv.decodeLong(key)
            mmkv.decodeFloat(key, Float.MIN_VALUE) != Float.MIN_VALUE -> mmkv.decodeFloat(key)
            mmkv.decodeDouble(key, Double.MIN_VALUE) != Double.MIN_VALUE -> mmkv.decodeDouble(key)
            else -> {
                val str = mmkv.decodeString(key, null)
                if (str != null) str
                else mmkv.decodeBool(key, false)
            }
        }

        return SkillResult.success(
            "Config item '$key' = $value",
            mapOf(
                "key" to key,
                "value" to value,
                "type" to value?.javaClass?.simpleName
            )
        )
    }

    private fun handleSet(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        val valueStr = args["value"] as? String
            ?: return SkillResult.error("Missing parameter: value")

        // Smart type conversion
        val success = when {
            valueStr.equals("true", ignoreCase = true) -> {
                mmkv.encode(key, true)
            }
            valueStr.equals("false", ignoreCase = true) -> {
                mmkv.encode(key, false)
            }
            valueStr.toIntOrNull() != null -> {
                mmkv.encode(key, valueStr.toInt())
            }
            valueStr.toLongOrNull() != null -> {
                mmkv.encode(key, valueStr.toLong())
            }
            valueStr.toFloatOrNull() != null -> {
                mmkv.encode(key, valueStr.toFloat())
            }
            valueStr.toDoubleOrNull() != null -> {
                mmkv.encode(key, valueStr.toDouble())
            }
            else -> {
                mmkv.encode(key, valueStr)
            }
        }

        return if (success) {
            SkillResult.success(
                "Config updated: $key = $valueStr",
                mapOf("key" to key, "value" to valueStr)
            )
        } else {
            SkillResult.error("Config update failed")
        }
    }

    private fun handleList(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val category = args["category"] as? String

        val allKeys = mmkv.allKeys() ?: emptyArray()

        // Filter by category (simple prefix matching)
        val filteredKeys = if (category != null) {
            allKeys.filter { it.startsWith(category, ignoreCase = true) }
        } else {
            allKeys.toList()
        }

        val configList = filteredKeys.sorted().joinToString("\n") { key ->
            val value = mmkv.decodeString(key, "[unknown]")
            "  - $key = $value"
        }

        val summary = if (category != null) {
            "[$category Config] (${filteredKeys.size} items)\n$configList"
        } else {
            "[All Config] (${filteredKeys.size} items)\n$configList"
        }

        return SkillResult.success(
            summary,
            mapOf(
                "category" to (category ?: "all"),
                "count" to filteredKeys.size,
                "keys" to filteredKeys
            )
        )
    }

    private fun handleDelete(mmkv: MMKV, args: Map<String, Any?>): SkillResult {
        val key = args["key"] as? String
            ?: return SkillResult.error("Missing parameter: key")

        if (!mmkv.contains(key)) {
            return SkillResult.error("Config item not found: $key")
        }

        mmkv.remove(key)

        return SkillResult.success(
            "Config item deleted: $key",
            mapOf("key" to key)
        )
    }
}
