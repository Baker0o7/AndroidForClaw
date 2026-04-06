/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: Android service layer.
 */
package info.plateaukao.einkbro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.browser.control.server.SimpleBrowserHttpServer

/**
 * BrowserForClaw HTTP API Front台Service
 *
 * Ensure HTTP Server (端口 58765) 持续Run在Back台
 * even ifapply在Back台或被系统Recycle,API 仍可Response
 */
class BrowserApiService : Service() {

    companion object {
        private const val TAG = "BrowserApiService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "browser_api_channel"
        private const val PORT = 58765

        fun start(context: Context) {
            val intent = Intent(context, BrowserApiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Starting BrowserApiService...")
        }

        fun stop(context: Context) {
            val intent = Intent(context, BrowserApiService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Stopping BrowserApiService...")
        }
    }

    private var httpServer: SimpleBrowserHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // CreateNotification渠道
        createNotificationChannel()

        // StartFront台ServiceNotification
        startForeground(NOTIFICATION_ID, createNotification())

        // Start HTTP Server
        startHttpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY // Service被杀BackAutoRestart
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopHttpServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Browser API Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BrowserForClaw HTTP API running"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // clickNotificationOpenMain Activity
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BrowserForClaw API")
            .setContentText("HTTP API running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // use系统Icon
            .setOngoing(true) // 不可swipeDelete
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startHttpServer() {
        try {
            if (httpServer == null) {
                httpServer = SimpleBrowserHttpServer(PORT)
                httpServer?.start()
                Log.i(TAG, "✅ HTTP Server started on port $PORT")
            } else {
                Log.w(TAG, "HTTP Server already running")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start HTTP Server", e)
        }
    }

    private fun stopHttpServer() {
        try {
            httpServer?.stop()
            httpServer = null
            Log.i(TAG, "✅ HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop HTTP Server", e)
        }
    }
}
