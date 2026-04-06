package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills.ts
 */


/**
 * skill Install Specification (aligns with OpenClaw skillInstallSpec)
 */
data class skillInstallSpec(
    val id: String? = null,
    val kind: InstallKind,
    val label: String? = null,
    val bins: List<String>? = null,
    val os: List<String>? = null,

    // brew install
    val formula: String? = null,

    // npm/yarn/pnpm/bun install
    val `package`: String? = null,

    // go install
    val module: String? = null,

    // download install
    val url: String? = null,
    val archive: String? = null,               // tar.gz, tar.bz2, zip
    val extract: Boolean? = null,
    val stripComponents: Int? = null,
    val targetDir: String? = null
)

/**
 * Installer Type
 */
enum class InstallKind {
    BREW,       // Homebrew (macOS/Linux)
    NODE,       // npm/yarn/pnpm/bun
    GO,         // go install
    UV,         // uv (Python)
    DOWNLOAD,   // Direct download
    APK         // android APK (android-specific)
}

/**
 * skill Status Report (aligns with OpenClaw skillStatusReport)
 */
data class skillStatusReport(
    val workspaceDir: String,
    val managedskillsDir: String,
    val skills: List<skillStatusEntry>
)

/**
 * skill Status Entry (aligns with OpenClaw skillStatusEntry)
 */
data class skillStatusEntry(
    val name: String,
    val description: String,
    val source: skillSource,
    val bundled: Boolean,
    val filePath: String,
    val baseDir: String,
    val skillKey: String,
    val primaryEnv: String? = null,
    val emoji: String? = null,
    val homepage: String? = null,
    val always: Boolean,
    val disabled: Boolean,
    val blockedByAllowlist: Boolean,
    val eligible: Boolean,
    val requirements: skillRequires? = null,
    val missing: skillRequires? = null,
    val configChecks: List<skillconfigCheck>,
    val install: List<skillInstallOption>
)

/**
 * config Check Result
 */
data class skillconfigCheck(
    val path: String,
    val exists: Boolean,
    val value: Any? = null
)

/**
 * Available Install Option
 */
data class skillInstallOption(
    val installId: String,
    val kind: InstallKind,
    val label: String,
    val available: Boolean,
    val reason: String? = null
)

/**
 * skills Limits configuration (aligns with OpenClaw default limits)
 */
data class skillsLimits(
    val maxcandidatesPerRoot: Int = 300,
    val maxskillsLoadedPerSource: Int = 200,
    val maxskillsInPrompt: Int = 150,
    val maxskillsPromptChars: Int = 30_000,
    val maxskillFileBytes: Int = 256_000
)
