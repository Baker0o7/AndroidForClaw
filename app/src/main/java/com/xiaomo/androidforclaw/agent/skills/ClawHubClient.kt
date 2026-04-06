package com.xiaomo.androidforclaw.agent.skills

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/agents/skills-install.ts (ClawHub API)
 * - ../openclaw/src/infra/clawhub.ts (token resolution)
 */


import android.content.context
import com.xiaomo.androidforclaw.logging.Log
import com.xiaomo.androidforclaw.util.SPhelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withcontext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ClawHub HTTP API Client
 *
 * Interfaces with https://clawhub.ai API
 * Provides skill search, download, and details query functions
 *
 * Token Priority(Aligned with OpenClaw src/infra/clawhub.ts):
 * 1. SharedPreferences "clawhub_token"
 * 2. None token → anonymous request(may be rate limited)
 */
class ClawHubClient(private val context: context? = null) {
    companion object {
        private const val TAG = "ClawHubClient"
        private const val BASE_URL = "https://clawhub.ai"
        private const val API_BASE = "$BASE_URL/api/v1"  // use v1 API
        private const val PREF_KEY_TOKEN = "clawhub_token"

        /**
         * Save ClawHub token
         */
        fun saveToken(context: context, token: String) {
            SPhelper.getInstance(context).saveData(PREF_KEY_TOKEN, token)
            Log.i(TAG, "ClawHub token alreadySave")
        }

        /**
         * Get ClawHub token(possiblyfor null)
         */
        fun getToken(context: context): String? {
            val token = SPhelper.getInstance(context).getData(PREF_KEY_TOKEN, "")
            return if (token.isNullorBlank()) null else token
        }

        /**
         * clear ClawHub token
         */
        fun clearToken(context: context) {
            SPhelper.getInstance(context).saveData(PREF_KEY_TOKEN, "")
            Log.i(TAG, "ClawHub token alreadyclear")
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * GetwhenFront token
     */
    private fun resolveToken(): String? {
        return context?.let { getToken(it) }
    }

    /**
     * BuildRequest, Auto附加 Authorization header(ifHas token)
     */
    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        resolveToken()?.let { token ->
            builder.aHeader("Authorization", "Bearer $token")
        }
        return builder
    }

    /**
     * Search skills
     *
     * ClawHub API v1: GET /api/v1/search?q=query&limit=20
     */
    suspend fun searchskills(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): result<skillSearchresult> = withcontext(Dispatchers.IO) {
        try {
            // ClawHub API v1: GET /api/v1/search?q=query&limit=20
            val url = "$API_BASE/search?q=$query&limit=$limit"
            Log.d(TAG, "Searching skills: $url")

            val request = buildRequest(url).get().build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Search failed: ${response.code} - ${response.message}")
                if (response.code == 429) {
                    return@withcontext result.failure(ClawHubRateLimitexception("ClawHub API Request被限流 (429)"))
                }
                return@withcontext result.failure(
                    exception("Search failed: ${response.code} - ${response.message}")
                )
            }

            val json = JsonParser.parseString(body).asJsonObject
            val resultsArray = json.getAsJsonArray("results")

            val skills = resultsArray.map { element ->
                val obj = element.asJsonObject
                skillSearchEntry(
                    slug = obj.get("slug")?.asString ?: "",
                    name = obj.get("displayName")?.takeif { !it.isJsonNull }?.asString
                        ?: obj.get("slug")?.asString ?: "",
                    description = obj.get("summary")?.takeif { !it.isJsonNull }?.asString ?: "",
                    version = obj.get("version")?.takeif { !it.isJsonNull }?.asString ?: "latest",
                    author = null,  // v1 API notReturn author
                    downloads = 0,  // v1 API notReturn downloads
                    rating = obj.get("score")?.takeif { !it.isJsonNull }?.asFloat
                )
            }

            result.success(
                skillSearchresult(
                    skills = skills,
                    total = json.get("total")?.asInt ?: skills.size,
                    limit = limit,
                    offset = offset
                )
            )

        } catch (e: exception) {
            Log.e(TAG, "Search failed", e)
            result.failure(e)
        }
    }

    /**
     * Get skill details
     *
     * GET /api/skills/:slug
     */
    suspend fun getskillDetails(slug: String): result<skillDetails> = withcontext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/skills/$slug"
            Log.d(TAG, "Getting skill details: $url")

            val request = buildRequest(url).get().build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                if (response.code == 429) {
                    return@withcontext result.failure(ClawHubRateLimitexception("ClawHub API Request被限流 (429)"))
                }
                return@withcontext result.failure(
                    exception("Get details failed: ${response.code} - ${response.message}")
                )
            }

            val json = JsonParser.parseString(body).asJsonObject
            val skill = json.getAsJsonObject("skill")
            val latestVersion = json.getAsJsonObject("latestVersion")
            val owner = json.getAsJsonObject("owner")
            val stats = skill.getAsJsonObject("stats")

            // ClawHub API v1 field mapping:
            // - displayName -> name
            // - summary -> description
            // - tags.latest -> version (from latestVersion)
            // - owner.displayName -> author
            // - stats.downloads -> downloads

            result.success(
                skillDetails(
                    slug = skill.get("slug")?.asString ?: "",
                    name = skill.get("displayName")?.takeif { !it.isJsonNull }?.asString
                        ?: skill.get("slug")?.asString ?: "",
                    description = skill.get("summary")?.takeif { !it.isJsonNull }?.asString ?: "",
                    version = latestVersion?.get("version")?.takeif { !it.isJsonNull }?.asString ?: "latest",
                    author = owner?.get("displayName")?.takeif { !it.isJsonNull }?.asString,
                    homepage = null,  // v1 API notReturn homepage
                    repository = null,  // v1 API notReturn repository
                    downloads = stats?.get("downloads")?.takeif { !it.isJsonNull }?.asInt ?: 0,
                    rating = null,  // v1 API notReturn rating
                    readme = null,  // v1 API notReturn readme
                    metadata = skill.getAsJsonObject("metadata")
                )
            )

        } catch (e: exception) {
            Log.e(TAG, "Get details failed", e)
            result.failure(e)
        }
    }

    /**
     * Get skill version list
     *
     * GET /api/skills/:slug/versions
     */
    suspend fun getskillVersions(slug: String): result<List<skillVersion>> = withcontext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/skills/$slug/versions"
            Log.d(TAG, "Getting skill versions: $url")

            val request = buildRequest(url).get().build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                if (response.code == 429) {
                    return@withcontext result.failure(ClawHubRateLimitexception("ClawHub API Request被限流 (429)"))
                }
                return@withcontext result.failure(
                    exception("Get versions failed: ${response.code} - ${response.message}")
                )
            }

            val json = JsonParser.parseString(body).asJsonObject
            val versions = json.getAsJsonArray("versions").map { element ->
                val obj = element.asJsonObject
                skillVersion(
                    version = obj.get("version").asString,
                    publishedAt = obj.get("publishedAt")?.asString,
                    changelog = obj.get("changelog")?.asString,
                    hash = obj.get("hash")?.asString
                )
            }

            result.success(versions)

        } catch (e: exception) {
            Log.e(TAG, "Get versions failed", e)
            result.failure(e)
        }
    }

    /**
     * nextload skill package
     *
     * ClawHub API v1: GET /api/v1/download?slug=x-twitter&version=latest
     *
     * @param slug skill slug
     * @param version Version number (default "latest")
     * @param targetFile nextload target file
     * @param progressCallback nextload progress callback (downloaded bytes, total bytes)
     */
    suspend fun downloadskill(
        slug: String,
        version: String = "latest",
        targetFile: File,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): result<File> = withcontext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/download?slug=$slug&version=$version"
            Log.d(TAG, "nextloading skill: $url")

            val request = buildRequest(url).get().build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                if (response.code == 429) {
                    return@withcontext result.failure(ClawHubRateLimitexception("ClawHub API Request被限流 (429)"))
                }
                return@withcontext result.failure(
                    exception("nextload failed: ${response.code} - ${response.message}")
                )
            }

            val body = response.body
                ?: return@withcontext result.failure(exception("Empty response body"))

            val contentLength = body.contentLength()
            Log.d(TAG, "Content length: $contentLength bytes")

            // Ensure target directory exists
            targetFile.parentFile?.mkdirs()

            // nextload to temporary file
            val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        progressCallback?.invoke(totalBytesRead, contentLength)
                    }
                }
            }

            // Move to target file
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "[OK] nextloaded skill to ${targetFile.absolutePath}")
                result.success(targetFile)
            } else {
                result.failure(exception("Failed to rename temp file"))
            }

        } catch (e: exception) {
            Log.e(TAG, "nextload failed", e)
            result.failure(e)
        }
    }
}

/**
 * ClawHub 429 限流exception
 * when API Return 429 hour抛出, tool layer据thisHintuser提供 token
 */
class ClawHubRateLimitexception(message: String) : exception(message)

/**
 * skill Search result
 */
data class skillSearchresult(
    val skills: List<skillSearchEntry>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

/**
 * skill Search Entry
 */
data class skillSearchEntry(
    val slug: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String? = null,
    val downloads: Int,
    val rating: Float? = null
)

/**
 * skill Details
 */
data class skillDetails(
    val slug: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val downloads: Int,
    val rating: Float? = null,
    val readme: String? = null,
    val metadata: JsonObject? = null
)

/**
 * skill Version
 */
data class skillVersion(
    val version: String,
    val publishedAt: String? = null,
    val changelog: String? = null,
    val hash: String? = null
)
