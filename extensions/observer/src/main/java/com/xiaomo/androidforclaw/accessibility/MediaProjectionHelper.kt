package com.xiaomo.androidforclaw.accessibility

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/gateway/(all)
 *
 * AndroidForClaw adaptation: observer permission and projection flow.
 */


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection Screen Recording Permission Manager (Refactored)
 *
 * Key improvements:
 * 1. Proper lifecycle management
 * 2. Automatic foreground service startup
 * 3. Comprehensive callback handling
 * 4. Error recovery mechanism
 * 5. Thread safety
 */
object MediaProjectionHelper {
    private const val TAG = "MediaProjectionHelper"
    private const val REQUEST_CODE = 10086

    // Status constants
    const val STATUS_NOT_INITIALIZED = "Not initialized"
    const val STATUS_WAITING_PERMISSION = "Waiting for authorization"
    const val STATUS_AUTHORIZED = "Authorized"
    const val STATUS_ERROR = "Error occurred"

    // State management
    private enum class State {
        IDLE,           // Idle
        REQUESTING,     // Requesting permission
        AUTHORIZED,     // Authorized
        ERROR           // Error state
    }

    @Volatile
    private var currentState = State.IDLE

    // MediaProjection related
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Screen parameters
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Context and configuration
    private var appContext: Context? = null
    private var screenshotDir: File? = null

    // Foreground service status
    private val isForegroundServiceRunning = AtomicBoolean(false)

    // Main thread Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize - must be called before use
     */
    fun initialize(context: Context, screenshotDirectory: File) {
        appContext = context.applicationContext
        screenshotDir = screenshotDirectory

        if (!screenshotDirectory.exists()) {
            screenshotDirectory.mkdirs()
        }

        // Set directory permissions
        try {
            screenshotDirectory.setReadable(true, false)
            screenshotDirectory.setWritable(true, false)
            screenshotDirectory.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set directory permissions", e)
        }

        // Get screen parameters
        updateScreenParameters(context)

        Log.i(TAG, "✅ MediaProjectionHelper initialized")
        Log.d(TAG, "   Screenshot dir: ${screenshotDirectory.absolutePath}")
        Log.d(TAG, "   Screen: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
    }

    /**
     * Update screen parameters
     */
    private fun updateScreenParameters(context: Context) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Service Context has no display, try to get one, use default display if failed
                val display = try {
                    context.display
                } catch (e: UnsupportedOperationException) {
                    Log.w(TAG, "Context.display not available (Service Context), using default display")
                    wm.defaultDisplay
                }
                display?.getRealMetrics(displayMetrics)
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(displayMetrics)
            }

            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi

            Log.d(TAG, "Screen parameters updated: ${screenWidth}x${screenHeight} @${screenDensity}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update screen parameters, using defaults", e)
            // Use common default values
            screenWidth = 1080
            screenHeight = 2400
            screenDensity = 480
        }
    }

    /**
     * Request screen recording permission
     *
     * @return false means waiting for user authorization, true means already authorized
     */
    fun requestPermission(activity: Activity): Boolean {
        Log.d(TAG, "requestPermission called, current state: $currentState")

        // If already authorized, return directly
        if (currentState == State.AUTHORIZED && mediaProjection != null) {
            Log.d(TAG, "Already authorized")
            return true
        }

        // Mark state as requesting
        currentState = State.REQUESTING

        try {
            // Start foreground service (enter foreground mode immediately to avoid ANR)
            startForegroundService(activity)

            // Request MediaProjection permission
            val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = manager.createScreenCaptureIntent()
            activity.startActivityForResult(intent, REQUEST_CODE)

            Log.i(TAG, "📋 Screen capture permission request started")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to request permission", e)
            currentState = State.ERROR
            stopForegroundService(activity)
            return false
        }
    }

    /**
     * Handle permission grant result
     * Must be called in Activity.onActivityResult
     */
    fun handlePermissionResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) {
            return false
        }

        Log.d(TAG, "handlePermissionResult: resultCode=$resultCode")

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                // 创建 MediaProjection
                val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(resultCode, data)

                if (mediaProjection == null) {
                    Log.e(TAG, "❌ MediaProjection is null despite RESULT_OK")
                    currentState = State.ERROR
                    stopForegroundService(activity)
                    return false
                }

                // 设置回调
                setupMediaProjectionCallbacks()

                // 初始化 ImageReader
                initializeImageReader()

                // 标记为已授权
                currentState = State.AUTHORIZED
                isForegroundServiceRunning.set(true)

                Log.i(TAG, "✅ MediaProjection permission granted successfully")
                Log.d(TAG, "   Foreground service: running")
                Log.d(TAG, "   ImageReader: ${imageReader != null}")
                return true

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to setup MediaProjection", e)
                currentState = State.ERROR
                cleanup()
                stopForegroundService(activity)
                return false
            }
        } else {
            Log.w(TAG, "⚠️ Permission denied by user")
            currentState = State.IDLE
            stopForegroundService(activity)
            return false
        }
    }

    /**
     * Set up MediaProjection callbacks
     */
    private fun setupMediaProjectionCallbacks() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.w(TAG, "⚠️ MediaProjection stopped by system")
                mainHandler.post {
                    currentState = State.IDLE
                    cleanup()
                }
            }
        }, mainHandler)
    }

    /**
     * Initialize ImageReader
     */
    private fun initializeImageReader() {
        try {
            // Close old ImageReader
            imageReader?.close()
            virtualDisplay?.release()

            // Create new ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2  // Buffer count
            )

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            Log.d(TAG, "✅ ImageReader and VirtualDisplay initialized")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ImageReader", e)
            throw e
        }
    }

    /**
     * Capture screen screenshot
     *
     * @return Pair<Bitmap, String> or null (on failure)
     */
    fun captureScreen(): Pair<Bitmap, String>? {
        if (currentState != State.AUTHORIZED || mediaProjection == null || imageReader == null) {
            Log.w(TAG, "Cannot capture: not authorized (state=$currentState, projection=${mediaProjection != null}, reader=${imageReader != null})")
            return null
        }

        try {
            // Get latest Image
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "Failed to acquire image")
                return null
            }

            try {
                // Create Bitmap from Image
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop padding
                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                } else {
                    bitmap
                }

                // Save to file
                val timestamp = System.currentTimeMillis()
                val file = File(screenshotDir, "screenshot_$timestamp.png")
                FileOutputStream(file).use { out ->
                    croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                Log.i(TAG, "✅ Screenshot captured: ${file.absolutePath}")
                return Pair(croppedBitmap, file.absolutePath)

            } finally {
                image.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to capture screen", e)
            return null
        }
    }

    /**
     * Check if permission has been granted
     */
    fun isAuthorized(): Boolean {
        return currentState == State.AUTHORIZED && mediaProjection != null
    }

    /**
     * Get detailed status
     */
    fun getDetailedStatus(): String {
        return when (currentState) {
            State.IDLE -> STATUS_NOT_INITIALIZED
            State.REQUESTING -> STATUS_WAITING_PERMISSION
            State.AUTHORIZED -> {
                if (mediaProjection != null && imageReader != null) {
                    "$STATUS_AUTHORIZED\nForeground service: ${if (isForegroundServiceRunning.get()) "Running" else "Stopped"}"
                } else {
                    "$STATUS_ERROR: Objects not initialized"
                }
            }
            State.ERROR -> STATUS_ERROR
        }
    }

    /**
     * Reset permission (release resources but keep foreground service)
     */
    fun reset() {
        Log.i(TAG, "Resetting MediaProjection...")
        currentState = State.IDLE
        cleanup()
    }

    /**
     * Release completely (including stopping foreground service)
     */
    fun releaseCompletely(context: Context) {
        Log.i(TAG, "Releasing MediaProjection completely...")
        currentState = State.IDLE
        cleanup()
        stopForegroundService(context)
    }

    /**
     * Clean up resources
     */
    private fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            mediaProjection?.stop()
            mediaProjection = null

            Log.d(TAG, "✅ Resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Start foreground service
     */
    private fun startForegroundService(context: Context) {
        try {
            val intent = Intent(context, ObserverForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service", e)
        }
    }

    /**
     * Stop foreground service
     */
    private fun stopForegroundService(context: Context) {
        try {
            val intent = Intent(context, ObserverForegroundService::class.java)
            context.stopService(intent)
            isForegroundServiceRunning.set(false)
            Log.i(TAG, "✅ Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }
    }

    // ========== Legacy API for backward compatibility (deprecated) ==========

    @Deprecated("Use initialize() instead", ReplaceWith("initialize(context, screenshotDirectory)"))
    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    @Deprecated("Use initialize() instead", ReplaceWith("initialize(context, screenshotDirectory)"))
    fun setScreenshotDirectory(dir: File) {
        screenshotDir = dir
        if (!dir.exists()) dir.mkdirs()
    }

    @Deprecated("Use requestPermission() instead", ReplaceWith("requestPermission(activity)"))
    fun requestMediaProjection(activity: Activity): Boolean {
        return requestPermission(activity)
    }

    @Deprecated("Use handlePermissionResult() instead")
    fun handleActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return handlePermissionResult(activity, requestCode, resultCode, data)
    }

    @Deprecated("Use isAuthorized() instead", ReplaceWith("isAuthorized()"))
    fun isMediaProjectionGranted(): Boolean {
        return isAuthorized()
    }

    @Deprecated("Use getDetailedStatus() instead", ReplaceWith("getDetailedStatus()"))
    fun getPermissionStatus(): String {
        return getDetailedStatus()
    }

    @Deprecated("Use reset() instead", ReplaceWith("reset()"))
    fun resetPermission() {
        reset()
    }

    @Deprecated("Use releaseCompletely() instead", ReplaceWith("releaseCompletely(context)"))
    fun releasePermissionCompletely(context: Context) {
        releaseCompletely(context)
    }
}
