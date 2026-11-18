package app.switchboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.Conversation
import app.switchboard.data.Role as MsgRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "now", "5m", "8h", "3d", or "20 Oct" for older chats. */
fun ago(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val d = (now - ms).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        d < 7 * 86_400 -> "${d / 86_400}d"
        else -> Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

/**
 * The sidebar: New chat, Search, Scheduled and Customize at the top; then chats grouped under their projects
 * (collapsible) and "No folder"; settings at the bottom. Used as the side pane on wide screens and in the drawer on phones.
 */
@Composable
fun ChatListPanel(
    vm: ChatViewModel, onPick: (String) -> Unit, onNew: (String?) -> Unit, onRename: (Conversation) -> Unit,
    onManageProjects: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier, onOpenPage: (String) -> Unit = { onManageProjects() },
) {
    val projects = vm.settings.projects
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var customize by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun matches(c: Conversation) = q.isEmpty() || c.title.contains(q, true) || c.messages.any { it.text.contains(q, true) }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            item { NavRow(Icons.Default.Add, "New chat") { onNew(null) } }
            item { NavRow(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Close search" else "Search") { searching = !searching; if (!searching) query = "" } }
            if (searching) item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Chats and messages") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
            item { NavRow(Icons.Default.Schedule, "Scheduled") { onOpenPage(Pages.TASKS) } }
            item { NavRow(Icons.Default.Tune, "Customize", trailing = if (customize) Icons.Default.ExpandLess else Icons.Default.ExpandMore) { customize = !customize } }
            if (customize) {
                listOf(Pages.SKILLS to "Skills", Pages.AGENTS to "Agents", Pages.CONNECTORS to "Connectors", Pages.EMBEDDED to "Embedded tools", Pages.TOOLBOX to "Toolbox", Pages.PROJECTS to "Projects")
                    .forEach { (page, label) -> item(key = "cz-$page") { NavRow(null, label, indent = true) { onOpenPage(page) } } }
            }
            item { Spacer(Modifier.height(12.dp)) }

            if (q.isNotEmpty()) {
                val found = vm.conversations.filter(::matches)
                item { GroupHeader("Results", null, null) }
                if (found.isEmpty()) item { Text("Nothing found", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(found, key = { "r-" + it.id }) { c -> ChatRow(vm, c, indent = false, onPick, onRename) }
            } else {
                projects.forEach { p ->
                    val open = p.id !in collapsed
                    item(key = "p-" + p.id) {
                        GroupHeader(p.name, open, onNewHere = { onNew(p.id) }) { collapsed = if (open) collapsed + p.id else collapsed - p.id }
                    }
                    if (open) {
                        val chats = vm.conversations.filter { it.projectId == p.id }
                        if (chats.isEmpty()) item(key = "pe-" + p.id) { Text("No chats yet", Modifier.padding(start = 28.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(chats, key = { it.id }) { c -> ChatRow(vm, c, indent = false, onPick, onRename) }
                    }
                }
                val loose = vm.conversations.filter { c -> c.projectId == null || projects.none { it.id == c.projectId } }
                val open = "none" !in collapsed
                item(key = "p-none") { GroupHeader(if (projects.isEmpty()) "Chats" else "No folder", open, null) { collapsed = if (open) collapsed + "none" else collapsed - "none" } }
                if (open) {
                    if (loose.isEmpty()) item { Text("No chats yet", Modifier.padding(start = 28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(loose, key = { "c-" + it.id }) { c -> ChatRow(vm, c, indent = false, onPick, onRename) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        // Footer: the app and its settings.
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                Text("S", color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Switchboard", style = MaterialTheme.typography.bodyMedium)
                Text("${vm.rotation.size} provider${if (vm.rotation.size == 1) "" else "s"} on · ${vm.settings.runnerMode.label()}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
    }
}

private fun app.switchboard.data.RunnerMode.label() = when (this) {
    app.switchboard.data.RunnerMode.PHONE -> "tools on this phone"
    app.switchboard.data.RunnerMode.TERMUX -> "tools in Termux"
    app.switchboard.data.RunnerMode.REMOTE -> "tools on computer"
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, indent: Boolean = false,
                   trailing: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).combinedClickableCompat(onClick = onClick)
        .padding(start = if (indent) 44.dp else 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.size(14.dp)) }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A project (or "No folder") heading: tap to fold; + starts a chat in it. */
@Composable
private fun GroupHeader(title: String, open: Boolean?, onNewHere: (() -> Unit)?, onToggle: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).combinedClickableCompat(onClick = onToggle)
        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (open != null) Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, if (open) "Fold $title" else "Unfold $title",
            Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (onNewHere != null) IconButton(onClick = onNewHere, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, "New chat in $title", Modifier.size(18.dp)) }
        else Spacer(Modifier.height(36.dp))
    }
}

/** One chat: its title and when it was last active. Long-press for Rename / Delete. */
@Composable
private fun ChatRow(vm: ChatViewModel, c: Conversation, indent: Boolean, onPick: (String) -> Unit, onRename: (Conversation) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = c.id == vm.current.id
    Box {
        Row(Modifier.fillMaxWidth().padding(start = if (indent) 24.dp else 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickableCompat(onClick = { onPick(c.id) }, onLongClick = { menu = true })
            .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            Text(ago(c.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename or move") }, onClick = { menu = false; onRename(c) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.delete(c.id) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) =
    combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
