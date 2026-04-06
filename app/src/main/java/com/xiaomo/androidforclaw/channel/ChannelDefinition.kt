/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/registry.ts
 */
package com.xiaomo.androidforclaw.channel

/**
 * channel Definition - Define android App channel according to OpenClaw architecture
 *
 * OpenClaw channel core concepts:
 * - channel: Communication channel(whatsApp, Telegram, Discord, etc.)
 * - Account: Account within channel (multi-account support)
 * - session: session instance (conversation with user/device)
 * - Capabilities: channel capabilities(polls, threads, media, etc.)
 *
 * android App channel characteristics:
 * - Device control channel (non-social messaging channel)
 * - Single device direct execution mode (no groups, no threads)
 * - tool-intensive(tap, swipe, screenshot, etc.)
 * - Authentication: ADB/Accessibility pairing (not token)
 */

/**
 * channel ID - channel unique identifier
 */
const val CHANNEL_ID = "android-app"

/**
 * channel Meta - channel metadata
 */
data class channelMeta(
    val label: String,               // Display name
    val emoji: String,               // Icon emoji
    val description: String,         // Description
    val systemImage: String? = null  // System icon path
)

val CHANNEL_META = channelMeta(
    label = "android App",
    emoji = "[APP]",
    description = "androidforClaw android device control channel"
)

/**
 * channel Capabilities - channel capability definition (reference OpenClaw)
 */
data class channelCapabilities(
    val chatTypes: List<chat type>,    // Supported chat types
    val polls: Boolean = false,       // Polls
    val reactions: Boolean = false,   // Reactions/emoji
    val edit: Boolean = false,        // Edit messages
    val unsend: Boolean = false,      // Unsend messages
    val reply: Boolean = false,       // Reply to messages
    val effects: Boolean = false,     // Visual effects
    val groupManagement: Boolean = false,  // Group management
    val threads: Boolean = false,     // Threads/nested conversations
    val media: Boolean = false,       // Media (images/files)
    val nativeCommands: Boolean = false,   // Native commands
    val blockStreaming: Boolean = false    // Block streaming response
) {
    enum class chat type {
        DIRECT,      // Direct conversation
        GROUP,       // Group
        CHANNEL,     // channel
        THREAD       // Thread
    }
}

/**
 * android App channel capability configuration
 *
 * Comparison with other OpenClaw channels:
 * - whatsApp: direct, group, polls, reactions, media
 * - Telegram: direct, group, channel, thread, polls, reactions, media, nativeCommands, blockStreaming
 * - Discord: direct, channel, thread, polls, reactions, media, nativeCommands, blockStreaming
 * - Slack: direct, channel, thread, reactions, media, nativeCommands, blockStreaming
 * - Signal: direct, group, reactions, media
 *
 * android App: Minimal capabilities (device control only)
 */
val ANDROID_CHANNEL_CAPABILITIES = channelCapabilities(
    chatTypes = listOf(channelCapabilities.chat type.DIRECT),  // Direct execution only
    polls = false,
    reactions = false,
    edit = false,
    unsend = false,
    reply = false,
    effects = false,
    groupManagement = false,
    threads = false,
    media = true,                    // ✓ Screenshot/screen recording
    nativeCommands = true,           // ✓ Device operation commands
    blockStreaming = true            // ✓ Block streaming response(Wait for complete result)
)

/**
 * channel Account - Account configuration (corresponds to OpenClaw's channelAccountSnapshot)
 */
data class channelAccount(
    val accountId: String,                     // Account ID(android: device-{uuid})
    val name: String? = null,                  // Account name (device name)
    val enabled: Boolean = true,               // Is enabled
    val configured: Boolean = false,           // Is configured
    val linked: Boolean = false,               // Is linked
    val running: Boolean = false,              // Is running
    val connected: Boolean = false,            // Is linked
    val reconnectAttempts: Int = 0,            // Reconnect attempt count
    val lastConnectedAt: Long? = null,         // Last connected time
    val lastError: String? = null,             // Last error
    val lastStartAt: Long? = null,             // Last start time
    val lastStopAt: Long? = null,              // Last stop time
    val lastInboundAt: Long? = null,           // Last inbound message time
    val lastOutboundAt: Long? = null,          // Last outbound message time
    val lastProbeAt: Long? = null,             // Last probe time

    // android-specific fields
    val deviceId: String? = null,              // Device ID
    val devicemodel: String? = null,           // Device model
    val androidVersion: String? = null,        // android version
    val apiLevel: Int? = null,                 // API Level
    val architecture: String? = null,          // CPU architecture
    val accessibilityEnabled: Boolean = false, // Accessibility service status
    val overlayPermission: Boolean = false,    // overlay permission
    val mediaProjection: Boolean = false       // Screen recording permission
)

/**
 * channel Status - channel status snapshot (corresponds to OpenClaw's channelsStatusSnapshot)
 */
data class channelStatus(
    val timestamp: Long = System.currentTimeMillis(),
    val channelId: String = CHANNEL_ID,
    val meta: channelMeta = CHANNEL_META,
    val capabilities: channelCapabilities = ANDROID_CHANNEL_CAPABILITIES,
    val accounts: List<channelAccount> = emptyList(),
    val defaultAccountId: String? = null
)

/**
 * agent Prompt Hints - System prompt hints (corresponds to OpenClaw's agentPrompt.messagetoolHints)
 */
object androidchannelPromptHints {

    /**
     * Generate channel-specific system prompt hints
     */
    fun getMessagetoolHints(account: channelAccount? = null): List<String> {
        val hints = mutableListOf<String>()

        // Basic hints
        hints.a("You are running on an android device with direct access to device controls")
        hints.a("use tools to observe and control the device:")
        hints.a("  - Observation: screenshot, get_ui_tree")
        hints.a("  - Actions: tap, swipe, type, long_press")
        hints.a("  - Navigation: home, back, open_app")
        hints.a("  - System: wait, stop, notification")

        // Device-specific hints
        if (account != null) {
            hints.a("")
            hints.a("Device Information:")
            hints.a("  - model: ${account.devicemodel ?: "Unknown"}")
            hints.a("  - android: ${account.androidVersion ?: "Unknown"} (API ${account.apiLevel ?: "Unknown"})")
            hints.a("  - Architecture: ${account.architecture ?: "Unknown"}")

            // Permissions status hints
            hints.a("")
            hints.a("Permissions Status:")
            hints.a("  - Accessibility: ${if (account.accessibilityEnabled) "✓ Enabled" else "✗ Disabled"}")
            hints.a("  - overlay: ${if (account.overlayPermission) "✓ Granted" else "✗ not granted"}")
            hints.a("  - Screen Capture: ${if (account.mediaProjection) "✓ Granted" else "✗ not granted"}")
        }

        // Best practices hints
        hints.a("")
        hints.a("Best Practices:")
        hints.a("  - Always screenshot before and after actions")
        hints.a("  - Verify state changes after operations")
        hints.a("  - use wait() for loading states")
        hints.a("  - Try alternative approaches when blocked")

        return hints
    }

    /**
     * Generate Runtime Section channel information
     */
    fun getRuntimechannelInfo(account: channelAccount? = null): String {
        return buildString {
            appendLine("channel: $CHANNEL_ID")
            appendLine("channel_label: ${CHANNEL_META.label}")
            if (account != null) {
                appendLine("account_id: ${account.accountId}")
                appendLine("device_id: ${account.deviceId ?: "unknown"}")
                appendLine("device_model: ${account.devicemodel ?: "unknown"}")
            }
        }.trim()
    }
}

/**
 * channel config - channel configuration
 */
data class channelconfig(
    val enabled: Boolean = true,
    val defaultAccount: String? = null,
    val accounts: Map<String, channelAccount> = emptyMap()
)
