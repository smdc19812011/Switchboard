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
import androidx.compose.material.icons.filled.Science
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
    val h = vm.settings.heretic
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = if (open == "heretic") "" else "heretic" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Science, null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Heretic", style = MaterialTheme.typography.titleMedium)
                Text("Modify language models on your computer (remove refusals, less slop…) · " +
                    when { !h.enabled -> "off"; !vm.hereticOnComputer -> "needs the computer runner"; else -> "on" },
                    style = MaterialTheme.typography.bodySmall, color = if (h.enabled && vm.hereticOnComputer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = h.enabled, onCheckedChange = { on -> vm.updateHeretic { it.copy(enabled = on) }; if (on) open = "heretic" })
            Icon(if (open == "heretic") Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp))
        }
        if (open == "heretic") Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HereticSetup(vm)
        }
    }
}

@Composable
private fun HereticSetup(vm: ChatViewModel) {
    val h = vm.settings.heretic
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf(h.lastModel.ifBlank { "Qwen/Qwen3-4B-Instruct-2507" }) }
    var trials by rememberSaveable { mutableStateOf(h.trials.toString()) }
    var runNote by remember { mutableStateOf<String?>(null) }
    HorizontalDivider()
    Text("Heretic changes a model's behaviour without training: by default it removes refusals; presets reduce slop, sycophancy or humor, or shorten answers. " +
        "It runs on your computer (Python + PyTorch, ideally an NVIDIA GPU; a 4B model takes about 30–60 minutes), then the result can come to this phone as a local GGUF model. " +
        "The AI can also use it (heretic_run, heretic_to_phone).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!vm.hereticOnComputer) {
        Text("Set Settings > Tools to Computer and start runner.py on a computer with a GPU (Linux, macOS, or WSL on Windows) to use Heretic.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    Text("1. Set up the computer", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { check = "Checking…"; scope.launch { check = vm.hereticCheck().fold({ it }, { "Couldn't check: ${it.message}" }) } }) { Text("Check computer") }
        FilledTonalButton(onClick = { vm.hereticInstall() }) { Text("Install / update") }
    }
    check?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }

    Text("2. Process a model", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(model, { model = it.trim() }, label = { Text("Hugging Face model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Model id (safetensors), e.g. Qwen/Qwen3-4B-Instruct-2507, or a folder on the computer") })
    GoalPicker(h.goal) { g -> vm.updateHeretic { it.copy(goal = g) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(trials, { trials = it.filter(Char::isDigit).take(4) }, label = { Text("Trials") }, singleLine = true, modifier = Modifier.weight(1f),
            supportingText = { Text("fewer = faster") })
        Column(Modifier.weight(2f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("4-bit loading", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = h.quant4bit, onCheckedChange = { on -> vm.updateHeretic { it.copy(quant4bit = on) } })
            }
            Text("Fits bigger models in less GPU memory", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Finish by itself", style = MaterialTheme.typography.bodyMedium)
            Text("Pick the best result and save it to heretic/out on the computer. Off: choose in the terminal (also lets you chat with or benchmark it first).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = h.unattended, onCheckedChange = { on -> vm.updateHeretic { it.copy(unattended = on) } })
    }
    FilledTonalButton(enabled = model.isNotBlank(), onClick = {
        runNote = "Starting…"
        scope.launch { runNote = runCatching { vm.hereticStart(model, h.goal, h.quant4bit, trials.toIntOrNull() ?: 100, h.unattended) }
            .fold({ "Running in the terminal window. It saves to heretic/out/${app.switchboard.engine.Heretic.outName(model)} when done." }, { "Couldn't start: ${it.message}" }) }
    }) { Text("Start Heretic") }
    runNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }

    Text("3. Put it on this phone", style = MaterialTheme.typography.titleSmall)
    var fmtMenu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { fmtMenu = true }) { Text("Format: " + (app.switchboard.engine.Heretic.exportTypes.firstOrNull { it.first == h.format }?.second ?: h.format)) }
        DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
            app.switchboard.engine.Heretic.exportTypes.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { fmtMenu = false; vm.updateHeretic { it.copy(format = id) } }) }
        }
    }
    FilledTonalButton(enabled = model.isNotBlank(), onClick = { scope.launch { runCatching { vm.hereticToPhone(model, h.format) } } }) { Text("Convert and download") }
    Text("Converts heretic/out/${app.switchboard.engine.Heretic.outName(model)} to GGUF with llama.cpp on the computer, downloads it and adds it as a local model.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    vm.hereticStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("Heretic by Philipp Emanuel Weidmann (AGPL-3.0), github.com/p-e-w/heretic: installed on your computer from PyPI, not bundled with Switchboard.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GoalPicker(selected: String, onPick: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cur = app.switchboard.engine.Heretic.goal(selected)
    Column {
        Text("Goal", style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text(cur.label); Text(cur.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                app.switchboard.engine.Heretic.goals.forEach { g ->
                    DropdownMenuItem(text = { Column { Text(g.label); Text(g.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        onClick = { menu = false; onPick(g.id) })
                }
            }
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
