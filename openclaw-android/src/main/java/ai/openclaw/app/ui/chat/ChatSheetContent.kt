package ai.openclaw.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.R
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.OutgoingAttachment
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileDanger
import ai.openclaw.app.ui.mobileDangerSoft
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatSheetContent(viewModel: MainViewModel) {
  val messages by viewModel.chatMessages.collectAsState()
  val errorText by viewModel.chatError.collectAsState()
  val pendingRunCount by viewModel.pendingRunCount.collectAsState()
  val healthOk by viewModel.chatHealthOk.collectAsState()
  val sessionKey by viewModel.chatSessionKey.collectAsState()
  val mainSessionKey by viewModel.mainSessionKey.collectAsState()
  val thinkingLevel by viewModel.chatThinkingLevel.collectAsState()
  val streamingAssistantText by viewModel.chatStreamingAssistantText.collectAsState()
  val pendingToolCalls by viewModel.chatPendingToolCalls.collectAsState()
  val sessions by viewModel.chatSessions.collectAsState()

  LaunchedEffect(mainSessionKey) {
    viewModel.loadChat(mainSessionKey)
  }

  val context = LocalContext.current
  val resolver = context.contentResolver
  val scope = rememberCoroutineScope()

  val attachments = remember { mutableStateListOf<PendingImageAttachment>() }

  val pickImages =
    rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
      if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        val next =
          uris.take(8).mapNotNull { uri ->
            try {
              loadSizedImageAttachment(resolver, uri)
            } catch (_: Throwable) {
              null
            }
          }
        withContext(Dispatchers.Main) {
          attachments.addAll(next)
        }
      }
    }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .imePadding()
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    SessionHeader(
      sessionKey = sessionKey,
      sessions = sessions,
      mainSessionKey = mainSessionKey,
      onSelectSession = { key -> viewModel.switchChatSession(key) },
      onDeleteSession = { key -> viewModel.deleteChatSession(key) },
      onNewSession = { viewModel.refreshChatSessions(limit = 200) },
    )

    if (!errorText.isNullOrBlank()) {
      ChatErrorRail(errorText = errorText!!)
    }

    ChatMessageListCard(
      messages = messages,
      pendingRunCount = pendingRunCount,
      pendingToolCalls = pendingToolCalls,
      streamingAssistantText = streamingAssistantText,
      healthOk = healthOk,
      modifier = Modifier.weight(1f, fill = true),
    )

    Row(modifier = Modifier.fillMaxWidth()) {
      ChatComposer(
        healthOk = healthOk,
        thinkingLevel = thinkingLevel,
        pendingRunCount = pendingRunCount,
        attachments = attachments,
        onPickImages = { pickImages.launch("image/*") },
        onRemoveAttachment = { id -> attachments.removeAll { it.id == id } },
        onSetThinkingLevel = { level -> viewModel.setChatThinkingLevel(level) },
        onRefresh = {
          viewModel.refreshChat()
          viewModel.refreshChatSessions(limit = 200)
        },
        onAbort = { viewModel.abortChat() },
        onSend = { text ->
          val outgoing =
            attachments.map { att ->
              OutgoingAttachment(
                type = "image",
                mimeType = att.mimeType,
                fileName = att.fileName,
                base64 = att.base64,
              )
            }
          viewModel.sendChat(message = text, thinking = thinkingLevel, attachments = outgoing)
          attachments.clear()
        },
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionHeader(
  sessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  onSelectSession: (String) -> Unit,
  onDeleteSession: ((String) -> Unit)? = null,
  onNewSession: (() -> Unit)? = null,
) {
  val sessionOptions =
    remember(sessionKey, sessions, mainSessionKey) {
      resolveSessionChoices(sessionKey, sessions, mainSessionKey = mainSessionKey)
    }

  var deleteConfirmKey by remember { mutableStateOf<String?>(null) }

  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (entry in sessionOptions) {
      val active = entry.key == sessionKey
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) mobileAccent else mobileCardSurface,
        border = BorderStroke(1.dp, if (active) mobileAccentBorderStrong else mobileBorderStrong),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.combinedClickable(
          onClick = { onSelectSession(entry.key) },
          onLongClick = {
            if (onDeleteSession != null && entry.key != mainSessionKey) {
              deleteConfirmKey = entry.key
            }
          },
        ),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(start = 12.dp, end = if (active && entry.key != mainSessionKey) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
          Text(
            text = friendlySessionName(entry.displayName ?: entry.key),
            style = mobileCaption1.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
            color = if (active) Color.White else mobileText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (active && entry.key != mainSessionKey) {
            IconButton(
              onClick = { deleteConfirmKey = entry.key },
              modifier = Modifier.size(24.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_session),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      }
    }
    
    if (onNewSession != null) {
      IconButton(
        onClick = onNewSession,
        modifier = Modifier.size(36.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = mobileCardSurface,
          border = BorderStroke(1.dp, mobileBorderStrong),
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "New Session",
            tint = mobileTextSecondary,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }

  // Delete confirmation dialog
  deleteConfirmKey?.let { key ->
    val displayName = sessionOptions.find { it.key == key }?.let {
      friendlySessionName(it.displayName ?: it.key)
    } ?: key
    AlertDialog(
      onDismissRequest = { deleteConfirmKey = null },
      title = { Text(stringResource(R.string.delete_session)) },
      text = { Text(stringResource(R.string.delete_session_confirm, displayName)) },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteSession?.invoke(key)
            deleteConfirmKey = null
          },
          colors = ButtonDefaults.textButtonColors(contentColor = mobileDanger),
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteConfirmKey = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun ChatErrorRail(errorText: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = mobileDangerSoft,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, mobileDanger),
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = "ConversationError",
        style = mobileCaption2.copy(letterSpacing = 0.6.sp),
        color = mobileDanger,
      )
      Text(text = errorText, style = mobileCallout, color = mobileText)
    }
  }
}

data class PendingImageAttachment(
  val id: String,
  val fileName: String,
  val mimeType: String,
  val base64: String,
)
