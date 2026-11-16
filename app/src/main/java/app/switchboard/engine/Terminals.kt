package app.switchboard.engine

import app.switchboard.net.ApiException
import app.switchboard.net.Runner
import app.switchboard.net.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

/** One interactive terminal running on the tool machine. */
data class TermInfo(val id: String, val title: String, val command: String, val alive: Boolean = true, val exitCode: Int? = null)

/** Output bytes for terminal [id], for the terminal window. */
/** [end] = total bytes received by that terminal once [data] is included (to skip chunks already in a snapshot). */
class TermOutput(val id: String, val data: ByteArray, val end: Long)

/**
 * Keeps interactive terminals (pseudo-terminals in runner.py) in sync: polls their output, keeps up to 1 MB per terminal
 * so the window can redraw after being minimized, and gives the model a plain-text view. Pure JVM; tested against runner.py.
 */
class TerminalHub(private val scope: CoroutineScope, private val onChange: (List<TermInfo>) -> Unit) {
    private class Session(var info: TermInfo, val runner: Runner) {
        val raw = ByteArrayOutputStream()
        var offset = 0L
        var modelSeen = 0
        var received = 0L
        var job: Job? = null
        @Volatile var lastOutputAt = 0L
    }

    private val sessions = LinkedHashMap<String, Session>()
    private val _output = MutableSharedFlow<TermOutput>(extraBufferCapacity = 256)
    val output: SharedFlow<TermOutput> = _output

    val list: List<TermInfo> get() = synchronized(sessions) { sessions.values.map { it.info } }

    private fun changed() = onChange(list)

    suspend fun start(runner: Runner, command: String, title: String?, cwd: String? = null, cols: Int = 100, rows: Int = 30): TermInfo {
        val o = runner.termStart(command, cwd, cols, rows)
        val id = (o["id"] as? JsonPrimitive)?.content ?: throw ApiException("The runner didn't start a terminal.")
        val name = title?.takeIf { it.isNotBlank() } ?: command.ifBlank { "Shell" }.take(32)
        val s = Session(TermInfo(id, name, command.ifBlank { "shell" }), runner)
        synchronized(sessions) { sessions[id] = s }
        s.job = scope.launch { pump(s) }
        changed()
        return s.info
    }

    private suspend fun pump(s: Session) {
        var failures = 0
        while (scope.isActive) {
            val chunk = try { s.runner.termRead(s.info.id, s.offset, 15).also { failures = 0 } } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (++failures >= 5) { s.info = s.info.copy(alive = false); changed(); return }
                delay(1000L * failures); continue
            }
            if (chunk.data.isNotEmpty()) {
                val end = synchronized(s.raw) {
                    s.raw.write(chunk.data)
                    s.received += chunk.data.size
                    if (s.raw.size() > 1_000_000) { // keep the newest 1 MB
                        val keep = s.raw.toByteArray().let { it.copyOfRange(it.size - 800_000, it.size) }
                        s.modelSeen = (s.modelSeen - (s.raw.size() - keep.size)).coerceAtLeast(0)
                        s.raw.reset(); s.raw.write(keep)
                    }
                    s.received
                }
                s.lastOutputAt = System.currentTimeMillis()
                _output.emit(TermOutput(s.info.id, chunk.data, end))
            }
            s.offset = chunk.offset
            if (!chunk.alive) { s.info = s.info.copy(alive = false, exitCode = chunk.exitCode); changed(); return }
        }
    }

    /** Waits up to [maxMs] for output the model hasn't seen, then until it pauses for [quietMs] (so a whole screen arrives). */
    suspend fun awaitOutput(id: String, maxMs: Long, quietMs: Long = 600) {
        val s = sessions[id] ?: return
        val end = System.currentTimeMillis() + maxMs
        fun unseen() = synchronized(s.raw) { s.raw.size() } > s.modelSeen
        while (System.currentTimeMillis() < end && s.info.alive && !unseen()) delay(100)
        while (System.currentTimeMillis() < end && s.info.alive && System.currentTimeMillis() - s.lastOutputAt < quietMs) delay(100)
    }

    /** The kept output of terminal [id] (at most [max] bytes, newest) and the byte count it reaches, for redrawing the window. */
    fun snapshot(id: String, max: Int = 300_000): Pair<ByteArray, Long> = sessions[id]?.let { s ->
        synchronized(s.raw) { val b = s.raw.toByteArray(); (if (b.size > max) b.copyOfRange(b.size - max, b.size) else b) to s.received }
    } ?: (ByteArray(0) to 0L)

    suspend fun send(id: String, data: ByteArray) { (sessions[id] ?: throw ApiException("No terminal '$id'.")).let { if (it.info.alive) it.runner.termWrite(id, data) } }
    suspend fun resize(id: String, cols: Int, rows: Int) { sessions[id]?.takeIf { it.info.alive }?.runner?.termResize(id, cols, rows) }
    suspend fun stop(id: String) { sessions[id]?.takeIf { it.info.alive }?.runner?.termKill(id) }

    /** Removes a terminal from the list (stopping it first if it's still running). */
    suspend fun close(id: String) {
        runCatching { stop(id) }
        synchronized(sessions) { sessions.remove(id) }?.job?.cancel()
        changed()
    }

    /** What the model sees: new output since its last look (cleaned of terminal codes), else the last screenful. */
    fun modelView(id: String): String {
        val s = sessions[id] ?: return "No terminal '$id'. Open terminals: ${list.joinToString { "${it.id} (${it.title})" }.ifEmpty { "none" }}"
        val all = synchronized(s.raw) { s.raw.toByteArray() }
        val fresh = all.copyOfRange(s.modelSeen.coerceAtMost(all.size), all.size)
        s.modelSeen = all.size
        val status = if (s.info.alive) "running" else "exited with code ${s.info.exitCode}"
        val text = if (fresh.isNotEmpty()) "New output:\n" + clean(fresh.decodeToString()).takeLast(6000)
        else "No new output. Last lines on screen:\n" + clean(all.decodeToString()).lines().takeLast(30).joinToString("\n")
        return "Terminal ${s.info.id} (${s.info.title}) is $status.\n$text"
    }

    companion object {
        private val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val osc = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
        private val esc2 = Regex("\u001B[@-Z\\\\-_()#%][0-9A-Za-z]?")

        /** Terminal output as plain text: no colors or cursor codes; carriage-return redraws keep only the final text. */
        fun clean(s: String): String {
            val noCodes = s.replace(osc, "").replace(csi, "").replace(esc2, "")
            return noCodes.split('\n').joinToString("\n") { line ->
                var l = line.trimEnd('\r')
                l = l.substringAfterLast('\r')
                val sb = StringBuilder()
                for (c in l) when {
                    c == '\b' -> if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                    c == '\t' || c >= ' ' -> sb.append(c)
                }
                sb.toString()
            }
        }

        private val keys = mapOf("ENTER" to "\r", "TAB" to "\t", "ESC" to "\u001B", "BACKSPACE" to "\u007F", "SPACE" to " ",
            "UP" to "\u001B[A", "DOWN" to "\u001B[B", "RIGHT" to "\u001B[C", "LEFT" to "\u001B[D", "HOME" to "\u001B[H", "END" to "\u001B[F",
            "PGUP" to "\u001B[5~", "PGDN" to "\u001B[6~", "DELETE" to "\u001B[3~", "F1" to "\u001BOP", "F2" to "\u001BOQ", "F3" to "\u001BOR",
            "F4" to "\u001BOS", "F10" to "\u001B[21~")

        /** Text with key names: {ENTER} {TAB} {ESC} {UP} {CTRL+C} … become the bytes a keyboard sends. */
        fun keysToBytes(text: String): ByteArray {
            val out = StringBuilder(); var i = 0
            val tok = Regex("^\\{(CTRL\\+[A-Z\\[\\\\\\]^_]|[A-Z0-9]+)\\}")
            while (i < text.length) {
                val m = tok.find(text.substring(i))
                val name = m?.groupValues?.get(1)
                val seq = when {
                    name == null -> null
                    name.startsWith("CTRL+") -> (name.last().code and 0x1f).toChar().toString()
                    else -> keys[name]
                }
                if (seq != null) { out.append(seq); i += m!!.value.length } else { out.append(text[i]); i++ }
            }
            return out.toString().toByteArray()
        }

        private fun obj(required: List<String>, vararg props: Pair<String, String>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (k, d) -> putJsonObject(k) { put("type", if (k == "wait_ms") "integer" else "string"); put("description", d) } } }
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }

        const val START = "terminal_start"; const val SEND = "terminal_send"; const val READ = "terminal_read"; const val STOP = "terminal_stop"
        val names = setOf(START, SEND, READ, STOP)
        /** Talking to a terminal that's already running doesn't need approval; starting one does. */
        val noApproval = setOf(SEND, READ, STOP)

        val tools = listOf(
            ToolSpec(START, "Start an interactive program in a real terminal on the tool machine (Termux/computer): anything that asks questions, " +
                "shows menus or full-screen screens, or keeps running: installers with prompts, serial monitors (picocom, arduino-cli monitor), ssh, " +
                "adb shell, python/node REPLs, htop, nano. It appears in the user's terminal window (they can watch and type too). " +
                "Returns the terminal id and its first output. For simple one-shot commands, use run_command instead.",
                obj(listOf("command"), "command" to "Command to run (empty = a plain shell)", "title" to "Short name for the window tab", "cwd" to "Working directory")),
            ToolSpec(SEND, "Type into a running terminal. Use key names in braces: {ENTER} {TAB} {ESC} {BACKSPACE} {UP} {DOWN} {LEFT} {RIGHT} " +
                "{PGUP} {PGDN} {HOME} {END} {F1}-{F4} {CTRL+C} {CTRL+D} {CTRL+X} etc. Answer a prompt with e.g. \"y{ENTER}\". Returns the output that follows.",
                obj(listOf("id", "text"), "id" to "Terminal id from terminal_start", "text" to "Text and keys to send")),
            ToolSpec(READ, "Read a terminal's new output (or its last screen if nothing new), waiting up to wait_ms for more.",
                obj(listOf("id"), "id" to "Terminal id", "wait_ms" to "How long to wait for new output (default 2000, max 20000)")),
            ToolSpec(STOP, "Stop the program in a terminal (it stays in the user's window list until they close it).", obj(listOf("id"), "id" to "Terminal id")),
        )
    }
}
