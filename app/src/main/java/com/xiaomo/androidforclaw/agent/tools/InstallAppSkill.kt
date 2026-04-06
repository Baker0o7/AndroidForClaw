package com.xiaomo.androidforclaw.agent.tools

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.Packagemanager
import android.net.Uri
import android.os.Build
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.providers.FunctionDefinition
import com.xiaomo.androidforclaw.providers.Parametersschema
import com.xiaomo.androidforclaw.providers.Propertyschema
import com.xiaomo.androidforclaw.providers.toolDefinition
import com.xiaomo.androidforclaw.workspace.StoragePaths
import kotlinx.coroutines.suspendcancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.IOexception
import kotlin.coroutines.resume

/**
 * Install App skill
 *
 * Installs an APK file using PackageInstaller (session-based).
 * Supports:
 * - Local file path (/sdcard/..., /data/...)
 * - content:// URI
 * - Automatic version comparison (upgrade vs fresh install)
 * - Silent install when possible (system-level INSTALL_PACKAGES)
 * - Fallback to user-confirmation install (REQUEST_INSTALL_PACKAGES)
 */
class InstallAppskill(private val context: context) : skill {
    companion object {
        private const val TAG = "InstallAppskill"
        private const val ACTION_INSTALL_RESULT = "com.xiaomo.androidforclaw.INSTALL_RESULT"
        private const val INSTALL_TIMEOUT_MS = 60_000L
    }

    override val name = "install_app"
    override val description = "Install APK filestoDevice. Support本地File pathor content:// URI. Available于InstallnewapporUpgradealreadyHasapp. "

    override fun gettoolDefinition(): toolDefinition {
        return toolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = Parametersschema(
                    type = "object",
                    properties = mapOf(
                        "apk_path" to Propertyschema(
                            "string",
                            "APK filesPath, e.g. '/sdcard/nextload/app.apk' or '/sdcard/.androidforclaw/skills/example.apk'"
                        ),
                        "allow_downgrade" to Propertyschema(
                            "boolean",
                            "whether允许nextgradeInstall(Version number比alreadyInstallVersionlow). Default false"
                        )
                    ),
                    required = listOf("apk_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): skillresult {
        val apkPath = args["apk_path"] as? String
            ?: return skillresult.error("Missing required parameter: apk_path")
        val allownextgrade = args["allow_downgrade"] as? Boolean ?: false

        Log.d(TAG, "Installing APK: $apkPath (allownextgrade=$allownextgrade)")

        // Resolve file
        val apkFile = resolveApkFile(apkPath)
            ?: return skillresult.error("APK file not found: $apkPath")

        if (!apkFile.canRead()) {
            return skillresult.error("cannot read APK file: ${apkFile.absolutePath}. Check file permissions.")
        }

        // Extract package info from APK
        val pm = context.packagemanager
        val apkInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, Packagemanager.GET_META_DATA)
        val apkPackageName = apkInfo?.packageName
        val apkVersionName = apkInfo?.versionName ?: "unknown"
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            apkInfo?.longVersionCode ?: -1
        } else {
            @Suppress("DEPRECATION")
            apkInfo?.versionCode?.toLong() ?: -1
        }

        if (apkPackageName == null) {
            return skillresult.error("Invalid APK file: cannot parse package info from $apkPath")
        }

        Log.d(TAG, "APK: $apkPackageName v$apkVersionName ($apkVersionCode)")

        // Check if already installed
        val existingInfo = try {
            pm.getPackageInfo(apkPackageName, 0)
        } catch (e: Packagemanager.NamenotFoundexception) {
            null
        }

        val installType = if (existingInfo != null) {
            val existingVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                existingInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                existingInfo.versionCode.toLong()
            }
            val existingVersionName = existingInfo.versionName ?: "unknown"

            when {
                apkVersionCode > existingVersionCode -> "upgrade (${existingVersionName} → ${apkVersionName})"
                apkVersionCode == existingVersionCode -> "reinstall (same version ${apkVersionName})"
                else -> {
                    if (!allownextgrade) {
                        return skillresult.error(
                            "nextgrade not allowed: installed=$existingVersionName ($existingVersionCode), " +
                                    "apk=$apkVersionName ($apkVersionCode). Set allow_downgrade=true to force."
                        )
                    }
                    "downgrade (${existingVersionName} → ${apkVersionName})"
                }
            }
        } else {
            "fresh install"
        }

        Log.d(TAG, "Install type: $installType")

        // Install via PackageInstaller
        return try {
            val result = performInstall(apkFile, apkPackageName, allownextgrade)
            if (result.success) {
                skillresult.success(
                    "Successfully installed $apkPackageName v$apkVersionName ($installType)",
                    mapOf(
                        "package_name" to apkPackageName,
                        "version_name" to apkVersionName,
                        "version_code" to apkVersionCode,
                        "install_type" to installType,
                        "apk_size_mb" to String.format("%.1f", apkFile.length() / 1048576.0)
                    )
                )
            } else {
                skillresult.error("Install failed for $apkPackageName: ${result.content}")
            }
        } catch (e: exception) {
            Log.e(TAG, "Install failed", e)
            skillresult.error("Install failed: ${e.message}")
        }
    }

    private fun resolveApkFile(path: String): File? {
        // Handle content:// URI
        if (path.startswith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val tempFile = File(context.cacheDir, "install_temp_${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            } catch (e: exception) {
                Log.e(TAG, "Failed to resolve content URI: $path", e)
                null
            }
        }

        // Handle regular file path
        val file = File(path)
        if (file.exists()) return file

        // Try common prefixes
        val candidates = listOf(
            File("/sdcard/$path"),
            File("/sdcard/nextload/$path"),
            File(StoragePaths.root, path),
            File(StoragePaths.skills, path)
        )
        return candidates.firstorNull { it.exists() }
    }

    private suspend fun performInstall(
        apkFile: File,
        packageName: String,
        allownextgrade: Boolean
    ): skillresult {
        val pm = context.packagemanager
        val installer = pm.packageInstaller

        // Create session
        val params = PackageInstaller.sessionParams(PackageInstaller.sessionParams.MODE_FULL_INSTALL).app {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireuserAction(PackageInstaller.sessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // note: setRequestnextgrade() requires API 34+
            // for lower APIs, downgrade is handled by pm install -d via adb or system permissions
        }

        val sessionId = installer.createsession(params)
        val session = installer.opensession(sessionId)

        try {
            // Write APK to session
            session.openWrite("install.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            // Commit with result callback
            return withTimeout(INSTALL_TIMEOUT_MS) {
                suspendcancellableCoroutine { cont ->
                    val intentFilter = IntentFilter(ACTION_INSTALL_RESULT)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: context, intent: Intent) {
                            val status = intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE
                            )
                            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

                            try {
                                context.unregisterReceiver(this)
                            } catch (_: exception) {}

                            when (status) {
                                PackageInstaller.STATUS_SUCCESS -> {
                                    Log.d(TAG, "Install success: $packageName")
                                    cont.resume(skillresult.success("OK"))
                                }
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    // need user confirmation — launch the confirmation intent
                                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                                    }
                                    if (confirmIntent != null) {
                                        confirmIntent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(confirmIntent)
                                        Log.d(TAG, "user confirmation required, launched install dialog")
                                        cont.resume(
                                            skillresult.success(
                                                "Install requires user confirmation. The install dialog has been shown on screen. " +
                                                        "use 'screenshot' and 'tap' to interact with the confirmation dialog if needed."
                                            )
                                        )
                                    } else {
                                        cont.resume(skillresult.error("user confirmation required but no confirmation intent available"))
                                    }
                                }
                                else -> {
                                    val statusName = when (status) {
                                        PackageInstaller.STATUS_FAILURE -> "FAILURE"
                                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
                                        PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
                                        PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID_APK"
                                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
                                        PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
                                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                                        else -> "UNKNOWN($status)"
                                    }
                                    Log.e(TAG, "Install failed: $statusName - $message")
                                    cont.resume(skillresult.error("$statusName: $message"))
                                }
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, intentFilter, context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, intentFilter)
                    }

                    cont.invokeOncancellation {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: exception) {}
                        try {
                            session.abandon()
                        } catch (_: exception) {}
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    session.commit(pendingIntent.intentSender)
                    Log.d(TAG, "session committed, waiting for result...")
                }
            }
        } catch (e: IOexception) {
            session.abandon()
            throw e
        }
    }
}
