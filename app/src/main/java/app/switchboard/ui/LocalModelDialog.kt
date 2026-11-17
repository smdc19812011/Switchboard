package app.switchboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.switchboard.data.LocalModel
import app.switchboard.data.Provider
import app.switchboard.local.LocalEngine
import app.switchboard.local.ModelFiles
import kotlinx.coroutines.launch
import java.io.File

/**
 * Add or edit a model that runs on the phone: get a .gguf by link (Hugging Face links work) or pick one already on the
 * phone, then set context size and threads. The built-in llama.cpp engine serves it to chats like any other provider.
 */
@Composable
fun LocalModelDialog(vm: ChatViewModel, initial: Provider, isNew: Boolean, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    val lm0 = initial.local ?: LocalModel()
    var name by remember { mutableStateOf(if (isNew) "Local model" else initial.name) }
    var path by remember { mutableStateOf(lm0.path) }
    var source by remember { mutableStateOf(lm0.source) }
    var link by remember { mutableStateOf(lm0.source) }
    var token by remember { mutableStateOf("") }
    var ctx by remember { mutableStateOf(lm0.contextSize.toString()) }
    var threads by remember { mutableStateOf(if (lm0.threads > 0) lm0.threads.toString() else "") }
    var keep by remember { mutableStateOf(lm0.keepMinutes.toString()) }
    var picked by remember { mutableStateOf<ChatViewModel.PickedFile?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) } // re-check file access after returning from settings
    val scope = rememberCoroutineScope()
    val status by LocalEngine.status.collectAsState()
    val transfer = vm.modelTransfer?.takeIf { it.path == path }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = vm.describePicked(uri)
        if (!f.name.endsWith(".gguf", true)) { testNote = "${f.name} isn't a .gguf file."; return@rememberLauncherForActivityResult }
        // Use it where it is when the engine can read it; otherwise offer to copy it (or to allow All files access).
        if (f.path != null && File(f.path).canRead()) { path = f.path; source = ""; picked = null } else picked = f
    }
    // Back from the All files access screen: the picked file may be readable in place now.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(refresh) { picked?.path?.let { if (File(it).canRead()) { path = it; source = ""; picked = null } } }

    val file = File(path)
    val fileOk = path.isNotBlank() && (file.exists() || transfer != null)
    val ram = vm.deviceRam()
    fun draft(): Provider {
        val model = initial.model.takeIf { !isNew && it.isNotBlank() && path == lm0.path } ?: file.name.removeSuffix(".gguf").ifBlank { "local" }
        return initial.copy(name = name.trim().ifBlank { "Local model" }, baseUrl = LocalEngine.baseUrl,
            apiKey = initial.apiKey.ifBlank { java.util.UUID.randomUUID().toString().replace("-", "") },
            model = model, chatModels = listOf(model), imageModels = emptyList(), audioModels = emptyList(), videoModels = emptyList(),
            local = LocalModel(path = path, source = source, contextSize = ctx.toIntOrNull()?.coerceIn(512, 262_144) ?: 8192,
                threads = threads.toIntOrNull()?.coerceIn(0, 32) ?: 0, keepMinutes = keep.toIntOrNull()?.coerceIn(1, 1440) ?: 10))
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "Add local model" else "Edit local model") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vm.localSupported) Text("This phone isn't supported: local models need a 64-bit ARM (arm64-v8a) processor.", color = MaterialTheme.colorScheme.error)
            Text("Runs a GGUF model on this phone with the built-in llama.cpp engine: private, offline, no API key. Small models (1–4B parameters, Q4_K_M) work best on phones; a model needs a bit more free memory than its file size.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Model file", style = MaterialTheme.typography.titleSmall)
            if (path.isNotBlank()) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(buildString {
                    append(if (file.exists()) ModelFiles.human(file.length()) else if (transfer != null) "Not finished yet" else "File missing")
                    if (ram > 0 && file.exists() && file.length() > ram * 0.7) append(" · larger than this phone's memory can comfortably hold (${ModelFiles.human(ram)} RAM)")
                    append("\n"); append(path)
                }, style = MaterialTheme.typography.bodySmall, color = if (file.exists() && ram > 0 && file.length() > ram * 0.7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("None yet. Download one by link or choose a .gguf already on the phone.", style = MaterialTheme.typography.bodySmall)

            transfer?.let { t ->
                Text(t.error?.let { "Failed: $it" } ?: if (t.finished) "Done." else "${t.label}: ${ModelFiles.human(t.done)}" + if (t.total > 0) " of ${ModelFiles.human(t.total)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (t.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (!t.finished && t.error == null) {
                    if (t.total > 0) LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.cancelModelTransfer() }) { Text("Cancel") }
                }
                if (t.error != null && t.label.startsWith("Downloading")) TextButton(onClick = { path = vm.downloadModel(link, token) }) { Text("Try again (resumes)") }
            }

            HfSearchSection(vm, token, ram, busy = transfer?.let { !it.finished && it.error == null } == true) { f ->
                path = vm.downloadModelParts(f.urls, f.size, token); source = f.urls.first(); link = f.urls.first()
                if (isNew && (name.isBlank() || name == "Local model")) name = f.name.removeSuffix(".gguf").substringBefore("-00001-of-")
            }
            Text("Or paste a link", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(link, { link = it.trim() }, label = { Text("Download link (.gguf)") }, singleLine = true,
                placeholder = { Text("https://huggingface.co/…/model-Q4_K_M.gguf") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it.trim() }, label = { Text("Access token (only for gated models)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = link.startsWith("http") && (transfer == null || transfer.finished || transfer.error != null),
                    onClick = { path = vm.downloadModel(link, token); source = link }) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.size(6.dp)); Text("Download")
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.size(6.dp)); Text("Choose file") }
            }
            picked?.let { f ->
                Text("${f.name}${if (f.size > 0) " (${ModelFiles.human(f.size)})" else ""}: Switchboard can't read it where it is yet.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { path = vm.copyModel(f.uri, f.name, f.size); source = ""; picked = null }) { Text("Copy into app") }
                    if (f.path != null && !vm.hasAllFilesAccess()) OutlinedButton(onClick = { vm.openAllFilesAccessSettings() }) { Text("Allow All files access") }
                }
                Text(if (f.path != null) "Copying uses another ${ModelFiles.human(f.size)} of storage. Allowing All files access uses the file where it is instead (come back here after allowing)."
                    else "This file can only be copied (its location isn't a normal folder).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("Engine", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ctx, { ctx = it.filter(Char::isDigit).take(6) }, label = { Text("Context") }, singleLine = true,
                    supportingText = { Text("tokens") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(threads, { threads = it.filter(Char::isDigit).take(2) }, label = { Text("Threads") }, singleLine = true, placeholder = { Text("Auto") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(keep, { keep = it.filter(Char::isDigit).take(4) }, label = { Text("Unload after") }, singleLine = true,
                    supportingText = { Text("idle min") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Text("More context remembers more of the chat but uses more memory. With tools on, the tool descriptions alone take a few thousand tokens, so keep at least 8192 or turn tools off for this chat.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = vm.localSupported && file.exists() && transfer?.let { it.finished || it.error != null } != false && testNote?.startsWith("Loading") != true, onClick = {
                    testNote = "Loading the model…"
                    scope.launch { testNote = vm.loadLocal(draft()).fold({ it }, { "Couldn't load it: ${it.message}" }) }
                }) { Text("Load now to test") }
                if (status != null && testNote?.startsWith("Loading") != true) TextButton(onClick = { vm.unloadLocal(); testNote = "Unloaded." }) { Text("Unload") }
            }
            (testNote ?: status?.let { if (it.startsWith("Loading")) it else "Loaded now: $it" })?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (!isNew && path.isNotBlank() && file.parentFile?.canonicalPath == vm.modelsDir().canonicalPath && file.exists())
                TextButton(onClick = { vm.unloadLocal(); vm.deleteModelFile(path); path = "" }) { Text("Delete model file (${ModelFiles.human(file.length())})", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = fileOk && name.isNotBlank(), onClick = { onSave(draft()) }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Search Hugging Face for GGUF models, open one to see its files (quantizations) and download the chosen one. */
@Composable
private fun HfSearchSection(vm: ChatViewModel, token: String, ram: Long, busy: Boolean, onDownload: (app.switchboard.local.HfSearch.GgufFile) -> Unit) {
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<app.switchboard.local.HfSearch.Repo>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<app.switchboard.local.HfSearch.GgufFile>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun search() {
        note = "Searching…"; repos = null; open = null; files = null
        scope.launch {
            runCatching { vm.hf.search(query, token) }
                .onSuccess { repos = it; note = if (it.isEmpty()) "No GGUF models found for \"$query\"." else null }
                .onFailure { note = "Search failed: ${it.message}" }
        }
    }
    Text("Search Hugging Face", style = MaterialTheme.typography.labelMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("e.g. qwen 3b, llama 1b, gemma") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (query.isNotBlank()) search() }))
        FilledTonalButton(enabled = query.isNotBlank(), onClick = { search() }) { Icon(Icons.Default.Search, "Search") }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Search failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    repos?.forEach { r ->
        val isOpen = open == r.id
        Column(Modifier.fillMaxWidth().clickable {
            if (isOpen) { open = null; files = null } else {
                open = r.id; files = null; note = "Loading files of ${r.id}…"
                scope.launch {
                    runCatching { vm.hf.files(r.id, token) }
                        .onSuccess { if (open == r.id) { files = it; note = if (it.isEmpty()) "${r.id} has no .gguf files." else null } }
                        .onFailure { note = "Couldn't list files: ${it.message}" + if (r.gated) " (gated model: accept its license on huggingface.co and enter an access token)" else "" }
                }
            }
        }.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(r.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text("↓ ${compact(r.downloads)}  ♥ ${compact(r.likes)}" + if (r.gated) "  · gated" else "", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp))
        }
        if (isOpen) files?.let { fs ->
            val rec = app.switchboard.local.HfSearch.recommended(fs.filter { ram <= 0 || it.size < ram * 0.7 })
            fs.forEach { f ->
                val tooBig = ram > 0 && f.size > ram * 0.7
                Row(Modifier.fillMaxWidth().padding(start = 24.dp).clickable(enabled = !busy) { onDownload(f) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, "Download ${f.name}", tint = if (tooBig) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(f.quant.ifBlank { null }, ModelFiles.human(f.size), "${f.parts} parts".takeIf { f.parts > 1 },
                            "recommended".takeIf { f == rec }, "too big for this phone".takeIf { tooBig }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = when { tooBig -> MaterialTheme.colorScheme.error; f == rec -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                }
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> "$n"
}
