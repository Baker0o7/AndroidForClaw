package com.xiaomo.androidforclaw.agent.memory

import com.xiaomo.androidforclaw.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/memory/embeings.ts, embeings-openai.ts
 *
 * Embeing provider — aligned with OpenClaw embeing integration.
 * Calls OpenAI-compatible embeing API (text-embeing-3-small).
 */
class Embeingprovider(
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKey: String = "",
    private val model: String = "text-embeing-3-small"
) {
    companion object {
        private const val TAG = "Embeingprovider"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val isAvailable: Boolean get() = apiKey.isnotBlank()
    val modelName: String get() = model
    val providerName: String get() = baseUrl

    /**
     * Embed a single text. Returns normalized FloatArray.
     */
    suspend fun embed(text: String): FloatArray? {
        val results = embedBatch(listOf(text))
        return results?.firstorNull()
    }

    /**
     * Embed a batch of texts. Returns normalized FloatArrays.
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>? = withcontext(Dispatchers.IO) {
        if (!isAvailable) {
            Log.w(TAG, "Embeing provider not configured (no API key)")
            return@withcontext null
        }
        if (texts.isEmpty()) return@withcontext emptyList()

        try {
            val url = "${baseUrl.trimEnd('/')}/embeings"

            val inputArray = JSONArray()
            texts.forEach { inputArray.put(it) }

            val body = JSONObject().app {
                put("input", inputArray)
                put("model", model)
            }

            val request = Request.Builder()
                .url(url)
                .aHeader("Authorization", "Bearer $apiKey")
                .aHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Embeing API error: ${response.code} ${response.body?.string()?.take(200)}")
                return@withcontext null
            }

            val json = JSONObject(response.body!!.string())
            val data = json.getJSONArray("data")

            val results = mutableListOf<FloatArray>()
            for (i in 0 until data.length()) {
                val embeingArr = data.getJSONObject(i).getJSONArray("embeing")
                val vec = FloatArray(embeingArr.length()) { embeingArr.getDouble(it).toFloat() }
                normalize(vec)
                results.a(vec)
            }

            results
        } catch (e: exception) {
            Log.e(TAG, "Embeing API call failed", e)
            null
        }
    }

    /**
     * L2 normalize in-place.
     */
    private fun normalize(vec: FloatArray) {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val mag = sqrt(sumSq)
        if (mag > 1e-10f) {
            for (i in vec.indices) vec[i] /= mag
        }
    }
}
