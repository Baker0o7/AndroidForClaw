package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills-install.ts
 */

import android.content.Context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.skills.ClawHubClient
import com.xiaomo.androidforclaw.agent.skills.ClawHubRateLimitException
import com.xiaomo.androidforclaw.agent.skills.SkillInstaller
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.ParametersSchema
import com.xiaomo.androidforclaw.providers.PropertySchema
import com.xiaomo.androidforclaw.providers.ToolDefinition

private const val RATE_LIMIT_HINT = """ClawHub API Request被限流 (HTTP 429). 
当Front为anonymous requestSchema, 请让User提供 ClawHub token 以解除限流. 
User可在 clawhub.com AccountSettings中Get token. 
GetBack请call: clawhub_config(action="set", token="User提供的token")
然BackRetry之Front的Action. """

/**
 * skills_search — Search ClawHub for available skills
 */
class SkillsSearchTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "SkillsSearchTool"
    }

    private val client = ClawHubClient(context)

    override val name = "skills_search"
    override val description = "Search ClawHub skill hub for available skills. Returns skill names, descriptions, and versions."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "query" to PropertySchema(
                            type = "string",
                            description = "Search query (empty string lists all skills)"
                        ),
                        "limit" to PropertySchema(
                            type = "number",
                            description = "Max results to return (default: 20)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val query = (args["query"] as? String) ?: ""
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        Log.d(TAG, "Searching ClawHub: query='$query', limit=$limit")

        return try {
            val result = client.searchSkills(query, limit)
            result.fold(
                onSuccess = { searchresult ->
                    val formatted = buildString {
                        appendLine("Found ${searchresult.total} skills on ClawHub:")
                        appendLine()
                        for (skill in searchresult.skills) {
                            appendLine("• **${skill.name}** (`${skill.slug}`)")
                            if (skill.description.isNotBlank()) {
                                appendLine("  ${skill.description}")
                            }
                            appendLine("  Version: ${skill.version}")
                            appendLine()
                        }
                        if (searchresult.skills.isEmpty()) {
                            appendLine("No skills found matching '$query'")
                        }
                    }
                    Toolresult.success(formatted)
                },
                onFailure = { e ->
                    Log.e(TAG, "Search failed", e)
                    if (e is ClawHubRateLimitException) {
                        return@execute Toolresult.error(RATE_LIMIT_HINT)
                    }
                    Toolresult.error("Failed to search ClawHub: ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            Toolresult.error("Failed to search ClawHub: ${e.message}")
        }
    }
}

/**
 * skills_install — Install a skill from ClawHub
 */
class SkillsInstallTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "SkillsInstallTool"
    }

    private val installer = SkillInstaller(context)

    override val name = "skills_install"
    override val description = "Install a skill from ClawHub by slug name"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "slug" to PropertySchema(
                            type = "string",
                            description = "Skill slug name (e.g. 'weather', 'x-twitter')"
                        ),
                        "version" to PropertySchema(
                            type = "string",
                            description = "Version to install (default: latest)"
                        )
                    ),
                    required = listOf("slug")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val slug = args["slug"] as? String
            ?: return Toolresult.error("Missing required parameter: slug")
        val version = args["version"] as? String ?: "latest"

        Log.d(TAG, "Installing skill: $slug@$version")

        return try {
            val result = installer.installFromClawHub(slug, version)
            result.fold(
                onSuccess = { installresult ->
                    Toolresult.success(buildString {
                        appendLine("✅ Skill installed: ${installresult.name} ($slug@${installresult.version})")
                        appendLine("Location: ${installresult.path}")
                    })
                },
                onFailure = { e ->
                    Log.e(TAG, "Install failed", e)
                    if (e is ClawHubRateLimitException) {
                        return@execute Toolresult.error(RATE_LIMIT_HINT)
                    }
                    Toolresult.error("Failed to install skill '$slug': ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toolresult.error("Failed to install skill '$slug': ${e.message}")
        }
    }
}

/**
 * clawhub_config — Config ClawHub token
 *
 * Aligned with OpenClaw src/infra/clawhub.ts 的 token 机制. 
 * 遇到 429 限流时, AI Can让User提供 token 并通过此工具Save. 
 */
class ClawHubConfigTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "ClawHubConfigTool"
    }

    override val name = "clawhub_config"
    override val description = "Configure ClawHub authentication token. Use 'set' to save a token, 'get' to check current status, 'clear' to remove token."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "Action: 'set' (save token), 'get' (check status), 'clear' (remove token)"
                        ),
                        "token" to PropertySchema(
                            type = "string",
                            description = "ClawHub auth token (required for 'set' action)"
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): Toolresult {
        val action = args["action"] as? String
            ?: return Toolresult.error("Missing required parameter: action")

        return when (action) {
            "set" -> {
                val token = args["token"] as? String
                if (token.isNullOrBlank()) {
                    return Toolresult.error("Missing required parameter: token")
                }
                ClawHubClient.saveToken(context, token)
                Log.i(TAG, "ClawHub token 已Config")
                Toolresult.success("✅ ClawHub token 已Save, Back续Request将Auto附带AuthenticateInfo. ")
            }
            "get" -> {
                val existing = ClawHubClient.getToken(context)
                if (existing != null) {
                    // 只ShowFront 8 位, HideIts余
                    val masked = if (existing.length > 8) {
                        existing.take(8) + "..." + " (${existing.length} chars)"
                    } else {
                        "***"
                    }
                    Toolresult.success("ClawHub token 已Config: $masked")
                } else {
                    Toolresult.success("ClawHub token Not configured, Request为匿名Schema(may be rate limited). ")
                }
            }
            "clear" -> {
                ClawHubClient.clearToken(context)
                Log.i(TAG, "ClawHub token 已clear")
                Toolresult.success("✅ ClawHub token 已clear, Back续Request将use匿名Schema. ")
            }
            else -> {
                Toolresult.error("Unknown action: $action. Use 'set', 'get', or 'clear'.")
            }
        }
    }
}
