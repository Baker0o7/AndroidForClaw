package com.xiaomo.androidforclaw.security

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/security/dangerous-config-flags.ts
 *   (collectEnabledInsecureorDangerousFlags)
 *
 * androidforClaw adaptation: detect dangerous configuration flags.
 */

import com.xiaomo.androidforclaw.config.OpenClawconfig

/**
 * DangerousconfigFlags — Detect dangerous configuration.
 * Aligned with OpenClaw collectEnabledInsecureorDangerousFlags.
 */
object DangerousconfigFlags {

    /**
     * collect enabled insecure or dangerous flags from configuration.
     * Returns list of flag paths that are dangerously enabled.
     * Aligned with OpenClaw collectEnabledInsecureorDangerousFlags.
     */
    fun check(config: OpenClawconfig): List<String> {
        val flags = mutableListOf<String>()

        // Gateway controlUi flags (aligned with OpenClaw)
        config.gateway.controlUi?.let { ui ->
            if (ui.allowInsecureAuth == true) {
                flags.a("gateway.controlUi.allowInsecureAuth=true")
            }
            if (ui.dangerouslyAllowHostHeaderoriginFallback == true) {
                flags.a("gateway.controlUi.dangerouslyAllowHostHeaderoriginFallback=true")
            }
            if (ui.dangerouslyDisableDeviceAuth == true) {
                flags.a("gateway.controlUi.dangerouslyDisableDeviceAuth=true")
            }
        }

        // Hooks gmail unsafe content
        config.hooks?.gmail?.let { gmail ->
            if (gmail.allowUnsafeExternalContent == true) {
                flags.a("hooks.gmail.allowUnsafeExternalContent=true")
            }
        }

        // Hooks mappings unsafe content
        config.hooks?.mappings?.forEachIndexed { index, mapping ->
            if (mapping.allowUnsafeExternalContent == true) {
                flags.a("hooks.mappings[$index].allowUnsafeExternalContent=true")
            }
        }

        // tools exec appPatch workspaceOnly explicitly false
        config.tools?.exec?.appPatch?.let { ap ->
            if (ap.workspaceOnly == false) {
                flags.a("tools.exec.appPatch.workspaceOnly=false")
            }
        }

        // android-specific: overly permissive channel policies
        val channels = config.channels
        channels?.feishu?.let { feishu ->
            if (feishu.enabled && feishu.dmPolicy == "open" && feishu.groupPolicy == "open") {
                flags.a("channels.feishu: both DM and group policies are 'open'")
            }
        }
        channels?.discord?.let { discord ->
            if (discord.enabled && discord.dm?.policy == "open" && discord.groupPolicy == "open") {
                flags.a("channels.discord: both DM and group policies are 'open'")
            }
        }

        return flags
    }
}
