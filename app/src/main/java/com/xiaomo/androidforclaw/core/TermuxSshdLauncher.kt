package com.xiaomo.androidforclaw.core

import android.app.PendingIntent
import android.content.ComponentName
import android.content.context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.looper
import android.widget.Toast
import com.xiaomo.androidforclaw.logging.Log

/**
 * through Termux RUN_COMMAND intent AutoStart sshd. 
 *
 * needin androidManifest.xml 中声明:
 *   <uses-permission android:name="com.termux.permission.RUN_COMMAND" />
 *
 * need Termux v0.119.0+ 且userin Termux Settings中Enable
 * "Allow External Apps" (~/.termux/termux.properties → allow-external-apps = true). 
 *
 * Reference: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 */
object TermuxSshdLauncher {

    private const val TAG = "TermuxSshdLauncher"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "$TERMUX_PACKAGE.app.RunCommandservice"
    private const val ACTION_RUN_COMMAND = "$TERMUX_PACKAGE.RUN_COMMAND"

    // Termux RUN_COMMAND extras
    private const val EXTRA_COMMAND = "$TERMUX_PACKAGE.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_BACKGROUND = "$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND"

    /** sshd 完整Path */
    const val SSHD_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/sshd"

    /** Ensure Termux 拉upback再发 RUN_COMMAND 初始WaitTime */
    private const val TERMUX_LAUNCH_WAIT_MS = 3000L

    /** RUN_COMMAND Maxretrytimes数 */
    private const val MAX_LAUNCH_RETRIES = 3

    /** retryInterval */
    private const val RETRY_INTERVAL_MS = 2000L

    /** 用于Build通用 RUN_COMMAND intent  bash Path */
    private const val BASH_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash"

    /**
     * Build RUN_COMMAND intent(Available于Test). 
     */
    fun buildIntent(): Intent = Intent(ACTION_RUN_COMMAND).app {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, SSHD_PATH)
        putExtra(EXTRA_BACKGROUND, true)
    }

    /**
     * Buildonethrough bash -c executionAny命令 RUN_COMMAND intent. 
     */
    private fun buildBashIntent(command: String): Intent = Intent(ACTION_RUN_COMMAND).app {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, BASH_PATH)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
        putExtra(EXTRA_BACKGROUND, true)
    }

    /**
     * 检测whetherfor MIUI/HyperOS 等small米系统(will拦截跨app startservice). 
     */
    fun isMiui(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val miuiVersion = get.invoke(null, "ro.miui.ui.version.name", "") as String
            miuiVersion.isnotEmpty()
        } catch (_: exception) {
            // fallback: check manufacturer
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Redmi", ignoreCase = true)
        }
    }

    /**
     * Ensure Termux ProcessalreadyStart. 
     * RUN_COMMAND need Termux  RunCommandservice inRun才canResponse, 
     * so先用 launch intent  Termux 拉upcome. 
     *
     * @return true ifSuccesssendStart intent
     */
    fun ensureTermuxRunning(context: context): Boolean {
        val pm = context.packagemanager
        val launchIntent = pm.getLaunchIntentforPackage(TERMUX_PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "Termux notInstall, cannot拉up")
            return false
        }
        // FLAG_ACTIVITY_NO_HISTORY: Termux not留inReturnStack, user按Return直接return forClaw
        launchIntent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            context.startActivity(launchIntent)
            Log.i(TAG, "[OK] alreadysend Termux Start intent")
            return true
        } catch (e: exception) {
            Log.w(TAG, "Start Termux Failed: ${e.message}")
            return false
        }
    }

    /**
     * send RUN_COMMAND intent 让 Termux execution sshd. 
     * 先Try startservice, FailedthenTry PendingIntent 绕over OEM Limit. 
     */
    fun launch(context: context) {
        val intent = buildIntent()
        try {
            context.startservice(intent)
            Log.i(TAG, "[OK] alreadysend RUN_COMMAND Start sshd (startservice)")
        } catch (e: Securityexception) {
            Log.w(TAG, "startservice 被deny, Try PendingIntent 方式: ${e.message}")
            launchViaPendingIntent(context)
        } catch (e: exception) {
            Log.w(TAG, "startservice Failed, Try PendingIntent 方式: ${e.message}")
            launchViaPendingIntent(context)
        }
    }

    /**
     * through PendingIntent send RUN_COMMAND. 
     * PendingIntent 走not同 framework Path, partially OEM Limitnotwill拦截. 
     */
    fun launchViaPendingIntent(context: context) {
        try {
            val intent = buildIntent()
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getservice(context, 0, intent, flags)
            pi.send()
            Log.i(TAG, "[OK] alreadysend RUN_COMMAND Start sshd (PendingIntent)")
        } catch (e: exception) {
            Log.w(TAG, "PendingIntent 方式AlsoFailed: ${e.message}")
            throw e
        }
    }

    /**
     * through RUN_COMMAND will公钥注入to Termux  ~/.ssh/authorized_keys. 
     * 用于 sshd can达butAuthenticateFailed(authorized_keys lose)场景. 
     */
    fun injectPublicKey(context: context, publicKey: String) {
        // 转义公钥中单引号(although SSH 公钥usuallynot含单引号)
        val escaped = publicKey.replace("'", "'\\''")
        val cmd = "mkdir -p ~/.ssh && echo '$escaped' >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
        val intent = buildBashIntent(cmd)
        try {
            context.startservice(intent)
            Log.i(TAG, "[OK] alreadythrough RUN_COMMAND 注入公钥to authorized_keys")
        } catch (e: exception) {
            Log.w(TAG, "startservice 注入公钥Failed, Try PendingIntent: ${e.message}")
            try {
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getservice(context, 1, intent, flags)
                pi.send()
                Log.i(TAG, "[OK] alreadythrough PendingIntent 注入公钥to authorized_keys")
            } catch (e2: exception) {
                Log.w(TAG, "PendingIntent 注入公钥AlsoFailed: ${e2.message}")
            }
        }
    }

    /**
     * sshd Readybackforeground切return forClaw. 
     */
    fun bringbackforClaw(context: context) {
        try {
            val intent = context.packagemanager.getLaunchIntentforPackage(context.packageName)
            if (intent != null) {
                intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                Log.i(TAG, "[OK] already切return forClaw")
            }
        } catch (e: exception) {
            Log.w(TAG, "切return forClaw Failed: ${e.message}")
        }
    }

    /**
     * Open MIUI 自StartManage页面, steeruser给 Termux open自StartPermission. 
     *
     * @return true ifSuccessOpenSettings页面
     */
    fun openAutoStartSettings(context: context): Boolean {
        // MIUI / HyperOS 自StartManage页面already知 ComponentName List
        val autoStartActivities = listOf(
            // MIUI 经典Path
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // HyperOS / new版 MIUI
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
        )

        for (component in autoStartActivities) {
            try {
                val intent = Intent().app {
                    this.component = component
                    aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "[OK] alreadyOpen自StartSettings: ${component.className}")
                return true
            } catch (_: exception) {
                // shouldPathnotExists, Trynextone
            }
        }
        Log.w(TAG, "not找to MIUI 自StartSettings页面")
        return false
    }

    /**
     * inmainThreadShow Toast Hintuser. 
     */
    fun showAutoStartGuide(context: context) {
        Handler(looper.getMainlooper()).post {
            Toast.makeText(
                context,
                "[WARN] small米系统拦截 Termux 自Start, pleasein弹出Settings页中找to Termux 并open自StartPermission",
                Toast.LENGTH_LONG
            ).show()
        }
        // TryOpenSettings页
        openAutoStartSettings(context)
    }

    /**
     * 先Ensure Termux alreadyStart, WaitItsInitializeComplete, 再send RUN_COMMAND execution sshd. 
     * if RUN_COMMAND Failed(Termux 尚notReady), willretrymostmany [MAX_LAUNCH_RETRIES] times. 
     * 适合in IO 协程中call(Contains delay). 
     */
    suspend fun ensureandLaunch(context: context) {
        // 先拉up Termux
        ensureTermuxRunning(context)
        // 等 Termux Initialize(RunCommandservice Register)
        kotlinx.coroutines.delay(TERMUX_LAUNCH_WAIT_MS)

        // send RUN_COMMAND, Failedthenretry
        for (attempt in 1..MAX_LAUNCH_RETRIES) {
            try {
                launch(context)
                Log.i(TAG, "RUN_COMMAND sendSuccess(第 ${attempt} times)")
                return
            } catch (e: exception) {
                Log.w(TAG, "RUN_COMMAND 第 ${attempt} timesFailed: ${e.message}")
                if (attempt < MAX_LAUNCH_RETRIES) {
                    kotlinx.coroutines.delay(RETRY_INTERVAL_MS)
                } else {
                    throw e
                }
            }
        }
    }
}
