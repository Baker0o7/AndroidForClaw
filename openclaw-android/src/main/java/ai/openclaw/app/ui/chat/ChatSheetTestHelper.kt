package ai.openclaw.app.ui.chat

import androidx.compose.runtime.Composable
import ai.openclaw.app.chat.ChatSessionEntry

/**
 * Test helper to expose internal SessionHeader for UI testing.
 */
object ChatSheetTestHelper {
    @Composable
    fun SessionHeaderTest(
        sessionKey: String,
        sessions: List<ChatSessionEntry>,
        mainSessionKey: String,
        onSelectSession: (String) -> Unit = {},
        onDeleteSession: ((String) -> Unit)? = null,
        onNewSession: (() -> Unit)? = null,
    ) {
        SessionHeader(
            sessionKey = sessionKey,
            sessions = sessions,
            mainSessionKey = mainSessionKey,
            onSelectSession = onSelectSession,
            onDeleteSession = onDeleteSession,
            onNewSession = onNewSession,
        )
    }
}
