package com.xiaomo.androidforclaw.config

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validate BuiltInKeyProvider 能正确DecryptInside置 OpenRouter Key
 */
@RunWith(AndroidJUnit4::class)
class BuiltInKeyProviderTest {

    @Test
    fun getKey_returnsNonNullValidKey() {
        val key = BuiltInKeyProvider.getKey()
        assertNotNull("Inside置 Key 不应为 null", key)
        assertTrue("Inside置 Key 应以 sk-or- 开头", key!!.startsWith("sk-or-"))
    }

    @Test
    fun encryptAndDecrypt_roundtrip() {
        val testKey = "sk-or-v1-test-roundtrip-key-12345"
        val encrypted = BuiltInKeyProvider.encrypt(testKey)

        // EncryptBack的Result不Equals原文
        assertNotEquals(testKey, encrypted)

        // DecryptBack得到原文
        val data = Base64.decode(encrypted, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(
            "AndroidForClawKeyProviderSecret!".toByteArray(Charsets.UTF_8), "AES"
        )
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decrypted = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        assertEquals("加Decrypt roundtrip 应一致", testKey, decrypted)
    }
}
