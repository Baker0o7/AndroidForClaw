package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills-install.ts
 */

import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.agent.skills.ClawHubClient
import com.xiaomo.androidforclaw.agent.skills.ClawHubRateLimitexception
import com.xiaomo.androidforclaw.agent.skills.skillInstaller
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition

private const val RATE_LIMIT_HINT = """ClawHub API Request被限流 (HTTP 429). 
whenFrontforanonymous requestschema, please让user提供 ClawHub token by解除限流. 
usercanin clawhub.com AccountSettings中Get token. 
Getbackpleasecall: clawhub_config(action="set", token="user提供token")
然backretry之FrontAction. """

/**
 * skills_search — Search ClawHub for available skills
 */
class skillsSearchtool(private val context: context) : tool {
    companion object {
        private const val TAG = "skillsSearchtool"
    }

    private val client = ClawHubClient(context)

    override val name = "skills_search"
    override val description = "Search ClawHub skill hub for available skills. Returns skill names, descriptions, and versions."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "query" to Propertyschema(
                            type = "string",
                            description = "Search query (empty string lists all skills)"
                        ),
                        "limit" to Propertyschema(
                            type = "number",
                            description = "Max results to return (default: 20)"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val query = (args["query"] as? String) ?: ""
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        Log.d(TAG, "Searching ClawHub: query='$query', limit=$limit")

        return try {
            val result = client.searchskills(query, limit)
            result.fold(
                onSuccess = { searchresult ->
                    val formatted = buildString {
                        appendLine("Found ${searchresult.total} skills on ClawHub:")
                        appendLine()
                        for (skill in searchresult.skills) {
                            appendLine("• **${skill.name}** (`${skill.slug}`)")
                            if (skill.description.isnotBlank()) {
                                appendLine("  ${skill.description}")
                            }
                            appendLine("  Version: ${skill.version}")
                            appendLine()
                        }
                        if (searchresult.skills.isEmpty()) {
                            appendLine("No skills found matching '$query'")
                        }
                    }
                    toolresult.success(formatted)
                },
                onFailure = { e ->
                    Log.e(TAG, "Search failed", e)
                    if (e is ClawHubRateLimitexception) {
                        return@execute toolresult.error(RATE_LIMIT_HINT)
                    }
                    toolresult.error("Failed to search ClawHub: ${e.message}")
                }
            )
        } catch (e: exception) {
            Log.e(TAG, "Search failed", e)
            toolresult.error("Failed to search ClawHub: ${e.message}")
        }
    }
}

/**
 * skills_install — Install a skill from ClawHub
 */
class skillsInstalltool(private val context: context) : tool {
    companion object {
        private const val TAG = "skillsInstalltool"
    }

    private val installer = skillInstaller(context)

    override val name = "skills_install"
    override val description = "Install a skill from ClawHub by slug name"

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "slug" to Propertyschema(
                            type = "string",
                            description = "skill slug name (e.g. 'weather', 'x-twitter')"
                        ),
                        "version" to Propertyschema(
                            type = "string",
                            description = "Version to install (default: latest)"
                        )
                    ),
                    required = listOf("slug")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val slug = args["slug"] as? String
            ?: return toolresult.error("Missing required parameter: slug")
        val version = args["version"] as? String ?: "latest"

        Log.d(TAG, "Installing skill: $slug@$version")

        return try {
            val result = installer.installfromClawHub(slug, version)
            result.fold(
                onSuccess = { installresult ->
                    toolresult.success(buildString {
                        appendLine("[OK] skill installed: ${installresult.name} ($slug@${installresult.version})")
                        appendLine("Location: ${installresult.path}")
                    })
                },
                onFailure = { e ->
                    Log.e(TAG, "Install failed", e)
                    if (e is ClawHubRateLimitexception) {
                        return@execute toolresult.error(RATE_LIMIT_HINT)
                    }
                    toolresult.error("Failed to install skill '$slug': ${e.message}")
                }
            )
        } catch (e: exception) {
            Log.e(TAG, "Install failed", e)
            toolresult.error("Failed to install skill '$slug': ${e.message}")
        }
    }
}

/**
 * clawhub_config — config ClawHub token
 *
 * Aligned with OpenClaw src/infra/clawhub.ts  token 机制. 
 * 遇to 429 限流hour, AI can让user提供 token 并throughthis工具Save. 
 */
class ClawHubconfigtool(private val context: context) : tool {
    companion object {
        private const val TAG = "ClawHubconfigtool"
    }

    override val name = "clawhub_config"
    override val description = "configure ClawHub authentication token. use 'set' to save a token, 'get' to check current status, 'clear' to remove token."

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "action" to Propertyschema(
                            type = "string",
                            description = "Action: 'set' (save token), 'get' (check status), 'clear' (remove token)"
                        ),
                        "token" to Propertyschema(
                            type = "string",
                            description = "ClawHub auth token (required for 'set' action)"
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): toolresult {
        val action = args["action"] as? String
            ?: return toolresult.error("Missing required parameter: action")

        return when (action) {
            "set" -> {
                val token = args["token"] as? String
                if (token.isNullorBlank()) {
                    return toolresult.error("Missing required parameter: token")
                }
                ClawHubClient.saveToken(context, token)
                Log.i(TAG, "ClawHub token alreadyconfig")
                toolresult.success("[OK] ClawHub token alreadySave, back续RequestwillAuto附带AuthenticateInfo. ")
            }
            "get" -> {
                val existing = ClawHubClient.getToken(context)
                if (existing != null) {
                    // 只ShowFront 8 position, HideIts余
                    val masked = if (existing.length > 8) {
                        existing.take(8) + "..." + " (${existing.length} chars)"
                    } else {
                        "***"
                    }
                    toolresult.success("ClawHub token alreadyconfig: $masked")
                } else {
                    toolresult.success("ClawHub token not configured, Requestfor匿名schema(may be rate limited). ")
                }
            }
            "clear" -> {
                ClawHubClient.clearToken(context)
                Log.i(TAG, "ClawHub token alreadyclear")
                toolresult.success("[OK] ClawHub token alreadyclear, back续Requestwilluse匿名schema. ")
            }
            else -> {
                toolresult.error("Unknown action: $action. use 'set', 'get', or 'clear'.")
            }
        }
    }
}
