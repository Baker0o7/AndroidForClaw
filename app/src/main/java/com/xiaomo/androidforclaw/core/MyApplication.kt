package com.xiaomo.androidforclaw.core

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/boot.ts, ../openclaw/src/entry.ts
 */


import android.app.Activity
import com.xiaomo.androidforclaw.util.ReasoningTagFilter
import android.app.Application
import android.content.context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.looper
import android.os.Message
import android.provider.Settings
import com.xiaomo.androidforclaw.logging.Log
import android.widget.Toast
import com.xiaomo.androidforclaw.accessibility.AccessibilityProxy
import com.xiaomo.androidforclaw.accessibility.AccessibilityHealthMonitor
import com.xiaomo.androidforclaw.util.GlobalexceptionHandler
import com.xiaomo.androidforclaw.workspace.StoragePaths
import com.xiaomo.androidforclaw.util.SPhelper
import com.xiaomo.androidforclaw.util.WakeLockmanager
import com.xiaomo.androidforclaw.camera.CameraCapturemanager
import com.xiaomo.androidforclaw.data.model.TaskDatamanager
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.withTimeout
import com.xiaomo.androidforclaw.gateway.Gatewayservice
import com.xiaomo.androidforclaw.gateway.MainEntryagentHandler
import com.xiaomo.androidforclaw.gateway.GatewayServer
import com.xiaomo.androidforclaw.gateway.GatewayController
import com.xiaomo.androidforclaw.agent.session.sessionmanager
import com.xiaomo.androidforclaw.agent.skills.skillsLoader
import com.xiaomo.androidforclaw.config.configLoader
import com.xiaomo.feishu.Feishuchannel
import com.xiaomo.feishu.Feishuconfig
import com.xiaomo.discord.Discordchannel
import com.xiaomo.discord.Discordconfig
import com.xiaomo.discord.channelEvent
import com.xiaomo.discord.session.Discordsessionmanager
import com.xiaomo.discord.session.DiscordHistorymanager
import com.xiaomo.discord.session.DiscordDedup
import com.xiaomo.discord.messaging.DiscordTyping
import com.xiaomo.telegram.Telegramchannel
import com.xiaomo.telegram.Telegramconfig
import com.xiaomo.telegram.TelegramClient
import com.xiaomo.telegram.messaging.TelegramTyping
import com.xiaomo.telegram.messaging.TelegramSender
import com.xiaomo.slack.Slackchannel
import com.xiaomo.slack.Slackconfig
import com.xiaomo.slack.SlackClient
import com.xiaomo.slack.messaging.SlackSender
import com.xiaomo.slack.messaging.SlackTyping as SlackTypinghelper
import com.xiaomo.signal.Signalchannel
import com.xiaomo.signal.Signalconfig
import com.xiaomo.signal.SignalClient
import com.xiaomo.signal.messaging.SignalTyping
import com.xiaomo.signal.messaging.SignalSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import com.xiaomo.androidforclaw.providers.llm.tonewMessage
import com.xiaomo.androidforclaw.providers.llm.toLegacyMessage
import com.xiaomo.androidforclaw.agent.tools.toolRegistry
import com.xiaomo.androidforclaw.agent.tools.androidtoolRegistry
import com.xiaomo.androidforclaw.agent.context.contextBuilder
import com.xiaomo.androidforclaw.agent.loop.agentloop
import com.xiaomo.androidforclaw.agent.loop.ProgressUpdate
import com.xiaomo.androidforclaw.providers.UnifiedLLMprovider
import com.xiaomo.androidforclaw.core.channel.channelAdapter
import com.xiaomo.androidforclaw.core.channel.channelMessageProcessor

/**
 */
class MyApplication : ai.openclaw.app.NodeApp(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "MyApplication"
        private var activeActivityCount = 0
        private var isChangingconfiguration = false

        lateinit var application: Application

        // Singleton access
        val instance: MyApplication
            get() = application as MyApplication

        // Gateway Server
        private var gatewayServer: GatewayServer? = null

        // Gateway Controller
        private var gatewayController: GatewayController? = null

        // local in-process channel(bypass WebSocket)
        private var localGatewaychannel: com.xiaomo.androidforclaw.gateway.LocalGatewaychannel? = null

        fun isGatewayRunning(): Boolean = gatewayController != null

        // Feishu channel
        private var feishuchannel: Feishuchannel? = null
        private var feishuWakeLock: android.os.Powermanager.WakeLock? = null

        /**
         * Get Feishu channel (for tool invocation)
         */
        fun getFeishuchannel(): Feishuchannel? = feishuchannel

        // Message queue manager: fully aligned with OpenClaw's queue mechanism
        // Supports five modes: interrupt, steer, followup, collect, queue
        private val messagequeuemanager = Messagequeuemanager()

        // Discord channel
        private var discordchannel: Discordchannel? = null
        private val discordsessionmanager = Discordsessionmanager()
        private val discordHistorymanager = DiscordHistorymanager(maxHistoryPerchannel = 50)
        private val discordDedup = DiscordDedup()
        private var discordTyping: DiscordTyping? = null
        private val discordProcessingJobs = mutableMapOf<String, Job>()

        // Telegram channel
        private var telegramchannel: Telegramchannel? = null
        private var telegramTyping: TelegramTyping? = null
        private val telegramProcessingJobs = mutableMapOf<String, Job>()
        private val telegramDedup = java.util.collections.synchronizedSet(mutableSetOf<Long>())

        // Signal channel
        private var signalchannel: Signalchannel? = null
        private var signalTyping: SignalTyping? = null
        private val signalProcessingJobs = mutableMapOf<String, Job>()
        private val signalDedup = java.util.collections.synchronizedSet(mutableSetOf<Long>())

        // Slack channel
        private var slackchannel: Slackchannel? = null
        private val slackProcessingJobs = mutableMapOf<String, Job>()
        private val slackDedup = java.util.collections.synchronizedSet(mutableSetOf<String>())

        // Weixin channel
        private var weixinchannel: com.xiaomo.weixin.Weixinchannel? = null
        fun getWeixinchannel(): com.xiaomo.weixin.Weixinchannel? = weixinchannel

        // Weixin agent loop tracking moved to Messagequeuemanager (channel-agnostic)

        // Cron heartbeat delivery: last active chat
        private var lastActiveChatId: String? = null
        private var lastActivechannel: String? = null

        fun getLastActiveChat(): Pair<String?, String?> = Pair(lastActivechannel, lastActiveChatId)

        fun setLastActiveChat(channel: String, chatId: String) {
            lastActivechannel = channel
            lastActiveChatId = chatId
        }

        // Accessibility Health Monitor
        private var healthMonitor: AccessibilityHealthMonitor? = null

        // Camera Capture manager (Aligned with OpenClaw CameraCapturemanager)
        private var cameraCapturemanager: CameraCapturemanager? = null

        fun getCameraCapturemanager(): CameraCapturemanager? = cameraCapturemanager

        private fun onAppforeground() {
            Log.d(TAG, "App returned to foreground")
            // Check if test task is running, if so ensure WakeLock is acquired
            ensureWakeLockforTesting()
        }

        private fun onAppbackground() {
            Log.d(TAG, "App entered background")
            // Check if test task is running, if so ensure WakeLock is acquired
            ensureWakeLockforTesting()
        }

        /**
         * Check test task status, if test task is running ensure WakeLock is acquired
         * This ensures the app won't lock screen when running in background
         *
         * Called at:
         * 1. App startup (onCreate)
         * 2. App entering background (onAppbackground)
         * 3. App returning to foreground (onAppforeground)
         */
        private fun ensureWakeLockforTesting() {
            try {
                val taskDatamanager = TaskDatamanager.getInstance()
                val hasTask = taskDatamanager.hasCurrentTask()
                
                if (hasTask) {
                    val taskData = taskDatamanager.getCurrentTaskData()
                    val isRunning = taskData?.getIsRunning() ?: false

                    if (isRunning) {
                        // Test task is running, ensure WakeLock is acquired
                        // acquireScreenWakeLock has internal duplicate acquisition prevention, safe to call
                        Log.d(TAG, "Detected test task running, WakeLock acquired(app status: ${if (activeActivityCount == 0) "background" else "foreground"})")
                        WakeLockmanager.acquireScreenWakeLock()
                    } else {
                        // Test task has stopped, release WakeLock
                        Log.d(TAG, "Test task stopped, Releasing WakeLock")
                        WakeLockmanager.releaseScreenWakeLock()
                    }
                } else {
                    // No test task, ensure WakeLock is released
                    // releaseScreenWakeLock has internal check, skip if not active
                    if (WakeLockmanager.isScreenWakeLockActive()) {
                        Log.d(TAG, "No test task, Releasing WakeLock")
                        WakeLockmanager.releaseScreenWakeLock()
                    } else {
                        Log.d(TAG, "No test task, WakeLock not active, nothing to release")
                    }
                }
            } catch (e: exception) {
                Log.e(TAG, "Check test task status failed: ${e.message}", e)
            }
        }

        /**
         * Handle messages from ChatBroadcastReceiver
         * Send local broadcast for MainActivityCompose to handle
         */
        fun handleChatBroadcast(message: String) {
            Log.d(TAG, "[MSG] handleChatBroadcast: $message")
            try {
                // Send local broadcast for MainActivityCompose to handle
                val intent = Intent("com.xiaomo.androidforclaw.CHAT_MESSAGE_FROM_BROADCAST")
                intent.putExtra("message", message)
                androidx.localbroadcastmanager.content.LocalBroadcastmanager
                    .getInstance(application)
                    .sendBroadcast(intent)
                Log.d(TAG, "[OK] sent local broadcast")
            } catch (e: exception) {
                Log.e(TAG, "send local broadcast failed: ${e.message}", e)
            }
        }
    }

    override fun provideLocalChatchannel(): com.xiaomo.base.IGatewaychannel? = localGatewaychannel

    override fun onCreate() {
        super.onCreate()
        application = this

        // Apply saved language settings
        com.xiaomo.androidforclaw.util.Localehelper.appLanguage(this)

        MMKV.initialize(this)
        com.xiaomo.androidforclaw.config.providerRegistry.init(this)
        registerActivityLifecycleCallbacks(this)

        // Initialize CameraCapturemanager (Aligned with OpenClaw camera.snap/clip)
        cameraCapturemanager = CameraCapturemanager(this)

        // Initialize file logging system
        initializeFileLogger()

        // Initialize workspace (aligned with OpenClaw)
        initializeWorkspace()

        // Initialize Cron scheduled tasks
        initializeCronJobs()

        // Register global exception handler
        Thread.setDefaultUncaughtexceptionHandler(GlobalexceptionHandler())

        // Start foreground service keep-alive
        startforegroundserviceKeepAlive()

        // Start Gateway server
        startGatewayServer()

        // [OK] Test config system
        testconfigSystem()

        // [WARN] Block 1: skillParser test temporarily skipped (JSON parsing issue pending fix)
        // testskillParser()
        Log.i(TAG, "[SKIP]  Block 1 test skipped, app continues to start")

        // Check if test task is running on app startup, if so acquire WakeLock
        // Delayed check to ensure TaskDatamanager is initialized
        Handler(looper.getMainlooper()).postDelayed({
            ensureWakeLockforTesting()
        }, 1000) // 1 second delay

        // [NET] Start Gateway service
        startGatewayservice()

        // [PENGUIN] Termux SSH pre-warm: only warm-up when sshd is already running, don't actively pull up Termux
        // Termux on-demand start: triggered only when AI actually calls exec tool
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val termux = com.xiaomo.androidforclaw.agent.tools.TermuxBridgetool(applicationcontext)
                if (!termux.isTermuxInstalled()) return@launch
                val status = termux.getStatus()
                if (status.ready) {
                    com.xiaomo.androidforclaw.agent.tools.TermuxSSHPool.warmUp(applicationcontext)
                    Log.i(TAG, "[OK] Termux SSH pool warmed up (sshd already running)")
                } else {
                    Log.i(TAG, "Termux SSH warm-up skipped: sshd not running (on-demand start)")
                }
            } catch (e: exception) {
                Log.w(TAG, "Termux SSH warm-up skipped: ${e.message}")
            }
        }

        // [APP] Start channels (only if storage permission is granted)
        if (hasStoragePermission()) {
            startAllchannels()
        } else {
            Log.w(TAG, "[WARN] Storage permission not authorized, skip channel initialization. authorized back will auto start. ")
        }

        // [WINDOW] Initialize floating window manager
        com.xiaomo.androidforclaw.ui.float.sessionFloatWindow.init(this)

        // [PLUG] Start health monitoring (serviceInstance managed by observer lifecycle)
        healthMonitor = AccessibilityHealthMonitor(applicationcontext)
        healthMonitor?.startMonitoring()

        // Listen to connection status
        GlobalScope.launch(Dispatchers.Main) {
            AccessibilityProxy.isConnected.observeforever { connected ->
                if (connected) {
                    Log.i(TAG, "[OK] Accessibilityservice connected")
                } else {
                    Log.w(TAG, "[WARN] Accessibilityservicenot connected")
                }
            }
        }



    }

    fun isAppInbackground(): Boolean {
        return activeActivityCount == 0
    }

    /**
     * Start foreground service keep-alive
     */
    private fun startforegroundserviceKeepAlive() {
        try {
            val serviceIntent = Intent(this, foregroundservice::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startforegroundservice(serviceIntent)
            } else {
                startservice(serviceIntent)
            }
            Log.i(TAG, "[OK] foreground service started(keep-alive)")
        } catch (e: android.app.foregroundserviceStartnotAllowedexception) {
            // android 14+: cannot start foreground service from background
            Log.w(TAG, "[WARN] foreground service start restricted(app in background), will retry next time returning to foreground")
        } catch (e: exception) {
            Log.e(TAG, "[ERROR] foregroundserviceStartFailed", e)
        }
    }

    /**
     * Start Gateway server
     */
    private fun startGatewayServer() {
        try {
            // Stop old instance first (if exists)
            gatewayServer?.stop()
            gatewayServer = null

            // Create and start new instance
            gatewayServer = GatewayServer(this, port = 19789)
            gatewayServer?.start()

            Log.i(TAG, "[OK] Gateway Server started successfully")
            Log.i(TAG, "  - HTTP: http://0.0.0.0:19789")
            Log.i(TAG, "  - WebSocket: ws://0.0.0.0:19789/ws")

            // Get local IP
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val ip = getLocalIpAress()
                    if (ip != null) {
                        Log.i(TAG, "  - LAN access: http://$ip:19789")
                    }
                } catch (e: exception) {
                    Log.w(TAG, "cannot get local IP", e)
                }
            }

        } catch (e: exception) {
            Log.e(TAG, "[ERROR] Gateway Server failed to start", e)
        }
    }

    /**
     * Get local IP aress
     */
    private fun getLocalIpAress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val aresses = networkInterface.inetAresses
                while (aresses.hasMoreElements()) {
                    val aress = aresses.nextElement()
                    if (!aress.isloopbackAress && aress is java.net.Inet4Aress) {
                        return aress.hostAress
                    }
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Get IP aress failed", e)
        }
        return null
    }

    /**
     * Test config system
     */
    /**
     * Initialize file logging system
     */
    private fun initializeFileLogger() {
        try {
            com.xiaomo.androidforclaw.logging.AppLog.init(this)
            Log.i(TAG, "[OK] File logging system initialized")
        } catch (e: exception) {
            Log.e(TAG, "Initialize file logging system failed", e)
        }
    }

    /**
     * Initialize Cron scheduled tasks
     */
    private fun initializeCronJobs() {
        try {
            com.xiaomo.androidforclaw.cron.CronInitializer.initialize(this)
            Log.i(TAG, "[OK] Cron system initialized")
        } catch (e: exception) {
            Log.e(TAG, "Initialize Cron system failed", e)
        }
    }

    /**
     * Initialize workspace (aligned with OpenClaw)
     */
    private fun initializeWorkspace() {
        try {
            val initializer = com.xiaomo.androidforclaw.workspace.WorkspaceInitializer(this)

            if (!initializer.isWorkspaceInitialized()) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "[DIR] first start - Initialize workspace...")
                Log.i(TAG, "========================================")

                val success = initializer.initializeWorkspace()

                if (success) {
                    Log.i(TAG, "[OK] Workspace initialized successfully")
                    Log.i(TAG, "   Path: ${initializer.getWorkspacePath()}")
                    Log.i(TAG, "   Device ID: ${initializer.getDeviceId()}")
                    Log.i(TAG, "   files: BOOTSTRAP.md, IDENTITY.md, USER.md, SOUL.md, AGENTS.md, TOOLS.md")
                } else {
                    Log.e(TAG, "[ERROR] Workspace initialized failed")
                }
            } else {
                Log.d(TAG, "Workspace initialized: ${initializer.getWorkspacePath()}")
            }

            // Always ensure bundled skills are deployed (copies missing, won't overwrite)
            initializer.ensureBundledskills()

        } catch (e: exception) {
            Log.e(TAG, "Initialize workspace Failed", e)
        }
    }

    private fun testconfigSystem() {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "[TEST] config system test started")
            Log.d(TAG, "========================================")

            // Run basic config tests
            // com.xiaomo.androidforclaw.config.configTestRunner.runBasicTests(this)

            // Test LegacyRepository config integration
            // com.xiaomo.androidforclaw.config.configTestRunner.testLegacyRepository(this)

            Log.d(TAG, "")
            Log.d(TAG, "========================================")
            Log.i(TAG, "[OK] config system test complete!")
            Log.d(TAG, "========================================")

        } catch (e: exception) {
            Log.e(TAG, "[ERROR] config system test exception: ${e.message}", e)
        }
    }



    /**
     * Start auto test
     */
    private fun startAutoTest() {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "[START] Start auto test")
            Log.i(TAG, "========================================")
            /*
            */
            Log.i(TAG, "========================================")

            // Start MainEntrynew to execute test
            /*
            GlobalScope.launch(Dispatchers.Main) {
                try {
                    MainEntrynew.run(
                        application = application
                    )
                } catch (e: exception) {
                    Log.e(TAG, "Auto test execution failed: ${e.message}", e)
                }
            }
            */

        } catch (e: exception) {
            Log.e(TAG, "Start auto testFailed: ${e.message}", e)
        }
    }

    /**
     * Start Gateway service
     */
    private fun startGatewayservice() {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "[NET] Start Gateway service (GatewayController)...")
            Log.i(TAG, "========================================")

            // Initialize TaskDatamanager
            val taskDatamanager = TaskDatamanager.getInstance()

            // Initialize LLM provider
            val llmprovider = UnifiedLLMprovider(this)

            // Initialize dependencies
            val toolRegistry = toolRegistry(this, taskDatamanager)
            val androidtoolRegistry = androidtoolRegistry(this, taskDatamanager, cameraCapturemanager = cameraCapturemanager)
            val skillsLoader = skillsLoader(this)
            val workspaceDir = StoragePaths.workspace
            val sessionmanager = sessionmanager(workspaceDir)

            // Create agentloop (requires these dependencies)
            val agentloop = agentloop(
                llmprovider = llmprovider,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                contextmanager = null,
                maxIterations = 50,
                modelRef = null
            )

            // Create GatewayController(port from MMKV config, default 8765)
            val gwUrl = com.tencent.mmkv.MMKV.defaultMMKV()
                ?.decodeString(com.xiaomo.androidforclaw.util.MMKVKeys.GATEWAY_URL.key, "ws://127.0.0.1:8765")
                ?: "ws://127.0.0.1:8765"
            val gwPort = try { java.net.URI(gwUrl).port.coerceAtLeast(8765) } catch (_: exception) { 8765 }

            gatewayController = GatewayController(
                context = this,
                agentloop = agentloop,
                sessionmanager = sessionmanager,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                skillsLoader = skillsLoader,
                port = gwPort,
                authToken = null // Temporarily disable auth
            )

            Log.i(TAG, "[OK] GatewayController instance created successfully")

            // Create local in-process channel, bypass WebSocket
            localGatewaychannel = com.xiaomo.androidforclaw.gateway.LocalGatewaychannel(gatewayController!!)
            Log.i(TAG, "[OK] LocalGatewaychannel created successfully")

            // Start service
            gatewayController?.start()

            Log.i(TAG, "========================================")
            Log.i(TAG, "[OK] Gateway service started: ws://0.0.0.0:$gwPort")
            Log.i(TAG, "========================================")

        } catch (e: exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "[ERROR] Gateway initialization failed", e)
            e.printStackTrace()
            Log.e(TAG, "========================================")
        }
    }

    /**
     * Check if MANAGE_EXTERNAL_STORAGE permission is granted (android 11+).
     * below android 11, always returns true.
     */
    private fun hasStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStoragemanager()
        } else {
            true
        }
    }

    /**
     * Start all messaging channels. Called after storage permission is granted.
     * Safe to call multiple times — guarded by AtomicBoolean to prevent duplicate collectors.
     * use [restartAllchannels] to force restart.
     */
    private val channelsStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun startAllchannels() {
        if (!channelsStarted.compareandSet(false, true)) {
            Log.i(TAG, "[SKIP] startAllchannels() already started, skip duplicate call")
            return
        }
        Log.i(TAG, "[START] startAllchannels() — Starting all message channels")
        startFeishuchannelifEnabled()
        startDiscordchannelifEnabled()
        startTelegramchannelifEnabled()
        startSlackchannelifEnabled()
        startSignalchannelifEnabled()
        startWeixinchannelifEnabled()
    }

    fun restartAllchannels() {
        Log.i(TAG, "[SYNC] restartAllchannels() — RestartAllMessagechannel")
        channelsStarted.set(false)
        startAllchannels()
    }

    /**
     * Start Feishu channel (if enabled in config)
     */
    private fun startFeishuchannelifEnabled() {
        Log.i(TAG, "[TIME] startFeishuchannelIfEnabled() called")
        val scope = CoroutineScope(Dispatchers.IO)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "========================================")
                Log.i(TAG, "[APP] Check Feishu channel config...")
                Log.i(TAG, "========================================")

                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val feishuconfig = openClawconfig.channels.feishu

                if (!feishuconfig.enabled) {
                    Log.i(TAG, "[SKIP]  Feishu channel not enabled, skip initialization")
                    Log.i(TAG, "   configPath: /sdcard/.androidforclaw/openclaw.json")
                    Log.i(TAG, "   Set channels.feishu.enabled = true to enable")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                Log.i(TAG, "[OK] Feishu channel enabled, ready to start...")
                Log.i(TAG, "   App ID: ${feishuconfig.appId}")
                Log.i(TAG, "   Domain: ${feishuconfig.domain}")
                Log.i(TAG, "   Mode: ${feishuconfig.connectionMode}")
                Log.i(TAG, "   DM Policy: ${feishuconfig.dmPolicy}")
                Log.i(TAG, "   Group Policy: ${feishuconfig.groupPolicy}")

                // Create Feishuconfig
                val config = Feishuconfig(
                    appId = feishuconfig.appId,
                    appSecret = feishuconfig.appSecret,
                    verificationToken = feishuconfig.verificationToken,
                    encryptKey = feishuconfig.encryptKey,
                    domain = feishuconfig.domain,
                    connectionMode = when (feishuconfig.connectionMode) {
                        "webhook" -> Feishuconfig.ConnectionMode.WEBHOOK
                        "websocket" -> Feishuconfig.ConnectionMode.WEBSOCKET
                        else -> Feishuconfig.ConnectionMode.WEBSOCKET
                    },
                    dmPolicy = when (feishuconfig.dmPolicy.lowercase()) {
                        "open" -> Feishuconfig.DmPolicy.OPEN
                        "pairing" -> Feishuconfig.DmPolicy.PAIRING
                        "allowlist" -> Feishuconfig.DmPolicy.ALLOWLIST
                        else -> Feishuconfig.DmPolicy.PAIRING
                    },
                    groupPolicy = when (feishuconfig.groupPolicy.lowercase()) {
                        "open" -> Feishuconfig.GroupPolicy.OPEN
                        "allowlist" -> Feishuconfig.GroupPolicy.ALLOWLIST
                        "disabled" -> Feishuconfig.GroupPolicy.DISABLED
                        else -> Feishuconfig.GroupPolicy.ALLOWLIST
                    },
                    requireMention = feishuconfig.requireMention,
                    historyLimit = feishuconfig.historyLimit ?: 0,
                    dmHistoryLimit = feishuconfig.dmHistoryLimit ?: 0
                )

                // Create and start Feishuchannel
                feishuchannel = Feishuchannel(config)
                val result = feishuchannel?.start()

                if (result?.isSuccess == true) {
                    // Update MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_feishu_enabled", true)

                    // get PARTIAL_WAKE_LOCK to keep CPU running, prevent lock screen Doze network disconnect
                    try {
                        val pm = getSystemservice(android.content.context.POWER_SERVICE) as android.os.Powermanager
                        val wl = pm.newWakeLock(
                            android.os.Powermanager.PARTIAL_WAKE_LOCK,
                            "androidforClaw::Feishuchannel"
                        )
                        wl.setReferenceCounted(false)
                        wl.acquire()
                        feishuWakeLock = wl
                        Log.i(TAG, "[LOCK] Acquired Feishu channel WakeLock(prevent lock screen disconnection)")
                    } catch (e: exception) {
                        Log.w(TAG, "Get Feishu WakeLock Failed: ${e.message}")
                    }

                    Log.i(TAG, "========================================")
                    Log.i(TAG, "[OK] Feishu channel StartSuccess!")
                    Log.i(TAG, "   can now receive Feishu messages")
                    Log.i(TAG, "========================================")

                    // Register feishu tools into MainEntrynew's toolRegistry
                    // (so broadcast/gateway messages also get feishu tools)
                    try {
                        val maintoolRegistry = MainEntrynew.gettoolRegistry()
                        val ftr = feishuchannel?.gettoolRegistry()
                        if (maintoolRegistry != null && ftr != null) {
                            // Register Feishu tools into function-call toolRegistry
                            // (skills are guidance; actual tool execution still needs registry entry)
                            val count = com.xiaomo.androidforclaw.agent.tools.registerFeishutools(maintoolRegistry, ftr)
                            Log.i(TAG, "[WRENCH] registered $count Feishu tools to MainEntrynew toolRegistry")
                        } else {
                            Log.w(TAG, "[WARN] MainEntrynew not initialized, Feishu tools will register on first message process")
                        }
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to register Feishu tools to MainEntrynew: ${e.message}")
                    }

                    // Subscribe to event flow, handle received messages
                    scope.launch(Dispatchers.IO) {
                        feishuchannel?.eventFlow?.collect { event ->
                            handleFeishuEvent(event)
                        }
                    }
                } else {
                    // Clear MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_feishu_enabled", false)

                    Log.e(TAG, "========================================")
                    Log.e(TAG, "[ERROR] Feishu channel StartFailed")
                    Log.e(TAG, "   Error: ${result?.exceptionorNull()?.message}")
                    Log.e(TAG, "========================================")
                }

            } catch (e: exception) {
                // Clear MMKV status
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_feishu_enabled", false)

                Log.e(TAG, "========================================")
                Log.e(TAG, "[ERROR] Feishu channel Initializeexception", e)
                Log.e(TAG, "========================================")
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

    }

    override fun onActivityStarted(activity: Activity) {
        activeActivityCount += 1
        if (activeActivityCount == 1 && isChangingconfiguration) {
            isChangingconfiguration = false
        } else if (activeActivityCount == 1) {
            // App returned to foreground from background
            onAppforeground()
        }
    }

    override fun onActivityresumed(activity: Activity) {
        // Attach LifecycleOwner to CameraCapturemanager for CameraX binding
        if (activity is androidx.lifecycle.LifecycleOwner) {
            cameraCapturemanager?.attachLifecycleOwner(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {

    }

    override fun onActivityStopped(activity: Activity) {
        activeActivityCount -= 1
        if (activity.isChangingconfigurations) {
            isChangingconfiguration = true
        } else if (activeActivityCount == 0) {
            // App entered background
            onAppbackground()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

    }

    override fun onActivityDestroyed(activity: Activity) {

    }

    /**
     * Get queue mode (aligned with OpenClaw)
     *
     * Reference: openclaw/src/auto-reply/reply/queue/resolve-settings.ts
     */
    private fun getqueueModeforChat(chatId: String, chatType: String): Messagequeuemanager.queueMode {
        return try {
            val configLoader = configLoader(this@MyApplication)
            val openClawconfig = configLoader.loadOpenClawconfig()

            // Read Feishu queue config
            val queueMode = openClawconfig.channels.feishu.queueMode ?: "followup"

            // Set both queue capacity and drop policy
            val queueKey = "feishu:$chatId"
            messagequeuemanager.setqueueSettings(
                key = queueKey,
                cap = openClawconfig.channels.feishu.queueCap,
                dropPolicy = when (openClawconfig.channels.feishu.queueDropPolicy.lowercase()) {
                    "new" -> Messagequeuemanager.DropPolicy.NEW
                    "summarize" -> Messagequeuemanager.DropPolicy.SUMMARIZE
                    else -> Messagequeuemanager.DropPolicy.OLD
                }
            )

            when (queueMode.lowercase()) {
                "interrupt" -> Messagequeuemanager.queueMode.INTERRUPT
                "steer" -> Messagequeuemanager.queueMode.STEER
                "followup" -> Messagequeuemanager.queueMode.FOLLOWUP
                "collect" -> Messagequeuemanager.queueMode.COLLECT
                "queue" -> Messagequeuemanager.queueMode.QUEUE
                else -> {
                    Log.w(TAG, "Unknown queue mode: $queueMode, using FOLLOWUP")
                    Messagequeuemanager.queueMode.FOLLOWUP
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Failed to load queue mode, using default FOLLOWUP", e)
            Messagequeuemanager.queueMode.FOLLOWUP
        }
    }

    /**
     * Process Feishu message (with Typing Indicator)
     *
     * Aligned with OpenClaw message processing flow:
     * 1. A "typing" reaction
     * 2. Process message (call agent)
     * 3. Remove "typing" reaction
     * 4. Send reply
     */
    private suspend fun processFeishuMessagewithTyping(
        event: com.xiaomo.feishu.FeishuEvent.Message,
        queuedMessage: Messagequeuemanager.queuedMessage
    ) {
        var typingReactionId: String? = null
        try {
            // 1. A "typing" reaction (Typing Indicator)
            val configLoader = configLoader(this@MyApplication)
            val openClawconfig = configLoader.loadOpenClawconfig()
            val typingIndicatorEnabled = openClawconfig.channels.feishu.typingIndicator

            if (typingIndicatorEnabled) {
                Log.d(TAG, "⌨️  a typing indicator...")
                val reactionresult = feishuchannel?.aReaction(event.messageId, "Typing")
                if (reactionresult?.isSuccess == true) {
                    typingReactionId = reactionresult.getorNull()
                    Log.d(TAG, "[OK] typing indicator aed: $typingReactionId")
                }
            }

            // 2. Call MainEntrynew to process message
            val response = processFeishuMessage(event)

            // 2.5 Check if reply should be skipped (noReply logic)
            if (shouldSkipReply(response, queuedMessage)) {
                Log.d(TAG, "[SILENT] noReply directive detected, skipping reply")
                // Remove reaction and return immediately
                if (typingReactionId != null) {
                    Log.d(TAG, "[CLEAN] remove typing indicator...")
                    feishuchannel?.removeReaction(event.messageId, typingReactionId)
                }
                return
            }

            // 3. Remove typing reaction
            if (typingReactionId != null) {
                Log.d(TAG, "[CLEAN] remove typing indicator...")
                feishuchannel?.removeReaction(event.messageId, typingReactionId)
            }

            // 4. Send final reply to Feishu (skip if already sent via block reply)
            if (response == "\u0000BLOCK_REPLY_ALREADY_SENT") {
                Log.d(TAG, "[OK] Final reply already sent via block reply, skipping")
            } else {
                // Strip leaked model control tokens before sending to user (OpenClaw 2026.3.11)
                var sanitizedResponse = com.xiaomo.androidforclaw.agent.session.HistorySanitizer
                    .stripControlTokensfromText(response)
                // Strip trailing NO_REPLY / HEARTBEAT_OK from mixed content
                // Aligned with OpenClaw stripSilentToken(): (?:^|\s+|\*+)NO_REPLY\s*$
                sanitizedResponse = sanitizedResponse
                    .replace(Regex("(?:^|\\s+|\\*+)NO_REPLY\\s*$"), "")
                    .replace(Regex("(?:^|\\s+|\\*+)HEARTBEAT_OK\\s*$"), "")
                    .trim()
                // Redact secrets in group chat outbound messages (contextSecurityGuard)
                if (event.chatType == "group") {
                    sanitizedResponse = com.xiaomo.androidforclaw.agent.context.contextSecurityGuard
                        .redactforSharedcontext(sanitizedResponse)
                }
                if (sanitizedResponse.isnotBlank()) {
                    sendFeishuReply(event, sanitizedResponse)
                } else {
                    Log.d(TAG, "[SILENT] Response became empty after stripping silent tokens, skipping reply")
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Process Feishu message failed", e)
            // Ensure reaction is removed (even if error occurs)
            if (typingReactionId != null) {
                try {
                    feishuchannel?.removeReaction(event.messageId, typingReactionId)
                } catch (cleanupError: exception) {
                    Log.w(TAG, "clear typing indicator failed", cleanupError)
                }
            }
            // send error hint to user, avoid silent no reply
            try {
                sendFeishuReply(event, "[WARN] Error processing message: ${e.message?.take(200) ?: "Unknown error"}")
            } catch (sendError: exception) {
                Log.e(TAG, "send error hint also failed", sendError)
            }
        }
    }

    /**
     * Check if reply should be skipped (noReply logic)
     *
     * Aligned with OpenClaw's noReply detection:
     * - agent can return special directive indicating no reply needed
     * - Certain message types (notifications, status updates) don't need reply
     * - Batch messages may contain noReply flag
     */
    private fun shouldSkipReply(
        response: String,
        queuedMessage: Messagequeuemanager.queuedMessage
    ): Boolean {
        // 1. Check if response is a silent reply
        // Aligned with OpenClaw isSilentReplyText(): exact match only (^\s*NO_REPLY\s*$)
        val trimmed = response.trim()
        if (trimmed.equals(contextBuilder.SILENT_REPLY_TOKEN, ignoreCase = false)) {
            Log.d(TAG, "Silent reply detected (exact match): NO_REPLY")
            return true
        }

        // 2. Check HEARTBEAT_OK (exact match only, aligned with OpenClaw HEARTBEAT_TOKEN)
        if (trimmed.equals("HEARTBEAT_OK", ignoreCase = false)) {
            Log.d(TAG, "Heartbeat ack detected, skipping reply")
            return true
        }

        // 3. Check if response is empty
        if (response.isBlank()) {
            Log.d(TAG, "Response is empty, skipping reply")
            return true
        }

        // 4. Check batch message metadata
        val isBatch = queuedMessage.metadata["isBatch"] as? Boolean ?: false
        if (isBatch) {
            val noReplyFlag = queuedMessage.metadata["noReply"] as? Boolean ?: false
            if (noReplyFlag) {
                return true
            }
        }

        return false
    }

    /**
     * Handle Feishu event
     */
    private fun handleFeishuEvent(event: com.xiaomo.feishu.FeishuEvent) {
        when (event) {
            is com.xiaomo.feishu.FeishuEvent.Message -> {
                Log.i(TAG, "[MSG] Received Feishu message")
                Log.i(TAG, "   sender: ${event.senderId}")
                Log.i(TAG, "   content: ${event.content}")
                Log.i(TAG, "   chat type: ${event.chatType}")
                Log.i(TAG, "   mentions: ${event.mentions}")

                // [SYNC] Update current chat context (for agent tool use)
                feishuchannel?.updateCurrentChatcontext(
                    receiveId = event.chatId,
                    receiveIdType = "chat_id",
                    messageId = event.messageId
                )
                Log.d(TAG, "[OK] Updated current conversation context: chatId=${event.chatId}")

                // [OK] Check message permissions (aligned with OpenClaw bot.ts)
                try {
                    val configLoader = configLoader(this@MyApplication)
                    val openClawconfig = configLoader.loadOpenClawconfig()
                    val feishuconfig = openClawconfig.channels.feishu

                    // Check DM Policy (private chat permission)
                    if (event.chatType == "p2p") {
                        val dmPolicy = feishuconfig.dmPolicy
                        Log.d(TAG, "   DM Policy: $dmPolicy")

                        when (dmPolicy) {
                            "pairing" -> {
                                // TODO: Implement pairing logic
                                // Temporarily allow all DMs (dev mode)
                                Log.d(TAG, "[OK] DM allowed (pairing mode - pairing validation not implemented yet)")
                            }
                            "allowlist" -> {
                                // Check allowlist
                                val allowfrom = feishuconfig.allowfrom
                                if (allowfrom.isEmpty() || event.senderId !in allowfrom) {
                                    Log.d(TAG, "[ERROR] DM from ${event.senderId} not in allowlist, sending reject message")

                                    // Send rejection message in coroutine
                                    val sender = feishuchannel?.sender
                                    if (sender != null) {
                                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val rejectMessage = "[WARN] sorry, Your account not in allowlist, cannot use this bot. \n\nYour Feishu user ID: `${event.senderId}`\n\nif you need to use, contact admin to a your user ID to allowlist. "
                                                sender.sendTextMessage(
                                                    receiveId = event.chatId,
                                                    text = rejectMessage,
                                                    receiveIdType = "chat_id",
                                                    renderMode = com.xiaomo.feishu.messaging.RenderMode.AUTO
                                                )
                                                Log.i(TAG, "[OK] sent allowlist rejection hint")
                                            } catch (e: exception) {
                                                Log.e(TAG, "[ERROR] sendallowlist rejection hintFailed: ${e.message}")
                                            }
                                        }
                                    }
                                    return
                                }
                                Log.d(TAG, "[OK] DM allowed (sender in allowlist)")
                            }
                            "open" -> {
                                Log.d(TAG, "[OK] DM allowed (open policy)")
                            }
                            else -> {
                                Log.w(TAG, "[WARN] Unknown DM policy: $dmPolicy, defaulting to open")
                            }
                        }
                    }

                    // Check group messages (aligned with OpenClaw: resolve requireMention based on groupPolicy)
                    if (event.chatType == "group") {
                        // OpenClaw: requireMentionDefault = groupPolicy === "open" ? false : true
                        val groupPolicy = feishuconfig.groupPolicy
                        val requireMentionDefault = groupPolicy != "open"
                        val requireMention = feishuconfig.requireMention ?: requireMentionDefault
                        Log.d(TAG, "   requireMention: $requireMention (groupPolicy=$groupPolicy, explicit=${feishuconfig.requireMention})")

                        if (requireMention) {
                            // Check @_all (aligned with OpenClaw: treat as @ all bots)
                            if (event.content.contains("@_all")) {
                                Log.d(TAG, "[OK] MessageContains @_all")
                            } else if (event.mentions.isEmpty()) {
                                // No @mention at all
                                Log.w(TAG, "[ERROR] group message needs @bot, but no @mention, ignore this message")
                                Log.w(TAG, "   Messagecontent: ${event.content}")
                                return
                            } else {
                                // Has @mention, check if bot is @mentioned
                                val botOpenId = feishuchannel?.getBotOpenId()
                                if (botOpenId == null) {
                                    // cannot get bot open_id, reject message for safety
                                    Log.w(TAG, "[ERROR] cannot get bot open_id, cannot validate @mention, ignore this message")
                                    Log.w(TAG, "   Hint: Check Feishu config or network connection, ensure can get bot info")
                                    return
                                } else if (botOpenId !in event.mentions) {
                                    // open_id mismatch, fallback to mentionNames(Lark SDK occasional open_id parse exception)
                                    val botName = feishuchannel?.getBotName()
                                    val nameMatch = botName != null && event.mentionNames.any {
                                        it.equals(botName, ignoreCase = true)
                                    }
                                    if (nameMatch) {
                                        Log.w(TAG, "[WARN] group message @mention open_id mismatch(${event.mentions}), but name matches($botName), allow")
                                    } else {
                                        Log.w(TAG, "[ERROR] group message @someone else but not @bot(${botOpenId}), ignore this message")
                                        Log.w(TAG, "   Messagecontent: ${event.content}")
                                        Log.w(TAG, "   Bot Open ID: $botOpenId, Bot Name: $botName")
                                        Log.w(TAG, "   mentions: ${event.mentions}")
                                        Log.w(TAG, "   MentionNames: ${event.mentionNames}")
                                        return
                                    }
                                } else {
                                    Log.d(TAG, "[OK] group message contains bot @mention")
                                }
                            }
                        } else {
                            Log.d(TAG, "[OK] group message doesn't need @bot(requireMention=false)")
                        }
                    }
                } catch (e: exception) {
                    Log.e(TAG, "Check message permission failed", e)
                    // for safety, ignore message on error
                    return
                }

                // [KEY] Generate queue key (aligned with OpenClaw)
                val queueKey = "feishu:${event.chatId}"

                // 🛑 channel-agnostic stop command check
                if (messagequeuemanager.isStopCommand(event.content)) {
                    GlobalScope.launch(Dispatchers.IO) {
                        if (messagequeuemanager.stopActiveRun(queueKey)) {
                            sendFeishuReply(event, "[OK] stopped current task")
                        } else {
                            sendFeishuReply(event, "no task currently running")
                        }
                    }
                    return
                }

                // [PACKAGE] Build queued message
                val queuedMessage = Messagequeuemanager.queuedMessage(
                    messageId = event.messageId,
                    content = event.content,
                    senderId = event.senderId,
                    chatId = event.chatId,
                    chatType = event.chatType,
                    metadata = mapOf(
                        "event" to event
                    )
                )

                // [TARGET] Get queue mode (read from config)
                val queueMode = getqueueModeforChat(event.chatId, event.chatType)

                // [START] Enqueue message for processing (fully aligned with OpenClaw)
                // OpenClaw main session no message process timeout, also no timeout limit here
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        messagequeuemanager.enqueue(
                            key = queueKey,
                            message = queuedMessage,
                            mode = queueMode
                        ) { msg ->
                            // Restore original event from metadata
                            val originalEvent = msg.metadata["event"] as? com.xiaomo.feishu.FeishuEvent.Message
                                ?: event
                            processFeishuMessagewithTyping(originalEvent, msg)
                        }
                    } catch (e: exception) {
                        Log.e(TAG, "MessagequeueProcessFailed", e)
                    }
                }
            }
            is com.xiaomo.feishu.FeishuEvent.Connected -> {
                Log.i(TAG, "[OK] Feishu WebSocket connected")
            }
            is com.xiaomo.feishu.FeishuEvent.Disconnected -> {
                Log.w(TAG, "[WARN] Feishu WebSocket disconnected, retry reconnect in 5 seconds...")
                GlobalScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(5000)
                    try {
                        Log.i(TAG, "[SYNC] try reconnect Feishu WebSocket...")
                        feishuchannel?.stop()
                        val result = feishuchannel?.start()
                        if (result?.isSuccess == true) {
                            Log.i(TAG, "[OK] Feishu WebSocket reconnected successfully")
                        } else {
                            Log.e(TAG, "[ERROR] Feishu WebSocket reconnection failed: ${result?.exceptionorNull()?.message}")
                        }
                    } catch (e: exception) {
                        Log.e(TAG, "[ERROR] Feishu WebSocket reconnection exception", e)
                    }
                }
            }
            is com.xiaomo.feishu.FeishuEvent.Error -> {
                Log.e(TAG, "[ERROR] Feishu Error: ${event.error.message}")
            }
        }
    }

    /**
     * Process Feishu message - call agent
     *
     * Create lightweight agentloop call and return result directly
     */
    private suspend fun processFeishuMessage(event: com.xiaomo.feishu.FeishuEvent.Message): String {
        return withcontext(Dispatchers.IO) {
            try {
                // Track last active chat for cron delivery
                setLastActiveChat("feishu", event.chatId)

                Log.i(TAG, "🤖 StartProcessMessage: ${event.content}")

                // [ATTACH] nextload media attachment if present (aligned with OpenClaw resolveFeishuMediaList)
                var userMessage = event.content
                val eventMediaKeys = event.mediaKeys
                if (eventMediaKeys != null) {
                    try {
                        val medianextload = com.xiaomo.feishu.messaging.FeishuMedianextload(
                            client = feishuchannel!!.getClient(),
                            cacheDir = cacheDir
                        )
                        val downloadresult = medianextload.downloadMedia(event.messageId, eventMediaKeys)
                        if (downloadresult.isSuccess) {
                            val localPath = downloadresult.getorNull()!!.file.absolutePath
                            Log.i(TAG, "[ATTACH] media attachment downloaded: $localPath (type=${eventMediaKeys.media type})")
                            // Aligned with OpenClaw: append file path for lazy loading in agent loop
                            if (eventMediaKeys.media type == "image") {
                                userMessage = if (userMessage.isBlank()) {
                                    "[Image: source: $localPath]"
                                } else {
                                    "$userMessage\n[Image: source: $localPath]"
                                }
                            } else {
                                userMessage = "$userMessage\n[attachment downloaded: $localPath]"
                            }
                        } else {
                            Log.w(TAG, "[ATTACH] media download failed: ${downloadresult.exceptionorNull()?.message}")
                        }
                    } catch (e: exception) {
                        Log.w(TAG, "[ATTACH] media download exception: ${e.message}")
                    }
                }

                // 🆔 Generate session ID: use chatId_chatType as unique identifier
                // This way different groups/private chats have independent session history
                val sessionId = "${event.chatId}_${event.chatType}"
                Log.i(TAG, "🆔 session ID: $sessionId (chatType: ${event.chatType})")

                // Execute agentloop synchronously and return result
                val sessionmanager = MainEntrynew.getsessionmanager()
                if (sessionmanager == null) {
                    MainEntrynew.initialize(this@MyApplication)
                }

                val session = MainEntrynew.getsessionmanager()?.getorCreate(sessionId)
                if (session == null) {
                    return@withcontext "system error: cannot create session"
                }

                Log.i(TAG, "[CLIP] [session] Load session: ${session.messageCount()} historical messages")

                // Get history messages and cleanup (ensure tool_use and tool_result are paired)
                val rawHistory = session.getRecentMessages(20)
                val contextHistory = cleanuptoolMessages(rawHistory)
                Log.i(TAG, "[CLIP] [session] clean up: ${contextHistory.size} messages(original: ${rawHistory.size})")

                // Initialize components
                val taskDatamanager = TaskDatamanager.getInstance()
                val toolRegistry = toolRegistry(
                    context = this@MyApplication,
                    taskDatamanager = taskDatamanager
                )
                val androidtoolRegistry = androidtoolRegistry(
                    context = this@MyApplication,
                    taskDatamanager = taskDatamanager,
                    cameraCapturemanager = cameraCapturemanager,
                )

                // Register feishu tools into toolRegistry (aligned with OpenClaw extension tools)
                val fc = feishuchannel
                if (fc != null) {
                    try {
                        val feishutoolRegistry = fc.gettoolRegistry()
                        if (feishutoolRegistry != null) {
                            // Register Feishu tools into function-call toolRegistry
                            // (skills help routing, but real execution requires tool registration)
                            val feishutoolCount = com.xiaomo.androidforclaw.agent.tools.registerFeishutools(toolRegistry, feishutoolRegistry)
                            Log.i(TAG, "[WRENCH] registered $feishutoolCount Feishu tools to toolRegistry")
                        }
                    } catch (e: exception) {
                        Log.w(TAG, "Feishu toolsRegisterFailed: ${e.message}")
                    }
                }

                val configLoader = configLoader(this@MyApplication)
                val contextBuilder = contextBuilder(
                    context = this@MyApplication,
                    toolRegistry = toolRegistry,
                    androidtoolRegistry = androidtoolRegistry,
                    configLoader = configLoader
                )
                val llmprovider = com.xiaomo.androidforclaw.providers.UnifiedLLMprovider(this@MyApplication)
                val contextmanager = com.xiaomo.androidforclaw.agent.context.contextmanager(llmprovider)

                // Load maxIterations from config
                val config = configLoader.loadOpenClawconfig()
                val maxIterations = config.agent.maxIterations

                val agentloop = agentloop(
                    llmprovider = llmprovider,
                    toolRegistry = toolRegistry,
                    androidtoolRegistry = androidtoolRegistry,
                    contextmanager = contextmanager,
                    maxIterations = maxIterations,
                    modelRef = null
                )

                // Register agentloop for STEER mode mid-run message injection
                val steerqueueKey = "feishu:${event.chatId}"
                messagequeuemanager.setActiveagentloop(steerqueueKey, agentloop)

                // Build system prompt (with channel context for messaging awareness)
                val channelCtx = contextBuilder.channelcontext(
                    channel = "feishu",
                    chatId = event.chatId,
                    chatType = event.chatType,
                    senderId = event.senderId,
                    messageId = event.messageId
                )
                val systemPrompt = contextBuilder.buildSystemPrompt(
                    userGoal = event.content,
                    packageName = "",
                    testMode = "chat",
                    channelcontext = channelCtx
                )

                // [OK] Streaming Card: real-time card updates during agent processing
                // Aligned with OpenClaw reply-dispatcher.ts + streaming-card.ts
                val blockRepliesSent = mutableListOf<String>()
                val streamingCard = feishuchannel?.createStreamingCard()
                var streamingCardMessageId: String? = null
                var streamingFailed = false

                val progressJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    agentloop.progressFlow.collect { update ->
                        when {
                            // Start streaming card on first Thinking event
                            update is ProgressUpdate.Thinking && streamingCard != null && !streamingFailed && streamingCard.cardId == null -> {
                                try {
                                    val startresult = streamingCard.start("*Thinking...*")
                                    if (startresult.isSuccess) {
                                        val cardId = startresult.getorNull()!!
                                        val sender = feishuchannel?.sender
                                        if (sender != null) {
                                            // Send card message with reply routing
                                            val sendresult = if (event.chatType == "group" && event.rootId == null) {
                                                sender.sendCardByIdReply(event.messageId, cardId)
                                            } else {
                                                sender.sendCardById(event.chatId, cardId)
                                            }
                                            streamingCardMessageId = sendresult.getorNull()?.messageId
                                            Log.i(TAG, "📺 Streaming card sent: $streamingCardMessageId")
                                        }
                                    } else {
                                        Log.w(TAG, "Streaming card creation failed: ${startresult.exceptionorNull()?.message}")
                                        streamingFailed = true
                                    }
                                } catch (e: exception) {
                                    Log.w(TAG, "Streaming card start failed: ${e.message}")
                                    streamingFailed = true
                                }
                            }

                            // Update streaming card with reasoning
                            update is ProgressUpdate.Reasoning && streamingCard?.isActive() == true -> {
                                try {
                                    streamingCard.appendText("> ${update.content}\n\n---\n\n")
                                } catch (e: exception) {
                                    Log.w(TAG, "Streaming reasoning update failed: ${e.message}")
                                }
                            }

                            // Update streaming card with tool call info
                            update is ProgressUpdate.toolCall && streamingCard?.isActive() == true -> {
                                try {
                                    streamingCard.appendText("`Using: ${update.name}...`\n\n")
                                } catch (e: exception) { /* ignore */ }
                            }

                            // streaming increment: reasoning token appended to streaming card in real-time
                            update is ProgressUpdate.ReasoningDelta && streamingCard?.isActive() == true -> {
                                try {
                                    streamingCard.appendText(update.text)
                                } catch (e: exception) { /* ignore */ }
                            }

                            // streaming increment: content token appended to streaming card in real-time
                            update is ProgressUpdate.ContentDelta && streamingCard?.isActive() == true -> {
                                try {
                                    streamingCard.appendText(update.text)
                                } catch (e: exception) { /* ignore */ }
                            }

                            // Update streaming card with block reply text
                            update is ProgressUpdate.BlockReply -> {
                                val text = update.text.trim()
                                if (text.isnotEmpty()) {
                                    if (streamingCard?.isActive() == true) {
                                        try {
                                            streamingCard.appendText(text)
                                            blockRepliesSent.a(text)
                                        } catch (e: exception) {
                                            Log.w(TAG, "Streaming block reply update failed: ${e.message}")
                                        }
                                    } else {
                                        // Fallback: send as separate message (old behavior)
                                        try {
                                            sendFeishuReply(event, text)
                                            blockRepliesSent.a(text)
                                        } catch (e: exception) {
                                            Log.w(TAG, "send intermediate reply failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Run agentloop (convert history messages)
                val result = try {
                    agentloop.run(
                        systemPrompt = systemPrompt,
                        userMessage = userMessage,
                        contextHistory = contextHistory.map { it.tonewMessage() },
                        reasoningEnabled = true
                    )
                } finally {
                    // Always unregister after the run completes (or fails)
                    messagequeuemanager.clearActiveagentloop(steerqueueKey)
                }

                // Stop progress listener
                progressJob.cancel()

                // Save messages to session (convert back to old format)
                result.messages.forEach { message ->
                    session.aMessage(message.toLegacyMessage())
                }
                MainEntrynew.getsessionmanager()?.save(session)
                Log.i(TAG, "[SAVE] [session] session saved, total message count: ${session.messageCount()}")

                Log.i(TAG, "[OK] agent ProcessComplete")
                Log.i(TAG, "   iteration count: ${result.iterations}")
                Log.i(TAG, "   use tools: ${result.toolsused.joinToString(", ")}")

                // Close streaming card with final content
                val finalContent = com.xiaomo.androidforclaw.util.ReplyTagFilter.strip(result.finalContent ?: "sorry, cannot process this request. ")

                if (streamingCard?.isActive() == true) {
                    try {
                        streamingCard.close(finalContent)
                        Log.i(TAG, "📺 Streaming card closed with final content")
                        "\u0000BLOCK_REPLY_ALREADY_SENT"  // Sentinel: final content is in the streaming card
                    } catch (e: exception) {
                        Log.w(TAG, "Failed to close streaming card: ${e.message}")
                        finalContent // Fall through to normal reply
                    }
                } else if (blockRepliesSent.isnotEmpty() && blockRepliesSent.last().trim() == finalContent.trim()) {
                    Log.i(TAG, "[SEND] Final content matches last block reply, marking as already sent")
                    "\u0000BLOCK_REPLY_ALREADY_SENT"
                } else if (streamingFailed && blockRepliesSent.isnotEmpty() && blockRepliesSent.any { it.trim() == finalContent.trim() }) {
                    // Streaming card failed; final content was already sent via block reply fallback
                    Log.i(TAG, "[SEND] Final content matches a block reply (streaming failed), skipping duplicate")
                    "\u0000BLOCK_REPLY_ALREADY_SENT"
                } else {
                    finalContent
                }

            } catch (e: exception) {
                Log.e(TAG, "agent ProcessFailed", e)
                "sorry, error processing message: ${e.message}"
            }
        }
    }

    /**
     * Send reply to Feishu
     *
     * Features:
     * - Reply routing: group → quote reply, thread → thread reply, DM → direct send
     * - Fallback: quote reply fails → direct send (message may have been withdrawn)
     * - use FeishuSender to auto-detect Markdown and render with cards
     * - Detect screenshot paths and auto-upload send images
     *
     * Aligned with OpenClaw reply-dispatcher.ts
     */
    private suspend fun sendFeishuReply(event: com.xiaomo.feishu.FeishuEvent.Message, content: String) {
        try {
            Log.i(TAG, "[SEND] send reply to Feishu...")

            // Filter internal reasoning tags (<think>, <final>, etc.)
            val cleanContent = filterReasoningTags(content)

            val sender = feishuchannel?.sender
            if (sender == null) {
                Log.e(TAG, "[ERROR] FeishuSender not initialized")
                return
            }

            // Detect screenshot path
            val screenshotPathRegex = Regex("""Path:\s*((?:/storage/|/sdcard/|content://)[^\s\n]+\.png)""")
            val screenshotMatch = screenshotPathRegex.find(cleanContent)

            if (screenshotMatch != null) {
                val screenshotPath = screenshotMatch.groupValues[1]
                Log.i(TAG, "📸 DetectedScreenshotPath: $screenshotPath")

                // Upload and send image
                val imageFile = resolveImageFile(screenshotPath)
                if (imageFile != null && imageFile.exists()) {
                    try {
                        val imageresult = feishuchannel?.uploadandSendImage(
                            imageFile = imageFile,
                            receiveId = event.chatId,
                            receiveIdType = "chat_id"
                        )
                        if (imageresult?.isSuccess == true) {
                            Log.i(TAG, "[OK] image sent successfully")
                        } else {
                            Log.e(TAG, "[ERROR] image upload failed: ${imageresult?.exceptionorNull()?.message}")
                        }
                    } catch (e: exception) {
                        Log.e(TAG, "UploadScreenshot failed", e)
                    }
                }

                // Send text reply (remove screenshot path info)
                val textContent = cleanContent.replace(screenshotPathRegex, "").trim()
                if (textContent.isnotEmpty()) {
                    sendTextwithRouting(sender, event, textContent)
                }
            } else {
                sendTextwithRouting(sender, event, cleanContent)
            }
        } catch (e: exception) {
            Log.e(TAG, "send Feishu reply failed", e)
        }
    }

    /**
     * sendText reply(with route policy)
     * Aligned with OpenClaw reply-dispatcher: group → quote reply, thread → thread reply, DM → direct
     */
    private suspend fun sendTextwithRouting(
        sender: com.xiaomo.feishu.messaging.FeishuSender,
        event: com.xiaomo.feishu.FeishuEvent.Message,
        text: String
    ) {
        val renderMode = com.xiaomo.feishu.messaging.RenderMode.AUTO

        // Determine reply strategy
        val useQuoteReply = event.chatType == "group" && event.rootId == null && event.threadId == null
        val useThreadReply = event.rootId != null || event.threadId != null

        var result: result<com.xiaomo.feishu.messaging.Sendresult>? = null

        // Strategy 1: Thread reply (message is in a topic)
        if (useThreadReply) {
            val rootId = event.rootId ?: event.messageId
            result = try {
                sender.replyInThread(messageId = rootId, text = text)
            } catch (e: exception) {
                Log.w(TAG, "Thread reply failed, falling back to direct: ${e.message}")
                null
            }
        }

        // Strategy 2: Quote reply (group message, not in topic)
        if (result == null && useQuoteReply) {
            result = try {
                sender.sendTextReply(
                    replyToMessageId = event.messageId,
                    text = text,
                    renderMode = renderMode
                )
            } catch (e: exception) {
                Log.w(TAG, "Quote reply failed, falling back to direct: ${e.message}")
                null
            }
            // Fallback: quote reply API error → direct send
            if (result != null && result.isFailure) {
                Log.w(TAG, "Quote reply returned error: ${result.exceptionorNull()?.message}, falling back to direct")
                result = null
            }
        }

        // Strategy 3: Direct send (DM or fallback)
        if (result == null || result.isFailure) {
            result = sender.sendTextMessage(
                receiveId = event.chatId,
                text = text,
                receiveIdType = "chat_id",
                renderMode = renderMode
            )
        }

        if (result.isSuccess) {
            Log.i(TAG, "[OK] reply sent successfully: ${result.getorNull()?.messageId}")
        } else {
            val errorMsg = result.exceptionorNull()?.message ?: "Unknown error"
            Log.e(TAG, "[ERROR] reply send failed: $errorMsg")

            // Fallback: card fails → plain text
            if (errorMsg.contains("table number over limit") || errorMsg.contains("230099") || errorMsg.contains("HTTP 400")) {
                Log.w(TAG, "[WARN] downgrade to pure text schema retry...")
                sender.sendTextMessage(
                    receiveId = event.chatId,
                    text = text,
                    receiveIdType = "chat_id",
                    renderMode = com.xiaomo.feishu.messaging.RenderMode.TEXT
                )
            }
        }
    }

    /**
     * parse image file path(support content URI and file path)
     */
    private fun resolveImageFile(path: String): java.io.File? {
        return if (path.startswith("content://")) {
            try {
                val uri = android.net.Uri.parse(path)
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = java.io.File(cacheDir, "temp_screenshot_${System.currentTimeMillis()}.png")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                tempFile
            } catch (e: exception) {
                Log.e(TAG, "Failed to resolve Content URI", e)
                null
            }
        } else {
            java.io.File(path)
        }
    }

    /**
     * Filter reasoning tags from LLM response.
     * Delegates to ReasoningTagFilter to avoid code duplication.
     */
    private fun filterReasoningTags(content: String): String =
        ReasoningTagFilter.stripReasoningTags(content)

    /**
     * Start Discord channel (if configured)
     */
    private fun startDiscordchannelifEnabled() {
        Log.i(TAG, "[TIME] startDiscordchannelIfEnabled() called")
        val scope = CoroutineScope(Dispatchers.IO)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "========================================")
                Log.i(TAG, "🤖 Check Discord channel config...")
                Log.i(TAG, "========================================")

                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val discordconfigData = openClawconfig.channels.discord

                if (discordconfigData == null || !discordconfigData.enabled) {
                    Log.i(TAG, "[SKIP]  Discord channel not enabled, skip initialization")
                    Log.i(TAG, "   configPath: /sdcard/.androidforclaw/openclaw.json")
                    Log.i(TAG, "   Settings channels.discord.enabled = true to enable")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                val token = discordconfigData.token
                if (token.isNullorBlank()) {
                    Log.w(TAG, "[WARN]  Discord Bot Token not configured, SkipStart")
                    Log.i(TAG, "   please set channels.discord.token in config")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                Log.i(TAG, "[OK] Discord channel enabled, ready to start...")
                Log.i(TAG, "   Name: ${discordconfigData.name ?: "default"}")
                Log.i(TAG, "   DM Policy: ${discordconfigData.dm?.policy ?: "pairing"}")
                Log.i(TAG, "   Group Policy: ${discordconfigData.groupPolicy ?: "open"}")
                Log.i(TAG, "   Reply Mode: ${discordconfigData.replyToMode ?: "off"}")

                // Create Discordconfig
                val config = Discordconfig(
                    enabled = true,
                    token = token,
                    name = discordconfigData.name,
                    dm = discordconfigData.dm?.let {
                        Discordconfig.Dmconfig(
                            policy = it.policy ?: "pairing",
                            allowfrom = it.allowfrom ?: emptyList()
                        )
                    },
                    groupPolicy = discordconfigData.groupPolicy,
                    guilds = discordconfigData.guilds?.mapValues { (_, guildData) ->
                        Discordconfig.Guildconfig(
                            channels = guildData.channels,
                            requireMention = guildData.requireMention ?: true,
                            toolPolicy = guildData.toolPolicy
                        )
                    },
                    replyToMode = discordconfigData.replyToMode,
                    accounts = discordconfigData.accounts?.mapValues { (_, accountData) ->
                        Discordconfig.DiscordAccountconfig(
                            enabled = accountData.enabled ?: true,
                            token = accountData.token,
                            name = accountData.name,
                            dm = accountData.dm?.let {
                                Discordconfig.Dmconfig(
                                    policy = it.policy ?: "pairing",
                                    allowfrom = it.allowfrom ?: emptyList()
                                )
                            },
                            guilds = accountData.guilds?.mapValues { (_, guildData) ->
                                Discordconfig.Guildconfig(
                                    channels = guildData.channels,
                                    requireMention = guildData.requireMention ?: true,
                                    toolPolicy = guildData.toolPolicy
                                )
                            }
                        )
                    }
                )

                // Start Discordchannel
                val result = Discordchannel.start(this@MyApplication, config)

                if (result.isSuccess) {
                    discordchannel = result.getorNull()

                    // Initialize DiscordTyping
                    discordchannel?.let { channel ->
                        val client = com.xiaomo.discord.DiscordClient(token)
                        discordTyping = DiscordTyping(client)
                    }

                    // Update MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_discord_enabled", true)

                    Log.i(TAG, "========================================")
                    Log.i(TAG, "[OK] Discord channel StartSuccess!")
                    Log.i(TAG, "   Bot: ${discordchannel?.getBotusername()} (${discordchannel?.getBotuserId()})")
                    Log.i(TAG, "   can now receive Discord messages")
                    Log.i(TAG, "========================================")

                    // Subscribe to event flow, handle received messages
                    scope.launch(Dispatchers.IO) {
                        discordchannel?.eventFlow?.collect { event ->
                            handleDiscordEvent(event)
                        }
                    }
                } else {
                    // Clear MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_discord_enabled", false)

                    Log.e(TAG, "========================================")
                    Log.e(TAG, "[ERROR] Discord channel StartFailed")
                    Log.e(TAG, "   Error: ${result.exceptionorNull()?.message}")
                    Log.e(TAG, "========================================")
                }

            } catch (e: exception) {
                // Clear MMKV status
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_discord_enabled", false)

                Log.e(TAG, "========================================")
                Log.e(TAG, "[ERROR] Discord channel Initializeexception", e)
                Log.e(TAG, "========================================")
            }
        }
    }

    /**
     * Handle Discord event
     */
    private suspend fun handleDiscordEvent(event: channelEvent) {
        try {
            when (event) {
                is channelEvent.Connected -> {
                    Log.i(TAG, "🔗 Discord Connected")
                }

                is channelEvent.Message -> {
                    Log.i(TAG, "[MSG] received Discord message")
                    Log.i(TAG, "   from: ${event.authorName} (${event.authorId})")
                    Log.i(TAG, "   Content: ${event.content}")
                    Log.i(TAG, "   Type: ${event.chatType}")
                    Log.i(TAG, "   channel: ${event.channelId}")

                    // Send reply
                    sendDiscordReply(event)
                }

                is channelEvent.ReactionA -> {
                    Log.d(TAG, "👍 Discord Reaction Aed: ${event.emoji}")
                }

                is channelEvent.ReactionRemove -> {
                    Log.d(TAG, "👎 Discord Reaction Removed: ${event.emoji}")
                }

                is channelEvent.TypingStart -> {
                    Log.d(TAG, "⌨️ Discord user Typing: ${event.userId}")
                }

                is channelEvent.Error -> {
                    Log.e(TAG, "[ERROR] Discord Error", event.error)
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Process Discord EventFailed", e)
        }
    }

    private suspend fun sendDiscordReply(event: channelEvent.Message) {
        try {
            if (discordDedup.isDuplicate(event.messageId)) return
            discordProcessingJobs[event.channelId]?.cancel()
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val adapter = object : channelAdapter {
                        override val channelName = "discord"
                        override val sessionPrefix = "discord"
                        override val messageCharLimit = 1900
                        override val supportsReactions = true
                        override val supportsTyping = true
                        override suspend fun aThinkingReaction() { discordchannel?.aReaction(event.channelId, event.messageId, "\uD83E\uDD14") }
                        override suspend fun removeThinkingReaction() { discordchannel?.removeReaction(event.channelId, event.messageId, "\uD83E\uDD14") }
                        override suspend fun aCompletionReaction() { discordchannel?.aReaction(event.channelId, event.messageId, "\u2705") }
                        override suspend fun aErrorReaction() { discordchannel?.aReaction(event.channelId, event.messageId, "\u274C") }
                        override fun startTyping() { discordTyping?.startContinuous(event.channelId) }
                        override fun stopTyping() { discordTyping?.stopContinuous(event.channelId) }
                        override suspend fun sendMessageChunk(text: String, isfirstChunk: Boolean) {
                            discordchannel?.sendMessage(channelId = event.channelId, content = text, replyToId = if (isfirstChunk) event.messageId else null)
                        }
                        override suspend fun sendErrorMessage(error: String) {
                            discordchannel?.sendMessage(channelId = event.channelId, content = error, replyToId = event.messageId)
                        }
                        override fun isGroupcontext() = event.guildId != null
                        override fun getuserMessage() = event.content
                        override fun getsessionKey() = "discord_${event.channelId}"
                        override fun buildSystemPrompt() = buildDiscordSystemPrompt(event)
                    }
                    channelMessageProcessor(this@MyApplication).processMessage(adapter)
                } finally {
                    discordProcessingJobs.remove(event.channelId)
                }
            }
            discordProcessingJobs[event.channelId] = job
        } catch (e: exception) {
            Log.e(TAG, "Send Discord reply failed", e)
            try { discordchannel?.aReaction(event.channelId, event.messageId, "\u274C") } catch (_: exception) {}
        }
    }

    // ── Weixin channel ───────────────────────────────────────────────────────

    /**
     * restart WeChat channel(scan login success callback). 
     * will stop old channel first(if has), then re-initialize and start monitor. 
     */
    // Track WeChat collector job to cancel on restart (prevents duplicate collectors)
    private var weixincollectorJob: kotlinx.coroutines.Job? = null

    fun restartWeixinchannel() {
        Log.i(TAG, "[SYNC] restartWeixinchannel() called")
        weixinchannel?.stop()
        weixinchannel = null
        weixincollectorJob?.cancel()
        weixincollectorJob = null
        startWeixinchannelifEnabled()
    }

    private fun startWeixinchannelifEnabled() {
        Log.i(TAG, "[TIME] startWeixinchannelIfEnabled() called")

        // cancel old collector + channel to prevent duplicate processing
        weixincollectorJob?.cancel()
        weixincollectorJob = null
        weixinchannel?.stop()
        weixinchannel = null

        weixincollectorJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val weixinCfg = openClawconfig.channels.weixin

                if (weixinCfg == null || !weixinCfg.enabled) {
                    Log.i(TAG, "[SKIP]  Weixin channel not enabled, Skip")
                    return@launch
                }

                Log.i(TAG, "[OK] Weixin channel enabled, ready to start...")

                val config = com.xiaomo.weixin.Weixinconfig(
                    enabled = true,
                    baseUrl = weixinCfg.baseUrl,
                    cdnBaseUrl = weixinCfg.cdnBaseUrl,
                    routeTag = weixinCfg.routeTag,
                )

                val channel = com.xiaomo.weixin.Weixinchannel(config)
                val configured = channel.init()

                if (!configured) {
                    Log.w(TAG, "Weixin channel not logged in, need to scan QR first")
                    return@launch
                }

                val started = channel.start()
                if (!started) {
                    Log.e(TAG, "Weixin channel StartFailed")
                    return@launch
                }

                weixinchannel = channel
                Log.i(TAG, "[OK] Weixin channel StartSuccess")

                // collect inbound messages and dispatch to agent via Messagequeuemanager
                channel.messageFlow?.collect { msg ->
                    Log.i(TAG, "[MSG] Weixin received message: from=${msg.fromuserId} body=${msg.body.take(50)} has media=${msg.has media} media type=${msg.media type}")

                    // nextload media if present
                    var downloadedMediaFile: java.io.File? = null
                    if (msg.has media && msg.mediaCdn != null) {
                        try {
                            downloadedMediaFile = com.xiaomo.weixin.cdn.WeixinCdnnextloader.downloadandDecrypt(
                                media = msg.mediaCdn!!,
                                fileExtension = msg.mediaFileExtension ?: "bin",
                            )
                            if (downloadedMediaFile != null) {
                                Log.i(TAG, "[ATTACH] Weixin media download success: ${downloadedMediaFile.absolutePath} (${downloadedMediaFile.length()} bytes)")
                            } else {
                                Log.w(TAG, "[WARN] Weixin media download failed")
                            }
                        } catch (e: exception) {
                            Log.e(TAG, "Weixin media download exception", e)
                        }
                    }

                    if (msg.body.isBlank() && !msg.has media) {
                        Log.d(TAG, "Weixin: null message and no media, Skip")
                        return@collect
                    }

                    val queueKey = "weixin:${msg.fromuserId}"

                    // Build content for agent: text + media context
                    val agentContent = buildString {
                        if (msg.body.isnotBlank()) {
                            append(msg.body)
                        }
                        if (msg.has media && downloadedMediaFile != null) {
                            if (isnotEmpty()) append("\n\n")
                            when (msg.media type) {
                                com.xiaomo.weixin.api.MessageItemType.IMAGE -> {
                                    append("[Image: source: ${downloadedMediaFile.absolutePath}]")
                                }
                                com.xiaomo.weixin.api.MessageItemType.VOICE -> {
                                    append("[user sent a voice message")
                                    if (msg.voicePlaytime != null) append(", duration${msg.voicePlaytime}seconds")
                                    if (!msg.voiceText.isNullorBlank()) append(", content: ${msg.voiceText}")
                                    append(", files: ${downloadedMediaFile.absolutePath}]")
                                }
                                com.xiaomo.weixin.api.MessageItemType.FILE -> {
                                    append("[user sent a file: ${msg.mediaFileName ?: downloadedMediaFile.name}, Path: ${downloadedMediaFile.absolutePath}]")
                                }
                                com.xiaomo.weixin.api.MessageItemType.VIDEO -> {
                                    append("[user sent a video: ${downloadedMediaFile.absolutePath}]")
                                }
                            }
                        } else if (msg.has media && downloadedMediaFile == null) {
                            if (isnotEmpty()) append("\n\n")
                            append("[user sent media files, but download failed]")
                        }
                    }

                    // channel-agnostic stop command check
                    if (messagequeuemanager.isStopCommand(agentContent)) {
                        if (messagequeuemanager.stopActiveRun(queueKey)) {
                            channel.sender?.sendText(msg.fromuserId, "[OK] stopped current task")
                        } else {
                            channel.sender?.sendText(msg.fromuserId, "no task currently running")
                        }
                        return@collect
                    }

                    val queuedMessage = Messagequeuemanager.queuedMessage(
                        messageId = msg.messageId?.toString() ?: "weixin_${System.currentTimeMillis()}",
                        content = agentContent,
                        senderId = msg.fromuserId,
                        chatId = msg.fromuserId,
                        chatType = "p2p",
                        metadata = mapOf("weixinMsg" to msg, "mediaFile" to downloadedMediaFile)
                    )

                    val queueMode = getqueueModeforChat(msg.fromuserId, "p2p")

                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            messagequeuemanager.enqueue(
                                key = queueKey,
                                message = queuedMessage,
                                mode = queueMode
                            ) { qMsg ->
                                val originalMsg = qMsg.metadata["weixinMsg"]
                                    as? com.xiaomo.weixin.messaging.WeixinInboundMessage ?: msg
                                processWeixinMessagequeued(originalMsg, queueKey)
                            }
                        } catch (e: kotlinx.coroutines.cancellationexception) {
                            Log.i(TAG, "Weixin: task cancelled by user ${msg.fromuserId}")
                        } catch (e: exception) {
                            Log.e(TAG, "Weixin MessagequeueProcessFailed", e)
                            try {
                                channel.sender?.sendText(msg.fromuserId, "Error processing message: ${e.message}")
                            } catch (_: exception) {}
                        }
                    }
                }

            } catch (e: exception) {
                Log.e(TAG, "Weixin channel Startexception", e)
            }
        }
    }

    /**
     * Process Weixin message through Messagequeuemanager pipeline.
     * Registers agentloop with Messagequeuemanager for channel-agnostic stop support.
     */
    private suspend fun processWeixinMessagequeued(
        msg: com.xiaomo.weixin.messaging.WeixinInboundMessage,
        queueKey: String
    ) {
        val channel = weixinchannel ?: return
        val sender = channel.sender
        val touser = msg.fromuserId

        try {
            // Track last active chat for cron delivery
            setLastActiveChat("weixin", msg.fromuserId)

            // Send typing indicator
            sender?.sendTyping(touser)

            val sessionId = "weixin_${msg.fromuserId}"
            Log.i(TAG, "🆔 Weixin session ID: $sessionId")

            // Rebuild agent content from message fields (includes media context)
            val agentContent = buildString {
                if (msg.body.isnotBlank()) {
                    append(msg.body)
                }
                if (msg.has media) {
                    if (isnotEmpty()) append("\n\n")
                    when (msg.media type) {
                        com.xiaomo.weixin.api.MessageItemType.IMAGE -> {
                            // download WeChat CDN image(AES decrypt)→ attach image: source path(aligned with OpenClaw delay load schema)
                            if (msg.mediaCdn != null) {
                                try {
                                    val imageFile = com.xiaomo.weixin.cdn.WeixinCdnnextloader.downloadandDecrypt(
                                        media = msg.mediaCdn!!,
                                        fileExtension = "jpg"
                                    )
                                    if (imageFile != null) {
                                        append("[Image: source: ${imageFile.absolutePath}]")
                                        Log.i(TAG, "🖼️ Weixin image downloaded: ${imageFile.absolutePath}")
                                    } else {
                                        append("[user sent an image]")
                                        Log.w(TAG, "🖼️ Weixin image CDN download returned null")
                                    }
                                } catch (e: exception) {
                                    append("[user sent an image]")
                                    Log.e(TAG, "🖼️ Weixin image download failed", e)
                                }
                            } else {
                                append("[user sent an image]")
                            }
                        }
                        com.xiaomo.weixin.api.MessageItemType.VOICE -> {
                            append("[user sent a voice message")
                            if (msg.voicePlaytime != null) append(", duration${msg.voicePlaytime}seconds")
                            if (!msg.voiceText.isNullorBlank()) append(", content: ${msg.voiceText}")
                            append("]")
                        }
                        com.xiaomo.weixin.api.MessageItemType.FILE -> {
                            append("[user sent a file: ${msg.mediaFileName ?: "Unknownfile name"}]")
                        }
                        com.xiaomo.weixin.api.MessageItemType.VIDEO -> {
                            append("[user sent a video]")
                        }
                    }
                }
            }

            if (MainEntrynew.getsessionmanager() == null) {
                MainEntrynew.initialize(this@MyApplication)
            }
            val sessionmanager = MainEntrynew.getsessionmanager()
            if (sessionmanager == null) {
                sender?.sendText(touser, "system error: cannot create session")
                return
            }

            val session = sessionmanager.getorCreate(sessionId)

            val rawHistory = session.getRecentMessages(20)
            val contextHistory = cleanuptoolMessages(rawHistory)

            val taskDatamanager = TaskDatamanager.getInstance()
            val toolRegistry = toolRegistry(
                context = this@MyApplication,
                taskDatamanager = taskDatamanager
            )
            val androidtoolRegistry = androidtoolRegistry(
                context = this@MyApplication,
                taskDatamanager = taskDatamanager,
                cameraCapturemanager = cameraCapturemanager,
            )

            val configLoader = configLoader(this@MyApplication)
            val contextBuilder = contextBuilder(
                context = this@MyApplication,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                configLoader = configLoader
            )
            val llmprovider = com.xiaomo.androidforclaw.providers.UnifiedLLMprovider(this@MyApplication)
            val contextmanager = com.xiaomo.androidforclaw.agent.context.contextmanager(llmprovider)

            val config = configLoader.loadOpenClawconfig()
            val maxIterations = config.agent.maxIterations

            // use weixin-specific model if configured
            val weixinmodel = config.channels.weixin?.model

            val agentloop = agentloop(
                llmprovider = llmprovider,
                toolRegistry = toolRegistry,
                androidtoolRegistry = androidtoolRegistry,
                contextmanager = contextmanager,
                maxIterations = maxIterations,
                modelRef = weixinmodel
            )

            // Register with Messagequeuemanager (channel-agnostic stop/steer support)
            messagequeuemanager.setActiveagentloop(queueKey, agentloop)

            val channelCtx = contextBuilder.channelcontext(
                channel = "weixin",
                chatId = msg.fromuserId,
                chatType = "p2p",
                senderId = msg.fromuserId,
                messageId = msg.messageId?.toString() ?: ""
            )
            val systemPrompt = contextBuilder.buildSystemPrompt(
                userGoal = agentContent,
                packageName = "",
                testMode = "chat",
                channelcontext = channelCtx
            )

            // collect intermediate progress updates — WeChat does NOT send BlockReply
            // as separate messages to avoid duplicate-looking output.
            // Only send Error events immediately; everything else goes into finalContent.
            val progressJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                agentloop.progressFlow.collect { update ->
                    when (update) {
                        is ProgressUpdate.toolCall -> {
                            Log.d(TAG, "Weixin: toolCall ${update.name}")
                        }
                        is ProgressUpdate.BlockReply -> {
                            // Log only — do NOT send as separate message.
                            // The final response will contain this content.
                            Log.d(TAG, "Weixin: BlockReply suppressed (${update.text.take(100)})")
                        }
                        is ProgressUpdate.Error -> {
                            try {
                                sender?.sendText(touser, "[WARN] ${update.message}")
                            } catch (_: exception) {}
                        }
                        else -> { /* ignore other updates */ }
                    }
                }
            }

            val result = try {
                agentloop.run(
                    systemPrompt = systemPrompt,
                    userMessage = agentContent,
                    contextHistory = contextHistory.map { it.tonewMessage() },
                )
            } finally {
                // Always unregister after the run completes (or fails)
                messagequeuemanager.clearActiveagentloop(queueKey)
            }

            // cancel progress collection after agent finishes
            progressJob.cancel()

            // Save to session history
            session.aMessage(com.xiaomo.androidforclaw.providers.LegacyMessage(
                role = "user", content = agentContent
            ))
            session.aMessage(com.xiaomo.androidforclaw.providers.LegacyMessage(
                role = "assistant", content = result.finalContent
            ))

            // Send final reply — skip parts already sent as BlockReply
            val response = result.finalContent
            if (response.isnotBlank()) {
                val trimmed = response.trim()
                if (trimmed != "NO_REPLY" && trimmed != "HEARTBEAT_OK") {
                    var sanitized = com.xiaomo.androidforclaw.agent.session.HistorySanitizer
                        .stripControlTokensfromText(response)
                        .replace(Regex("(?:^|\\s+|\\*+)NO_REPLY\\s*$"), "")
                        .replace(Regex("(?:^|\\s+|\\*+)HEARTBEAT_OK\\s*$"), "")
                        .trim()
                    if (sanitized.isnotBlank()) {
                        sender?.sendText(touser, sanitized)
                    }
                }
            }

            // cancel typing
            sender?.cancelTyping(touser)
        } catch (e: kotlinx.coroutines.cancellationexception) {
            Log.i(TAG, "Weixin: task cancelled by user ${msg.fromuserId}")
        } catch (e: exception) {
            Log.e(TAG, "processWeixinMessagequeued exception", e)
            try {
                sender?.sendText(touser, "Error processing message: ${e.message}")
            } catch (_: exception) {}
        }
    }

    /**
     * Build Discord system prompt
     */
    private fun buildDiscordSystemPrompt(event: channelEvent.Message): String {
        val botName = discordchannel?.getBotusername() ?: "androidforClaw Bot"
        val botId = discordchannel?.getBotuserId() ?: ""

        return """
# identity
you are **$botName**, running on smart assistant on Android device, interact with user via Discord. 

# current context
- **platform**: Discord
- **channelType**: ${event.chatType}
- **channel ID**: ${event.channelId}
- **user**: ${event.authorName} (ID: ${event.authorId})
- **Bot ID**: $botId

# core capability
you can control Android device through tool calls: 
- 📸 screenshot to observe screen
- 👆 click、swipe、Input
- [HOME] navigate, open app
- [SEARCH] Get UI Info

# interaction rules
1. **be concise**: Discord message should be concise, use Markdown format for important info
2. **take initiative screenshot**: use screenshot tool when need to observe screen
3. **execute step by step**: break complex task into multiple steps
4. **feedback progress**: tell user progress for long time actions
5. **error handling**: explain reason and provide suggestions when issues encountered

# response format
- use Discord Markdown: **bold**、*italic*、`code`、```code block```
- use emoji for important action results: [OK] [ERROR] [WARN] [SYNC]
- use - or numbered list

# note matters
- notneedOutputoverlongMessage(suggest 1500 characters inside)
- code block syntax highlighting
- linkuse [Text](URL) format

now, pleaseProcessuserMessage. 
        """.trimIndent()
    }

    // ==================== Telegram channel ====================

    private fun startTelegramchannelifEnabled() {
        Log.i(TAG, "startTelegramchannelifEnabled() called")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Checking Telegram channel config...")
                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val telegramconfigData = openClawconfig.channels.telegram

                if (telegramconfigData == null || !telegramconfigData.enabled) {
                    Log.i(TAG, "Telegram channel not enabled, skipping")
                    return@launch
                }

                val token = telegramconfigData.botToken
                if (token.isBlank()) {
                    Log.w(TAG, "Telegram Bot Token not configured, skipping")
                    return@launch
                }

                Log.i(TAG, "Telegram channel enabled, starting...")

                val config = Telegramconfig(
                    enabled = true,
                    botToken = token,
                    dmPolicy = telegramconfigData.dmPolicy,
                    groupPolicy = telegramconfigData.groupPolicy,
                    requireMention = telegramconfigData.requireMention,
                    historyLimit = telegramconfigData.historyLimit ?: 50,
                    webhookUrl = telegramconfigData.webhookUrl,
                    model = telegramconfigData.model
                )

                val result = Telegramchannel.start(this@MyApplication, config)

                if (result.isSuccess) {
                    telegramchannel = result.getorNull()

                    telegramchannel?.let { channel ->
                        val client = channel.getClient()
                        if (client != null) {
                            telegramTyping = TelegramTyping(client)
                        }
                    }

                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_telegram_enabled", true)

                    Log.i(TAG, "Telegram channel started: bot=${telegramchannel?.getBotusername()}")

                    CoroutineScope(Dispatchers.IO).launch {
                        telegramchannel?.eventFlow?.collect { event ->
                            handleTelegramEvent(event)
                        }
                    }
                } else {
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_telegram_enabled", false)
                    Log.e(TAG, "Telegram channel start failed: ${result.exceptionorNull()?.message}")
                }
            } catch (e: exception) {
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_telegram_enabled", false)
                Log.e(TAG, "Telegram channel init error", e)
            }
        }
    }

    private suspend fun handleTelegramEvent(event: Telegramchannel.channelEvent) {
        try {
            when (event) {
                is Telegramchannel.channelEvent.Connected -> {
                    Log.i(TAG, "Telegram Connected")
                }
                is Telegramchannel.channelEvent.Message -> {
                    Log.i(TAG, "Telegram message from ${event.senderName} in ${event.chatId}: ${event.content.take(50)}")
                    sendTelegramReply(event)
                }
                is Telegramchannel.channelEvent.Error -> {
                    Log.e(TAG, "Telegram Error", event.error)
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Handle Telegram event failed", e)
        }
    }

    private suspend fun sendTelegramReply(event: Telegramchannel.channelEvent.Message) {
        try {
            if (!telegramDedup.a(event.messageId)) return
            telegramProcessingJobs[event.chatId]?.cancel()
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val adapter = object : channelAdapter {
                        override val channelName = "telegram"
                        override val sessionPrefix = "telegram"
                        override val messageCharLimit = 4000
                        override val supportsReactions = true
                        override val supportsTyping = true
                        override suspend fun aThinkingReaction() { telegramchannel?.setMessageReaction(event.chatId, event.messageId, "\uD83E\uDD14") }
                        override suspend fun removeThinkingReaction() { telegramchannel?.removeMessageReaction(event.chatId, event.messageId) }
                        override suspend fun aCompletionReaction() { telegramchannel?.setMessageReaction(event.chatId, event.messageId, "\u2705") }
                        override suspend fun aErrorReaction() { telegramchannel?.setMessageReaction(event.chatId, event.messageId, "\u274C") }
                        override fun startTyping() { telegramTyping?.startContinuous(event.chatId) }
                        override fun stopTyping() { telegramTyping?.stopContinuous(event.chatId) }
                        override suspend fun sendMessageChunk(text: String, isfirstChunk: Boolean) {
                            telegramchannel?.sendMessage(event.chatId, text, if (isfirstChunk) event.messageId else null)
                        }
                        override suspend fun sendErrorMessage(error: String) {
                            telegramchannel?.sendMessage(event.chatId, error, event.messageId)
                        }
                        override fun isGroupcontext() = event.chatType == "group"
                        override fun getuserMessage() = event.content
                        override fun getsessionKey() = "telegram_${event.chatId}"
                        override fun buildSystemPrompt(): String {
                            val botName = telegramchannel?.getBotusername() ?: "androidforClaw Bot"
                            val botId = telegramchannel?.getBotId() ?: ""
                            return """
# identity
you are **$botName**, running on smart assistant on Android device, through Telegram and user interaction. 

# current context
- **platform**: Telegram
- **chat type**: ${event.chatType}
- **Chat ID**: ${event.chatId}
- **user**: ${event.senderName} (ID: ${event.senderId})
- **Bot ID**: $botId

# core capability
you can control Android device through tool calls: 
- screenshot to observe screen
- click、swipe、Input
- navigate, open app
- Get UI Info

# interaction rules
1. **be concise**: Telegram message should be concise, use Markdown format for important info
2. **take initiative screenshot**: use screenshot tool when need to observe screen
3. **execute step by step**: break complex task into multiple steps
4. **feedback progress**: tell user progress for long time actions
5. **error handling**: explain reason and provide suggestions when issues encountered

# response format
- use Telegram Markdown: *bold*、_italic_、`code`、```code block```
- use - or numbered list

# note matters
- notneedOutputoverlongMessage(suggest 3000 characters inside)
- code block syntax highlighting

now, pleaseProcessuserMessage. 
                            """.trimIndent()
                        }
                    }
                    channelMessageProcessor(this@MyApplication).processMessage(adapter)
                } finally {
                    telegramProcessingJobs.remove(event.chatId)
                }
            }
            telegramProcessingJobs[event.chatId] = job
        } catch (e: exception) {
            Log.e(TAG, "Send Telegram reply failed", e)
            try { telegramchannel?.setMessageReaction(event.chatId, event.messageId, "\u274C") } catch (_: exception) {}
        }
    }

    // ==================== Slack channel ====================

    private fun startSlackchannelifEnabled() {
        Log.i(TAG, "startSlackchannelifEnabled() called")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val slackconfigData = openClawconfig.channels.slack

                if (slackconfigData == null || !slackconfigData.enabled) {
                    Log.i(TAG, "Slack channel not enabled, skipping")
                    return@launch
                }

                val token = slackconfigData.botToken
                if (token.isBlank()) {
                    Log.w(TAG, "Slack Bot Token not configured, skipping")
                    return@launch
                }

                val config = Slackconfig(
                    enabled = true,
                    botToken = token,
                    appToken = slackconfigData.appToken,
                    signingSecret = slackconfigData.signingSecret,
                    mode = slackconfigData.mode,
                    dmPolicy = slackconfigData.dmPolicy,
                    groupPolicy = slackconfigData.groupPolicy,
                    requireMention = slackconfigData.requireMention,
                    historyLimit = slackconfigData.historyLimit ?: 50,
                    model = slackconfigData.model
                )

                val result = Slackchannel.start(this@MyApplication, config)

                if (result.isSuccess) {
                    slackchannel = result.getorNull()

                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_slack_enabled", true)

                    Log.i(TAG, "Slack channel started: bot=${slackchannel?.getBotusername()}")

                    CoroutineScope(Dispatchers.IO).launch {
                        slackchannel?.eventFlow?.collect { event ->
                            handleSlackEvent(event)
                        }
                    }
                } else {
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_slack_enabled", false)
                    Log.e(TAG, "Slack channel start failed: ${result.exceptionorNull()?.message}")
                }
            } catch (e: exception) {
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_slack_enabled", false)
                Log.e(TAG, "Slack channel init error", e)
            }
        }
    }

    private suspend fun handleSlackEvent(event: Slackchannel.channelEvent) {
        try {
            when (event) {
                is Slackchannel.channelEvent.Connected -> {
                    Log.i(TAG, "Slack Connected")
                }
                is Slackchannel.channelEvent.Message -> {
                    Log.i(TAG, "Slack message from ${event.userId} in ${event.channelId}: ${event.text.take(50)}")
                    sendSlackReply(event)
                }
                is Slackchannel.channelEvent.Error -> {
                    Log.e(TAG, "Slack Error", event.error)
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Handle Slack event failed", e)
        }
    }

    private suspend fun sendSlackReply(event: Slackchannel.channelEvent.Message) {
        try {
            if (!slackDedup.a(event.ts)) return
            slackProcessingJobs[event.channelId]?.cancel()
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val threadTs = event.threadTs ?: event.ts
                    val adapter = object : channelAdapter {
                        override val channelName = "slack"
                        override val sessionPrefix = "slack"
                        override val messageCharLimit = 3900
                        override val supportsReactions = true
                        override val supportsTyping = false
                        override suspend fun aThinkingReaction() { slackchannel?.aReaction(event.channelId, "thinking_face", event.ts) }
                        override suspend fun removeThinkingReaction() { slackchannel?.removeReaction(event.channelId, "thinking_face", event.ts) }
                        override suspend fun aCompletionReaction() { slackchannel?.aReaction(event.channelId, "white_check_mark", event.ts) }
                        override suspend fun aErrorReaction() { slackchannel?.aReaction(event.channelId, "x", event.ts) }
                        override fun startTyping() {}
                        override fun stopTyping() {}
                        override suspend fun sendMessageChunk(text: String, isfirstChunk: Boolean) {
                            slackchannel?.postMessage(event.channelId, text, threadTs)
                        }
                        override suspend fun sendErrorMessage(error: String) {
                            slackchannel?.postMessage(event.channelId, error, threadTs)
                        }
                        override fun isGroupcontext() = event.channelType == "group"
                        override fun getuserMessage() = event.text
                        override fun getsessionKey() = "slack_${event.channelId}"
                        override fun buildSystemPrompt(): String {
                            val botName = slackchannel?.getBotusername() ?: "androidforClaw Bot"
                            val botId = slackchannel?.getBotId() ?: ""
                            return """
# identity
you are **$botName**, running on smart assistant on Android device, through Slack and user interaction. 

# current context
- **platform**: Slack
- **channelType**: ${event.channelType}
- **channel ID**: ${event.channelId}
- **user ID**: ${event.userId}
- **Bot ID**: $botId

# core capability
you can control Android device through tool calls: 
- screenshot to observe screen
- click、swipe、Input
- navigate, open app
- Get UI Info

# interaction rules
1. **be concise**: Slack message should be concise, use Markdown format for important info
2. **take initiative screenshot**: use screenshot tool when need to observe screen
3. **execute step by step**: break complex task into multiple steps
4. **feedback progress**: tell user progress for long time actions
5. **error handling**: explain reason and provide suggestions when issues encountered

# response format
- use Slack mrkdwn: *bold*、_italic_、`code`、```code block```
- use - or numbered list
- not support standard Markdown link, use <URL|Text> format

# note matters
- notneedOutputoverlongMessage(suggest 3000 characters inside)
- code block syntax highlighting

now, pleaseProcessuserMessage. 
                            """.trimIndent()
                        }
                    }
                    channelMessageProcessor(this@MyApplication).processMessage(adapter)
                } finally {
                    slackProcessingJobs.remove(event.channelId)
                }
            }
            slackProcessingJobs[event.channelId] = job
        } catch (e: exception) {
            Log.e(TAG, "Send Slack reply failed", e)
            try { slackchannel?.aReaction(event.channelId, "x", event.ts) } catch (_: exception) {}
        }
    }

    // ==================== Signal channel ====================

    private fun startSignalchannelifEnabled() {
        Log.i(TAG, "startSignalchannelifEnabled() called")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val configLoader = configLoader(this@MyApplication)
                val openClawconfig = configLoader.loadOpenClawconfig()
                val signalconfigData = openClawconfig.channels.signal

                if (signalconfigData == null || !signalconfigData.enabled) {
                    Log.i(TAG, "Signal channel not enabled, skipping")
                    return@launch
                }

                val phoneNumber = signalconfigData.phoneNumber
                if (phoneNumber.isBlank()) {
                    Log.w(TAG, "Signal phone number not configured, skipping")
                    return@launch
                }

                val config = Signalconfig(
                    enabled = true,
                    phoneNumber = phoneNumber,
                    httpUrl = signalconfigData.httpUrl ?: "http://127.0.0.1",
                    httpPort = signalconfigData.httpPort,
                    dmPolicy = signalconfigData.dmPolicy,
                    groupPolicy = signalconfigData.groupPolicy,
                    requireMention = signalconfigData.requireMention,
                    historyLimit = signalconfigData.historyLimit ?: 50,
                    model = signalconfigData.model
                )

                val result = Signalchannel.start(this@MyApplication, config)

                if (result.isSuccess) {
                    signalchannel = result.getorNull()

                    signalchannel?.let { channel ->
                        val client = channel.getClient()
                        if (client != null) {
                            signalTyping = SignalTyping(client)
                        }
                    }

                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_signal_enabled", true)

                    Log.i(TAG, "Signal channel started: phone=${config.phoneNumber}")

                    CoroutineScope(Dispatchers.IO).launch {
                        signalchannel?.eventFlow?.collect { event ->
                            handleSignalEvent(event)
                        }
                    }
                } else {
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_signal_enabled", false)
                    Log.e(TAG, "Signal channel start failed: ${result.exceptionorNull()?.message}")
                }
            } catch (e: exception) {
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_signal_enabled", false)
                Log.e(TAG, "Signal channel init error", e)
            }
        }
    }

    private suspend fun handleSignalEvent(event: Signalchannel.channelEvent) {
        try {
            when (event) {
                is Signalchannel.channelEvent.Connected -> {
                    Log.i(TAG, "Signal Connected")
                }
                is Signalchannel.channelEvent.Message -> {
                    Log.i(TAG, "Signal message from ${event.sourceName} in ${event.chatId}: ${event.text.take(50)}")
                    sendSignalReply(event)
                }
                is Signalchannel.channelEvent.Error -> {
                    Log.e(TAG, "Signal Error", event.error)
                }
            }
        } catch (e: exception) {
            Log.e(TAG, "Handle Signal event failed", e)
        }
    }

    private suspend fun sendSignalReply(event: Signalchannel.channelEvent.Message) {
        try {
            if (!signalDedup.a(event.timestamp)) return
            signalProcessingJobs[event.chatId]?.cancel()
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val adapter = object : channelAdapter {
                        override val channelName = "signal"
                        override val sessionPrefix = "signal"
                        override val messageCharLimit = 1900
                        override val supportsReactions = false
                        override val supportsTyping = true
                        override suspend fun aThinkingReaction() {}
                        override suspend fun removeThinkingReaction() {}
                        override suspend fun aCompletionReaction() {}
                        override suspend fun aErrorReaction() {}
                        override fun startTyping() { signalTyping?.startContinuous(event.chatId) }
                        override fun stopTyping() { signalTyping?.stopContinuous(event.chatId) }
                        override suspend fun sendMessageChunk(text: String, isfirstChunk: Boolean) {
                            signalchannel?.sendMessage(event.chatId, text)
                        }
                        override suspend fun sendErrorMessage(error: String) {
                            signalchannel?.sendMessage(event.chatId, error)
                        }
                        override fun isGroupcontext() = event.chatType == "group"
                        override fun getuserMessage() = event.text
                        override fun getsessionKey() = "signal_${event.chatId}"
                        override fun buildSystemPrompt(): String {
                            val phoneNumber = signalchannel?.getPhoneNumber() ?: ""
                            return """
# identity
you arerunning on smart assistant on Android device, through Signal and user interaction. 

# current context
- **platform**: Signal
- **chat type**: ${event.chatType}
- **Chat ID**: ${event.chatId}
- **user**: ${event.sourceName} (${event.sourceNumber})
- **phone number**: $phoneNumber

# core capability
you can control Android device through tool calls: 
- screenshot to observe screen
- click、swipe、Input
- navigate, open app
- Get UI Info

# interaction rules
1. **be concise**: Signal message should be concise
2. **take initiative screenshot**: use screenshot tool when need to observe screen
3. **execute step by step**: break complex task into multiple steps
4. **feedback progress**: tell user progress for long time actions
5. **error handling**: explain reason and provide suggestions when issues encountered

# response format
- Signal support limited format, use pure text for main
- use - or numbered list
- notneedOutputoverlongMessage(suggest 1500 characters inside)

now, pleaseProcessuserMessage. 
                            """.trimIndent()
                        }
                    }
                    channelMessageProcessor(this@MyApplication).processMessage(adapter)
                } finally {
                    signalProcessingJobs.remove(event.chatId)
                }
            }
            signalProcessingJobs[event.chatId] = job
        } catch (e: exception) {
            Log.e(TAG, "Send Signal reply failed", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        // Stop Discord related services
        try {
            discordTyping?.cleanup()
            discordTyping = null

            discordProcessingJobs.values.forEach { it.cancel() }
            discordProcessingJobs.clear()

            discordsessionmanager.clearAll()
            discordHistorymanager.clearAll()

            Discordchannel.stop()
            discordchannel = null

            // Clear MMKV status
            val mmkv = MMKV.defaultMMKV()
            mmkv?.encode("channel_discord_enabled", false)

            Log.i(TAG, "Discord servicealreadyStop")
        } catch (e: exception) {
            Log.e(TAG, "Stop Discord service hour wrong", e)
        }

        // Stop Signal related services
        try {
            signalTyping?.cleanup()
            signalTyping = null
            signalProcessingJobs.values.forEach { it.cancel() }
            signalProcessingJobs.clear()
            signalDedup.clear()
            Signalchannel.stop()
            signalchannel = null
            val mmkvSig = MMKV.defaultMMKV()
            mmkvSig?.encode("channel_signal_enabled", false)
            Log.i(TAG, "Signal service stopped")
        } catch (e: exception) {
            Log.e(TAG, "Error stopping Signal service", e)
        }

        // Stop Slack related services
        try {
            slackProcessingJobs.values.forEach { it.cancel() }
            slackProcessingJobs.clear()
            slackDedup.clear()
            Slackchannel.stop()
            slackchannel = null
            val mmkvSlack = MMKV.defaultMMKV()
            mmkvSlack?.encode("channel_slack_enabled", false)
            Log.i(TAG, "Slack service stopped")
        } catch (e: exception) {
            Log.e(TAG, "Error stopping Slack service", e)
        }

        // Stop Telegram related services
        try {
            telegramTyping?.cleanup()
            telegramTyping = null
            telegramProcessingJobs.values.forEach { it.cancel() }
            telegramProcessingJobs.clear()
            telegramDedup.clear()
            Telegramchannel.stop()
            telegramchannel = null
            val mmkvTg = MMKV.defaultMMKV()
            mmkvTg?.encode("channel_telegram_enabled", false)
            Log.i(TAG, "Telegram service stopped")
        } catch (e: exception) {
            Log.e(TAG, "Error stopping Telegram service", e)
        }

        // Stop Feishu channel
        feishuchannel?.stop()
        feishuchannel = null

        // Stop Gateway Server
        gatewayServer?.stop()
        gatewayServer = null

        Log.i(TAG, "appTerminate, AllservicealreadyStop")
    }

    /**
     * Cleanup message history, ensure tool_use and tool_result are paired
     *
     * Problem: when loading history messages from session, there may be orphaned tool_results
     * (corresponding tool_use is in earlier messages, already truncated)
     *
     * Solution: Only keep complete user/assistant messages, remove all tool-related content
     */
    private fun cleanuptoolMessages(messages: List<com.xiaomo.androidforclaw.providers.LegacyMessage>): List<com.xiaomo.androidforclaw.providers.LegacyMessage> {
        return messages.filter { message ->
            // Only keep text messages from user and assistant
            // Remove all messages containing tool_calls or tool_result
            when (message.role) {
                "user" -> true  // Keep all user messages
                "assistant" -> {
                    // Only keep plain text assistant messages, remove those with tool_calls
                    message.content != null && message.toolCalls == null
                }
                else -> false  // Remove tool role messages
            }
        }
    }
}