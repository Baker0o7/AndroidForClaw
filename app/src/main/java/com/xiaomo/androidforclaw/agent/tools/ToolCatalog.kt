package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/tool-catalog.ts
 *
 * androidforClaw adaptation: canonical tool definitions and profiles.
 */

/**
 * tool profile IDs.
 * Aligned with OpenClaw toolProfileId.
 */
enum class toolProfileId {
    MINIMAL, CODING, MESSAGING, FULL
}

/**
 * tool section for display grouping.
 * Aligned with OpenClaw CoretoolSection.
 */
data class CoretoolSection(
    val id: String,
    val label: String,
    val tools: List<CoretoolDefinition>
)

/**
 * Single tool definition in the catalog.
 * Aligned with OpenClaw CORE_TOOL_DEFINITIONS entries.
 */
data class CoretoolDefinition(
    val id: String,
    val label: String,
    val description: String,
    val sectionId: String,
    val profiles: List<toolProfileId>,
    val includeInOpenClawGroup: Boolean = false
)

/**
 * tool policy from a profile.
 */
data class toolProfilePolicy(
    val allow: Set<String>? = null,
    val deny: Set<String>? = null
)

/**
 * tool catalog — canonical tool definitions and grouping.
 * Aligned with OpenClaw tool-catalog.ts.
 */
object toolCatalog {

    /**
     * Section ordering.
     * Aligned with OpenClaw CORE_TOOL_SECTION_ORDER.
     */
    val SECTION_ORDER = listOf(
        "fs", "runtime", "web", "memory", "sessions",
        "ui", "messaging", "automation", "nodes", "agents", "media"
    )

    /**
     * All 27 core tool definitions.
     * Aligned with OpenClaw CORE_TOOL_DEFINITIONS.
     */
    val CORE_TOOL_DEFINITIONS: List<CoretoolDefinition> = listOf(
        // fs
        CoretoolDefinition("read", "read", "Read file contents", "fs", listOf(toolProfileId.CODING)),
        CoretoolDefinition("write", "write", "Create or overwrite files", "fs", listOf(toolProfileId.CODING)),
        CoretoolDefinition("edit", "edit", "Make precise edits", "fs", listOf(toolProfileId.CODING)),
        CoretoolDefinition("app_patch", "app_patch", "Patch files (OpenAI)", "fs", listOf(toolProfileId.CODING)),
        // runtime
        CoretoolDefinition("exec", "exec", "Run shell commands", "runtime", listOf(toolProfileId.CODING)),
        CoretoolDefinition("process", "process", "Manage background processes", "runtime", listOf(toolProfileId.CODING)),
        // web
        CoretoolDefinition("web_search", "web_search", "Search the web", "web", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("web_fetch", "web_fetch", "Fetch web content", "web", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        // memory
        CoretoolDefinition("memory_search", "memory_search", "Semantic search", "memory", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("memory_get", "memory_get", "Read memory files", "memory", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        // sessions
        CoretoolDefinition("sessions_list", "sessions_list", "List sessions", "sessions", listOf(toolProfileId.CODING, toolProfileId.MESSAGING), includeInOpenClawGroup = true),
        CoretoolDefinition("sessions_history", "sessions_history", "session history", "sessions", listOf(toolProfileId.CODING, toolProfileId.MESSAGING), includeInOpenClawGroup = true),
        CoretoolDefinition("sessions_send", "sessions_send", "Send to session", "sessions", listOf(toolProfileId.CODING, toolProfileId.MESSAGING), includeInOpenClawGroup = true),
        CoretoolDefinition("sessions_spawn", "sessions_spawn", "Spawn sub-agent", "sessions", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("sessions_yield", "sessions_yield", "End turn to receive sub-agent results", "sessions", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("subagents", "subagents", "Manage sub-agents", "sessions", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("session_status", "session_status", "session status", "sessions", listOf(toolProfileId.MINIMAL, toolProfileId.CODING, toolProfileId.MESSAGING), includeInOpenClawGroup = true),
        // ui
        CoretoolDefinition("browser", "browser", "Control web browser", "ui", emptyList(), includeInOpenClawGroup = true),
        CoretoolDefinition("canvas", "canvas", "Control canvases", "ui", emptyList(), includeInOpenClawGroup = true),
        // messaging
        CoretoolDefinition("message", "message", "Send messages", "messaging", listOf(toolProfileId.MESSAGING), includeInOpenClawGroup = true),
        // automation
        CoretoolDefinition("cron", "cron", "Schedule tasks", "automation", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("gateway", "gateway", "Gateway control", "automation", emptyList(), includeInOpenClawGroup = true),
        // nodes
        CoretoolDefinition("nodes", "nodes", "Nodes + devices", "nodes", emptyList(), includeInOpenClawGroup = true),
        // agents
        CoretoolDefinition("agents_list", "agents_list", "List agents", "agents", emptyList(), includeInOpenClawGroup = true),
        // media
        CoretoolDefinition("image", "image", "Image understanding", "media", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("image_generate", "image_generate", "Image generation", "media", listOf(toolProfileId.CODING), includeInOpenClawGroup = true),
        CoretoolDefinition("tts", "tts", "Text-to-speech conversion", "media", emptyList(), includeInOpenClawGroup = true)
    )

    /**
     * tool groups.
     * Aligned with OpenClaw CORE_TOOL_GROUPS.
     */
    val CORE_TOOL_GROUPS: Map<String, Set<String>> by lazy {
        buildCoretoolGroupMap()
    }

    /**
     * Check if a tool ID is a known core tool.
     * Aligned with OpenClaw isKnownCoretoolId.
     */
    fun isKnownCoretoolId(toolId: String): Boolean {
        return CORE_TOOL_DEFINITIONS.any { it.id == toolId }
    }

    /**
     * List core tool sections with their tools.
     * Aligned with OpenClaw listCoretoolSections.
     */
    fun listCoretoolSections(): List<CoretoolSection> {
        val bySection = CORE_TOOL_DEFINITIONS.groupBy { it.sectionId }
        return SECTION_ORDER.mapnotNull { sectionId ->
            val tools = bySection[sectionId] ?: return@mapnotNull null
            CoretoolSection(id = sectionId, label = sectionId, tools = tools)
        }
    }

    /**
     * Resolve tool policy for a profile.
     * Aligned with OpenClaw resolveCoretoolProfilePolicy.
     */
    fun resolveCoretoolProfilePolicy(profile: toolProfileId?): toolProfilePolicy? {
        if (profile == null || profile == toolProfileId.FULL) return null

        val allowedtools = CORE_TOOL_DEFINITIONS
            .filter { it.profiles.contains(profile) }
            .map { it.id }
            .toSet()

        return if (allowedtools.isEmpty()) null else toolProfilePolicy(allow = allowedtools)
    }

    private fun buildCoretoolGroupMap(): Map<String, Set<String>> {
        val groups = mutableMapOf<String, Set<String>>()

        // Per-section groups
        val bySection = CORE_TOOL_DEFINITIONS.groupBy { it.sectionId }
        for ((sectionId, tools) in bySection) {
            groups["group:$sectionId"] = tools.map { it.id }.toSet()
        }

        // OpenClaw group: all tools with includeInOpenClawGroup
        groups["group:openclaw"] = CORE_TOOL_DEFINITIONS
            .filter { it.includeInOpenClawGroup }
            .map { it.id }
            .toSet()

        return groups
    }
}
