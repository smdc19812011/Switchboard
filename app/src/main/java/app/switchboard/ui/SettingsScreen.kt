package app.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.switchboard.data.ApiKind
import app.switchboard.data.Presets
import app.switchboard.data.Provider
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var confirmDelete by remember { mutableStateOf<Provider?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    val s = vm.settings

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Model providers", style = MaterialTheme.typography.titleMedium)
            Text("The selected provider answers new messages. You can switch mid-conversation; the full history is sent to whichever model is active.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.providers.forEach { p ->
                ListItem(
                    leadingContent = { RadioButton(selected = p.id == vm.activeProvider?.id, onClick = { vm.selectProvider(p.id) }) },
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.model.ifBlank { "no model" }} · ${p.baseUrl}", maxLines = 2) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Edit ${p.name}") }
                            IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, "Delete ${p.name}") }
                        }
                    },
                )
            }
            Box {
                FilledTonalButton(onClick = { presetMenu = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("Add provider") }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    Presets.all.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = { presetMenu = false; editing = preset.copy(id = UUID.randomUUID().toString()) })
                    }
                }
            }

            Spacer(Modifier.size(16.dp))
            Text("Conversation", style = MaterialTheme.typography.titleMedium)
            var sys by remember { mutableStateOf(s.systemPrompt) }
            OutlinedTextField(sys, { sys = it; vm.setSystemPrompt(it) }, label = { Text("System prompt (all providers)") },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            var max by remember { mutableStateOf(s.maxTokens.toString()) }
            OutlinedTextField(max, { v -> max = v.filter(Char::isDigit).take(6); max.toIntOrNull()?.let(vm::setMaxTokens) },
                label = { Text("Max output tokens (Anthropic)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("API keys are encrypted with the Android Keystore and only sent to the provider's own base URL.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editing?.let { p -> ProviderDialog(p, isNew = s.providers.none { it.id == p.id }, onDismiss = { editing = null }, onSave = { vm.upsertProvider(it); editing = null }) }
    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") }, text = { Text("Its saved API key will be removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.removeProvider(p.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(initial: Provider, isNew: Boolean, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var showKey by remember { mutableStateOf(false) }

    val urlOk = (url.startsWith("https://") && url.length > 8) || (url.startsWith("http://") && url.length > 7)
    val keyNeeded = kind == ApiKind.ANTHROPIC
    val valid = name.isNotBlank() && urlOk && (!keyNeeded || key.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add provider" else "Edit provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("API format", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ApiKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, ApiKind.entries.size)) {
                            Text(if (k == ApiKind.ANTHROPIC) "Anthropic" else "OpenAI-compatible")
                        }
                    }
                }
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Base URL") }, singleLine = true,
                    isError = !urlOk, supportingText = { if (!urlOk) Text("Must start with https:// (http:// only for local servers)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it.trim() }, label = { Text(if (keyNeeded) "API key" else "API key (optional for local servers)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key") } })
                OutlinedTextField(model, { model = it.trim() }, label = { Text("Model ID") }, singleLine = true,
                    supportingText = { Text("You can also browse the provider's models from the chat screen.") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), kind = kind, baseUrl = url.trimEnd('/'), apiKey = key, model = model))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
