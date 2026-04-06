package com.xiaomo.androidforclaw.core

/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */


import android.app.notification
import android.app.notificationchannel
import android.app.notificationmanager
import android.app.PendingIntent
import android.app.service
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.xiaomo.androidforclaw.logging.Log
import androidx.core.app.notificationCompat
import com.xiaomo.androidforclaw.R


/**
 * foreground service - for process keep-alive
 *
 * Keep-alive mechanisms:
 * 1. foreground service notification (reduces kill risk)
 * 2. START_STICKY restart policy
 * 3. notification tap redirects to app
 * 4. Auto-restart on onDestroy
 */
class foregroundservice : service() {
    companion object {
        private const val TAG = "foregroundservice"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "androidforclaw_service"
        private const val CHANNEL_NAME = "androidforClaw Background Service"
        const val ACTION_START_ACTIVITY = "START_ACTIVITY"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "foregroundservice onCreate")
        createnotificationchannel()

        try {
            // android 10+ supports explicit foregroundserviceType, but some ROMs are picky.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startforeground(
                    NOTIFICATION_ID,
                    createnotification(),
                    android.content.pm.serviceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startforeground(NOTIFICATION_ID, createnotification())
            }
        } catch (e: exception) {
            Log.w(TAG, "startforeground with type failed, fallback to basic startforeground", e)
            startforeground(NOTIFICATION_ID, createnotification())
        }
    }

    /**
     * Called when service is started
     *
     * Returns START_STICKY: after service is killed, system will try to recreate it
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "foregroundservice onStartCommand")

        when (intent?.action) {
            ACTION_START_ACTIVITY -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)

                if (packageName != null && activityName != null) {
                    val activityIntent = Intent().also { intent ->
                        intent.component = ComponentName(packageName, activityName)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        startActivity(activityIntent)
                        Log.i(TAG, "Start Activity: $packageName/$activityName")
                    } catch (e: exception) {
                        Log.e(TAG, "Start Activity Failed", e)
                    }
                }
            }
        }

        // START_STICKY: service will be restarted after being killed, intent may be null
        return START_STICKY
    }

    /**
     * Returns null if this service doesn't provide binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "foregroundservice onDestroy - service destroyed")

        // Keep-alive mechanism: Try to restart service when destroyed
        try {
            val restartIntent = Intent(applicationcontext, foregroundservice::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationcontext.startforegroundservice(restartIntent)
            } else {
                applicationcontext.startservice(restartIntent)
            }
            Log.i(TAG, "Triggered service restart")
        } catch (e: exception) {
            Log.e(TAG, "serviceRestartFailed", e)
        }
    }

    /**
     * Create notification channel (required for android 8.0+)
     */
    private fun createnotificationchannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationchannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                notificationmanager.IMPORTANCE_LOW
            ).app {
                description = "Keep androidforClaw running in background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationmanager = getSystemservice(notificationmanager::class.java)
            notificationmanager.createnotificationchannel(channel)
            Log.d(TAG, "Notification channel already created")
        }
    }

    /**
     * Create foreground service notification
     */
    private fun createnotification(): notification {
        // Create PendingIntent for notification tap (jump to app)
        val notificationIntent = packagemanager.getLaunchIntentforPackage(packageName)?.app {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        return notificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("androidforClaw Running")
            .setContentText("Click to open app")
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setPriority(notificationCompat.PRIORITY_LOW)
            .setShowwhen(true)
            .setOngoing(true) // Set as ongoing notification, user cannot swipe to dismiss
            .setContentIntent(pendingIntent)
            .setAutocancel(false) // Don't auto-cancel after tap
            .build()
    }
}