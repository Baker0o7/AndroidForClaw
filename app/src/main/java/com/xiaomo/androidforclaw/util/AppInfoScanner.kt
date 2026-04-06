/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (Android-only)
 */
package com.xiaomo.androidforclaw.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.core.MyApplication

/**
 * App information scanner tool
 * Used to quickly get app name, package name and main Activity of installed apps on device
 *
 * Usage:
 * 1. Call in code:AppInfoScanner.scanAndExport(context)
 * 2. View Logcat (tag: AppInfoScanner) to get output
 * 3. Or call AppInfoScanner.exportToFile(context) to export to file
 */
object AppInfoScanner {

    private const val TAG = "AppInfoScanner"

    /**
     * App information data class
     */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val mainActivity: String?
    )

    /**
     * Scan all installed apps and output to Logcat
     * @param context Context
     * @param includeSystemApps Whether to include system apps, default false
     * @param filterKeywords Filter keywords list
     */
    fun scanAndExport(
        context: Context,
        includeSystemApps: Boolean = false,
        filterKeywords: List<String> = listOf(
            "android.",
            "com.android.",
            "com.google.android.webview",
            "com.vendor.security",
            "com.vendor.system",
            "com.vendor.mipush",
            "com.qualcomm.",
            "vendor.qti.",
            "com.qti.",
            "org.codeaurora.",
            "com.longcheertel.",
            "com.boundax.",
            "com.wdstechnology.",
            "com.novatek.",
            "com.duokan.",
            "com.bsp.",
            "com.fingerprints.",
            "com.goodix.",
            "com.lbe.",
            "com.tencent.soter.",
            "com.microsoftsdk.",
            "com.agoda.",
            "com.booking.",
            "com.netflix.",
            "com.spotify.",
            "com.linkedin.",
            "com.facebook.",
            "com.amazon.",
            "com.google.ambient.",
            "com.wdstechnology.",
            "com.boundax.",
            "vendor.systemui.plugin",
            "android.autoinstalls.",
            "android.auto_generated_",
            "android.overlay.",
            "android.aosp.overlay.",
            "android.vendor.overlay.",
            "android.qvaoverlay.",
            "com.android.overlay.",
            "com.android.theme.",
            "com.android.internal.",
            "com.android.server.",
            "com.android.phone.auto_generated_",
            "com.android.providers.telephony.auto_generated_",
            "com.android.companiondevicemanager.auto_generated_",
            "com.vendor.systemui.overlay.",
            "com.vendor.settings.rro.",
            "com.vendor.phone.carriers.overlay.",
            "com.vendor.wallpaper.overlay.",
            "com.vendor.miwallpaper.overlay.",
            "com.vendor.miwallpaper.config.overlay.",
            "com.android.systemui.overlay.",
            "com.android.server.telecom.overlay.",
            "com.android.providers.telephony.overlay.",
            "com.android.carrierconfig.overlay.",
            "com.android.managedprovisioning.overlay.",
            "com.android.cellbroadcastreceiver.overlay.",
            "com.android.stk.overlay.",
            "com.android.bluetooth.overlay.",
            "com.android.phone.overlay.",
            "com.android.wifi.resources.overlay.",
            "com.android.wifi.resources.vendor",
            "com.google.android.overlay.",
            "com.google.android.wifi.resources.overlay.",
            "com.vendor.system.overlay",
            "com.vendor.systemui.carriers.overlay",
            "com.vendor.systemui.devices.overlay",
            "com.vendor.permissioncontroller.overlay",
            "com.vendor.cellbroadcastservice.overlay",
            "com.vendor.inputsettings.overlay",
            "com.android.settings.overlay.",
            "com.android.settings.intelligence",
            "com.android.inputsettings.overlay.",
            "com.android.inputdevices",
            "com.android.internal.systemui.",
            "com.android.internal.display.",
            "com.android.compos.",
            "com.android.microdroid.",
            "com.android.virtualmachine.",
            "com.android.uwb.resources.",
            "com.android.uwb.resources.overlay.",
            "com.android.dreams.",
            "com.android.egg",
            "com.android.emergency",
            "com.android.emergency",
            "com.android.hotwordenrollment.",
            "com.android.localtransport",
            "com.android.pacprocessor",
            "com.android.provision",
            "com.android.rkpdapp",
            "com.android.shell",
            "com.android.simappdialog",
            "com.android.soundpicker",
            "com.android.traceur",
            "com.android.vpndialogs",
            "com.android.wallpaperbackup",
            "com.android.wallpapercropper",
            "com.android.wallpaper.livepicker",
            "com.android.htmlviewer",
            "com.android.bookmarkprovider",
            "com.android.providers.",
            "com.android.proxyhandler",
            "com.android.sharedstoragebackup",
            "com.android.statementservice",
            "com.android.storagemanager",
            "com.android.externalstorage",
            "com.android.certinstaller",
            "com.android.calllogbackup",
            "com.android.backupconfirm",
            "com.android.avatarpicker",
            "com.android.apps.tag",
            "com.android.bips",
            "com.android.bluetoothmidiservice",
            "com.android.bluetooth",
            "com.android.carrierdefaultapp",
            "com.android.companiondevicemanager",
            "com.android.credentialmanager",
            "com.android.cts.",
            "com.android.DeviceAsWebcam",
            "com.android.devicediagnostics",
            "com.android.dreams.basic",
            "com.android.dreams.phototable",
            "com.android.dynsystem",
            "com.android.hotspot2.",
            "com.android.imsserviceentitlement",
            "com.android.keychain",
            "com.android.location.fused",
            "com.android.mms.service",
            "com.android.mtp",
            "com.android.musicfx",
            "com.android.nfc",
            "com.android.ons",
            "com.android.phone",
            "com.android.printspooler",
            "com.android.printservice.",
            "com.android.role.notes.enabled",
            "com.android.se",
            "com.android.settings",
            "com.android.smspush",
            "com.android.soundrecorder",
            "com.android.stk",
            "com.android.systemui",
            "com.android.systemui.accessibility.",
            "com.android.thememanager",
            "com.android.thememanager.customizethemeconfig.config.overlay",
            "com.android.theme.font.",
            "com.android.cameraextensions",
            "com.android.camera",
            "com.android.cellbroadcastreceiver",
            "com.android.cellbroadcastservice",
            "com.android.carrierconfig",
            "com.android.companiondevicemanager",
            "com.android.deskclock",
            "com.android.intentresolver",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.vendor.global.packageinstaller"
        )
    ) {
        val appInfoList = scanApps(context, includeSystemApps, filterKeywords)

        if (appInfoList.isEmpty()) {
            Log.w(TAG, "未找到任何apply")
            return
        }

        // Output到 Logcat
        Log.d(TAG, "=".repeat(100))
        Log.d(TAG, "StartOutputApp info(共 ${appInfoList.size} 个apply)")
        Log.d(TAG, "=".repeat(100))

        // 按apply名Sort
        val sortedList = appInfoList.sortedBy { it.appName }

        sortedList.forEach { appInfo ->
            val formattedCode = formatAppIntentInfo(appInfo)
            Log.d(TAG, formattedCode)
        }

        Log.d(TAG, "=".repeat(100))
        Log.d(TAG, "App infoOutputComplete")
        Log.d(TAG, "=".repeat(100))

        // Output statistics
        Log.d(TAG, "\nStatistics info: ")
        Log.d(TAG, "总apply数: ${appInfoList.size}")
        val launchableCount = appInfoList.count { it.mainActivity != null }
        val unlaunchableCount = appInfoList.count { it.mainActivity == null }
        Log.d(TAG, "HasMain Activity的apply: $launchableCount")
        Log.d(TAG, "NoneMain Activity的apply: $unlaunchableCount")
        if (unlaunchableCount > 0) {
            Log.d(TAG, "\nillustrate: NoneMain Activity的applyusuallyYes以DownType: ")
            Log.d(TAG, "1. ServiceClassapply(Service)- such as com.vendor.aiasst.service")
            Log.d(TAG, "2. 系统Group件和库 - such as com.vendor.analytics")
            Log.d(TAG, "3. Back台Service - such as com.google.android.ext.services")
            Log.d(TAG, "4. 这些applyCannot通过普通方式Start, thereforeNoneMain Activity")
        }
    }

    /**
     * 扫描All已Installapply
     */
    private fun scanApps(
        context: Context,
        includeSystemApps: Boolean,
        filterKeywords: List<String>
    ): List<AppInfo> {
        val appInfoList = mutableListOf<AppInfo>()

        try {
            val pm = context.packageManager
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }

            packages.forEach { packageInfo ->
                val packageName = packageInfo.packageName

                // Filter system apps
                if (!includeSystemApps) {
                    val isSystemApp = filterKeywords.any {
                        packageName.startsWith(it, ignoreCase = true)
                    }
                    if (isSystemApp) return@forEach
                }

                // Getapply名
                val appName = try {
                    val ai = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    packageName
                }

                // GetMain Activity(use多种MethodTry)
                val mainActivity = getMainActivity(context, packageName, pm)

                appInfoList.add(AppInfo(packageName, appName, mainActivity))
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描applyFailed: ${e.message}", e)
            LayoutExceptionLogger.log("AppInfoScanner#scanApps", e)
        }

        return appInfoList
    }

    /**
     * Getapply的Main Activity(use多种Method)
     * Method1: use getLaunchIntentForPackage(most快, 但possibly受Package可见性Limit)
     * Method2: Parse PackageInfo Find MAIN/LAUNCHER Activity(moreReliable)
     */
    private fun getMainActivity(
        context: Context,
        packageName: String,
        pm: PackageManager
    ): String? {
        // Method1: use getLaunchIntentForPackage(优先use, becausemost快)
        try {
            val intent = pm.getLaunchIntentForPackage(packageName)
            val className = intent?.component?.className
            if (!className.isNullOrEmpty()) {
                return className
            }
        } catch (e: Exception) {
            Log.d(TAG, "getLaunchIntentForPackage Failed ($packageName): ${e.message}")
        }

        // Method2: use queryIntentActivities 直接FindAll可Start的 Activity(moreEfficient)
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("NewApi")
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }

            // Findmatch当FrontPackage name的First Activity
            for (resolveInfo in resolveInfoList) {
                if (resolveInfo.activityInfo.packageName == packageName) {
                    return resolveInfo.activityInfo.name
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryIntentActivities Failed ($packageName): ${e.message}")
        }

        // Method3: Parse PackageInfo Find MAIN/LAUNCHER Activity(mostBack的备用Method)
        // Note: 某些apply(such asServiceClassapply、系统Group件)possiblyNoneMain Activity, 这Yes正常的
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_ACTIVITIES.toLong() or
                        PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
                )
            }

            // ifapplyNone任何 Activity, illustratepossiblyYesServiceClassapply
            val activities = packageInfo.activities
            if (activities == null || activities.isEmpty()) {
                Log.d(TAG, "apply $packageName None Activity(possiblyYesServiceClassapply)")
                return null
            }

            // Find带Has MAIN/LAUNCHER intent-filter 的 Activity
            packageInfo.activities?.forEach { activityInfo ->
                try {
                    // Check Activity YesNoHas MAIN/LAUNCHER intent-filter
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(packageName)
                        setClassName(packageName, activityInfo.name)
                    }

                    val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("NewApi")
                        pm.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(intent, 0)
                    }

                    if (resolveInfoList.isNotEmpty()) {
                        return activityInfo.name
                    }
                } catch (e: Exception) {
                    // IgnoreSingle Activity 的QueryError
                }
            }
        } catch (e: Exception) {
            // ifGet PackageInfo Failed, possiblyYesPermissionIssue或apply不Exists
            Log.d(TAG, "Parse PackageInfo Failed ($packageName): ${e.message}")
        }


        // AllMethod都Failed, Return null
        return null
    }

    /**
     * FormatSingleapply的 AppIntentInfo 代码
     */
    private fun formatAppIntentInfo(appInfo: AppInfo): String {
        // 清理apply名, 移除特殊字符
        val cleanAppName = appInfo.appName
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .trim()

        // 生成 appNameList(DefaultContainsapply名)
        val appNameList = mutableListOf<String>()
        appNameList.add(cleanAppName)

        // ifPackage nameHas意义, AlsoAdd到List中
        val packageNameParts = appInfo.packageName.split(".")
        if (packageNameParts.size > 1) {
            val lastPart = packageNameParts.last()
            if (lastPart.length > 2 &&
                !lastPart.equals(cleanAppName, ignoreCase = true) &&
                !lastPart.contains("overlay", ignoreCase = true) &&
                !lastPart.contains("rro", ignoreCase = true)) {
                appNameList.add(lastPart)
            }
        }

        // ifHasMain Activity, Output完整格式；No则只Output基本Info
        return if (appInfo.mainActivity != null) {
            """
    AppIntentInfo(
        appName = "$cleanAppName",
        appNameList = mutableListOf(${appNameList.joinToString(", ") { "\"$it\"" }}),
        packageName = "${appInfo.packageName}",
        mainActivity = "${appInfo.mainActivity}"
    ),""".trimIndent()
        } else {
            """
    // NoneMain Activity: $cleanAppName (${appInfo.packageName})
    // AppIntentInfo(
    //     appName = "$cleanAppName",
    //     appNameList = mutableListOf(${appNameList.joinToString(", ") { "\"$it\"" }}),
    //     packageName = "${appInfo.packageName}",
    //     mainActivity = "Unknown"
    // ),""".trimIndent()
        }
    }

    /**
     * Fast扫描(use MyApplication 的 context)
     */
    fun quickScan() {
        val context = MyApplication.application
        scanAndExport(context)
    }

    /**
     * 扫描并Export为Text格式(便于Copy)
     * @param context Context
     * @return Format的TextString
     */
    fun exportAsText(context: Context): String {
        val appInfoList = scanApps(context, includeSystemApps = false, filterKeywords = emptyList())
        val sortedList = appInfoList.sortedBy { it.appName }

        val sb = StringBuilder()
        sb.appendLine("=".repeat(100))
        sb.appendLine("App infoList(共 ${appInfoList.size} 个apply)")
        sb.appendLine("=".repeat(100))
        sb.appendLine()

        sortedList.forEach { appInfo ->
            sb.appendLine("apply名: ${appInfo.appName}")
            sb.appendLine("Package name: ${appInfo.packageName}")
            sb.appendLine("Main Activity: ${appInfo.mainActivity ?: "None"}")
            sb.appendLine("-".repeat(80))
        }

        return sb.toString()
    }

    /**
     * 只GetHasMain Activity的apply(可Start的apply)
     */
    fun scanLaunchableApps(context: Context): List<AppInfo> {
        return scanApps(context, includeSystemApps = false, filterKeywords = emptyList())
            .filter { it.mainActivity != null }
    }

    /**
     * OutputFormat的 AppIntentInfo 代码(仅ContainsHasMain Activity的apply)
     */
    fun exportAppIntentInfoCode(context: Context): String {
        val launchableApps = scanLaunchableApps(context)
        val sortedList = launchableApps.sortedBy { it.appName }

        val sb = StringBuilder()
        sb.appendLine("// 共 ${launchableApps.size} 个可Startapply")
        sb.appendLine()

        sortedList.forEach { appInfo ->
            val formattedCode = formatAppIntentInfo(appInfo)
            sb.appendLine(formattedCode)
            sb.appendLine()
        }

        return sb.toString()
    }
}
