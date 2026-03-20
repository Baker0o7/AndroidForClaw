package com.xiaomo.androidforclaw.config

import org.junit.Assert.*
import org.junit.Test

/**
 * ProviderRegistry 单元测试
 *
 * 验证 provider normalize 映射和注册完整性。
 */
class ProviderRegistryTest {

    @Test
    fun `normalizeProviderId maps kimi to moonshot`() {
        assertEquals("moonshot", ProviderRegistry.normalizeProviderId("kimi"))
        assertEquals("moonshot", ProviderRegistry.normalizeProviderId("KIMI"))
        assertEquals("moonshot", ProviderRegistry.normalizeProviderId("  kimi  "))
    }

    @Test
    fun `normalizeProviderId maps kimi-code and kimi-coding`() {
        assertEquals("kimi-coding", ProviderRegistry.normalizeProviderId("kimi-code"))
        assertEquals("kimi-coding", ProviderRegistry.normalizeProviderId("kimi-coding"))
        assertEquals("kimi-coding", ProviderRegistry.normalizeProviderId("KIMI-CODE"))
    }

    @Test
    fun `normalizeProviderId maps moonshot-cn to moonshot`() {
        assertEquals("moonshot", ProviderRegistry.normalizeProviderId("moonshot-cn"))
    }

    @Test
    fun `normalizeProviderId maps other known aliases`() {
        assertEquals("zai", ProviderRegistry.normalizeProviderId("z.ai"))
        assertEquals("qwen-portal", ProviderRegistry.normalizeProviderId("qwen"))
        assertEquals("amazon-bedrock", ProviderRegistry.normalizeProviderId("bedrock"))
        assertEquals("volcengine", ProviderRegistry.normalizeProviderId("doubao"))
    }

    @Test
    fun `normalizeProviderId passes through unknown IDs`() {
        assertEquals("openai", ProviderRegistry.normalizeProviderId("openai"))
        assertEquals("custom-xyz", ProviderRegistry.normalizeProviderId("custom-xyz"))
    }

    @Test
    fun `ALL list contains moonshot provider`() {
        val moonshot = ProviderRegistry.ALL.find { it.id == "moonshot" }
        assertNotNull("moonshot provider must exist in ALL", moonshot)
        assertEquals("Moonshot (Kimi)", moonshot!!.name)
        assertEquals("https://api.moonshot.ai/v1", moonshot.baseUrl)
        assertEquals(ModelApi.OPENAI_COMPLETIONS, moonshot.api)
    }

    @Test
    fun `ALL list contains kimi-coding provider`() {
        val kimiCoding = ProviderRegistry.ALL.find { it.id == "kimi-coding" }
        assertNotNull("kimi-coding provider must exist in ALL", kimiCoding)
        assertEquals("Kimi for Coding", kimiCoding!!.name)
        assertEquals("https://api.kimi.com/coding/", kimiCoding.baseUrl)
        assertEquals(ModelApi.ANTHROPIC_MESSAGES, kimiCoding.api)
        assertEquals(mapOf("User-Agent" to "claude-code/0.1.0"), kimiCoding.headers)
    }

    @Test
    fun `moonshot preset model is kimi-k2_5 with correct context window`() {
        val moonshot = ProviderRegistry.ALL.find { it.id == "moonshot" }!!
        val model = moonshot.presetModels.first()
        assertEquals("kimi-k2.5", model.id)
        assertEquals(256000, model.contextWindow)
        assertEquals(8192, model.maxTokens)
    }

    @Test
    fun `kimi-coding preset model is k2p5 with correct context window`() {
        val kimiCoding = ProviderRegistry.ALL.find { it.id == "kimi-coding" }!!
        val model = kimiCoding.presetModels.first()
        assertEquals("k2p5", model.id)
        assertEquals(262144, model.contextWindow)
        assertEquals(32768, model.maxTokens)
    }

    @Test
    fun `buildProviderConfig propagates headers from definition`() {
        val kimiCodingDef = ProviderRegistry.ALL.find { it.id == "kimi-coding" }!!
        val config = ProviderRegistry.buildProviderConfig(
            definition = kimiCodingDef,
            apiKey = "test-key",
            selectedModels = listOf(PresetModel(id = "k2p5", name = "Kimi for Coding"))
        )
        assertEquals(mapOf("User-Agent" to "claude-code/0.1.0"), config.headers)
        assertEquals(ModelApi.ANTHROPIC_MESSAGES, config.api)
        assertEquals("test-key", config.apiKey)
    }

    @Test
    fun `buildProviderConfig for moonshot has no extra headers`() {
        val moonshotDef = ProviderRegistry.ALL.find { it.id == "moonshot" }!!
        val config = ProviderRegistry.buildProviderConfig(
            definition = moonshotDef,
            apiKey = "test-key",
            selectedModels = listOf(PresetModel(id = "kimi-k2.5", name = "Kimi K2.5"))
        )
        assertNull(config.headers)
        assertEquals(ModelApi.OPENAI_COMPLETIONS, config.api)
    }
}
