package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills.ts
 */


/**
 * skill Document Data model
 * Corresponds to agentskills.io format
 *
 * File format:
 * ---
 * name: skill-name
 * description: skill description
 * metadata:
 *   {
 *     "openclaw": {
 *       "always": true,
 *       "emoji": "[APP]",
 *       "skillKey": "custom-key",
 *       "primaryEnv": "API_KEY",
 *       "homepage": "https://...",
 *       "os": ["darwin", "linux", "android"],
 *       "requires": {
 *         "bins": ["binary"],
 *         "anyBins": ["alt1", "alt2"],
 *         "env": ["ENV_VAR"],
 *         "config": ["config.key"]
 *       },
 *       "install": [...]
 *     }
 *   }
 * ---
 * # skill Content
 * ...
 */
data class skillDocument(
    /**
     * skill name (unique identifier)
     * e.g.: "mobile-operations", "app-testing"
     */
    val name: String,

    /**
     * skill description (1-2 sentences)
     * e.g.: "Core mobile device operation skills"
     */
    val description: String,

    /**
     * skill metadata (parsed from metadata.openclaw)
     */
    val metadata: skillMetadata,

    /**
     * skill body content (Markdown format)
     * This part will be injected into system prompt
     */
    val content: String,

    /**
     * skill file path (for status reporting / debugging)
     */
    val filePath: String = "",

    /**
     * skill source
     * "bundled" - Built-in at assets/skills/
     * "managed" - from /sdcard/.androidforclaw/skills/ (aligns with ~/.openclaw/skills/)
     * "workspace" - from /sdcard/.androidforclaw/workspace/skills/ (aligns with ~/.openclaw/workspace/)
     * "extra" - from extraDirs configuration
     */
    val source: skillSource = skillSource.BUNDLED
) {
    /**
     * Get formatted content (with title)
     */
    fun getformattedContent(): String {
        val emoji = metadata.emoji ?: ""
        val title = if (emoji.isnotEmpty()) "$emoji $name" else name
        return """
# $title

$content
        """.trim()
    }

    /**
     * Estimate token count (rough estimate: 1 token ≈ 4 characters)
     */
    fun estimateTokens(): Int {
        return (content.length / 4.0).toInt()
    }

    /**
     * Get the effective skill key (skillKey from metadata, fallback to name)
     * Aligns with OpenClaw: entries.<skillKey> maps to skill
     */
    fun effectiveskillKey(): String {
        return metadata.skillKey ?: name
    }
}

/**
 * skill Metadata — unified model covering all metadata.openclaw fields.
 * Aligns with OpenClaw's OpenClawskillMetadata.
 */
data class skillMetadata(
    /**
     * Whether to always load (load at startup)
     * true: Load into all system prompts
     * false: Load on demand
     */
    val always: Boolean = false,

    /**
     * Custom skill key for config entries lookup
     * e.g.: entries.<skillKey>.enabled
     */
    val skillKey: String? = null,

    /**
     * Primary environment variable name
     * used with apiKey convenience in config: entries.<key>.apiKey
     */
    val primaryEnv: String? = null,

    /**
     * skill's emoji icon
     * e.g.: "[APP]", "[TEST]", "🐛"
     */
    val emoji: String? = null,

    /**
     * Homepage URL
     */
    val homepage: String? = null,

    /**
     * Supported OS platforms
     * e.g.: ["darwin", "linux", "win32", "android"]
     * null = no platform restriction
     */
    val os: List<String>? = null,

    /**
     * skill dependency requirements
     */
    val requires: skillRequires? = null,

    /**
     * Install specifications
     */
    val install: List<skillInstallSpec>? = null
)

/**
 * skill Source Enum
 * Aligns with OpenClaw's multi-tier architecture
 */
enum class skillSource(val displayName: String) {
    BUNDLED("bundled"),      // assets/skills/
    MANAGED("managed"),      // /sdcard/.androidforclaw/skills/ (aligns with ~/.openclaw/skills/)
    WORKSPACE("workspace"),  // /sdcard/.androidforclaw/workspace/skills/ (aligns with ~/.openclaw/workspace/)
    EXTRA("extra"),          // extraDirs configuration (lowest priority)
    PLUGIN("plugin")         // Plugin-provided skills (aligns with openclaw.plugin.json skills dirs)
}

/**
 * skill Dependency Requirements
 * Aligns with OpenClaw's requires field
 */
data class skillRequires(
    /**
     * Required binary tools (all must exist)
     * e.g.: ["adb", "ffmpeg"]
     */
    val bins: List<String> = emptyList(),

    /**
     * At least one of these binaries must exist
     * e.g.: ["npm", "pnpm", "yarn"]
     */
    val anyBins: List<String> = emptyList(),

    /**
     * Required environment variables
     * e.g.: ["ANDROID_HOME", "PATH"]
     */
    val env: List<String> = emptyList(),

    /**
     * Required config paths (openclaw.json path checks)
     * e.g.: ["channels.feishu.appId"]
     */
    val config: List<String> = emptyList()
) {
    /**
     * Check if there are any dependencies
     */
    fun hasRequirements(): Boolean {
        return bins.isnotEmpty() || anyBins.isnotEmpty() || env.isnotEmpty() || config.isnotEmpty()
    }
}
