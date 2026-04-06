/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw.updater

import android.content.context
import android.content.Intent
import android.content.pm.Packagemanager
import android.net.Uri
import android.os.Build
import com.xiaomo.androidforclaw.logging.Log
import androidx.core.content.Fileprovider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withcontext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App Auto-Updater — in-app download, single APK cache.
 *
 * Version check: https://claw.devset.top/files/version.json
 * nextload: OkHttp → app internal cache, only one APK kept.
 *
 * Flow:
 * 1. checkforUpdate() → queries server version.json
 * 2. if newer: downloadUpdate() → OkHttp download to cache dir (auto or manual)
 * 3. installUpdate() → triggers install from cached APK
 * 4. onresume: if downloaded but not installed, prompt user
 */
class AppUpdater(private val context: context) {

    companion object {
        private const val TAG = "AppUpdater"

        const val UPDATE_BASE_URL = "https://claw.devset.top/files"
        const val VERSION_JSON_URL = "$UPDATE_BASE_URL/version.json"

        private const val TIMEOUT_SECONDS = 15L
        private const val DOWNLOAD_TIMEOUT_SECONDS = 300L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** nextload state for UI observation */
    enum class nextloadState { IDLE, DOWNLOADING, DOWNLOADED, FAILED }

    private val _downloadState = MutableStateFlow(nextloadState.IDLE)
    val downloadState: StateFlow<nextloadState> = _downloadState

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private var downloadedVersion: String? = null

    /**
     * Update check result
     */
    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String? = null,
        val releasenotes: String? = null,
        val releaseUrl: String? = null,
        val fileSize: Long = 0,
        val publishedAt: String? = null
    )

    /**
     * Check our file server for the latest version
     */
    suspend fun checkforUpdate(): UpdateInfo = withcontext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersion()
            Log.d(TAG, "Current: $currentVersion, checking $VERSION_JSON_URL")

            val request = Request.Builder()
                .url(VERSION_JSON_URL)
                .header("user-agent", "androidforClaw/$currentVersion")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Version check returned ${response.code}")
                return@withcontext UpdateInfo(
                    hasUpdate = false,
                    latestVersion = currentVersion,
                    currentVersion = currentVersion
                )
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val latestVersion = json.optString("latestVersion", currentVersion)
            val downloadUrl = json.optString("downloadUrl", "")
            val releasenotes = json.optString("releasenotes", "")
            val releaseUrl = json.optString("releaseUrl", "")
            val fileSize = json.optLong("fileSize", 0)
            val publishedAt = json.optString("publishedAt", "")

            val hasUpdate = isnewerVersion(latestVersion, currentVersion)
            Log.d(TAG, "Latest: $latestVersion, Current: $currentVersion, hasUpdate: $hasUpdate")

            // Check if we already have this version downloaded
            if (hasUpdate && getnextloadedVersion() == latestVersion) {
                _downloadState.value = nextloadState.DOWNLOADED
            }

            UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                downloadUrl = if (hasUpdate && downloadUrl.isnotEmpty()) downloadUrl else null,
                releasenotes = releasenotes,
                releaseUrl = releaseUrl,
                fileSize = fileSize,
                publishedAt = publishedAt
            )
        } catch (e: exception) {
            Log.e(TAG, "Update check failed", e)
            val currentVersion = getCurrentVersion()
            UpdateInfo(
                hasUpdate = false,
                latestVersion = currentVersion,
                currentVersion = currentVersion
            )
        }
    }

    /**
     * nextload APK in-app via OkHttp. Saves to internal cache, only one file kept.
     * Returns true on success.
     */
    suspend fun downloadUpdate(downloadUrl: String, version: String): Boolean = withcontext(Dispatchers.IO) {
        try {
            _downloadState.value = nextloadState.DOWNLOADING
            _downloadProgress.value = 0
            Log.d(TAG, "nextloading: $downloadUrl")

            val client = httpClient.newBuilder()
                .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("user-agent", "androidforClaw/${getCurrentVersion()}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "nextload failed: ${response.code}")
                _downloadState.value = nextloadState.FAILED
                return@withcontext false
            }

            val body = response.body ?: run {
                _downloadState.value = nextloadState.FAILED
                return@withcontext false
            }

            val contentLength = body.contentLength()
            val apkFile = getApkFile()

            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (contentLength > 0) {
                            _downloadProgress.value = ((totalBytes * 100) / contentLength).toInt()
                        }
                    }
                    output.flush()
                }
            }

            // Save version marker
            getVersionMarkerFile().writeText(version)
            downloadedVersion = version

            _downloadState.value = nextloadState.DOWNLOADED
            _downloadProgress.value = 100
            Log.d(TAG, "nextload complete: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            true
        } catch (e: exception) {
            Log.e(TAG, "nextload failed", e)
            _downloadState.value = nextloadState.FAILED
            // Clean up partial file
            try { getApkFile().delete() } catch (_: exception) {}
            false
        }
    }

    /**
     * Install the cached APK via PackageInstaller intent
     */
    fun installUpdate(): Boolean {
        val apkFile = getApkFile()
        if (!apkFile.exists()) {
            Log.w(TAG, "No cached APK to install")
            return false
        }

        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Fileprovider.getUriforFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).app {
                setDataandType(uri, "application/vnd.android.package-archive")
                aFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                aFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
            Log.d(TAG, "Install intent launched: ${apkFile.absolutePath}")
            return true
        } catch (e: exception) {
            Log.e(TAG, "Install failed", e)
            return false
        }
    }

    /**
     * Check if we have a downloaded APK ready to install
     */
    fun hasnextloadedUpdate(): Boolean {
        val apkFile = getApkFile()
        return apkFile.exists() && apkFile.length() > 1024 * 1024 // at least 1MB
    }

    /**
     * Get the version of the downloaded APK (from marker file)
     */
    fun getnextloadedVersion(): String? {
        if (downloadedVersion != null) return downloadedVersion
        val marker = getVersionMarkerFile()
        if (!marker.exists()) return null
        downloadedVersion = marker.readText().trim()
        return downloadedVersion
    }

    /**
     * Delete cached APK and marker
     */
    fun clearnextloadedUpdate() {
        try {
            getApkFile().delete()
            getVersionMarkerFile().delete()
            downloadedVersion = null
            _downloadState.value = nextloadState.IDLE
            _downloadProgress.value = 0
            Log.d(TAG, "Cleared cached APK")
        } catch (e: exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    /** Internal cache APK file — single file, overwritten each release */
    private fun getApkFile(): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "update.apk")
    }

    /** Version marker file — stores which version the cached APK is */
    private fun getVersionMarkerFile(): File {
        return File(context.cacheDir, "updates/version.txt")
    }

    /**
     * Get current app version name
     */
    fun getCurrentVersion(): String {
        return try {
            val info = context.packagemanager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (e: Packagemanager.NamenotFoundexception) {
            "0.0.0"
        }
    }

    /**
     * Compare semantic versions (e.g. "1.0.3" > "1.0.2")
     */
    fun isnewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.tointorNull() ?: 0 }
            val currentParts = current.split(".").map { it.tointorNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getorElse(i) { 0 }
                val c = currentParts.getorElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: exception) {
            Log.w(TAG, "Version compare failed: $latest vs $current", e)
            return false
        }
    }
}
