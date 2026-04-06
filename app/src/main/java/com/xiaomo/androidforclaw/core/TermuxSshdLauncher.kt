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
 * Auto-start sshd via Termux RUN_COMMAND intent.
 *
 * Required declarations in AndroidManifest.xml:
 *   <uses-permission android:name="com.termux.permission.RUN_COMMAND" />
 *
 * Requires Termux v0.119.0+ and user must enable "Allow External Apps"
 * in Termux Settings (~/.termux/termux.properties -> allow-external-apps = true).
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

/** Full path to sshd */
const val SSHD_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/sshd"

/** Wait time after launching Termux before sending RUN_COMMAND */
private const val TERMUX_LAUNCH_WAIT_MS = 3000L

/** Max retry attempts for RUN_COMMAND */
private const val MAX_LAUNCH_RETRIES = 3

/** Retry interval between attempts */
private const val RETRY_INTERVAL_MS = 2000L

/** Bash path for building generic RUN_COMMAND intents */
private const val BASH_PATH = "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash"

/**
 * Build RUN_COMMAND intent (available for testing).
 */
    fun buildIntent(): Intent = Intent(ACTION_RUN_COMMAND).app {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, SSHD_PATH)
        putExtra(EXTRA_BACKGROUND, true)
    }

/**
 * Build a RUN_COMMAND intent that executes any command via bash -c.
 */
    private fun buildBashIntent(command: String): Intent = Intent(ACTION_RUN_COMMAND).app {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        putExtra(EXTRA_COMMAND, BASH_PATH)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
        putExtra(EXTRA_BACKGROUND, true)
    }

/**
 * Check if running on MIUI/HyperOS (these systems intercept cross-app startService).
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
 * Ensure Termux process is already running.
 * RUN_COMMAND requires Termux's RunCommandService to be running to respond,
 * so we first launch Termux via launch intent.
 *
 * @return true if successfully sent start intent
 */
    fun ensureTermuxRunning(context: context): Boolean {
        val pm = context.packagemanager
        val launchIntent = pm.getLaunchIntentforPackage(TERMUX_PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "Termux not installed, cannot launch")
            return false
        }
        // FLAG_ACTIVITY_NO_HISTORY: Don't leave Termux in back stack, user presses back to return to Claw
        launchIntent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            context.startActivity(launchIntent)
            Log.i(TAG, "[OK] Sent Termux start intent")
            return true
        } catch (e: exception) {
            Log.w(TAG, "Start Termux Failed: ${e.message}")
            return false
        }
    }

/**
 * Send RUN_COMMAND intent to execute sshd in Termux.
 * Try startService first, if failed try PendingIntent to bypass OEM restrictions.
 */
    fun launch(context: context) {
        val intent = buildIntent()
        try {
            context.startservice(intent)
            Log.i(TAG, "[OK] Sent RUN_COMMAND to start sshd (startService)")
        } catch (e: Securityexception) {
            Log.w(TAG, "startService denied, trying PendingIntent: ${e.message}")
            launchViaPendingIntent(context)
        } catch (e: exception) {
            Log.w(TAG, "startService failed, trying PendingIntent: ${e.message}")
            launchViaPendingIntent(context)
        }
    }

/**
 * Send RUN_COMMAND via PendingIntent.
 * PendingIntent uses a different framework path, bypassing some OEM restrictions.
 */
    fun launchViaPendingIntent(context: context) {
        try {
            val intent = buildIntent()
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getservice(context, 0, intent, flags)
            pi.send()
            Log.i(TAG, "[OK] Sent RUN_COMMAND to start sshd (PendingIntent)")
        } catch (e: exception) {
            Log.w(TAG, "PendingIntent also failed: ${e.message}")
            throw e
        }
    }

/**
 * Inject public key into Termux ~/.ssh/authorized_keys via RUN_COMMAND.
 * Used when sshd runs but authentication fails (authorized_keys missing).
 */
    fun injectPublicKey(context: context, publicKey: String) {
        // Escape single quotes in public key (although SSH keys usually don't contain single quotes)
        val escaped = publicKey.replace("'", "'\\''")
        val cmd = "mkdir -p ~/.ssh && echo '$escaped' >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
        val intent = buildBashIntent(cmd)
        try {
            context.startservice(intent)
            Log.i(TAG, "[OK] Injected public key to authorized_keys via RUN_COMMAND")
        } catch (e: exception) {
            Log.w(TAG, "Failed to inject public key via startService, trying PendingIntent: ${e.message}")
            try {
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getservice(context, 1, intent, flags)
                pi.send()
                Log.i(TAG, "[OK] Injected public key to authorized_keys via PendingIntent")
            } catch (e2: exception) {
                Log.w(TAG, "PendingIntent also failed to inject public key: ${e2.message}")
            }
        }
    }

/**
 * Bring Claw back to foreground.
 */
    fun bringbackforClaw(context: context) {
        try {
            val intent = context.packagemanager.getLaunchIntentforPackage(context.packageName)
            if (intent != null) {
                intent.aFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                Log.i(TAG, "[OK] Switched back to Claw")
            }
        } catch (e: exception) {
            Log.w(TAG, "Failed to switch back to Claw: ${e.message}")
        }
    }

/**
 * Open MIUI Auto-start manager page, guiding user to enable auto-start for Termux.
 *
 * @return true if successfully opened settings page
 */
fun openAutoStartSettings(context: Context): Boolean {
    // Known ComponentNames for MIUI / HyperOS Auto-start manager page
        val autoStartActivities = listOf(
            // MIUI classic path
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // HyperOS / newer MIUI
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
        )

        for (component in autoStartActivities) {
            try {
                val intent = Intent().app {
                    this.component = component
                    aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "[OK] Opened auto-start settings: ${component.className}")
                return true
            } catch (_: exception) {
                // Try next path
            }
        }
        Log.w(TAG, "Could not find MIUI auto-start settings page")
        return false
    }

    /**
     * Show toast hint on main thread.
     */
    fun showAutoStartGuide(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "Warning: MIUI system blocked Termux auto-start. Please find Termux in settings and enable auto-start permission.",
                Toast.LENGTH_LONG
            ).show()
        }
        // Try to open settings page
        openAutoStartSettings(context)
    }

    /**
     * First ensure Termux is started, wait for it to initialize, then send RUN_COMMAND to execute sshd.
     * If RUN_COMMAND fails (Termux not ready), will retry up to [MAX_LAUNCH_RETRIES] times.
     * Suitable for calling in IO coroutine (contains delay).
     */
    suspend fun ensureAndLaunch(context: Context) {
        // First launch Termux
        ensureTermuxRunning(context)
        // Wait for Termux to initialize (RunCommandService registered)
        delay(TERMUX_LAUNCH_WAIT_MS)

        // Send RUN_COMMAND, retry on failure
        for (attempt in 1..MAX_LAUNCH_RETRIES) {
            try {
                launch(context)
                Log.i(TAG, "RUN_COMMAND sent successfully (attempt ${attempt})")
                return
            } catch (e: Exception) {
                Log.w(TAG, "RUN_COMMAND attempt ${attempt} failed: ${e.message}")
                if (attempt < MAX_LAUNCH_RETRIES) {
                    delay(RETRY_INTERVAL_MS)
                } else {
                    throw e
                }
            }
        }
    }
}
