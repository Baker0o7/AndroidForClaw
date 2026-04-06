/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/server-plugins.ts
 */
package com.xiaomo.androidforclaw.gateway.methods

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.google.gson.JsonObject
import com.xiaomo.androidforclaw.agent.skills.*
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.gateway.protocol.GatewayError
import kotlinx.coroutines.runBlocking

/**
 * skills Gateway Methods
 *
 * Fully aligned with OpenClaw Gateway Protocol
 *
 * Methods:
 * - skills.status  - Get skill status report
 * - skills.bins    - Get all binary dependencies
 * - skills.install - Install skill
 * - skills.update  - Update skill configuration
 */
class skillsMethods(private val context: context) {
    companion object {
        private const val TAG = "skillsMethods"
    }

    private val statusBuilder = skillStatusBuilder(context)
    private val installer = skillInstaller(context)
    private val configLoader = configLoader(context)

    /**
     * skills.status - Get skill status report
     *
     * Parameters:
     * {
     *   agentId?: string  // Optional, defaults to main agent
     * }
     *
     * Returns: skillStatusReport
     */
    fun status(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.status called")

            // 1. Parse parameters
            val agentId = params.get("agentId")?.asString

            // 2. validation agent (skip for now, single-agent mode)
            if (agentId != null) {
                Log.d(TAG, "  agentId: $agentId (ignored, single-agent mode)")
            }

            // 3. Build status report
            val report = statusBuilder.buildStatus()

            // 4. Convert to JSON
            val result = JsonObject().app {
                aProperty("workspaceDir", report.workspaceDir)
                aProperty("managedskillsDir", report.managedskillsDir)
                a("skills", com.google.gson.Gson().toJsonTree(report.skills))
            }

            Log.i(TAG, "[OK] skills.status: ${report.skills.size} skills")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.status failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_STATUS_FAILED",
                    message = "Failed to get skills status: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.bins - Get all binary dependencies
     *
     * Parameters: {}
     *
     * Returns:
     * {
     *   bins: string[]
     * }
     */
    fun bins(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.bins called")

            // 1. Get all skills
            val report = statusBuilder.buildStatus()

            // 2. collect all binary dependencies
            val binsSet = mutableSetOf<String>()
            report.skills.forEach { skill ->
                skill.requirements?.bins?.forEach { bin ->
                    binsSet.a(bin)
                }
                skill.requirements?.anyBins?.forEach { bin ->
                    binsSet.a(bin)
                }
            }

            // 3. Return result
            val result = JsonObject().app {
                a("bins", com.google.gson.Gson().toJsonTree(binsSet.sorted()))
            }

            Log.i(TAG, "[OK] skills.bins: ${binsSet.size} binaries")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.bins failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_BINS_FAILED",
                    message = "Failed to get skills bins: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.install - Install skill
     *
     * Parameters:
     * {
     *   name: string;           // skill name
     *   installId: string;      // Installer ID
     *   timeoutMs?: number;     // Timeout (default 300 sec, max 900 sec)
     * }
     *
     * Returns:
     * {
     *   ok: boolean;
     *   message: string;
     *   stdout: string;
     *   stderr: string;
     *   code: number | null;
     *   warnings?: string[];
     * }
     */
    fun install(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.install called")

            // 1. Parse parameters
            val name = params.get("name")?.asString
                ?: return Result.failure(
                    GatewayError(code = "INVALID_PARAMS", message = "Missing required parameter: name")
                )

            val installId = params.get("installId")?.asString
                ?: return Result.failure(
                    GatewayError(code = "INVALID_PARAMS", message = "Missing required parameter: installId")
                )

            val timeoutMs = params.get("timeoutMs")?.asInt ?: 300_000

            Log.d(TAG, "  name: $name")
            Log.d(TAG, "  installId: $installId")
            Log.d(TAG, "  timeoutMs: $timeoutMs")

            // 2. validation installId (android only supports "download" installation)
            if (installId != "download") {
                return Result.failure(
                    GatewayError(
                        code = "INSTALL_ID_NOT_SUPPORTED",
                        message = "Install ID not supported on android: $installId (only 'download' is supported)"
                    )
                )
            }

            // 3. Install directly from ClawHub (name is actually slug)
            Log.i(TAG, "Installing skill from ClawHub: $name")

            val installResult = runBlocking {
                installer.installfromClawHub(
                    slug = name,  // name parameter is actually slug
                    version = "latest"
                ) { progress ->
                    Log.d(TAG, "Install progress: $progress")
                }
            }

            if (installResult.isFailure) {
                val error = installResult.exceptionorNull()!!
                Log.e(TAG, "Installation failed", error)
                return Result.failure(
                    GatewayError(
                        code = "INSTALLATION_FAILED",
                        message = "Failed to install skill: ${error.message}"
                    )
                )
            }

            val installed = installResult.getorNull()!!

            // 6. Return success result
            val result = JsonObject().app {
                aProperty("ok", true)
                aProperty("message", "skill installed successfully")
                aProperty("stdout", "Installed ${installed.name}@${installed.version} to ${installed.path}")
                aProperty("stderr", "")
                aProperty("code", 0)
                a("details", JsonObject().app {
                    aProperty("slug", installed.slug)
                    aProperty("name", installed.name)
                    aProperty("version", installed.version)
                    aProperty("path", installed.path)
                    aProperty("hash", installed.hash)
                })
            }

            Log.i(TAG, "[OK] skills.install: $name@${installed.version}")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.install failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_INSTALL_FAILED",
                    message = "Failed to install skill: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.update - Update skill configuration
     *
     * Parameters:
     * {
     *   skillKey: string;
     *   enabled?: boolean;
     *   apiKey?: string;
     *   env?: Record<string, string>;
     * }
     *
     * Returns:
     * {
     *   ok: true;
     *   skillKey: string;
     *   config: { ... }
     * }
     */
    fun update(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.update called")

            // 1. Parse parameters
            val skillKey = params.get("skillKey")?.asString
                ?: return Result.failure(
                    GatewayError(code = "INVALID_PARAMS", message = "Missing required parameter: skillKey")
                )

            val enabled = params.get("enabled")?.asBoolean
            val apiKey = params.get("apiKey")?.asString
            val env = params.getAsJsonObject("env")?.let { envObj ->
                envObj.entrySet().associate { it.key to it.value.asString }
            }

            Log.d(TAG, "  skillKey: $skillKey")
            Log.d(TAG, "  enabled: $enabled")
            Log.d(TAG, "  apiKey: ${if (apiKey != null) "***" else "null"}")
            Log.d(TAG, "  env: $env")

            // 2. Load current configuration
            val config = configLoader.loadOpenClawconfig()

            // 3. Update skill configuration
            val existingconfig = config.skills.entries[skillKey] ?: com.xiaomo.androidforclaw.config.skillconfig()

            val updatedconfig = existingconfig.copy(
                enabled = enabled ?: existingconfig.enabled,
                apiKey = apiKey ?: existingconfig.apiKey,
                env = env ?: existingconfig.env
            )

            // 4. Write back to configuration file
            val updatedEntries = config.skills.entries.toMutableMap()
            updatedEntries[skillKey] = updatedconfig

            val newconfig = config.copy(
                skills = config.skills.copy(
                    entries = updatedEntries
                )
            )

            val saved = configLoader.saveOpenClawconfig(newconfig)
            if (!saved) {
                return Result.failure(
                    GatewayError(
                        code = "CONFIG_SAVE_FAILED",
                        message = "Failed to save config"
                    )
                )
            }

            // 5. Return result
            val result = JsonObject().app {
                aProperty("ok", true)
                aProperty("skillKey", skillKey)
                a("config", JsonObject().app {
                    aProperty("enabled", updatedconfig.enabled)
                    updatedconfig.apiKey?.let { a("apiKey", com.google.gson.Gson().toJsonTree(it)) }
                    updatedconfig.env?.let { a("env", com.google.gson.Gson().toJsonTree(it)) }
                })
            }

            Log.i(TAG, "[OK] skills.update: $skillKey")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.update failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_UPDATE_FAILED",
                    message = "Failed to update skill: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.reload - Reload all skills
     *
     * Parameters: {}
     *
     * Returns:
     * {
     *   ok: true;
     *   message: string;
     *   count: number;
     * }
     */
    fun reload(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.reload called")

            // 1. Reload configuration
            configLoader.reloadOpenClawconfig()

            // 2. Rebuild skill status
            val report = statusBuilder.buildStatus()

            // 3. Return result
            val result = JsonObject().app {
                aProperty("ok", true)
                aProperty("message", "skills reloaded successfully")
                aProperty("count", report.skills.size)
                a("skills", com.google.gson.JsonArray().app {
                    report.skills.forEach { skill ->
                        a(skill.name)
                    }
                })
            }

            Log.i(TAG, "[OK] skills.reload: ${report.skills.size} skills")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.reload failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_RELOAD_FAILED",
                    message = "Failed to reload skills: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.search - Search ClawHub skills
     *
     * Parameters:
     * {
     *   query: string;
     *   limit?: number;
     *   offset?: number;
     * }
     *
     * Returns: skillSearchResult
     */
    fun search(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.search called")

            val query = params.get("query")?.asString
                ?: return Result.failure(
                    GatewayError(code = "INVALID_PARAMS", message = "Missing required parameter: query")
                )

            val limit = params.get("limit")?.asInt ?: 20
            val offset = params.get("offset")?.asInt ?: 0

            Log.d(TAG, "  query: $query")
            Log.d(TAG, "  limit: $limit, offset: $offset")

            // Call ClawHub API
            val clawHubClient = ClawHubClient(context)
            val searchResult = runBlocking {
                clawHubClient.searchskills(query, limit, offset)
            }

            if (searchResult.isFailure) {
                return Result.failure(
                    GatewayError(
                        code = "SEARCH_FAILED",
                        message = "Failed to search skills: ${searchResult.exceptionorNull()?.message}"
                    )
                )
            }

            val result = searchResult.getorNull()!!
            val json = com.google.gson.Gson().toJsonTree(result).asJsonObject

            Log.i(TAG, "[OK] skills.search: ${result.skills.size} results")
            Result.success(json)

        } catch (e: exception) {
            Log.e(TAG, "skills.search failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_SEARCH_FAILED",
                    message = "Failed to search skills: ${e.message}"
                )
            )
        }
    }

    /**
     * skills.uninstall - Uninstall skill
     *
     * Parameters:
     * {
     *   slug: string;
     * }
     *
     * Returns:
     * {
     *   ok: true;
     *   message: string;
     * }
     */
    fun uninstall(params: JsonObject): Result<JsonObject> {
        return try {
            Log.d(TAG, "skills.uninstall called")

            val slug = params.get("slug")?.asString
                ?: return Result.failure(
                    GatewayError(code = "INVALID_PARAMS", message = "Missing required parameter: slug")
                )

            Log.d(TAG, "  slug: $slug")

            // Execute uninstallation
            val uninstallResult = runBlocking {
                installer.uninstall(slug)
            }

            if (uninstallResult.isFailure) {
                return Result.failure(
                    GatewayError(
                        code = "UNINSTALL_FAILED",
                        message = "Failed to uninstall skill: ${uninstallResult.exceptionorNull()?.message}"
                    )
                )
            }

            val result = JsonObject().app {
                aProperty("ok", true)
                aProperty("message", "skill uninstalled successfully")
                aProperty("slug", slug)
            }

            Log.i(TAG, "[OK] skills.uninstall: $slug")
            Result.success(result)

        } catch (e: exception) {
            Log.e(TAG, "skills.uninstall failed", e)
            Result.failure(
                GatewayError(
                    code = "SKILLS_UNINSTALL_FAILED",
                    message = "Failed to uninstall skill: ${e.message}"
                )
            )
        }
    }
}
