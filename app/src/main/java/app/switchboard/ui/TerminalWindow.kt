package app.switchboard.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Top-bar button for the terminal window: shows how many terminals are running; tap to open or minimize the window. */
@Composable
fun TerminalButton(vm: ChatViewModel) {
    val running = vm.terminals.count { it.alive }
    IconButton(onClick = { if (vm.terminals.isEmpty() && !vm.terminalOpen) vm.newShell() else vm.terminalOpen = !vm.terminalOpen }) {
        BadgedBox(badge = { if (vm.terminals.isNotEmpty()) Badge(containerColor = if (running > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) { Text("${vm.terminals.size}") } }) {
            Icon(Icons.Default.Terminal, if (vm.terminalOpen) "Minimize terminals" else "Open terminals")
        }
    }
}

private val darkBg = Color(0xFF101214)

/**
 * Floating terminal window over the chat: tabs for each terminal (the model's and the user's), an xterm.js view the user can type in,
 * and an extra-keys row for keys a phone keyboard lacks. Drag the title bar to move it; minimizing keeps the programs running.
 */
@Composable
fun TerminalWindow(vm: ChatViewModel) {
    var maximized by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf(IntOffset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = if (maximized) maxWidth else (maxWidth * 0.94f).coerceAtMost(900.dp)
        val h = if (maximized) maxHeight else (maxHeight * 0.62f).coerceAtLeast(260.dp).coerceAtMost(maxHeight)
        val density = LocalDensity.current
        val maxX = with(density) { ((maxWidth - w) / 2).toPx() }; val maxY = with(density) { (maxHeight - h).toPx() }
        Surface(
            shape = RoundedCornerShape(if (maximized) 0.dp else 14.dp), tonalElevation = 6.dp, shadowElevation = 12.dp, color = darkBg,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset { if (maximized) IntOffset.Zero else IntOffset(drag.x.coerceIn(-maxX.roundToInt(), maxX.roundToInt()), drag.y.coerceIn(0, maxY.roundToInt().coerceAtLeast(0))) }
                .width(w).height(h).padding(top = if (maximized) 0.dp else 4.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Title bar: tabs, new shell, maximize, minimize. Drag it to move the window.
                Row(Modifier.fillMaxWidth().background(Color(0xFF1C1F22))
                    .pointerInput(maximized) { if (!maximized) detectDragGestures { c, d -> c.consume(); drag = IntOffset(drag.x + d.x.roundToInt(), drag.y + d.y.roundToInt()) } }
                    .padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        vm.terminals.forEach { t ->
                            val sel = t.id == vm.terminalSelected
                            Row(Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) Color(0xFF2E3338) else Color.Transparent)
                                .clickable { vm.terminalSelected = t.id }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (t.alive) Color(0xFF5BC97A) else Color(0xFF777777)))
                                Spacer(Modifier.width(6.dp))
                                Text(t.title, color = Color(0xFFE6E6E6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                                IconButton(onClick = { vm.termClose(t.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Close, "Close ${t.title}", tint = Color(0xFFAAAAAA), modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                    IconButton(onClick = { vm.newShell() }) { Icon(Icons.Default.Add, "New shell", tint = Color(0xFFE6E6E6)) }
                    IconButton(onClick = { maximized = !maximized }) { Icon(if (maximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (maximized) "Restore" else "Maximize", tint = Color(0xFFE6E6E6)) }
                    IconButton(onClick = { vm.terminalOpen = false }) { Icon(Icons.Default.Minimize, "Minimize", tint = Color(0xFFE6E6E6)) }
                }
                val sel = vm.terminals.firstOrNull { it.id == vm.terminalSelected }
                if (sel == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No terminals. Tap + for a shell, or ask the model to run an interactive program.", color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(24.dp))
                    }
                } else {
                    XtermView(vm, sel.id, Modifier.weight(1f).fillMaxWidth())
                    if (!sel.alive) Text("Exited" + (sel.exitCode?.let { " with code $it" } ?: ""), color = Color(0xFFAAAAAA), fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                }
            }
        }
    }
}

private class XtermBridge(val onInput: (ByteArray) -> Unit, val onResize: (Int, Int) -> Unit, val onReady: () -> Unit, val onCtrlUsed: () -> Unit) {
    @JavascriptInterface fun input(s: String) = onInput(s.toByteArray())
    @JavascriptInterface fun inputBinary(b64: String) = onInput(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
    @JavascriptInterface fun resize(cols: Int, rows: Int) = onResize(cols, rows)
    @JavascriptInterface fun ready() = onReady()
    @JavascriptInterface fun ctrlUsed() = onCtrlUsed()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XtermView(vm: ChatViewModel, id: String, modifier: Modifier) {
    var ready by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(100 to 30) }
    var font by rememberSaveable { mutableStateOf(13) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentId by androidx.compose.runtime.rememberUpdatedState(id)
    val bridge = remember {
        XtermBridge(onInput = { vm.termInput(currentId, it) }, onResize = { c, r -> main.post { size = c to r; vm.termResize(currentId, c, r) } },
            onReady = { main.post { ready = true } }, onCtrlUsed = { main.post { ctrl = false } })
    }
    var web by remember { mutableStateOf<WebView?>(null) }
    fun js(code: String) = web?.evaluateJavascript(code, null)

    Column(modifier) {
        AndroidView(factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                setBackgroundColor(android.graphics.Color.rgb(16, 18, 20))
                addJavascriptInterface(bridge, "SB")
                loadUrl("file:///android_asset/xterm/index.html")
                web = this
            }
        }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.destroy() })

        // Redraw the selected terminal from its kept output, then follow new output.
        LaunchedEffect(id, ready) {
            if (!ready) return@LaunchedEffect
            js("sbReset()")
            val (snap, upTo) = withContext(Dispatchers.Default) { vm.terms.snapshot(id) }
            snap.asSequence().chunked(48_000).forEach { js("sbWrite('${android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)}')") }
            js("sbRefit()")
            vm.termResize(id, size.first, size.second)
            vm.terms.output.filter { it.id == id && it.end > upTo }.collect {
                js("sbWrite('${android.util.Base64.encodeToString(it.data, android.util.Base64.NO_WRAP)}')")
            }
        }
        LaunchedEffect(font, ready) { if (ready) js("sbFont($font)") }

        // Keys a phone keyboard doesn't have.
        val keys = listOf("Esc" to "\u001B", "Tab" to "\t", "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C",
            "|" to "|", "-" to "-", "/" to "/", "~" to "~", "Home" to "\u001B[H", "End" to "\u001B[F", "PgUp" to "\u001B[5~", "PgDn" to "\u001B[6~")
        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1F22)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyChip("Ctrl", active = ctrl) { ctrl = !ctrl; js("sbCtrl($ctrl)") }
            KeyChip("^C") { vm.termInput(id, byteArrayOf(3)) }
            keys.forEach { (label, seq) -> KeyChip(label) { vm.termInput(id, seq.toByteArray()); js("sbFocus()") } }
            KeyChip("A−") { font = (font - 1).coerceAtLeast(8) }
            KeyChip("A+") { font = (font + 1).coerceAtMost(24) }
        }
    }
    DisposableEffect(Unit) { onDispose { web = null } }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF2E3338))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else Color(0xFFE6E6E6), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
