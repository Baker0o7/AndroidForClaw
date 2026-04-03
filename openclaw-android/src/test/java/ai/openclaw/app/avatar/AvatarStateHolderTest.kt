package ai.openclaw.app.avatar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

class AvatarStateHolderTest {

    @Before
    fun reset() {
        AvatarStateHolder.setPhase(AvatarPhase.Idle)
        AvatarStateHolder.setMouthOpen(0f)
    }

    // ════════ Phase ════════

    @Test
    fun `initial phase is Idle`() {
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `setPhase updates phase flow`() {
        AvatarStateHolder.setPhase(AvatarPhase.Talking)
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)

        AvatarStateHolder.setPhase(AvatarPhase.Listening)
        assertEquals(AvatarPhase.Listening, AvatarStateHolder.phase.value)

        AvatarStateHolder.setPhase(AvatarPhase.Thinking)
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)

        AvatarStateHolder.setPhase(AvatarPhase.Idle)
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    // ════════ MouthOpen ════════

    @Test
    fun `initial mouthOpen is 0`() {
        assertEquals(0f, AvatarStateHolder.mouthOpen.value, 0.001f)
    }

    @Test
    fun `setMouthOpen updates flow`() {
        AvatarStateHolder.setMouthOpen(0.5f)
        assertEquals(0.5f, AvatarStateHolder.mouthOpen.value, 0.001f)
    }

    @Test
    fun `setMouthOpen clamps to 0-1 range`() {
        AvatarStateHolder.setMouthOpen(-0.5f)
        assertEquals(0f, AvatarStateHolder.mouthOpen.value, 0.001f)

        AvatarStateHolder.setMouthOpen(2.5f)
        assertEquals(1f, AvatarStateHolder.mouthOpen.value, 0.001f)
    }

    @Test
    fun `setMouthOpen boundary values`() {
        AvatarStateHolder.setMouthOpen(0f)
        assertEquals(0f, AvatarStateHolder.mouthOpen.value, 0.001f)

        AvatarStateHolder.setMouthOpen(1f)
        assertEquals(1f, AvatarStateHolder.mouthOpen.value, 0.001f)
    }

    // ════════ Triggers ════════

    @Test
    fun `fireTrigger sends to channel`() = runTest {
        val received = mutableListOf<String>()
        val job = launch {
            AvatarStateHolder.triggers.collect { received.add(it) }
        }

        AvatarStateHolder.fireTrigger("smile")
        AvatarStateHolder.fireTrigger("wave")
        AvatarStateHolder.fireTrigger("nod")

        // Let the collector process
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("smile", "wave", "nod"), received)
    }

    @Test
    fun `fireTrigger does not block on buffered channel`() {
        // Should not throw even without a collector
        AvatarStateHolder.fireTrigger("smile")
        AvatarStateHolder.fireTrigger("wave")
        AvatarStateHolder.fireTrigger("surprise")
        AvatarStateHolder.fireTrigger("celebrate")
    }

    // ════════ AvatarPhase enum ════════

    @Test
    fun `AvatarPhase has 4 values`() {
        val values = AvatarPhase.entries
        assertEquals(4, values.size)
        assertNotNull(values.find { it == AvatarPhase.Idle })
        assertNotNull(values.find { it == AvatarPhase.Listening })
        assertNotNull(values.find { it == AvatarPhase.Thinking })
        assertNotNull(values.find { it == AvatarPhase.Talking })
    }
}
