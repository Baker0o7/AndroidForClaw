package com.xiaomo.androidforclaw.channel

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/command-gating.ts
 *   (resolveCommandAuthorizedfromAuthorizers, resolveControlCommandGate, CommandAuthorizer)
 *
 * androidforClaw adaptation: command authorization and gating.
 * Controls which commands can be executed based on sender authorization.
 */

/**
 * Command authorizer result.
 * Aligned with OpenClaw CommandAuthorizer.
 */
data class CommandAuthorizer(
    val configured: Boolean,
    val allowed: Boolean
)

/**
 * Mode for command gating when access groups are off.
 * Aligned with OpenClaw CommandGatingModewhenAccessGroupsOff.
 */
enum class CommandGatingMode {
    ALLOW,   // Allow all commands
    DENY,    // Deny all commands
    CONFIGURED  // Check if any authorizer is configured+allowed
}

/**
 * Command gate result.
 */
data class CommandGateResult(
    val commandAuthorized: Boolean,
    val shouldBlock: Boolean
)

/**
 * CommandGating — Command authorization and gating.
 * Aligned with OpenClaw command-gating.ts.
 */
object CommandGating {

    /**
     * Resolve command authorization from multiple authorizers.
     * Aligned with OpenClaw resolveCommandAuthorizedfromAuthorizers.
     */
    fun resolveAuthorized(
        useAccessGroups: Boolean,
        authorizers: List<CommandAuthorizer>,
        modewhenAccessGroupsOff: CommandGatingMode = CommandGatingMode.ALLOW
    ): Boolean {
        if (!useAccessGroups) {
            return when (modewhenAccessGroupsOff) {
                CommandGatingMode.ALLOW -> true
                CommandGatingMode.DENY -> false
                CommandGatingMode.CONFIGURED -> {
                    authorizers.any { it.configured && it.allowed }
                }
            }
        }

        // when access groups are on: require at least one configured+allowed
        return authorizers.any { it.configured && it.allowed }
    }

    /**
     * Resolve control command gate.
     * Aligned with OpenClaw resolveControlCommandGate.
     *
     * @param useAccessGroups Whether access group system is enabled
     * @param authorizers List of authorizer results
     * @param allowTextCommands Whether text commands are enabled
     * @param hasControlCommand Whether the message contains a control command
     * @return Gate result with authorization and block status
     */
    fun resolveGate(
        useAccessGroups: Boolean,
        authorizers: List<CommandAuthorizer>,
        allowTextCommands: Boolean,
        hasControlCommand: Boolean,
        modewhenAccessGroupsOff: CommandGatingMode = CommandGatingMode.ALLOW
    ): CommandGateResult {
        val authorized = resolveAuthorized(useAccessGroups, authorizers, modewhenAccessGroupsOff)
        val shouldBlock = allowTextCommands && hasControlCommand && !authorized
        return CommandGateResult(
            commandAuthorized = authorized,
            shouldBlock = shouldBlock
        )
    }

    /**
     * Convenience for dual-authorizer pattern (primary + secondary).
     * Aligned with OpenClaw resolveDualTextControlCommandGate.
     */
    fun resolveDualGate(
        useAccessGroups: Boolean,
        primaryconfigured: Boolean,
        primaryAllowed: Boolean,
        secondaryconfigured: Boolean,
        secondaryAllowed: Boolean,
        hasControlCommand: Boolean,
        modewhenAccessGroupsOff: CommandGatingMode = CommandGatingMode.ALLOW
    ): CommandGateResult {
        val authorizers = listOf(
            CommandAuthorizer(primaryconfigured, primaryAllowed),
            CommandAuthorizer(secondaryconfigured, secondaryAllowed)
        )
        return resolveGate(
            useAccessGroups = useAccessGroups,
            authorizers = authorizers,
            allowTextCommands = true,
            hasControlCommand = hasControlCommand,
            modewhenAccessGroupsOff = modewhenAccessGroupsOff
        )
    }
}
