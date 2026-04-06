package com.xiaomo.androidforclaw.core

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiaomo.androidforclaw.logging.Log

/**
 * 通过 Termux RUN_COMMAND intent AutoStart sshd. 
 *
 * Need在 AndroidManifest.xml 中声明:
 *   <uses-permission android:name="com.termux.permission.RUN_COMMAND" />
 *
 * Need Termux v0.119.0+ 且User在 Termux Settings中Enabledd
 * "Allow External Apps" (~/.termux/termux.properties → allow-external-apps = true). 
 *
 * Reference: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 */
object TermuxSshdLauncher {

    private const val TAG = "TermuxSshdLauncher"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "$TERMUX_PACKAGE.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "$TERMUX_PACKAGE.RUN_COMMAND"

    // Termux RUN_COMMAND extras
    private const val EXTRA_COMMAND = "$TERMUX_PACKAGE.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_BACKGROUND = "$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND"

    /** sshd 的完整Path */
    const val SSHD_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/sshd"

    /** Ensure Termux 拉起Back再发 RUN_COMMAND 的初始WaitTime */
    private const val TERMUX_LAUNCH_WAIT_MS = 3000L

    /** RUN_COMMAND MaxRetry次数 */
    private const val MAX_LAUNCH_RETRIES = 3

    /** RetryInterval */
    private const val RETRY_INTERVAL_MS = 2000L

    /** 用于Build通用 RUN_COMMAND intent 的 bash Path */
    private const val BASH_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash"

    /**
     * Build RUN_COMMAND intent(Available于Test). 
     */
    fun buildIntent(): Intent = Intent(ACTION_RUN_COMMAND).apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, SSHD_PATH)
        putExtra(EXTRA_BACKGROUND, true)
    }

    /**
     * Build一个通过 bash -c 执RowAny命令的 RUN_COMMAND intent. 
     */
    private fun buildBashIntent(command: String): Intent = Intent(ACTION_RUN_COMMAND).apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, BASH_PATH)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
        putExtra(EXTRA_BACKGROUND, true)
    }

    /**
     * 检测YesNo为 MIUI/HyperOS 等小米系统(会拦截跨apply startService). 
     */
    fun isMiui(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val miuiVersion = get.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVersion.isNotEmpty()
        } catch (_: Exception) {
            // fallback: check manufacturer
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
        }
    }

    /**
     * Ensure Termux Process已Start. 
     * RUN_COMMAND Need Termux 的 RunCommandService 在Run才能Response, 
     * so先用 launch intent 把 Termux 拉起来. 
     *
     * @return true ifSuccesssend了Start intent
     */
    fun ensureTermuxRunning(context: Context): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "Termux 未Install, Cannot拉起")
            return false
        }
        // FLAG_ACTIVITY_NO_HISTORY: Termux 不留在ReturnStack, User按Return直接回 ForClaw
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            context.startActivity(launchIntent)
            Log.i(TAG, "✅ 已send Termux Start intent")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Start Termux Failed: ${e.message}")
            return false
        }
    }

    /**
     * send RUN_COMMAND intent 让 Termux 执Row sshd. 
     * 先Try startService, Failed则Try PendingIntent 绕过 OEM Limit. 
     */
    fun launch(context: Context) {
        val intent = buildIntent()
        try {
            context.startService(intent)
            Log.i(TAG, "✅ 已send RUN_COMMAND Start sshd (startService)")
        } catch (e: SecurityException) {
            Log.w(TAG, "startService 被deny, Try PendingIntent 方式: ${e.message}")
            launchViaPendingIntent(context)
        } catch (e: Exception) {
            Log.w(TAG, "startService Failed, Try PendingIntent 方式: ${e.message}")
            launchViaPendingIntent(context)
        }
    }

    /**
     * 通过 PendingIntent send RUN_COMMAND. 
     * PendingIntent 走不同的 framework Path, partially OEM Limit不会拦截. 
     */
    fun launchViaPendingIntent(context: Context) {
        try {
            val intent = buildIntent()
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getService(context, 0, intent, flags)
            pi.send()
            Log.i(TAG, "✅ 已send RUN_COMMAND Start sshd (PendingIntent)")
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent 方式AlsoFailed: ${e.message}")
            throw e
        }
    }

    /**
     * 通过 RUN_COMMAND 将公钥注入到 Termux 的 ~/.ssh/authorized_keys. 
     * 用于 sshd 可达但AuthenticateFailed(authorized_keys lose)的场景. 
     */
    fun injectPublicKey(context: Context, publicKey: String) {
        // 转义公钥中的单引号(although SSH 公钥usually不含单引号)
        val escaped = publicKey.replace("'", "'\\''")
        val cmd = "mkdir -p ~/.ssh && echo '$escaped' >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
        val intent = buildBashIntent(cmd)
        try {
            context.startService(intent)
            Log.i(TAG, "✅ 已通过 RUN_COMMAND 注入公钥到 authorized_keys")
        } catch (e: Exception) {
            Log.w(TAG, "startService 注入公钥Failed, Try PendingIntent: ${e.message}")
            try {
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getService(context, 1, intent, flags)
                pi.send()
                Log.i(TAG, "✅ 已通过 PendingIntent 注入公钥到 authorized_keys")
            } catch (e2: Exception) {
                Log.w(TAG, "PendingIntent 注入公钥AlsoFailed: ${e2.message}")
            }
        }
    }

    /**
     * sshd ReadyBack把Front台切回 ForClaw. 
     */
    fun bringBackForClaw(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                Log.i(TAG, "✅ 已切回 ForClaw")
            }
        } catch (e: Exception) {
            Log.w(TAG, "切回 ForClaw Failed: ${e.message}")
        }
    }

    /**
     * Open MIUI 自StartManage页面, 引导User给 Termux 开启自StartPermission. 
     *
     * @return true ifSuccessOpen了Settings页面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        // MIUI / HyperOS 自StartManage页面的已知 ComponentName List
        val autoStartActivities = listOf(
            // MIUI 经典Path
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // HyperOS / New版 MIUI
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
        )

        for (component in autoStartActivities) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "✅ 已Open自StartSettings: ${component.className}")
                return true
            } catch (_: Exception) {
                // 该Path不Exists, TryDown一个
            }
        }
        Log.w(TAG, "未找到 MIUI 自StartSettings页面")
        return false
    }

    /**
     * 在主ThreadShow Toast HintUser. 
     */
    fun showAutoStartGuide(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "⚠️ 小米系统拦截了 Termux 自Start, 请在弹出的Settings页中找到 Termux 并开启自StartPermission",
                Toast.LENGTH_LONG
            ).show()
        }
        // TryOpenSettings页
        openAutoStartSettings(context)
    }

    /**
     * 先Ensure Termux 已Start, WaitItsInitializeComplete, 再send RUN_COMMAND 执Row sshd. 
     * if RUN_COMMAND Failed(Termux 尚未Ready), 会Retrymost多 [MAX_LAUNCH_RETRIES] 次. 
     * 适合在 IO 协程中call(Contains delay). 
     */
    suspend fun ensureAndLaunch(context: Context) {
        // 先拉起 Termux
        ensureTermuxRunning(context)
        // 等 Termux Initialize(RunCommandService Register)
        kotlinx.coroutines.delay(TERMUX_LAUNCH_WAIT_MS)

        // send RUN_COMMAND, Failed则Retry
        for (attempt in 1..MAX_LAUNCH_RETRIES) {
            try {
                launch(context)
                Log.i(TAG, "RUN_COMMAND sendSuccess(第 ${attempt} 次)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "RUN_COMMAND 第 ${attempt} 次Failed: ${e.message}")
                if (attempt < MAX_LAUNCH_RETRIES) {
                    kotlinx.coroutines.delay(RETRY_INTERVAL_MS)
                } else {
                    throw e
                }
            }
        }
    }
}
