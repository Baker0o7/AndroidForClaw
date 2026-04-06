package com.xiaomo.androidforclaw.accessibility

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: observer permission and projection flow.
 */


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service (refactored version)
 *
 * Used to maintain MediaProjection screen recording permission
 *
 * Main improvements:
 * 1. Simplified startup - starts in foreground mode immediately
 * 2. Removed broadcast mechanism - reduced complexity
 * 3. Added health check
 * 4. Improved notification content
 */
class ObserverForegroundService : Service() {
    companion object {
        private const val TAG = "S4ClawForeground"
        private const val NOTIFICATION_ID = 10086
        private const val CHANNEL_ID = "s4claw_media_projection"
        private const val CHANNEL_NAME = "S4Claw Screen Recording Service"

        @Volatile
        private var isRunning = false

        fun isServiceRunning(): Boolean = isRunning
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "========== ForegroundService onCreate ==========")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "========== onStartCommand called ==========")

        try {
            // Immediately enter foreground mode
            startForegroundWithType()
            isRunning = true

            Log.i(TAG, "Foreground service started successfully")
            Log.d(TAG, "   Notification ID: $NOTIFICATION_ID")
            Log.d(TAG, "   Channel ID: $CHANNEL_ID")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            // Start failed, stop service
            stopSelf()
            isRunning = false
        }

        return START_STICKY  // System will auto-restart after killing
    }

    /**
     * Start foreground service with correct foregroundServiceType
     */
    private fun startForegroundWithType() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit foregroundServiceType
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            Log.d(TAG, "Started with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION (Android 14+)")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Started as foreground service (Android 10-13)")
        } else {
            // Android 9-
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Started as foreground service (Android 9-)")
        }
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "S4Claw requires foreground service to maintain screen recording permission"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(): Notification {
        // Click notification to open permission management page
        val notificationIntent = Intent(this, PermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S4Claw Screen Recording Service")
            .setContentText("Maintaining screen recording permission - tap to view status")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(true)
            .setOngoing(true)  // Cannot be swiped away
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null  // Binding not supported
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.w(TAG, "========== ForegroundService onDestroy ==========")
        Log.w(TAG, "   Service stopped - MediaProjection may be invalidated")
    }
}
