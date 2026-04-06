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
 * Channel Manager - Manage Android App Channel 的生命周期和Status
 *
 * 职责:
 * - AccountManage(Create、Update、Delete)
 * - StatusTrace(Connect、Run、Error)
 * - PermissionCheck(Accessibility、悬浮窗、录屏)
 * - 系统Hint词集成(Channel Hints)
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val TAG = "ChannelManager"
        private const val DEFAULT_ACCOUNT_ID = "android-device-default"
    }

    // 当FrontAccount(单DeviceSchema)
    private var currentAccount: ChannelAccount? = null

    // 渠道Config
    private var channelConfig = ChannelConfig()

    init {
        // InitializeDefaultAccount
        initializeDefaultAccount()
    }

    /**
     * InitializeDefaultAccount(单DeviceSchema)
     */
    private fun initializeDefaultAccount() {
        val deviceId = getDeviceId()
        val account = ChannelAccount(
            accountId = DEFAULT_ACCOUNT_ID,
            name = "${android.os.Build.MODEL} (${android.os.Build.DEVICE})",
            enabled = true,
            configured = true,  // Android App None需额OutsideConfig
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

        Log.d(TAG, "✅ Initialized Android App Channel")
        Log.d(TAG, "  - Account ID: ${account.accountId}")
        Log.d(TAG, "  - Device: ${account.name}")
        Log.d(TAG, "  - Android: ${account.androidVersion} (API ${account.apiLevel})")
    }

    /**
     * GetDevice ID(Unique标识)
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
     * UpdateAccountStatus(Permission、Connect等)
     */
    fun updateAccountStatus(): ChannelAccount {
        val current = currentAccount ?: return currentAccount!!

        val accessibilityEnableddd = AccessibilityBinderService.serviceInstance != null
        val overlayPermission = Settings.canDrawOverlays(context)
        val mediaProjection = MediaProjectionHelper.isMediaProjectionGranted()

        // 悬浮窗YesOptionalFeature, 不影响核心ConnectStatus
        val connected = accessibilityEnableddd && mediaProjection
        val running = accessibilityEnableddd  // 至少NeedAccessibilityService

        val updatedAccount = current.copy(
            accessibilityEnableddd = accessibilityEnableddd,
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

        Log.d(TAG, "📊 Account status updated:")
        Log.d(TAG, "  - Accessibility: $accessibilityEnableddd")
        Log.d(TAG, "  - Overlay: $overlayPermission")
        Log.d(TAG, "  - Media Projection: $mediaProjection")
        Log.d(TAG, "  - Connected: $connected")
        Log.d(TAG, "  - Running: $running")

        return updatedAccount
    }

    /**
     * Record入站Message(Usersend)
     */
    fun recordInbound() {
        currentAccount = currentAccount?.copy(
            lastInboundAt = System.currentTimeMillis()
        )
    }

    /**
     * Record出站Message(Agent Response)
     */
    fun recordOutbound() {
        currentAccount = currentAccount?.copy(
            lastOutboundAt = System.currentTimeMillis()
        )
    }

    /**
     * RecordError
     */
    fun recordError(error: String) {
        currentAccount = currentAccount?.copy(
            lastError = error
        )
        Log.e(TAG, "Channel error: $error")
    }

    /**
     * RecordStart
     */
    fun recordStart() {
        currentAccount = currentAccount?.copy(
            running = true,
            lastStartAt = System.currentTimeMillis()
        )
    }

    /**
     * RecordStop
     */
    fun recordStop() {
        currentAccount = currentAccount?.copy(
            running = false,
            lastStopAt = System.currentTimeMillis()
        )
    }

    /**
     * Get当Front渠道Status
     */
    fun getChannelStatus(): ChannelStatus {
        updateAccountStatus()  // RefreshStatus

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
     * Get当FrontAccount
     */
    fun getCurrentAccount(): ChannelAccount {
        return currentAccount ?: throw IllegalStateException("No current account")
    }

    /**
     * Check渠道YesNoAvailable(AllPermission已grant)
     */
    fun isChannelReady(): Boolean {
        val account = currentAccount ?: return false
        return account.accessibilityEnableddd &&
               account.overlayPermission &&
               account.mediaProjection
    }

    /**
     * Get缺失的PermissionList
     */
    fun getMissingPermissions(): List<String> {
        val account = currentAccount ?: return emptyList()
        val missing = mutableListOf<String>()

        if (!account.accessibilityEnableddd) missing.add("Accessibility Service")
        if (!account.overlayPermission) missing.add("Display Over Apps")
        if (!account.mediaProjection) missing.add("Screen Capture")

        return missing
    }

    /**
     * Get Agent Prompt Hints(系统Hint词集成)
     */
    fun getAgentPromptHints(): List<String> {
        val account = currentAccount
        return AndroidChannelPromptHints.getMessageToolHints(account)
    }

    /**
     * Get Runtime Channel Info(Runtime Section 集成)
     */
    fun getRuntimeChannelInfo(): String {
        val account = currentAccount
        return AndroidChannelPromptHints.getRuntimeChannelInfo(account)
    }

    /**
     * Get渠道CapabilityDescription(用于Log)
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
