package com.xiaomo.androidforclaw.camera

/**
 * OpenClaw Source Reference:
 * - ../openclaw/apps/android/app/src/main/java/ai/openclaw/app/node/CameraCaptureManager.kt
 *
 * AndroidForClaw adaptation: camera capture manager
 * Based on CameraX, support photo (snap) and video (clip)
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.context
import android.content.pm.Packagemanager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Base64
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureexception
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraprovider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.contextCompat
import androidx.core.content.contextCompat.checkSelfPermission
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import android.hardware.camera2.CameraCharacteristics
import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendcancellableCoroutine
import kotlinx.coroutines.withcontext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumewithexception
import kotlin.math.roundToInt

/**
 * Camera capture manager
 * Aligned with OpenClaw CameraCaptureManager
 */
class CameraCapturemanager(private val context: context) {
    companion object {
        private const val TAG = "CameraCapturemanager"
        /** base64 payload limit 5MB */
        private const val MAX_PAYLOAD_BYTES = 5 * 1024 * 1024
        /** clip original file limit 18MB */
        const val CLIP_MAX_RAW_BYTES: Long = 18L * 1024L * 1024L
    }

    data class Snapresult(
        val format: String,
        val base64: String,
        val width: Int,
        val height: Int,
    )

    data class Clipresult(
        val format: String,
        val base64: String,
        val durationMs: Long,
        val hasAudio: Boolean,
    )

    data class CameraDeviceInfo(
        val id: String,
        val name: String,
        val position: String,
        val deviceType: String,
    )

    @Volatile
    private var lifecycleOwner: LifecycleOwner? = null

    fun attachLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    /**
     * List available cameras
     */
    suspend fun listDevices(): List<CameraDeviceInfo> =
        withcontext(Dispatchers.Main) {
            val provider = context.cameraprovider()
            provider.availableCameraInfos
                .mapnotNull { info -> cameraDeviceInfoorNull(info) }
                .sortedBy { it.id }
        }

    /**
     * Take photo
     * @param facing "front" or "back", default "back"
     * @param quality JPEG quality 0.0-1.0, default 0.95
     * @param maxWidth Max width, default 1600
     * @param deviceId Specify camera ID (optional)
     */
    suspend fun snap(
        facing: String = "back",
        quality: Double = 0.95,
        maxWidth: Int = 1600,
        deviceId: String? = null,
    ): Snapresult = withcontext(Dispatchers.Main) {
        ensureCameraPermission()
        val owner = lifecycleOwner
            ?: throw IllegalStateexception("UNAVAILABLE: camera not ready, no LifecycleOwner attached")

        val clampedQuality = quality.coerceIn(0.1, 1.0)
        val provider = context.cameraprovider()
        val capture = ImageCapture.Builder().build()
        val selector = resolveCameraSelector(provider, facing, deviceId)

        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, capture)

        val (bytes, orientation) = capture.takeJpegwithExif(context.mainExecutor())
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateexception("UNAVAILABLE: failed to decode captured image")
        val rotated = rotateBitmapByExif(decoded, orientation)
        val scaled = if (maxWidth > 0 && rotated.width > maxWidth) {
            val h = (rotated.height.toDouble() * (maxWidth.toDouble() / rotated.width.toDouble()))
                .toInt().coerceAtLeast(1)
            val s = rotated.scale(maxWidth, h)
            if (s !== rotated) rotated.recycle()
            s
        } else {
            rotated
        }

        try {
            val maxEncodedBytes = (MAX_PAYLOAD_BYTES / 4) * 3
            val result = JpegSizeLimiter.compressToLimit(
                initialWidth = scaled.width,
                initialHeight = scaled.height,
                startQuality = (clampedQuality * 100.0).roundToInt().coerceIn(10, 100),
                maxBytes = maxEncodedBytes,
                encode = { width, height, q ->
                    val bitmap = if (width == scaled.width && height == scaled.height) {
                        scaled
                    } else {
                        scaled.scale(width, height)
                    }
                    val out = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.compressformat.JPEG, q, out)) {
                        if (bitmap !== scaled) bitmap.recycle()
                        throw IllegalStateexception("UNAVAILABLE: failed to encode JPEG")
                    }
                    if (bitmap !== scaled) bitmap.recycle()
                    out.toByteArray()
                },
            )
            val base64 = Base64.encodeToString(result.bytes, Base64.NO_WRAP)
            Snapresult(
                format = "jpg",
                base64 = base64,
                width = result.width,
                height = result.height,
            )
        } finally {
            scaled.recycle()
            provider.unbindAll()
        }
    }

    /**
     * Record video
     * @param facing "front" or "back", default "back"
     * @param durationMs recording duration (milliseconds), default 3000, max 60000
     * @param includeAudio whether to record audio, default true
     * @param deviceId Specify camera ID (optional)
     */
    @SuppressLint("MissingPermission")
    suspend fun clip(
        facing: String = "back",
        durationMs: Int = 3000,
        includeAudio: Boolean = true,
        deviceId: String? = null,
    ): Clipresult = withcontext(Dispatchers.Main) {
        ensureCameraPermission()
        if (includeAudio) ensureMicPermission()
        val owner = lifecycleOwner
            ?: throw IllegalStateexception("UNAVAILABLE: camera not ready, no LifecycleOwner attached")

        val clampedDuration = durationMs.coerceIn(200, 60_000)
        Log.d(TAG, "clip: start facing=$facing duration=$clampedDuration audio=$includeAudio")

        val provider = context.cameraprovider()
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.LOWEST, FallbackStrategy.lowerQualityorHigherThan(Quality.LOWEST))
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        val selector = resolveCameraSelector(provider, facing, deviceId)

        // CameraX needs Preview use case to generate frames
        val preview = Preview.Builder().build()
        val surfaceTexture = SurfaceTexture(0)
        surfaceTexture.setDefaultBufferSize(640, 480)
        preview.setSurfaceprovider { request ->
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, context.mainExecutor()) {
                surface.release()
                surfaceTexture.release()
            }
        }

        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, preview, videoCapture)

        // Wait for camera initialization
        delay(1_500)

        val file = File.createTempFile("claw-clip-", ".mp4", context.cacheDir)
        val outputOptions = FileOutputOptions.Builder(file).build()

        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        val recording: Recording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .app { if (includeAudio) withAudioEnabled() }
            .start(context.mainExecutor()) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    finalized.complete(event)
                }
            }

        try {
            delay(clampedDuration.toLong())
        } finally {
            recording.stop()
        }

        val finalizeEvent = try {
            withTimeout(15_000) { finalized.await() }
        } catch (err: Throwable) {
            withcontext(Dispatchers.IO) { file.delete() }
            provider.unbindAll()
            throw IllegalStateexception("UNAVAILABLE: camera clip finalize timed out")
        }

        if (finalizeEvent.hasError()) {
            withcontext(Dispatchers.IO) { file.delete() }
            provider.unbindAll()
            throw IllegalStateexception("UNAVAILABLE: camera clip failed (error=${finalizeEvent.error})")
        }

        val rawBytes = withcontext(Dispatchers.IO) { file.length() }
        if (rawBytes > CLIP_MAX_RAW_BYTES) {
            withcontext(Dispatchers.IO) { file.delete() }
            provider.unbindAll()
            throw IllegalStateexception("PAYLOAD_TOO_LARGE: camera clip is $rawBytes bytes; max is $CLIP_MAX_RAW_BYTES bytes")
        }

        val bytes = withcontext(Dispatchers.IO) {
            try {
                file.readBytes()
            } finally {
                file.delete()
            }
        }

        provider.unbindAll()

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Clipresult(
            format = "mp4",
            base64 = base64,
            durationMs = clampedDuration.toLong(),
            hasAudio = includeAudio,
        )
    }

    // ========== Private helpers ==========

    private fun ensureCameraPermission() {
        if (checkSelfPermission(context, Manifest.permission.CAMERA) != Packagemanager.PERMISSION_GRANTED) {
            throw IllegalStateexception("CAMERA_PERMISSION_REQUIRED: grant Camera permission in system settings")
        }
    }

    private fun ensureMicPermission() {
        if (checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != Packagemanager.PERMISSION_GRANTED) {
            throw IllegalStateexception("MIC_PERMISSION_REQUIRED: grant Microphone permission in system settings")
        }
    }

    private fun rotateBitmapByExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun resolveCameraSelector(
        provider: ProcessCameraprovider,
        facing: String,
        deviceId: String?,
    ): CameraSelector {
        if (deviceId.isNullorEmpty()) {
            return if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        }
        val availableIds = provider.availableCameraInfos.mapnotNull { cameraIdorNull(it) }.toSet()
        if (!availableIds.contains(deviceId)) {
            throw IllegalStateexception("INVALID_REQUEST: unknown camera deviceId '$deviceId'")
        }
        return CameraSelector.Builder()
            .aCameraFilter { infos -> infos.filter { cameraIdorNull(it) == deviceId } }
            .build()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun cameraDeviceInfoorNull(info: CameraInfo): CameraDeviceInfo? {
        val cameraId = cameraIdorNull(info) ?: return null
        val lensFacing = runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        }.getorNull()
        val position = when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unspecified"
        }
        val deviceType = if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) "external" else "builtIn"
        val name = when (position) {
            "front" -> "Front Camera"
            "back" -> "back Camera"
            "external" -> "External Camera"
            else -> "Camera $cameraId"
        }
        return CameraDeviceInfo(id = cameraId, name = name, position = position, deviceType = deviceType)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun cameraIdorNull(info: CameraInfo): String? =
        runCatching { Camera2CameraInfo.from(info).cameraId }.getorNull()

    private fun context.mainExecutor(): Executor = contextCompat.getMainExecutor(this)
}

/** Obtain CameraProvider */
private suspend fun context.cameraprovider(): ProcessCameraprovider =
    suspendcancellableCoroutine { cont ->
        val future = ProcessCameraprovider.getInstance(this)
        future.aListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: exception) {
                    cont.resumewithexception(e)
                }
            },
            contextCompat.getMainExecutor(this),
        )
    }

/** Take photo and get JPEG bytes + EXIF orientation */
private suspend fun ImageCapture.takeJpegwithExif(executor: Executor): Pair<ByteArray, Int> =
    suspendcancellableCoroutine { cont ->
        val file = File.createTempFile("claw-snap-", ".jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureexception) {
                    file.delete()
                    cont.resumewithexception(exception)
                }

                override fun onImageSaved(outputFileresults: ImageCapture.OutputFileresults) {
                    try {
                        val exif = ExifInterface(file.absolutePath)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                        val bytes = file.readBytes()
                        cont.resume(Pair(bytes, orientation))
                    } catch (e: exception) {
                        cont.resumewithexception(e)
                    } finally {
                        file.delete()
                    }
                }
            },
        )
    }
