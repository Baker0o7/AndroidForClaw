/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-runtime-config.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import android.content.context
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * config RPC methods implementation
 *
 * Provides configuration management
 */
class configMethods(
    private val context: context
) {
    private val configLoader = configLoader(context)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath = StoragePaths.openclawconfig.absolutePath

    /**
     * config.get() - Get current configuration
     */
    fun configGet(params: Any?): configGetResult {
        @Suppress("UNCHECKED_CAST")
        val paramsMap = params as? Map<String, Any?>
        val path = paramsMap?.get("path") as? String

        return try {
            val config = configLoader.loadOpenClawconfig()
            val configMap = gson.fromJson(gson.toJson(config), Map::class.java)

            // if path is specified, return specific field
            val value = if (path != null) {
                getValueByPath(configMap, path)
            } else {
                configMap
            }

            configGetResult(
                success = true,
                config = value,
                path = configPath
            )
        } catch (e: exception) {
            configGetResult(
                success = false,
                error = "Failed to get config: ${e.message}",
                path = configPath
            )
        }
    }

    /**
     * config.set() - Set configuration value
     */
    fun configSet(params: Any?): configSetResult {
        @Suppress("UNCHECKED_CAST")
        val paramsMap = params as? Map<String, Any?>
            ?: return configSetResult(false, "params must be an object")

        val path = paramsMap["path"] as? String
            ?: return configSetResult(false, "path required")

        val value = paramsMap["value"]
            ?: return configSetResult(false, "value required")

        return try {
            val configFile = File(configPath)
            if (!configFile.exists()) {
                return configSetResult(false, "config file not found")
            }

            val configText = configFile.readText()
            @Suppress("UNCHECKED_CAST")
            val config = gson.fromJson(configText, MutableMap::class.java) as MutableMap<String, Any?>

            // Set value
            setValueByPath(config, path, value)

            // Write back to file
            configFile.writeText(gson.toJson(config))

            configSetResult(
                success = true,
                message = "config updated: $path",
                path = configPath
            )
        } catch (e: exception) {
            configSetResult(
                success = false,
                message = "Failed to set config: ${e.message}"
            )
        }
    }

    /**
     * config.reload() - Reload configuration
     */
    fun configReload(): configReloadResult {
        return try {
            configLoader.loadOpenClawconfig()
            configReloadResult(
                success = true,
                message = "config reloaded"
            )
        } catch (e: exception) {
            configReloadResult(
                success = false,
                message = "Failed to reload config: ${e.message}"
            )
        }
    }

    /**
     * Get value by path (supports dot notation, e.g. "agent.maxIterations")
     */
    @Suppress("UNCHECKED_CAST")
    private fun getValueByPath(config: Any?, path: String): Any? {
        if (config !is Map<*, *>) return null

        val parts = path.split(".")
        var current: Any? = config

        for (part in parts) {
            if (current is Map<*, *>) {
                current = current[part]
            } else {
                return null
            }
        }

        return current
    }

    /**
     * Set value by path
     */
    @Suppress("UNCHECKED_CAST")
    private fun setValueByPath(config: MutableMap<String, Any?>, path: String, value: Any?) {
        val parts = path.split(".")
        var current: MutableMap<String, Any?> = config

        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            if (current[part] !is MutableMap<*, *>) {
                current[part] = mutableMapOf<String, Any?>()
            }
            current = current[part] as MutableMap<String, Any?>
        }

        current[parts.last()] = value
    }
}

/**
 * config get result
 */
data class configGetResult(
    val success: Boolean,
    val config: Any? = null,
    val error: String? = null,
    val path: String? = null
)

/**
 * config set result
 */
data class configSetResult(
    val success: Boolean,
    val message: String,
    val path: String? = null
)

/**
 * config reload result
 */
data class configReloadResult(
    val success: Boolean,
    val message: String
)
