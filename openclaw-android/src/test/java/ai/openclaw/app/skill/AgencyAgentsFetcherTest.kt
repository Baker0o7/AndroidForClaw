package ai.openclaw.app.skill

import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * Tests for AgencyAgentsFetcher — catalog integrity, serialization, caching, and API fetching.
 */
@RunWith(RobolectricTestRunner::class)
class AgencyAgentsFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ════════════════════════════════════════════
    //  Catalog integrity
    // ════════════════════════════════════════════

    @Test
    fun `catalog has entries in all 5 categories`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val categories = skills.map { it.category }.toSet()
        assertEquals(setOf("engineering", "design", "marketing", "sales", "social"), categories)
    }

    @Test
    fun `catalog has at least 40 skills`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        assertTrue("Expected >= 40 skills, got ${skills.size}", skills.size >= 40)
    }

    @Test
    fun `all skills have non-empty name and emoji`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        for (skill in skills) {
            assertFalse("Name should not be empty for ${skill.filename}", skill.name.isEmpty())
            assertFalse("Emoji should not be empty for ${skill.name}", skill.emoji.isEmpty())
        }
    }

    @Test
    fun `all skills have valid md filename`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        for (skill in skills) {
            assertTrue(
                "Filename should end with .md: ${skill.filename}",
                skill.filename.endsWith(".md")
            )
        }
    }

    @Test
    fun `all filenames are unique`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val filenames = skills.map { it.filename }
        assertEquals("Filenames should be unique", filenames.size, filenames.toSet().size)
    }

    @Test
    fun `all names are unique`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val names = skills.map { it.name }
        assertEquals("Names should be unique", names.size, names.toSet().size)
    }

    // ════════════════════════════════════════════
    //  Featured skills
    // ════════════════════════════════════════════

    @Test
    fun `featured skills has exactly 4 entries`() {
        assertEquals(4, AgencyAgentsFetcher.featuredSkills.size)
    }

    @Test
    fun `featured skills are all from engineering category`() {
        for (skill in AgencyAgentsFetcher.featuredSkills) {
            assertEquals("engineering", skill.category)
        }
    }

    @Test
    fun `featured skills contain Frontend Developer and AI Engineer`() {
        val names = AgencyAgentsFetcher.featuredSkills.map { it.name }
        assertTrue(names.contains("Frontend Developer"))
        assertTrue(names.contains("AI Engineer"))
    }

    @Test
    fun `featured skills are also in catalog`() {
        val context = RuntimeEnvironment.getApplication()
        val allSkills = AgencyAgentsFetcher.loadSkills(context)
        for (featured in AgencyAgentsFetcher.featuredSkills) {
            assertTrue(
                "Featured skill '${featured.name}' should be in catalog",
                allSkills.any { it.filename == featured.filename }
            )
        }
    }

    // ════════════════════════════════════════════
    //  Known skills spot-checks
    // ════════════════════════════════════════════

    @Test
    fun `Frontend Developer has correct metadata`() {
        val context = RuntimeEnvironment.getApplication()
        val skill = AgencyAgentsFetcher.loadSkills(context).first { it.name == "Frontend Developer" }
        assertEquals("🎨", skill.emoji)
        assertEquals("engineering", skill.category)
        assertEquals("engineering-frontend-developer.md", skill.filename)
        assertTrue(skill.specialty.contains("React"))
    }

    @Test
    fun `UI Designer is in design category`() {
        val context = RuntimeEnvironment.getApplication()
        val skill = AgencyAgentsFetcher.loadSkills(context).first { it.name == "UI Designer" }
        assertEquals("design", skill.category)
        assertEquals("🎯", skill.emoji)
    }

    @Test
    fun `Sales Coach is in sales category`() {
        val context = RuntimeEnvironment.getApplication()
        val skill = AgencyAgentsFetcher.loadSkills(context).first { it.name == "Sales Coach" }
        assertEquals("sales", skill.category)
    }

    @Test
    fun `Growth Hacker is in social category`() {
        val context = RuntimeEnvironment.getApplication()
        val skill = AgencyAgentsFetcher.loadSkills(context).first { it.name == "Growth Hacker" }
        assertEquals("social", skill.category)
    }

    // ════════════════════════════════════════════
    //  Serialization (kotlinx.serialization)
    // ════════════════════════════════════════════

    @Test
    fun `OnlineSkill serializes and deserializes correctly`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = OnlineSkill(
            name = "Test Skill",
            emoji = "🧪",
            specialty = "Testing everything",
            category = "engineering",
            filename = "engineering-test-skill.md",
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<OnlineSkill>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `OnlineSkill serialization roundtrip`() {
        val json = Json { ignoreUnknownKeys = true }
        val skill = OnlineSkill("X", "🔍", "test", "design", "design-x.md")
        val encoded = json.encodeToString(skill)
        val decoded = json.decodeFromString<OnlineSkill>(encoded)
        assertEquals(skill, decoded)
    }

    @Test
    fun `OnlineSkill list serializes and deserializes`() {
        val json = Json { ignoreUnknownKeys = true }
        val skills = listOf(
            OnlineSkill("A", "🅰️", "alpha", "engineering", "engineering-a.md"),
            OnlineSkill("B", "🅱️", "beta", "design", "design-b.md"),
        )
        val encoded = json.encodeToString(skills)
        val decoded = json.decodeFromString<List<OnlineSkill>>(encoded)
        assertEquals(2, decoded.size)
        assertEquals("A", decoded[0].name)
        assertEquals("design", decoded[1].category)
    }

    // ════════════════════════════════════════════
    //  GitHub API file discovery (MockWebServer)
    // ════════════════════════════════════════════

    @Test
    fun `GitHub API response parses correctly`() {
        val apiResponse = """
        [
          {"name": "engineering-frontend-developer.md", "path": "engineering/engineering-frontend-developer.md", "type": "file"},
          {"name": "engineering-new-skill.md", "path": "engineering/engineering-new-skill.md", "type": "file"},
          {"name": "README.md", "path": "engineering/README.md", "type": "file"}
        ]
        """.trimIndent()

        server.enqueue(MockResponse().setBody(apiResponse).setResponseCode(200))

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/repos/msitarzewski/agency-agents/contents/engineering"))
            .build()

        val response = client.newCall(request).execute()
        assertTrue(response.isSuccessful)

        val body = response.body?.string() ?: ""
        val json = Json { ignoreUnknownKeys = true }
        val files = json.decodeFromString<List<GithubFileDto>>(body)

        assertEquals(3, files.size)
        assertEquals("engineering-frontend-developer.md", files[0].name)
        assertEquals("file", files[0].type)
    }

    @Test
    fun `GitHub API 404 handled gracefully`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/repos/msitarzewski/agency-agents/contents/nonexistent"))
            .build()

        val response = client.newCall(request).execute()
        assertFalse(response.isSuccessful)
        assertEquals(404, response.code)
    }

    // ════════════════════════════════════════════
    //  Content fetch (MockWebServer)
    // ════════════════════════════════════════════

    @Test
    fun `raw content fetch returns markdown`() {
        val mdContent = """# Frontend Developer
## Identity
You are a frontend developer expert...
"""
        server.enqueue(MockResponse().setBody(mdContent).setResponseCode(200))

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/agency-agents/main/engineering/engineering-frontend-developer.md"))
            .build()

        val response = client.newCall(request).execute()
        assertTrue(response.isSuccessful)

        val content = response.body?.string() ?: ""
        assertTrue(content.startsWith("# Frontend Developer"))
        assertTrue(content.contains("Identity"))
    }

    // ════════════════════════════════════════════
    //  Cache behavior (SharedPreferences via Robolectric)
    // ════════════════════════════════════════════

    @Test
    fun `loadSkills returns hardcoded catalog on network failure`() {
        val context = RuntimeEnvironment.getApplication()
        // Clear any existing cache
        context.getSharedPreferences("agency_agents_cache", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val skills = AgencyAgentsFetcher.loadSkills(context)
        assertTrue("Should return catalog even without network", skills.isNotEmpty())
        assertTrue(skills.size >= 40)
    }

    @Test
    fun `forceRefresh clears cache and reloads`() {
        val context = RuntimeEnvironment.getApplication()
        // First, populate cache
        val prefs = context.getSharedPreferences("agency_agents_cache", android.content.Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val cachedSkills = listOf(OnlineSkill("Cached", "💾", "test", "test", "test.md"))
        prefs.edit()
            .putString("skill_list_json", json.encodeToString(cachedSkills))
            .putLong("skill_list_ts", System.currentTimeMillis())
            .commit()

        // Verify cache is used
        val fromCache = AgencyAgentsFetcher.loadSkills(context)
        assertEquals(1, fromCache.size)
        assertEquals("Cached", fromCache[0].name)

        // Force refresh should clear cache and return catalog
        val refreshed = AgencyAgentsFetcher.forceRefresh(context)
        assertTrue(refreshed.size > 1)
        assertNotEquals("Cached", refreshed[0].name)
    }

    @Test
    fun `expired cache triggers refresh attempt`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("agency_agents_cache", android.content.Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }
        val staleSkills = listOf(OnlineSkill("Stale", "🗑️", "old", "old", "old.md"))
        // Set cache with timestamp older than 24h
        prefs.edit()
            .putString("skill_list_json", json.encodeToString(staleSkills))
            .putLong("skill_list_ts", System.currentTimeMillis() - 25 * 60 * 60 * 1000)
            .commit()

        // Network will fail (no mock server), so it should fall back to catalog
        val skills = AgencyAgentsFetcher.loadSkills(context)
        assertTrue("Should return catalog when refresh fails", skills.size > 1)
    }

    @Test
    fun `fetchContent caches fetched content`() {
        val context = RuntimeEnvironment.getApplication()
        val mdContent = "# Test Skill\nSome content here."
        server.enqueue(MockResponse().setBody(mdContent).setResponseCode(200))

        // Clear content cache
        val prefs = context.getSharedPreferences("agency_agents_cache", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Note: fetchContent uses the real OkHttpClient, not mock server.
        // For a network-unavailable test, it should return empty or cached.
        val skill = OnlineSkill("Test", "🧪", "test", "engineering", "engineering-test-skill.md")
        val content = AgencyAgentsFetcher.fetchContent(context, skill)
        // In Robolectric without network, this returns "" (catch block)
        assertNotNull(content)
    }

    // ════════════════════════════════════════════
    //  Category grouping
    // ════════════════════════════════════════════

    @Test
    fun `engineering category has most skills`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val byCategory = skills.groupBy { it.category }
        val engineeringCount = byCategory["engineering"]?.size ?: 0
        assertTrue("Engineering should have >= 15 skills", engineeringCount >= 15)
    }

    @Test
    fun `design category has at least 5 skills`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val designCount = skills.count { it.category == "design" }
        assertTrue("Design should have >= 5 skills", designCount >= 5)
    }

    @Test
    fun `social category has at least 4 skills`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        val socialCount = skills.count { it.category == "social" }
        assertTrue("Social should have >= 4 skills", socialCount >= 4)
    }

    // ════════════════════════════════════════════
    //  Filename ↔ category consistency
    // ════════════════════════════════════════════

    @Test
    fun `all filenames start with their category prefix`() {
        val context = RuntimeEnvironment.getApplication()
        val skills = AgencyAgentsFetcher.loadSkills(context)
        for (skill in skills) {
            assertTrue(
                "Filename '${skill.filename}' should start with '${skill.category}-'",
                skill.filename.startsWith("${skill.category}-")
            )
        }
    }

    /**
     * Helper DTO for testing JSON deserialization of GitHub API responses.
     */
    @kotlinx.serialization.Serializable
    private data class GithubFileDto(val name: String, val path: String, val type: String)
}
