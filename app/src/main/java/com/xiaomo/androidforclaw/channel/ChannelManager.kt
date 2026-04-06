/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/channels/session.ts, registry.ts
 */
package com.xiaomo.androidforclaw.channel

import android.content.context
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.accessibility.service.AccessibilityBinderservice
import com.xiaomo.androidforclaw.accessibility.MediaProjectionhelper
import java.util.UUID

/**
 * channel manager - Manage android App channel 生命week期andStatus
 *
 * 职责:
 * - AccountManage(Create、Update、Delete)
 * - StatusTrace(Connect、Run、Error)
 * - PermissionCheck(Accessibility、悬浮窗、录屏)
 * - 系统Hint词集成(channel Hints)
 */
class channelmanager(private val context: context) {

    companion object {
        private const val TAG = "channelmanager"
        private const val DEFAULT_ACCOUNT_ID = "android-device-default"
    }

    // whenFrontAccount(单Deviceschema)
    private var currentAccount: channelAccount? = null

    // 渠道config
    private var channelconfig = channelconfig()

    init {
        // InitializeDefaultAccount
        initializeDefaultAccount()
    }

    /**
     * InitializeDefaultAccount(单Deviceschema)
     */
    private fun initializeDefaultAccount() {
        val deviceId = getDeviceId()
        val account = channelAccount(
            accountId = DEFAULT_ACCOUNT_ID,
            name = "${android.os.Build.MODEL} (${android.os.Build.DEVICE})",
            enabled = true,
            configured = true,  // android App Noneneed额outsideconfig
            deviceId = deviceId,
            devicemodel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel = android.os.Build.VERSION.SDK_INT,
            architecture = android.os.Build.SUPPORTED_ABIS.firstorNull() ?: "unknown"
        )

        currentAccount = account
        channelconfig = channelconfig(
            enabled = true,
            defaultAccount = DEFAULT_ACCOUNT_ID,
            accounts = mapOf(DEFAULT_ACCOUNT_ID to account)
        )

        Log.d(TAG, "[OK] Initialized android App channel")
        Log.d(TAG, "  - Account ID: ${account.accountId}")
        Log.d(TAG, "  - Device: ${account.name}")
        Log.d(TAG, "  - android: ${account.androidVersion} (API ${account.apiLevel})")
    }

    /**
     * GetDevice ID(Uniqueid)
     */
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "android-${UUID.randomUUID()}"
        } catch (e: exception) {
            Log.w(TAG, "Failed to get device ID, using random UUID", e)
            "android-${UUID.randomUUID()}"
        }
    }

    /**
     * UpdateAccountStatus(Permission、Connect等)
     */
    fun updateAccountStatus(): channelAccount {
        val current = currentAccount ?: return currentAccount!!

        val accessibilityEnabled = AccessibilityBinderservice.serviceInstance != null
        val overlayPermission = Settings.canDrawoverlays(context)
        val mediaProjection = MediaProjectionhelper.isMediaProjectionGranted()

        // 悬浮窗YesOptionalFeature, not影响核心ConnectStatus
        val connected = accessibilityEnabled && mediaProjection
        val running = accessibilityEnabled  // 至fewneedAccessibilityservice

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
        channelconfig = channelconfig.copy(
            accounts = mapOf(DEFAULT_ACCOUNT_ID to updatedAccount)
        )

        Log.d(TAG, "[STATS] Account status updated:")
        Log.d(TAG, "  - Accessibility: $accessibilityEnabled")
        Log.d(TAG, "  - overlay: $overlayPermission")
        Log.d(TAG, "  - Media Projection: $mediaProjection")
        Log.d(TAG, "  - Connected: $connected")
        Log.d(TAG, "  - Running: $running")

        return updatedAccount
    }

    /**
     * Record入站Message(usersend)
     */
    fun recordInbound() {
        currentAccount = currentAccount?.copy(
            lastInboundAt = System.currentTimeMillis()
        )
    }

    /**
     * Record出站Message(agent Response)
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
        Log.e(TAG, "channel error: $error")
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
     * GetwhenFront渠道Status
     */
    fun getchannelStatus(): channelStatus {
        updateAccountStatus()  // RefreshStatus

        return channelStatus(
            timestamp = System.currentTimeMillis(),
            channelId = CHANNEL_ID,
            meta = CHANNEL_META,
            capabilities = ANDROID_CHANNEL_CAPABILITIES,
            accounts = listOf(currentAccount!!),
            defaultAccountId = DEFAULT_ACCOUNT_ID
        )
    }

    /**
     * GetwhenFrontAccount
     */
    fun getCurrentAccount(): channelAccount {
        return currentAccount ?: throw IllegalStateexception("No current account")
    }

    /**
     * Check渠道whetherAvailable(AllPermissionalreadygrant)
     */
    fun ischannelReady(): Boolean {
        val account = currentAccount ?: return false
        return account.accessibilityEnabled &&
               account.overlayPermission &&
               account.mediaProjection
    }

    /**
     * Get缺失PermissionList
     */
    fun getMissingPermissions(): List<String> {
        val account = currentAccount ?: return emptyList()
        val missing = mutableListOf<String>()

        if (!account.accessibilityEnabled) missing.a("Accessibility service")
        if (!account.overlayPermission) missing.a("Display over Apps")
        if (!account.mediaProjection) missing.a("Screen Capture")

        return missing
    }

    /**
     * Get agent Prompt Hints(系统Hint词集成)
     */
    fun getagentPromptHints(): List<String> {
        val account = currentAccount
        return androidchannelPromptHints.getMessagetoolHints(account)
    }

    /**
     * Get Runtime channel Info(Runtime Section 集成)
     */
    fun getRuntimechannelInfo(): String {
        val account = currentAccount
        return androidchannelPromptHints.getRuntimechannelInfo(account)
    }

    /**
     * Get渠道CapabilityDescription(用于Log)
     */
    fun getCapabilitiesDescription(): String {
        return buildString {
            appendLine("channel Capabilities:")
            appendLine("  - Chat Types: ${ANDROID_CHANNEL_CAPABILITIES.chatTypes.joinToString()}")
            appendLine("  - Media: ${ANDROID_CHANNEL_CAPABILITIES.media}")
            appendLine("  - Native Commands: ${ANDROID_CHANNEL_CAPABILITIES.nativeCommands}")
            appendLine("  - Block Streaming: ${ANDROID_CHANNEL_CAPABILITIES.blockStreaming}")
        }
    }
}
