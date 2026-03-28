/**
 * OpenClaw Source Reference:
 * - @tencent-weixin/openclaw-weixin/src/cdn/download.ts
 *
 * CDN media download with AES-128-ECB decryption.
 * WeChat CDN files are encrypted; the aes_key is base64-encoded in the message.
 */
package com.xiaomo.weixin.cdn

import android.util.Base64
import android.util.Log
import com.xiaomo.weixin.WeixinConfig
import com.xiaomo.weixin.api.CDNMedia
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object WeixinCdnDownloader {
    private const val TAG = "WeixinCdnDownloader"
    private const val TRANSFORMATION = "AES/ECB/NoPadding"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Download a media file from CDN, decrypt it, and save to a temp file.
     *
     * @param cdnBaseUrl CDN base URL (e.g. "https://novac2c.cdn.weixin.qq.com/c2c")
     * @param media The CDNMedia from the message item
     * @param fileExtension File extension for the output (e.g. "jpg", "png", "mp3", "silk")
     * @return Decrypted file, or null on failure
     */
    fun downloadAndDecrypt(
        cdnBaseUrl: String = WeixinConfig.DEFAULT_CDN_BASE_URL,
        media: CDNMedia,
        fileExtension: String = "bin",
    ): File? {
        val aesKeyBase64 = media.aesKey
        val encryptQueryParam = media.encryptQueryParam

        if (aesKeyBase64.isNullOrBlank() || encryptQueryParam.isNullOrBlank()) {
            Log.w(TAG, "Missing aes_key or encrypt_query_param")
            return null
        }

        // Decode AES key (base64)
        val aesKey: ByteArray
        try {
            aesKey = Base64.decode(aesKeyBase64, Base64.NO_WRAP)
            if (aesKey.size != 16) {
                Log.e(TAG, "AES key must be 16 bytes, got ${aesKey.size}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode AES key", e)
            return null
        }

        // Construct CDN URL
        val url = "$cdnBaseUrl/$encryptQueryParam"
        Log.d(TAG, "Downloading from: $url")

        // Download encrypted data
        val encrypted: ByteArray
        try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "CDN download failed: ${response.code}")
                return null
            }
            encrypted = response.body?.bytes() ?: return null
            Log.d(TAG, "Downloaded ${encrypted.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "CDN download error", e)
            return null
        }

        // Decrypt with AES-128-ECB
        val decrypted: ByteArray
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = SecretKeySpec(aesKey, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            decrypted = cipher.doFinal(encrypted)
            Log.d(TAG, "Decrypted to ${decrypted.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "AES decryption failed", e)
            return null
        }

        // Save to temp file
        return try {
            val tempFile = File.createTempFile("weixin_cdn_", ".$fileExtension")
            tempFile.writeBytes(decrypted)
            Log.i(TAG, "Saved decrypted file: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file", e)
            null
        }
    }
}
