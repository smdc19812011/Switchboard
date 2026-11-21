package app.switchboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.switchboard.local.LocalEngine
import app.switchboard.local.ModelFiles

/** One line per step, as shown in the status bar (pure, so it can be tested). */
fun localStatusLines(pr: LocalEngine.Progress): Pair<String, String?> {
    val mem = "${ModelFiles.human(pr.residentBytes)} of ${ModelFiles.human(pr.modelBytes)}"
    val title = when (pr.phase) {
        LocalEngine.Phase.LOADING -> "Loading ${pr.name} into memory"
        LocalEngine.Phase.READING -> "Reading your message" + if (pr.promptTotal > 0) " · ${"%,d".format(pr.promptDone)} of ${"%,d".format(pr.promptTotal)} tokens" else "…"
        LocalEngine.Phase.WRITING -> "Writing" + if (pr.tokensPerSec > 0) " · %.1f tokens/s".format(pr.tokensPerSec) else "…"
        LocalEngine.Phase.IDLE -> "${pr.name} is loaded"
    }
    val pct = (pr.memoryFraction * 100).toInt()
    val memory = when {
        pr.phase == LocalEngine.Phase.LOADING -> "$mem in memory ($pct%)" + if (pr.large) " · bigger-than-memory mode reads the rest from storage as it's needed" else ""
        pr.large || pr.memoryFraction < 0.95 -> "Model in memory: $pct% ($mem)" + if (pct < 95) " · reading weights from storage as needed (slower)" else ""
        else -> null
    }
    // While it reads or writes, how many of its threads are actually running (fewer than set = waiting on storage or memory).
    val threads = if (pr.threads > 0) "Threads: ${pr.threads} of ${pr.cores} cores" +
        (if (pr.phase == LocalEngine.Phase.READING || pr.phase == LocalEngine.Phase.WRITING) " · ${pr.busyThreads} busy now" else "") else null
    // Where the model runs: the GPU and its share of the layers (the rest on the CPU), or the CPU alone.
    val processor = when {
        pr.gpu != null -> {
            val npu = "(Hexagon)" in pr.gpu; val both = " + " in pr.gpu
            val what = when { both -> "NPU + GPU"; npu -> "NPU"; else -> "GPU" }
            val where = if (both) "on the NPU and GPU" else "on the $what"
            "$what: ${pr.gpu}" + (pr.gpuLayers?.let { l ->
                val (on, all) = l.split('/').map { it.toIntOrNull() ?: 0 }.let { it[0] to it.getOrElse(1) { 0 } }
                if (on >= all && all > 0) " · all $all layers $where" else " · $on of $all layers $where, the rest on the CPU"
            } ?: "") + (pr.gpuNote?.let { " · $it" } ?: "")
        }
        pr.phase != LocalEngine.Phase.LOADING && pr.threads > 0 -> "Running on the CPU" + (pr.gpuNote?.let { " · $it" } ?: "")
        else -> null
    }
    val detail = listOfNotNull(memory, processor, threads).joinToString("\n").ifEmpty { null }
    return title to detail
}

/**
 * Status bar for the on-phone model: loading into memory, reading the prompt, writing (with speed), plus how much of
 * the model is in memory, which matters most for MoE and bigger-than-memory models that read weights from storage.
 */
@Composable
fun LocalProgressBar(pr: LocalEngine.Progress, modifier: Modifier = Modifier) {
    val (title, detail) = localStatusLines(pr)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, detail).joinToString(". ") }) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        when (pr.phase) {
            LocalEngine.Phase.LOADING ->
                if (pr.residentBytes > 0 && !pr.large) LinearProgressIndicator(progress = { pr.memoryFraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            LocalEngine.Phase.READING ->
                if (pr.promptTotal > 0) LinearProgressIndicator(progress = { pr.promptFraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            LocalEngine.Phase.WRITING -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
            LocalEngine.Phase.IDLE -> {}
        }
        detail?.let {
            Spacer(Modifier.height(4.dp))
            // A thin second bar: how much of the model is in memory.
            if (pr.phase != LocalEngine.Phase.LOADING) LinearProgressIndicator(progress = { pr.memoryFraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
            // After a GPU/NPU fallback: copy the engine's logs of the failed attempts (to share, e.g. for a bug report).
            if (pr.gpuNote != null && LocalEngine.gpuFailures.isNotBlank()) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.TextButton(onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("GPU/NPU log", LocalEngine.lastProbe + "\n\nFailed GPU/NPU attempts:" + LocalEngine.gpuFailures))
                    android.widget.Toast.makeText(ctx, "GPU/NPU log copied", android.widget.Toast.LENGTH_SHORT).show()
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Copy GPU/NPU log", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
