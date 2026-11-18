package app.switchboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.RunnerMode
import app.switchboard.local.LocalEngine

/** "12.3k" style token counts. */
fun compactTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> "$n"
}

/**
 * The details panel beside the chat: what's answering and where tools run, how full the model's context is (and what
 * fills it), the connectors in use, the local engine and terminals. On wide screens it sits on the right; on phones it
 * opens as a sheet from the top bar.
 */
@Composable
fun DetailsPanel(vm: ChatViewModel, onClose: (() -> Unit)?, onOpenPage: (String) -> Unit, onPickModel: () -> Unit, modifier: Modifier = Modifier) {
    val p = vm.providerFor(vm.currentAgent)
    LaunchedEffect(p?.id, p?.model) { p?.let { vm.lookupContextWindow(it) } }
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (onClose != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close details") }
        }

        // What's answering and where things run.
        InfoRow(Icons.Default.SmartToy, p?.let { "${it.model.ifBlank { "no model" }}" } ?: "No provider", p?.name, onClick = onPickModel)
        if (vm.rotation.size > 1) InfoRow(null, "Taking turns: ${vm.rotation.joinToString { it.name }}", null)
        vm.currentAgent?.let { InfoRow(null, "Agent: ${it.name}", null) }
        vm.settings.projects.firstOrNull { it.id == vm.current.projectId }?.let { InfoRow(Icons.Default.Folder, it.name, "Project", onClick = { onOpenPage(Pages.PROJECTS) }) }
        InfoRow(Icons.Default.Computer, when (vm.settings.runnerMode) {
            RunnerMode.PHONE -> "Tools: this phone"; RunnerMode.TERMUX -> "Tools: Termux"; RunnerMode.REMOTE -> "Tools: computer"
        }, vm.settings.packages.count { it.installs[vm.settings.runnerMode.name]?.ok == true }.let { if (it > 0) "$it in Toolbox" else null }, onClick = { onOpenPage(Pages.TOOLBOX) })

        Section("Context")
        val usage = vm.contextUsage(p)
        val window = usage.window
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (window != null) "${(usage.used * 100L / window).coerceAtMost(100)}% used" else "About ${compactTokens(usage.used)} tokens", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (window != null) "${compactTokens(usage.used)} / ${compactTokens(window)}" else "window unknown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val colors = listOf(Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFFEF6C57))
        // One bar split by what fills the context; the empty part is free space.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val total = (window ?: usage.used).coerceAtLeast(1)
            usage.parts.forEachIndexed { i, (_, n) ->
                val f = n.toFloat() / total
                if (f > 0f) Box(Modifier.fillMaxHeight().weight(f.coerceAtMost(1f)).background(colors[i % colors.size]))
            }
            val free = 1f - usage.used.toFloat() / total
            if (free > 0f) Spacer(Modifier.weight(free))
        }
        usage.parts.forEachIndexed { i, (label, n) -> UsageRow(colors[i % colors.size], label, n, window) }
        if (window != null) UsageRow(MaterialTheme.colorScheme.surfaceVariant, "Free space", (window - usage.used).coerceAtLeast(0), window)
        if (vm.lastToolCount > 0) Text("${vm.lastToolCount} tools offered to the model", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        Text("Estimated at about 4 characters per token.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

        // The on-phone engine, when one is loaded.
        val pr by LocalEngine.progress.collectAsState()
        pr?.let {
            Section("Local engine")
            LocalProgressBar(it)
        }

        val conns = vm.settings.connectors
        Section("Connectors")
        if (conns.isEmpty()) InfoRow(null, "None yet", "Add MCP servers and APIs", onClick = { onOpenPage(Pages.CONNECTORS) })
        conns.take(8).forEach { c -> InfoRow(Icons.Default.Cable, c.name, if (c.enabled) null else "off", dot = if (c.enabled) Color(0xFF10B981) else null, onClick = { onOpenPage(Pages.CONNECTORS) }) }
        if (conns.size > 8) InfoRow(null, "Show all ${conns.size}", null, onClick = { onOpenPage(Pages.CONNECTORS) })

        val terms = vm.terminals.filter { it.id != app.switchboard.engine.TerminalHub.ACTIVITY }
        if (terms.isNotEmpty() || vm.commandsRunning > 0) {
            Section("Terminals")
            if (vm.commandsRunning > 0) InfoRow(Icons.Default.Terminal, "Running a command", null, dot = Color(0xFF10B981), onClick = { vm.terminalOpen = true })
            terms.forEach { t -> InfoRow(Icons.Default.Terminal, t.title, if (t.alive) "running" else "exited", dot = if (t.alive) Color(0xFF10B981) else null,
                onClick = { vm.terminalSelected = t.id; vm.terminalOpen = true }) }
        }
        val s = vm.settings
        Section("Customize")
        InfoRow(null, "Skills", "${s.skills.size}", onClick = { onOpenPage(Pages.SKILLS) })
        InfoRow(null, "Agents", "${s.agents.size}", onClick = { onOpenPage(Pages.AGENTS) })
        InfoRow(null, "Scheduled tasks", "${s.tasks.size}", onClick = { onOpenPage(Pages.TASKS) })
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp, start = 16.dp, end = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun InfoRow(icon: ImageVector?, title: String, trailing: String?, dot: Color? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp)) }
        else Spacer(Modifier.width(30.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        dot?.let { Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(it)) }
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
    }
}

@Composable
private fun UsageRow(color: Color, label: String, tokens: Int, window: Int?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(compactTokens(tokens), style = MaterialTheme.typography.bodySmall)
        if (window != null) Text("%.1f%%".format(tokens * 100.0 / window), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
