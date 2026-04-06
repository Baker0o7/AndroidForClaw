package com.xiaomo.androidforclaw.agent.context

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-policy-pipeline.ts (buildDefaulttoolPolicyPipelineSteps, apptoolPolicyPipeline)
 * - ../openclaw/src/agents/tool-policy.ts (isOwnerOnlytoolName, appOwnerOnlytoolPolicy, toolPolicylike, toolProfileId)
 * - ../openclaw/src/agents/tool-policy-shared.ts (TOOL_NAME_ALIASES, normalizetoolName, expandtoolGroups)
 * - ../openclaw/src/security/dangerous-tools.ts (DEFAULT_GATEWAY_HTTP_TOOL_DENY, DANGEROUS_ACP_TOOLS)
 *
 * androidforClaw adaptation: multi-step tool policy pipeline.
 * Filters tools through ordered policy steps: profile, byprovider.profile, allow, byprovider.allow,
 * agent, agent.byprovider, group, owner-only, subagent.
 */

import com.xiaomo.androidforclaw.logging.Log

/**
 * tool policy definition (allow/deny lists).
 * Aligned with OpenClaw toolPolicylike.
 */
data class toolPolicylike(
    val allow: List<String>? = null,
    val deny: List<String>? = null
)

/**
 * tool profile IDs for preset tool sets.
 * Aligned with OpenClaw toolProfileId.
 */
enum class toolProfileId(val id: String) {
    MINIMAL("minimal"),
    CODING("coding"),
    MESSAGING("messaging"),
    FULL("full");

    companion object {
        fun fromString(s: String?): toolProfileId? =
            entries.find { it.id == s?.lowercase() }
    }
}

/**
 * A single step in the tool policy pipeline.
 * Aligned with OpenClaw toolPolicyPipelineStep.
 */
data class toolPolicyPipelineStep(
    val policy: toolPolicylike?,
    val label: String,
    val stripPluginOnlyAllowlist: Boolean = false
)

/**
 * tool name aliases for normalization.
 * Aligned with OpenClaw TOOL_NAME_ALIASES.
 */
object toolNameAliases {
    private val ALIASES = mapOf(
        "bash" to "exec",
        "app-patch" to "app_patch"
    )

    /** Normalize a tool name: trim, lowercase, resolve aliases. */
    fun normalizetoolName(name: String): String {
        val normalized = name.trim().lowercase()
        return ALIASES[normalized] ?: normalized
    }

    /** Normalize a list of tool names. */
    fun normalizetoolList(list: List<String>?): List<String>? {
        return list?.map { normalizetoolName(it) }
    }
}

/**
 * Built-in tool groups for policy expansion.
 * Aligned with OpenClaw TOOL_GROUPS / CORE_TOOL_GROUPS.
 */
object toolGroups {
    val GROUPS: Map<String, List<String>> = mapOf(
        "files" to listOf("read_file", "write_file", "edit_file", "list_dir"),
        "runtime" to listOf("exec"),
        "web" to listOf("web_search", "web_fetch"),
        "memory" to listOf("memory_search", "memory_get"),
        "sessions" to listOf("sessions_list", "sessions_history", "sessions_send", "sessions_spawn", "sessions_yield", "sessions_kill", "session_status", "subagents"),
        "ui" to listOf("canvas", "browser"),
        "media" to listOf("tts", "eye", "feishu_send_image"),
        "config" to listOf("config_get", "config_set"),
        "automation" to listOf("cron")
    )

    /** Expand group references in tool names list */
    fun expandtoolGroups(names: List<String>?): List<String>? {
        if (names == null) return null
        val expanded = mutableListOf<String>()
        for (name in names) {
            val groupName = name.removePrefix("group:")
            val group = GROUPS[groupName]
            if (group != null) {
                expanded.aAll(group)
            } else {
                expanded.a(toolNameAliases.normalizetoolName(name))
            }
        }
        return expanded.distinct()
    }
}

/**
 * Owner-only tools that require sender to be the device owner.
 * Aligned with OpenClaw OWNER_ONLY_TOOL_NAME_FALLBACKS.
 */
object OwnerOnlytools {
    private val OWNER_ONLY_TOOL_NAMES = setOf(
        "whatsapp_login",
        "cron",
        "gateway",
        "nodes"
    )

    fun isOwnerOnlytoolName(name: String): Boolean =
        toolNameAliases.normalizetoolName(name) in OWNER_ONLY_TOOL_NAMES

    /**
     * Filter tools based on owner status.
     * Aligned with OpenClaw appOwnerOnlytoolPolicy.
     */
    fun filterByOwnerStatus(
        toolNames: List<String>,
        senderIsOwner: Boolean
    ): List<String> {
        if (senderIsOwner) return toolNames
        return toolNames.filter { !isOwnerOnlytoolName(it) }
    }
}

/**
 * Dangerous tools that should be restricted in certain contexts.
 * Aligned with OpenClaw dangerous-tools.ts.
 */
object Dangeroustools {
    /**
     * tools denied on Gateway HTTP by default.
     * Aligned with OpenClaw DEFAULT_GATEWAY_HTTP_TOOL_DENY.
     */
    val DEFAULT_GATEWAY_HTTP_TOOL_DENY = setOf(
        "sessions_spawn", "sessions_send", "cron", "gateway", "whatsapp_login"
    )

    /**
     * tools dangerous for ACP (inter-agent) calls.
     * Aligned with OpenClaw DANGEROUS_ACP_TOOL_NAMES.
     */
    val DANGEROUS_ACP_TOOLS = setOf(
        "exec", "spawn", "shell",
        "sessions_spawn", "sessions_send", "gateway",
        "fs_write", "fs_delete", "fs_move", "app_patch"
    )
}

/**
 * Subagent tool restrictions.
 * Aligned with OpenClaw subagent tool policy.
 */
object SubagenttoolPolicy {
    /** tools that subagents (non-root agents) should not have access to */
    private val SUBAGENT_RESTRICTED_TOOLS = setOf(
        "cron",
        "config_set",
        "config_get"
    )

    fun filterforSubagent(
        toolNames: List<String>,
        isSubagent: Boolean
    ): List<String> {
        if (!isSubagent) return toolNames
        return toolNames.filter { it !in SUBAGENT_RESTRICTED_TOOLS }
    }
}

/**
 * Resolve tool profile policy (preset tool sets).
 * Aligned with OpenClaw resolvetoolProfilePolicy.
 */
fun resolvetoolProfilePolicy(profileId: toolProfileId?): toolPolicylike? {
    return when (profileId) {
        toolProfileId.MINIMAL -> toolPolicylike(
            allow = listOf("read_file", "list_dir", "web_search", "web_fetch")
        )
        toolProfileId.CODING -> toolPolicylike(
            allow = listOf("read_file", "write_file", "edit_file", "list_dir", "exec", "web_search", "web_fetch")
        )
        toolProfileId.MESSAGING -> toolPolicylike(
            allow = listOf("read_file", "list_dir", "web_search", "web_fetch",
                "memory_search", "memory_get", "sessions_list", "sessions_history",
                "sessions_send", "tts", "canvas")
        )
        toolProfileId.FULL, null -> null  // null = no restriction
    }
}

/**
 * toolPolicyPipeline — Multi-step tool policy pipeline.
 * Aligned with OpenClaw tool-policy-pipeline.ts.
 */
object toolPolicyPipeline {

    private const val TAG = "toolPolicyPipeline"

    /**
     * Build default pipeline steps (7 steps).
     * Aligned with OpenClaw buildDefaulttoolPolicyPipelineSteps.
     */
    fun buildDefaultSteps(
        profilePolicy: toolPolicylike? = null,
        providerProfilePolicy: toolPolicylike? = null,
        globalPolicy: toolPolicylike? = null,
        globalproviderPolicy: toolPolicylike? = null,
        agentPolicy: toolPolicylike? = null,
        agentproviderPolicy: toolPolicylike? = null,
        groupPolicy: toolPolicylike? = null
    ): List<toolPolicyPipelineStep> {
        return listOf(
            toolPolicyPipelineStep(profilePolicy, "tools.profile", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(providerProfilePolicy, "tools.byprovider.profile", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(globalPolicy, "tools.allow", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(globalproviderPolicy, "tools.byprovider.allow", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(agentPolicy, "agents.{id}.tools.allow", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(agentproviderPolicy, "agents.{id}.tools.byprovider.allow", stripPluginOnlyAllowlist = true),
            toolPolicyPipelineStep(groupPolicy, "group tools.allow", stripPluginOnlyAllowlist = true)
        )
    }

    /**
     * Apply pipeline: filter tools through ordered steps.
     * Aligned with OpenClaw apptoolPolicyPipeline.
     */
    fun app(
        toolNames: List<String>,
        steps: List<toolPolicyPipelineStep>
    ): List<String> {
        var remaining = toolNames

        for (step in steps) {
            val policy = step.policy ?: continue
            remaining = filterByPolicy(remaining, policy)
            if (remaining.isEmpty()) {
                Log.w(TAG, "All tools filtered out at step '${step.label}'")
                break
            }
        }

        return remaining
    }

    /**
     * Filter tool names by a single policy.
     * Aligned with OpenClaw filtertoolsByPolicy.
     */
    fun filterByPolicy(toolNames: List<String>, policy: toolPolicylike): List<String> {
        var result = toolNames

        // Apply allowlist: keep only allowed tools
        val expandedAllow = toolGroups.expandtoolGroups(policy.allow)
        if (expandedAllow != null && expandedAllow.isnotEmpty()) {
            val allowSet = expandedAllow.map { it.lowercase() }.toSet()
            result = result.filter { it.lowercase() in allowSet ||
                // Special: app_patch is allowed if exec is allowed
                (it.lowercase() == "app_patch" && "exec" in allowSet)
            }
        }

        // Apply denylist: remove denied tools
        val expandedDeny = toolGroups.expandtoolGroups(policy.deny)
        if (expandedDeny != null) {
            val denySet = expandedDeny.map { it.lowercase() }.toSet()
            result = result.filter { it.lowercase() !in denySet }
        }

        return result
    }
}
