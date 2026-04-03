package ai.openclaw.app.avatar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Maps voice/agent state signals to [AvatarStateHolder.phase].
 * Priority: Talking > Listening > Thinking > Idle.
 */
class AvatarStateBridge(
    private val scope: CoroutineScope,
    private val isSpeaking: StateFlow<Boolean>,
    private val isListening: StateFlow<Boolean>,
    private val isSending: StateFlow<Boolean>,
    private val streamingText: StateFlow<String?>,
) {
    fun start() {
        scope.launch {
            combine(isSpeaking, isListening, isSending, streamingText) { speaking, listening, sending, streaming ->
                when {
                    speaking -> AvatarPhase.Talking
                    listening -> AvatarPhase.Listening
                    sending || !streaming.isNullOrBlank() -> AvatarPhase.Thinking
                    else -> AvatarPhase.Idle
                }
            }.distinctUntilChanged().collect { phase ->
                AvatarStateHolder.setPhase(phase)
            }
        }
    }
}
