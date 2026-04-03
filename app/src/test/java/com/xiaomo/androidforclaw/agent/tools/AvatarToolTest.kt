package com.xiaomo.androidforclaw.agent.tools

import ai.openclaw.app.avatar.AvatarPhase
import ai.openclaw.app.avatar.AvatarStateHolder
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AvatarToolTest {

    private val tool = AvatarTool()

    @Before
    fun reset() {
        AvatarStateHolder.setPhase(AvatarPhase.Idle)
    }

    // ════════ Tool metadata ════════

    @Test
    fun `tool name is avatar`() {
        assertEquals("avatar", tool.name)
    }

    @Test
    fun `tool definition has correct parameters`() {
        val def = tool.getToolDefinition()
        assertEquals("avatar", def.function.name)
        val props = def.function.parameters.properties
        assertTrue(props.containsKey("action"))
        assertTrue(props.containsKey("expression"))
        assertTrue(props.containsKey("mood"))
        assertEquals(listOf("action"), def.function.parameters.required)
    }

    @Test
    fun `action parameter has enum values`() {
        val def = tool.getToolDefinition()
        val actionSchema = def.function.parameters.properties["action"]!!
        assertEquals(listOf("trigger", "mood", "reset"), actionSchema.enum)
    }

    // ════════ trigger action ════════

    @Test
    fun `trigger fires expression`() = runTest {
        val result = tool.execute(mapOf("action" to "trigger", "expression" to "smile"))
        assertTrue(result.success)
        assertTrue(result.content.contains("smile"))
    }

    @Test
    fun `trigger without expression returns error`() = runTest {
        val result = tool.execute(mapOf("action" to "trigger"))
        assertFalse(result.success)
        assertTrue(result.content.contains("expression"))
    }

    @Test
    fun `trigger supports all expression types`() = runTest {
        val expressions = listOf("smile", "wave", "nod", "surprise", "sad", "celebrate")
        for (expr in expressions) {
            val result = tool.execute(mapOf("action" to "trigger", "expression" to expr))
            assertTrue("trigger $expr should succeed", result.success)
        }
    }

    // ════════ mood action ════════

    @Test
    fun `mood sets phase to talking`() = runTest {
        val result = tool.execute(mapOf("action" to "mood", "mood" to "talking"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood sets phase to listening`() = runTest {
        val result = tool.execute(mapOf("action" to "mood", "mood" to "listening"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Listening, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood sets phase to thinking`() = runTest {
        val result = tool.execute(mapOf("action" to "mood", "mood" to "thinking"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood sets phase to idle`() = runTest {
        AvatarStateHolder.setPhase(AvatarPhase.Talking)
        val result = tool.execute(mapOf("action" to "mood", "mood" to "idle"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood is case insensitive`() = runTest {
        val result = tool.execute(mapOf("action" to "mood", "mood" to "TALKING"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood with null resets to idle`() = runTest {
        AvatarStateHolder.setPhase(AvatarPhase.Talking)
        val result = tool.execute(mapOf("action" to "mood", "mood" to null))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood with blank string resets to idle`() = runTest {
        AvatarStateHolder.setPhase(AvatarPhase.Talking)
        val result = tool.execute(mapOf("action" to "mood", "mood" to "  "))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `mood with unknown value returns error`() = runTest {
        val result = tool.execute(mapOf("action" to "mood", "mood" to "angry"))
        assertFalse(result.success)
        assertTrue(result.content.contains("Unknown mood"))
    }

    // ════════ reset action ════════

    @Test
    fun `reset sets phase to Idle`() = runTest {
        AvatarStateHolder.setPhase(AvatarPhase.Talking)
        val result = tool.execute(mapOf("action" to "reset"))
        assertTrue(result.success)
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    // ════════ Error cases ════════

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(mapOf("action" to "dance"))
        assertFalse(result.success)
        assertTrue(result.content.contains("Unknown action"))
    }
}
