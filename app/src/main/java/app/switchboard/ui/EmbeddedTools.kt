package app.switchboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.switchboard.studio.Studio
import kotlinx.coroutines.launch

/**
 * Settings > Embedded tools: tools built into Switchboard that every chat's model can use. Each one is listed with
 * its status; tap to set it up. Today: the Open Higgsfield studio (image, video and lip sync through kie.ai).
 */
@Composable
fun EmbeddedToolsPage(vm: ChatViewModel) {
    var open by rememberSaveable { mutableStateOf("studio") }
    Text("Built into the app and offered to the AI in every chat (with tools on), whatever model you use, including text-only models: the model writes the request, the tool makes the result.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val ready = vm.studioReady
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = if (open == "studio") "" else "studio" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Movie, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Open Higgsfield AI", style = MaterialTheme.typography.titleMedium)
                Text("Images, videos and lip sync · ${Studio.models.size} models via kie.ai · " +
                    when { ready -> "on"; vm.settings.studio.enabled -> "needs an API key"; else -> "off" },
                    style = MaterialTheme.typography.bodySmall, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.settings.studio.enabled, onCheckedChange = { on -> vm.updateStudio { it.copy(enabled = on) }; if (on) open = "studio" })
            Icon(if (open == "studio") Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp))
        }
        if (open == "studio") Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioSetup(vm)
        }
    }
}

@Composable
private fun StudioSetup(vm: ChatViewModel) {
    val st = vm.settings.studio
    var key by remember(vm.studioKeyVersion) { mutableStateOf(vm.studioKey()) }
    var show by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    HorizontalDivider()
    Text("The AI gets three tools: studio_image (from text, or edit/combine up to 14 images from the chat), studio_video (from text, or animate an image) and studio_lip_sync (portrait or video + audio). " +
        "Results arrive in the chat as files. Generations use your kie.ai credits.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(key, { key = it.trim() }, label = { Text("kie.ai API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (show) "Hide key" else "Show key") } },
        supportingText = { Text("Get one at kie.ai (API Key page). Stored encrypted on this phone and only sent to api.kie.ai.") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(enabled = key != vm.studioKey(), onClick = { vm.setStudioKey(key); note = if (key.isBlank()) "Key removed." else "Saved." }) { Text("Save key") }
        OutlinedButton(enabled = key.isNotBlank(), onClick = { note = "Checking…"; scope.launch { note = vm.testStudio(key).fold({ it }, { "Couldn't use the key: ${it.message}" }) } }) { Text("Test") }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }

    ModelPicker("Default image model", Studio.of(Studio.Kind.IMAGE), st.imageModel) { id -> vm.updateStudio { it.copy(imageModel = id) } }
    ModelPicker("Default video model", Studio.of(Studio.Kind.VIDEO), st.videoModel) { id -> vm.updateStudio { it.copy(videoModel = id) } }
    Text("The AI can pick another model per request; edits with reference images switch to an edit model automatically.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask before each generation", style = MaterialTheme.typography.bodyMedium)
            Text("Shows Approve / Always / Deny with the model and prompt, since each one costs credits.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = st.confirm, onCheckedChange = { on -> vm.updateStudio { it.copy(confirm = on) } })
    }
    Text("Based on Open-Higgsfield-AI (MIT): github.com/smdc19812011/Open-Higgsfield-AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelPicker(label: String, models: List<Studio.Model>, selected: String, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cur = models.firstOrNull { it.id == selected } ?: models.first()
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(cur.label)
                    Text(cur.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                models.forEach { m ->
                    DropdownMenuItem(text = { Column { Text(m.label); Text(m.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        onClick = { menu = false; onPick(m.id) })
                }
            }
        }
    }
}
