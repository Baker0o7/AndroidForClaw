package com.xiaomo.androidforclaw.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Channel Config Persistence Tests
 *
 * Validates the 4 new channel config data classes (Slack, Telegram, WhatsApp, Signal)
 * have correct defaults, nullable behaviour in ChannelsConfig, and data class
 * copy (round-trip) semantics.
 */
class ChannelConfigTest {

    // ===== SlackChannelConfig defaults =====

    @Test
    fun slackConfig_defaults() {
        val config = SlackChannelConfig()
        assertFalse(config.enabled)
        assertEquals("", config.token)
        assertEquals("open", config.dmPolicy)
        assertEquals("open", config.groupPolicy)
        assertTrue(config.requireMention)
    }

    @Test
    fun slackConfig_customValues() {
        val config = SlackChannelConfig(
            enabled = true,
            token = "xoxb-test-token",
            dmPolicy = "allowlist",
            groupPolicy = "closed",
            requireMention = false
        )
        assertTrue(config.enabled)
        assertEquals("xoxb-test-token", config.token)
        assertEquals("allowlist", config.dmPolicy)
        assertEquals("closed", config.groupPolicy)
        assertFalse(config.requireMention)
    }

    // ===== TelegramChannelConfig defaults =====

    @Test
    fun telegramConfig_defaults() {
        val config = TelegramChannelConfig()
        assertFalse(config.enabled)
        assertEquals("", config.token)
        assertEquals("open", config.dmPolicy)
        assertEquals("open", config.groupPolicy)
        assertTrue(config.requireMention)
    }

    @Test
    fun telegramConfig_customValues() {
        val config = TelegramChannelConfig(
            enabled = true,
            token = "bot123456:ABC-DEF",
            dmPolicy = "pairing",
            groupPolicy = "allowlist",
            requireMention = false
        )
        assertTrue(config.enabled)
        assertEquals("bot123456:ABC-DEF", config.token)
        assertEquals("pairing", config.dmPolicy)
        assertEquals("allowlist", config.groupPolicy)
        assertFalse(config.requireMention)
    }

    // ===== WhatsAppChannelConfig defaults =====

    @Test
    fun whatsappConfig_defaults() {
        val config = WhatsAppChannelConfig()
        assertFalse(config.enabled)
        assertEquals("", config.phoneNumber)
        assertEquals("open", config.dmPolicy)
        assertEquals("open", config.groupPolicy)
        assertTrue(config.requireMention)
    }

    @Test
    fun whatsappConfig_customValues() {
        val config = WhatsAppChannelConfig(
            enabled = true,
            phoneNumber = "+1234567890",
            dmPolicy = "closed",
            groupPolicy = "open",
            requireMention = false
        )
        assertTrue(config.enabled)
        assertEquals("+1234567890", config.phoneNumber)
    }

    // ===== SignalChannelConfig defaults =====

    @Test
    fun signalConfig_defaults() {
        val config = SignalChannelConfig()
        assertFalse(config.enabled)
        assertEquals("", config.phoneNumber)
        assertEquals("open", config.dmPolicy)
        assertEquals("open", config.groupPolicy)
        assertTrue(config.requireMention)
    }

    @Test
    fun signalConfig_customValues() {
        val config = SignalChannelConfig(
            enabled = true,
            phoneNumber = "+0987654321",
            dmPolicy = "allowlist",
            groupPolicy = "closed",
            requireMention = true
        )
        assertTrue(config.enabled)
        assertEquals("+0987654321", config.phoneNumber)
    }

    // ===== ChannelsConfig nullable fields =====

    @Test
    fun channelsConfig_nullableByDefault() {
        val channels = ChannelsConfig()
        assertNull(channels.slack)
        assertNull(channels.telegram)
        assertNull(channels.whatsapp)
        assertNull(channels.signal)
        // feishu and discord also present in ChannelsConfig
        assertNotNull(channels.feishu) // feishu has non-null default
        assertNull(channels.discord)
    }

    @Test
    fun channelsConfig_includesAllChannels() {
        val channels = ChannelsConfig(
            slack = SlackChannelConfig(enabled = true, token = "xoxb-test"),
            telegram = TelegramChannelConfig(enabled = true, token = "bot123"),
            whatsapp = WhatsAppChannelConfig(enabled = true, phoneNumber = "+1234567890"),
            signal = SignalChannelConfig(enabled = true, phoneNumber = "+0987654321")
        )
        assertNotNull(channels.slack)
        assertTrue(channels.slack!!.enabled)
        assertEquals("xoxb-test", channels.slack!!.token)

        assertNotNull(channels.telegram)
        assertTrue(channels.telegram!!.enabled)
        assertEquals("bot123", channels.telegram!!.token)

        assertNotNull(channels.whatsapp)
        assertTrue(channels.whatsapp!!.enabled)
        assertEquals("+1234567890", channels.whatsapp!!.phoneNumber)

        assertNotNull(channels.signal)
        assertTrue(channels.signal!!.enabled)
        assertEquals("+0987654321", channels.signal!!.phoneNumber)
    }

    // ===== Data class copy round-trip =====

    @Test
    fun slackConfig_copyRoundTrip() {
        val original = SlackChannelConfig(enabled = true, token = "xoxb-abc")
        val copied = original.copy(dmPolicy = "closed")
        assertEquals("xoxb-abc", copied.token)
        assertTrue(copied.enabled)
        assertEquals("closed", copied.dmPolicy)
        // original unchanged
        assertEquals("open", original.dmPolicy)
    }

    @Test
    fun channelsConfig_copyRoundTrip() {
        val original = ChannelsConfig(
            slack = SlackChannelConfig(enabled = true, token = "xoxb-orig")
        )
        val updated = original.copy(
            telegram = TelegramChannelConfig(enabled = true, token = "bot-new")
        )
        // slack preserved
        assertEquals("xoxb-orig", updated.slack!!.token)
        // telegram added
        assertEquals("bot-new", updated.telegram!!.token)
        // original telegram still null
        assertNull(original.telegram)
    }

    @Test
    fun openClawConfig_channelsDefault() {
        val config = OpenClawConfig()
        assertNull(config.channels.slack)
        assertNull(config.channels.telegram)
        assertNull(config.channels.whatsapp)
        assertNull(config.channels.signal)
    }

    // ===== Data class equality =====

    @Test
    fun slackConfig_equality() {
        val a = SlackChannelConfig(enabled = true, token = "tok")
        val b = SlackChannelConfig(enabled = true, token = "tok")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun telegramConfig_equality() {
        val a = TelegramChannelConfig(enabled = false, token = "bot1")
        val b = TelegramChannelConfig(enabled = false, token = "bot1")
        assertEquals(a, b)
    }

    @Test
    fun whatsappConfig_inequality() {
        val a = WhatsAppChannelConfig(phoneNumber = "+111")
        val b = WhatsAppChannelConfig(phoneNumber = "+222")
        assertNotEquals(a, b)
    }
}
