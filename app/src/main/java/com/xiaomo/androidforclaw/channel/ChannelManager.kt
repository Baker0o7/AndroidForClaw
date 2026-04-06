/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/session.ts, registry.ts
 */
package com.xiaomo.androidforclaw.channel

import android.content.Context
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderService
import com.xiaomo.androidforclaw.accessibility.MediaProjectionHelper
import java.util.UUID

/**
 * Channel manager - Manage android App channel lifecycle and status
 *
 * Responsibilities:
 * - Account management (create, update, delete)
 * - Status tracking (connect, run, error)
 * - Permission checking (accessibility, overlay, screen capture)
 * - System hint words integration (channel hints)
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val TAG = "ChannelManager"
        private const val DEFAULT_ACCOUNT_ID = "android-device-default"
    }

    // Current account (single device schema)
    private var currentAccount: ChannelAccount? = null

    // Channel config
    private var channelConfig = ChannelConfig()

    init {
        // Initialize default account
        initializeDefaultAccount()
    }

    /**
     * Initialize default account (single device schema)
     */
    private fun initializeDefaultAccount() {
        val deviceId = getDeviceId()
        val account = ChannelAccount(
            accountId = DEFAULT_ACCOUNT_ID,
            name = "${android.os.Build.MODEL} (${android.os.Build.DEVICE})",
            enabled = true,
            configured = true,  // Android app doesn't need external config
            deviceId = deviceId,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel = android.os.Build.VERSION.SDK_INT,
            architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )

        currentAccount = account
        channelConfig = ChannelConfig(
            enabled = true,
            defaultAccount = DEFAULT_ACCOUNT_ID,
            accounts = mapOf(DEFAULT_ACCOUNT_ID to account)
        )

        Log.d(TAG, "[OK] Initialized android App channel")
        Log.d(TAG, "  - Account ID: ${account.accountId}")
        Log.d(TAG, "  - Device: ${account.name}")
        Log.d(TAG, "  - Android: ${account.androidVersion} (API ${account.apiLevel})")
    }

    /**
     * Get device ID (unique ID)
     */
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "android-${UUID.randomUUID()}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device ID, using random UUID", e)
            "android-${UUID.randomUUID()}"
        }
    }

    /**
     * Update account status (permissions, connect, etc.)
     */
    fun updateAccountStatus(): ChannelAccount {
        val current = currentAccount ?: return currentAccount!!

        val accessibilityEnabled = AccessibilityBinderService.serviceInstance != null
        val overlayPermission = Settings.canDrawOverlays(context)
        val mediaProjection = MediaProjectionHelper.isMediaProjectionGranted()

        // Overlay is optional feature, doesn't affect core connection status
        val connected = accessibilityEnabled && mediaProjection
        val running = accessibilityEnabled  // At least need accessibility service

        val updatedAccount = current.copy(
            accessibilityEnabled = accessibilityEnabled,
            overlayPermission = overlayPermission,
            mediaProjection = mediaProjection,
            connected = connected,
            running = running,
            linked = connected,
            lastProbeAt = System.currentTimeMillis()
        )

        currentAccount = updatedAccount
        channelConfig = channelConfig.copy(
            accounts = mapOf(DEFAULT_ACCOUNT_ID to updatedAccount)
        )

        Log.d(TAG, "[STATS] Account status updated:")
        Log.d(TAG, "  - Accessibility: $accessibilityEnabled")
        Log.d(TAG, "  - Overlay: $overlayPermission")
        Log.d(TAG, "  - Media Projection: $mediaProjection")
        Log.d(TAG, "  - Connected: $connected")
        Log.d(TAG, "  - Running: $running")

        return updatedAccount
    }

    /**
     * Record inbound message (user sent)
     */
    fun recordInbound() {
        currentAccount = currentAccount?.copy(
            lastInboundAt = System.currentTimeMillis()
        )
    }

    /**
     * Record outbound message (agent response)
     */
    fun recordOutbound() {
        currentAccount = currentAccount?.copy(
            lastOutboundAt = System.currentTimeMillis()
        )
    }

    /**
     * Record error
     */
    fun recordError(error: String) {
        currentAccount = currentAccount?.copy(
            lastError = error
        )
        Log.e(TAG, "Channel error: $error")
    }

    /**
     * Record start
     */
    fun recordStart() {
        currentAccount = currentAccount?.copy(
            running = true,
            lastStartAt = System.currentTimeMillis()
        )
    }

    /**
     * Record stop
     */
    fun recordStop() {
        currentAccount = currentAccount?.copy(
            running = false,
            lastStopAt = System.currentTimeMillis()
        )
    }

    /**
     * Get current channel status
     */
    fun getChannelStatus(): ChannelStatus {
        updateAccountStatus()  // Refresh status

        return ChannelStatus(
            timestamp = System.currentTimeMillis(),
            channelId = CHANNEL_ID,
            meta = CHANNEL_META,
            capabilities = ANDROID_CHANNEL_CAPABILITIES,
            accounts = listOf(currentAccount!!),
            defaultAccountId = DEFAULT_ACCOUNT_ID
        )
    }

    /**
     * Get current account
     */
    fun getCurrentAccount(): ChannelAccount {
        return currentAccount ?: throw IllegalStateException("No current account")
    }

    /**
     * Check channel whether available (all permissions already granted)
     */
    fun isChannelReady(): Boolean {
        val account = currentAccount ?: return false
        return account.accessibilityEnabled &&
               account.overlayPermission &&
               account.mediaProjection
    }

    /**
     * Get missing permission list
     */
    fun getMissingPermissions(): List<String> {
        val account = currentAccount ?: return emptyList()
        val missing = mutableListOf<String>()

        if (!account.accessibilityEnabled) missing.add("Accessibility service")
        if (!account.overlayPermission) missing.add("Display over Apps")
        if (!account.mediaProjection) missing.add("Screen Capture")

        return missing
    }

    /**
     * Get agent prompt hints (system hint words integration)
     */
    fun getAgentPromptHints(): List<String> {
        val account = currentAccount
        return AndroidChannelPromptHints.getMessageToolHints(account)
    }

    /**
     * Get runtime channel info (runtime section integration)
     */
    fun getRuntimeChannelInfo(): String {
        val account = currentAccount
        return AndroidChannelPromptHints.getRuntimeChannelInfo(account)
    }

    /**
     * Get channel capability description (for log)
     */
    fun getCapabilitiesDescription(): String {
        return buildString {
            appendLine("Channel Capabilities:")
            appendLine("  - Chat Types: ${ANDROID_CHANNEL_CAPABILITIES.chatTypes.joinToString()}")
            appendLine("  - Media: ${ANDROID_CHANNEL_CAPABILITIES.media}")
            appendLine("  - Native Commands: ${ANDROID_CHANNEL_CAPABILITIES.nativeCommands}")
            appendLine("  - Block Streaming: ${ANDROID_CHANNEL_CAPABILITIES.blockStreaming}")
        }
    }
}
