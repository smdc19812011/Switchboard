package app.switchboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.ChatMessage
import app.switchboard.data.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onSettings: () -> Unit, onPickModel: () -> Unit) {
    val provider = vm.activeProvider
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Chats") } },
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(enabled = provider != null, onClick = onPickModel)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { contentDescription = "Switch provider or model" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 220.dp)) {
                            Text(provider?.model?.ifBlank { "No model set" } ?: "Switchboard",
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (provider != null) Text(provider.name, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (provider != null) Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val msgs = vm.current.messages
            Box(Modifier.weight(1f)) {
                when {
                    provider == null -> EmptyState(
                        "Connect a model to start",
                        "Add Anthropic, OpenRouter, OpenAI, a local Ollama server, or any OpenAI-compatible endpoint.",
                        "Add a provider", onSettings,
                    )
                    msgs.isEmpty() -> EmptyState("What's on your mind?", "Replies come from ${provider.name}. Tap the model name above to switch at any time.", null, null)
                    else -> MessageList(msgs, vm.streaming, onRegenerate = vm::regenerate)
                }
            }
            Composer(enabled = provider != null, streaming = vm.streaming, onSend = vm::send, onStop = vm::stop)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action); Spacer(Modifier.size(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun MessageList(msgs: List<ChatMessage>, streaming: Boolean, onRegenerate: () -> Unit) {
    val state = rememberLazyListState()
    val lastLen = msgs.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(msgs.size, lastLen) { if (msgs.isNotEmpty()) state.scrollToItem(msgs.lastIndex, Int.MAX_VALUE) }
    LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(msgs, key = { it.id }) { m ->
            val isLast = m.id == msgs.last().id
            if (m.role == Role.USER) UserBubble(m) else AssistantBlock(m, isLast, streaming && isLast, onRegenerate)
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun AssistantBlock(m: ChatMessage, isLast: Boolean, live: Boolean, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        if (m.text.isEmpty() && live) TypingDots()
        else MarkdownText(m.text, if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!live) Row(verticalAlignment = Alignment.CenterVertically) {
            m.source?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) { Icon(Icons.Default.ContentCopy, "Copy reply", Modifier.size(18.dp)) }
            if (isLast) IconButton(onClick = onRegenerate) { Icon(Icons.Default.Refresh, "Regenerate with current model", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun TypingDots() {
    Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Waiting for reply" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, CircleShape)) }
    }
}

@Composable
private fun Composer(enabled: Boolean, streaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text, onValueChange = { text = it }, enabled = enabled,
                placeholder = { Text("Message") }, maxLines = 6,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Stop, "Stop generating") }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" }, enabled = enabled && text.isNotBlank(), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.Default.ArrowUpward, "Send") }
            }
        }
    }
}
