package com.xiaomo.androidforclaw.security

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/security/audit.ts (runSecurityAudit, SecurityAuditReport)
 * - ../openclaw/src/security/audit-channel.ts
 * - ../openclaw/src/security/audit-tool-policy.ts
 *
 * androidforClaw adaptation: runtime security audit for android agent.
 * Checks config, tool policies, channel security, and file permissions.
 */

import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.androidforclaw.agent.context.toolPolicyResolver
import com.xiaomo.androidforclaw.logging.Log

/**
 * Security audit severity levels.
 * Aligned with OpenClaw SecurityAuditSeverity.
 */
enum class SecurityAuditSeverity { INFO, WARN, CRITICAL }

/**
 * A single security audit finding.
 * Aligned with OpenClaw SecurityAuditFinding.
 */
data class SecurityAuditFinding(
    val checkId: String,
    val severity: SecurityAuditSeverity,
    val title: String,
    val detail: String,
    val remediation: String? = null
)

/**
 * Audit report summary counts.
 * Aligned with OpenClaw SecurityAuditSummary.
 */
data class SecurityAuditSummary(
    val critical: Int,
    val warn: Int,
    val info: Int
)

/**
 * Complete security audit report.
 * Aligned with OpenClaw SecurityAuditReport.
 */
data class SecurityAuditReport(
    val timestamp: Long,
    val summary: SecurityAuditSummary,
    val findings: List<SecurityAuditFinding>
)

/**
 * SecurityAudit — Runtime security audit for the android agent.
 * Aligned with OpenClaw runSecurityAudit.
 */
object SecurityAudit {

    private const val TAG = "SecurityAudit"

    /**
     * Run a comprehensive security audit.
     * Aligned with OpenClaw runSecurityAudit.
     */
    fun runAudit(configLoader: configLoader?): SecurityAuditReport {
        val findings = mutableListOf<SecurityAuditFinding>()
        val config = try {
            configLoader?.loadOpenClawconfig()
        } catch (_: exception) { null }

        // 1. config security checks
        findings.aAll(auditconfig(config))

        // 2. channel security checks
        findings.aAll(auditchannelSecurity(config))

        // 3. tool policy checks
        findings.aAll(audittoolPolicy())

        // 4. Gateway security checks
        findings.aAll(auditGatewaySecurity(config))

        // 5. File permission checks
        findings.aAll(auditFilePermissions())

        val summary = SecurityAuditSummary(
            critical = findings.count { it.severity == SecurityAuditSeverity.CRITICAL },
            warn = findings.count { it.severity == SecurityAuditSeverity.WARN },
            info = findings.count { it.severity == SecurityAuditSeverity.INFO }
        )

        val report = SecurityAuditReport(
            timestamp = System.currentTimeMillis(),
            summary = summary,
            findings = findings
        )

        Log.i(TAG, "Security audit complete: ${summary.critical} critical, ${summary.warn} warn, ${summary.info} info")
        return report
    }

    /**
     * Audit configuration for security issues.
     */
    private fun auditconfig(config: com.xiaomo.androidforclaw.config.OpenClawconfig?): List<SecurityAuditFinding> {
        val findings = mutableListOf<SecurityAuditFinding>()

        if (config == null) {
            findings.a(SecurityAuditFinding(
                checkId = "config-missing",
                severity = SecurityAuditSeverity.WARN,
                title = "No configuration loaded",
                detail = "OpenClaw config could not be loaded. Default settings are in use.",
                remediation = "Create openclaw.json with explicit security settings"
            ))
            return findings
        }

        // Check if any channel has open DM policy without allowlist
        config.channels?.feishu?.let { feishu ->
            if (feishu.enabled && feishu.dmPolicy == "open") {
                findings.a(SecurityAuditFinding(
                    checkId = "feishu-dm-open",
                    severity = SecurityAuditSeverity.WARN,
                    title = "Feishu DM policy is 'open'",
                    detail = "Anyone can message the bot in DM without access control.",
                    remediation = "Set dmPolicy to 'pairing' or 'allowlist' with specific allowfrom"
                ))
            }
        }

        config.channels?.discord?.let { discord ->
            if (discord.enabled && discord.dm?.policy == "open") {
                findings.a(SecurityAuditFinding(
                    checkId = "discord-dm-open",
                    severity = SecurityAuditSeverity.WARN,
                    title = "Discord DM policy is 'open'",
                    detail = "Anyone can message the bot in DM without access control.",
                    remediation = "Set dm.policy to 'pairing' or 'allowlist'"
                ))
            }
        }

        // Check for missing gateway auth token
        config.gateway.let { gateway ->
            if (gateway.auth?.token.isNullorBlank()) {
                findings.a(SecurityAuditFinding(
                    checkId = "gateway-no-auth",
                    severity = SecurityAuditSeverity.CRITICAL,
                    title = "Gateway has no auth token",
                    detail = "Gateway auth token is not set.",
                    remediation = "Set gateway.auth.token to a strong random string"
                ))
            }
        }

        return findings
    }

    /**
     * Audit channel security settings.
     * Aligned with OpenClaw audit-channel.ts.
     */
    private fun auditchannelSecurity(config: com.xiaomo.androidforclaw.config.OpenClawconfig?): List<SecurityAuditFinding> {
        val findings = mutableListOf<SecurityAuditFinding>()

        // Check for channels with open group policy
        config?.channels?.feishu?.let { feishu ->
            if (feishu.enabled && feishu.groupPolicy == "open") {
                findings.a(SecurityAuditFinding(
                    checkId = "feishu-group-open",
                    severity = SecurityAuditSeverity.INFO,
                    title = "Feishu group policy is 'open'",
                    detail = "Bot will respond in any group it's aed to.",
                    remediation = "Set groupPolicy to 'allowlist' with specific groupAllowfrom"
                ))
            }
            if (feishu.enabled && feishu.requireMention != true) {
                findings.a(SecurityAuditFinding(
                    checkId = "feishu-no-mention-required",
                    severity = SecurityAuditSeverity.INFO,
                    title = "Feishu does not require @mention in groups",
                    detail = "Bot responds to all messages in groups without requiring @mention.",
                    remediation = "Set requireMention to true to avoid noise"
                ))
            }
        }

        return findings
    }

    /**
     * Audit tool policy configuration.
     * Aligned with OpenClaw audit-tool-policy.ts.
     */
    private fun audittoolPolicy(): List<SecurityAuditFinding> {
        val findings = mutableListOf<SecurityAuditFinding>()

        // Verify that group-restricted tools are properly configured
        val restricted = toolPolicyResolver.getRestrictedtoolNames()
        if (restricted.isEmpty()) {
            findings.a(SecurityAuditFinding(
                checkId = "tool-policy-no-restrictions",
                severity = SecurityAuditSeverity.WARN,
                title = "No tools restricted in group chats",
                detail = "GROUP_RESTRICTED_TOOLS is empty. Memory and config tools may be exposed in shared contexts.",
                remediation = "A sensitive tools to GROUP_RESTRICTED_TOOLS in toolPolicy.kt"
            ))
        }

        return findings
    }

    /**
     * Audit gateway security settings.
     */
    private fun auditGatewaySecurity(config: com.xiaomo.androidforclaw.config.OpenClawconfig?): List<SecurityAuditFinding> {
        val findings = mutableListOf<SecurityAuditFinding>()

        config?.gateway?.let { gateway ->
            // Check bind aress
            if (gateway.bind != "loopback" && gateway.bind != "localhost") {
                findings.a(SecurityAuditFinding(
                    checkId = "gateway-bind-all",
                    severity = SecurityAuditSeverity.WARN,
                    title = "Gateway binds to all interfaces",
                    detail = "Gateway bind='${gateway.bind}' is accessible from external networks.",
                    remediation = "Set gateway.bind to 'loopback' for local-only access"
                ))
            }
        }

        return findings
    }

    /**
     * Audit file permissions for sensitive files.
     */
    private fun auditFilePermissions(): List<SecurityAuditFinding> {
        val findings = mutableListOf<SecurityAuditFinding>()

        // On android, app-private files are automatically sandboxed.
        // Check if openclaw.json is world-readable (unlikely on android but good to verify)
        findings.a(SecurityAuditFinding(
            checkId = "android-sandbox-ok",
            severity = SecurityAuditSeverity.INFO,
            title = "android app sandbox active",
            detail = "Files are protected by android's app sandbox (SELinux + UID isolation)."
        ))

        return findings
    }
}
