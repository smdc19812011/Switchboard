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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LocalModelDialog(vm: ChatViewModel, initial: Provider, isNew: Boolean, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    val lm0 = initial.local ?: LocalModel()
    var name by rememberSaveable { mutableStateOf(if (isNew) "Local model" else initial.name) }
    var path by rememberSaveable { mutableStateOf(lm0.path) }
    var source by rememberSaveable { mutableStateOf(lm0.source) }
    var link by rememberSaveable { mutableStateOf(lm0.source) }
    var token by rememberSaveable { mutableStateOf("") }
    var ctx by rememberSaveable { mutableStateOf(lm0.contextSize.toString()) }
    var threads by rememberSaveable { mutableStateOf(if (lm0.threads > 0) lm0.threads.toString() else "") }
    var keep by rememberSaveable { mutableStateOf(lm0.keepMinutes.toString()) }
    var large by rememberSaveable { mutableStateOf(lm0.largeMode) }
    var processor by rememberSaveable { mutableStateOf(lm0.processor) }
    var gpuLayers by rememberSaveable { mutableStateOf(if (lm0.gpuLayers >= 0) lm0.gpuLayers.toString() else "") }
    var gpuBackend by rememberSaveable { mutableStateOf(lm0.gpuBackend) }
    var devices by remember { mutableStateOf<List<LocalEngine.GpuDevice>?>(null) }
    LaunchedEffect(Unit) { devices = LocalEngine.devices() }
    var info by remember { mutableStateOf<app.switchboard.local.GgufInfo?>(null) }
    var picked by remember { mutableStateOf<ChatViewModel.PickedFile?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) } // re-check file access after returning from settings
    val scope = rememberCoroutineScope()
    val status by LocalEngine.status.collectAsState()
    // Downloads run app-wide, so they keep going (with a notification) when this dialog closes or the screen turns off.
    val transfers by app.switchboard.local.ModelDownloads.all.collectAsState()
    val transfer = transfers[path]
    var lastUrls by rememberSaveable { mutableStateOf(listOf<String>()) }
    var lastTotal by rememberSaveable { mutableStateOf(-1L) }
    // The key between the app and its own engine: made once (the engine only answers requests carrying it).
    val localKey = rememberSaveable { initial.apiKey.ifBlank { vm.settings.providers.firstOrNull { it.id == initial.id }?.apiKey.orEmpty() }.ifBlank { java.util.UUID.randomUUID().toString().replace("-", "") } }
    var savedEarly by rememberSaveable { mutableStateOf(false) }

    fun chose(size: Long) {
        val want = app.switchboard.local.Fit.wantsLargeMode(size, vm.deviceRam())
        if (want && !large) { large = true; if ((ctx.toIntOrNull() ?: 0) > 4096) ctx = "4096" }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = vm.describePicked(uri)
        if (!f.name.endsWith(".gguf", true)) { testNote = "${f.name} isn't a .gguf file."; return@rememberLauncherForActivityResult }
        // Use it where it is when the engine can read it; otherwise offer to copy it (or to allow All files access).
        if (f.path != null && File(f.path).canRead()) { path = f.path; source = ""; picked = null; chose(f.size) } else picked = f
    }
    // Back from the All files access screen: the picked file may be readable in place now.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(refresh) { picked?.let { pf -> pf.path?.let { if (File(it).canRead()) { path = it; source = ""; picked = null; chose(pf.size) } } } }

    val file = File(path)
    val fileDone = file.exists() && transfer?.let { it.finished || it.error != null } != false
    // Read the model's header once the file is there: is it Mixture of Experts? Big files get "Bigger than memory" mode.
    LaunchedEffect(path, fileDone) {
        info = if (fileDone) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { file.inputStream().use { app.switchboard.local.GgufInfo.read(it) } }.getOrNull() } else null
    }
    val fileOk = path.isNotBlank() && (file.exists() || transfer != null)
    val ram = vm.deviceRam()
    fun draft(): Provider {
        val model = initial.model.takeIf { !isNew && it.isNotBlank() && path == lm0.path } ?: file.name.removeSuffix(".gguf").ifBlank { "local" }
        return initial.copy(name = name.trim().ifBlank { "Local model" }, baseUrl = LocalEngine.baseUrl,
            apiKey = localKey,
            model = model, chatModels = listOf(model), imageModels = emptyList(), audioModels = emptyList(), videoModels = emptyList(),
            local = LocalModel(path = path, source = source, contextSize = ctx.toIntOrNull()?.coerceIn(512, 262_144) ?: 8192,
                threads = threads.toIntOrNull()?.coerceIn(0, 32) ?: 0, keepMinutes = keep.toIntOrNull()?.coerceIn(1, 1440) ?: 10, largeMode = large,
                processor = processor, gpuLayers = gpuLayers.toIntOrNull()?.coerceIn(0, 999) ?: -1, gpuBackend = gpuBackend))
    }

    /** Saves the provider as soon as a download or copy starts, so it isn't lost if the dialog closes before it finishes. */
    fun saveNow() { vm.upsertProvider(draft()); savedEarly = true }
    fun download(urls: List<String>, total: Long) {
        lastUrls = urls; lastTotal = total
        path = if (urls.size == 1) vm.downloadModel(urls.first(), token) else vm.downloadModelParts(urls, total, token)
        source = urls.first(); saveNow()
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
                val moe = info?.isMoe ?: app.switchboard.local.GgufInfo.looksMoe(file.name)
                val fit = if (file.exists()) app.switchboard.local.Fit.of(file.length(), ram, moe) else app.switchboard.local.Fit.FITS
                Text(buildString {
                    append(if (file.exists()) ModelFiles.human(file.length()) else if (transfer != null) "Not finished yet" else "File missing")
                    info?.let { i ->
                        i.architecture?.let { append(" · $it") }
                        if (i.isMoe) append(" · Mixture of Experts (${i.expertUsed} of ${i.expertCount} experts per token)")
                        if (i.contextLength > 0) append(" · trained for ${i.contextLength / 1024}k context")
                    }
                    when (fit) {
                        app.switchboard.local.Fit.BIG_MOE -> append("\nBigger than this phone's memory (${ModelFiles.human(ram)} RAM), but as a MoE model it can run from storage: slower, and the first replies warm it up.")
                        app.switchboard.local.Fit.TOO_BIG -> append("\nToo big for this phone's memory (${ModelFiles.human(ram)} RAM)" + if (moe) "." else ": every token uses all of a dense model's weights, so it would crawl reading from storage.")
                        else -> {}
                    }
                    append("\n"); append(path)
                }, style = MaterialTheme.typography.bodySmall, color = when (fit) {
                    app.switchboard.local.Fit.TOO_BIG -> MaterialTheme.colorScheme.error
                    app.switchboard.local.Fit.BIG_MOE -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant })
            } else Text("None yet. Download one by link or choose a .gguf already on the phone.", style = MaterialTheme.typography.bodySmall)

            transfer?.let { t ->
                Text(t.error?.let { "Failed: $it" } ?: if (t.finished) "Done." else "${t.label}: ${ModelFiles.human(t.done)}" + if (t.total > 0) " of ${ModelFiles.human(t.total)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (t.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (!t.finished && t.error == null) {
                    if (t.total > 0) LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.cancelModelTransfer(t.path) }) { Text("Cancel") }
                    if (savedEarly) Text("Saved. The download continues in the background, even with the screen off or this window closed (progress is in the notification).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.error != null && t.label.startsWith("Downloading")) TextButton(onClick = { download(lastUrls.ifEmpty { listOf(link) }, lastTotal) }) { Text("Try again (resumes)") }
            }

            HfSearchSection(vm, token, ram, busy = transfer?.let { !it.finished && it.error == null } == true) { f ->
                if (isNew && (name.isBlank() || name == "Local model")) name = f.name.removeSuffix(".gguf").substringBefore("-00001-of-")
                chose(f.size); link = f.urls.first(); download(f.urls, f.size)
            }
            Text("Or paste a link", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(link, { link = it.trim() }, label = { Text("Download link (.gguf)") }, singleLine = true,
                placeholder = { Text("https://huggingface.co/…/model-Q4_K_M.gguf") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it.trim() }, label = { Text("Access token (only for gated models)") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = link.startsWith("http") && (transfer == null || transfer.finished || transfer.error != null),
                    onClick = { download(listOf(link), -1) }) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.size(6.dp)); Text("Download")
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.size(6.dp)); Text("Choose file") }
            }
            picked?.let { f ->
                Text("${f.name}${if (f.size > 0) " (${ModelFiles.human(f.size)})" else ""}: Switchboard can't read it where it is yet.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { path = vm.copyModel(f.uri, f.name, f.size); source = ""; picked = null; chose(f.size); saveNow() }) { Text("Copy into app") }
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
            // Processor: which accelerators share the model with the CPU.
            val devs = devices
            val hasNpu = devs?.any { it.isNpu } == true
            val hasGpu = devs?.any { !it.isNpu } == true
            val choices = buildList {
                if (hasNpu && hasGpu) add("auto" to "All") else add("auto" to "Auto")
                if (hasNpu && hasGpu) { add("gpu" to "GPU"); add("npu" to "NPU") }
                add("cpu" to "CPU only")
            }
            Text("Processor", style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = processor == id || (id == "auto" && choices.none { it.first == processor }), onClick = { processor = id },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, choices.size)) { Text(label, maxLines = 1) }
                }
            }
            val chosen = devs?.let { LocalEngine.pickDevices(it, processor, gpuBackend) }.orEmpty()
            Text(when {
                devs == null -> "Looking for a GPU and NPU…"
                devs.isEmpty() -> "No usable GPU or NPU found on this phone, so it runs on the CPU."
                processor == "cpu" -> "Found ${devs.joinToString { "${it.description} (${it.api})" }}, not used: CPU only."
                chosen.isEmpty() -> "That isn't available here, so it runs on the CPU."
                else -> "Shares the model between ${chosen.joinToString(" + ") { (if (it.isNpu) "NPU " else "GPU ") + it.description + " (" + it.api + ")" }} and the CPU: " +
                    "as many layers as fit go on the ${if (chosen.size > 1) "NPU and GPU" else if (chosen[0].isNpu) "NPU" else "GPU"}, the rest run on the CPU. If they can't run a model, it falls back by itself."
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // What the engine itself reports, for when a GPU/NPU isn't found.
            var probe by remember { mutableStateOf<String?>(null) }
            val probeScope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    probe = "Checking… (the first check can take up to a minute while the GPU compiles its programs)"
                    probeScope.launch { devices = LocalEngine.devices(force = true); probe = LocalEngine.lastProbe + LocalEngine.gpuFailures.let { f -> if (f.isBlank()) "" else "\n\nLast GPU/NPU attempts that failed:$f" } }
                }) { Text("Check GPU/NPU") }
                if (probe != null && !probe!!.startsWith("Checking")) TextButton(onClick = {
                    val cm = vm.getApplication<android.app.Application>().getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("GPU/NPU check", probe))
                }) { Text("Copy") }
            }
            probe?.let {
                Text(it.lines().filter { l -> l.isNotBlank() }.takeLast(60).joinToString("\n"), style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chosen.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(gpuLayers, { gpuLayers = it.filter(Char::isDigit).take(3) }, label = { Text("GPU layers") }, placeholder = { Text("Auto") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    supportingText = { Text("blank = as many as fit") })
                if (devs.orEmpty().filter { !it.isNpu }.map { it.api }.distinct().size > 1 && processor != "npu") {
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { menu = true }) { Text(when (gpuBackend) { "opencl" -> "OpenCL"; "vulkan" -> "Vulkan"; else -> "Auto" }) }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            listOf("auto" to "Auto (OpenCL on Adreno)", "opencl" to "OpenCL", "vulkan" to "Vulkan").forEach { (id, label) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; gpuBackend = id })
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bigger than memory mode", style = MaterialTheme.typography.bodyMedium)
                    Text("For models larger than this phone's RAM, mainly Mixture of Experts (MoE) models such as Qwen3-30B-A3B or gpt-oss-20b. Weights are read from storage as they're needed instead of loaded up front, the conversation cache is compressed to 8-bit, and work buffers are smaller. Slower, but lets 15–20 GB MoE models run. Use a context of 4096 or less.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = large, onCheckedChange = { large = it })
            }
            MemoryPanel(vm, need = if (file.exists()) file.length() else 0L, large = large)
            val free = remember(path, transfer?.finished) { runCatching { android.os.StatFs(vm.modelsDir().path).availableBytes }.getOrDefault(-1L) }
            if (free >= 0) Text("Free storage: ${ModelFiles.human(free)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("More context remembers more of the chat but uses more memory. With tools on, the tool descriptions alone take a few thousand tokens, so keep at least 8192 or turn tools off for this chat.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = vm.localSupported && file.exists() && transfer?.let { it.finished || it.error != null } != false && testNote?.startsWith("Loading") != true, onClick = {
                    testNote = "Loading the model…"
                    scope.launch { testNote = vm.loadLocal(draft()).fold({ it }, { "Couldn't load it: ${it.message}" }) }
                }) { Text("Load now to test") }
                if (status != null && testNote?.startsWith("Loading") != true) TextButton(onClick = { vm.unloadLocal(); testNote = "Unloaded." }) { Text("Unload") }
            }
            val pr by LocalEngine.progress.collectAsState()
            pr?.takeIf { it.phase != LocalEngine.Phase.IDLE }?.let { LocalProgressBar(it, Modifier.padding(0.dp)) }
            (testNote?.takeUnless { it.startsWith("Loading") && pr != null } ?: status?.let { if (it.startsWith("Loading")) null else "Loaded now: $it" + (pr?.let { p -> " · ${ModelFiles.human(p.residentBytes)} in memory" } ?: "") })?.let {
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
            // Peek at the first file's header to see whether the model is MoE (can run bigger than RAM); fall back to the name.
            var peek by remember(r.id) { mutableStateOf<app.switchboard.local.GgufInfo?>(null) }
            LaunchedEffect(r.id) { fs.firstOrNull()?.let { f -> peek = runCatching { vm.hf.peek(f.urls.first(), token) }.getOrNull() } }
            val moe = peek?.takeIf { it.architecture != null }?.isMoe ?: (app.switchboard.local.GgufInfo.looksMoe(r.id) || fs.any { app.switchboard.local.GgufInfo.looksMoe(it.name) })
            fun fitOf(f: app.switchboard.local.HfSearch.GgufFile) = app.switchboard.local.Fit.of(f.size, ram, moe)
            val free = remember { runCatching { android.os.StatFs(vm.modelsDir().path).availableBytes }.getOrDefault(-1L) }
            Text(buildString {
                peek?.architecture?.let { append("$it · ") }
                if (moe) append("Mixture of Experts" + (peek?.takeIf { it.isMoe }?.let { " (${it.expertUsed} of ${it.expertCount} experts per token)" } ?: "") + ": can run bigger than memory, slower")
                else append("Dense model: needs to fit in memory")
            }, style = MaterialTheme.typography.labelSmall, color = if (moe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp))
            val rec = app.switchboard.local.HfSearch.recommended(fs.filter { fitOf(it) == app.switchboard.local.Fit.FITS }) ?: app.switchboard.local.HfSearch.recommended(fs.filter { fitOf(it) != app.switchboard.local.Fit.TOO_BIG })
            fs.forEach { f ->
                val fit = fitOf(f)
                val tooBig = fit == app.switchboard.local.Fit.TOO_BIG
                val noRoom = free in 0 until f.size
                Row(Modifier.fillMaxWidth().padding(start = 24.dp).clickable(enabled = !busy) { onDownload(f) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, "Download ${f.name}", tint = if (tooBig || noRoom) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(f.quant.ifBlank { null }, ModelFiles.human(f.size), "${f.parts} parts".takeIf { f.parts > 1 },
                            "recommended".takeIf { f == rec },
                            "bigger than memory: runs from storage".takeIf { fit == app.switchboard.local.Fit.BIG_MOE },
                            "too big for this phone".takeIf { tooBig }, "not enough free storage".takeIf { noRoom }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = when { tooBig || noRoom -> MaterialTheme.colorScheme.error; fit == app.switchboard.local.Fit.BIG_MOE -> MaterialTheme.colorScheme.tertiary
                                f == rec -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
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

/**
 * Live memory readout (free RAM and RAM Plus / swap), whether the chosen model fits right now, and a shortcut to
 * Samsung's RAM Plus setting, which lets Android park other apps on storage to free RAM for the model.
 */
@Composable
private fun MemoryPanel(vm: ChatViewModel, need: Long, large: Boolean) {
    var mem by remember { mutableStateOf(app.switchboard.local.MemoryStatus.read()) }
    var opened by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); mem = app.switchboard.local.MemoryStatus.read() } }
    val m = mem ?: return
    Text("Memory now", style = MaterialTheme.typography.titleSmall)
    Text("${ModelFiles.human(m.freeRam)} free of ${ModelFiles.human(m.totalRam)} RAM" +
        if (m.swapTotal > 0) " · ${if (vm.isSamsung) "RAM Plus" else "virtual memory"} ${ModelFiles.human(m.swapTotal)} (${ModelFiles.human(m.swapUsed)} in use)" else "",
        style = MaterialTheme.typography.bodySmall)
    if (need > 0) {
        // A model in memory needs a bit more than its file; in bigger-than-memory mode it only needs room for its working part.
        val fits = if (large) m.freeRam > minOf(need, 3L shl 30) else m.freeRam > need * 1.1
        Text(when {
            fits && !large -> "Enough free memory for this model right now."
            fits -> "Enough free memory to start; the rest of the model is read from storage as needed."
            else -> "Not enough free memory right now (${ModelFiles.human(m.freeRam)} free). Close other apps" +
                (if (vm.isSamsung) ", or turn up RAM Plus so Android can park them on storage" else "") + ", or pick a smaller file."
        }, style = MaterialTheme.typography.labelSmall, color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
    if (vm.isSamsung) {
        Text("RAM Plus is Samsung's virtual memory: it moves other apps' memory to storage, so more real RAM is left for the model. It doesn't make a model bigger than RAM run faster. " +
            "Set it to the largest size (Settings > Battery and device care > Memory > RAM Plus); the phone restarts to apply it.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { opened = vm.openRamPlus() }) { Text("Open RAM Plus settings") }
        opened?.let { if (it != "RAM Plus") Text(if (it.isEmpty()) "Couldn't open it here: go to Settings > Battery and device care > Memory > RAM Plus."
            else "Opened $it: tap Memory, then RAM Plus.", style = MaterialTheme.typography.labelSmall) }
    }
}
