package ai.openclaw.app.avatar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarStateBridgeTest {

    private lateinit var isSpeaking: MutableStateFlow<Boolean>
    private lateinit var isListening: MutableStateFlow<Boolean>
    private lateinit var isSending: MutableStateFlow<Boolean>
    private lateinit var streamingText: MutableStateFlow<String?>
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        isSpeaking = MutableStateFlow(false)
        isListening = MutableStateFlow(false)
        isSending = MutableStateFlow(false)
        streamingText = MutableStateFlow(null)
        AvatarStateHolder.setPhase(AvatarPhase.Idle)
        scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        AvatarStateBridge(
            scope = scope,
            isSpeaking = isSpeaking,
            isListening = isListening,
            isSending = isSending,
            streamingText = streamingText,
        ).start()
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    // ════════ Default state ════════

    @Test
    fun `default state is Idle`() {
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    // ════════ Single signal ════════

    @Test
    fun `speaking maps to Talking`() {
        isSpeaking.value = true
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `listening maps to Listening`() {
        isListening.value = true
        assertEquals(AvatarPhase.Listening, AvatarStateHolder.phase.value)
    }

    @Test
    fun `sending maps to Thinking`() {
        isSending.value = true
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `streaming text maps to Thinking`() {
        streamingText.value = "Hello world"
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)
    }

    // ════════ Priority ════════

    @Test
    fun `speaking has highest priority`() {
        isListening.value = true
        isSending.value = true
        isSpeaking.value = true
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)
    }

    @Test
    fun `listening beats thinking`() {
        isSending.value = true
        isListening.value = true
        assertEquals(AvatarPhase.Listening, AvatarStateHolder.phase.value)
    }

    @Test
    fun `thinking beats idle`() {
        isSending.value = true
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)
    }

    // ════════ Transitions ════════

    @Test
    fun `transitions back to Idle when all signals clear`() {
        isSpeaking.value = true
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)

        isSpeaking.value = false
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `full lifecycle idle to listening to thinking to talking to idle`() {
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)

        isListening.value = true
        assertEquals(AvatarPhase.Listening, AvatarStateHolder.phase.value)

        isListening.value = false
        isSending.value = true
        assertEquals(AvatarPhase.Thinking, AvatarStateHolder.phase.value)

        isSending.value = false
        isSpeaking.value = true
        assertEquals(AvatarPhase.Talking, AvatarStateHolder.phase.value)

        isSpeaking.value = false
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `blank streaming text treated as no streaming`() {
        streamingText.value = "   "
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }

    @Test
    fun `empty string streaming text treated as no streaming`() {
        streamingText.value = ""
        assertEquals(AvatarPhase.Idle, AvatarStateHolder.phase.value)
    }
}
