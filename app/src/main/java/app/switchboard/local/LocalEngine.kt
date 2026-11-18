package app.switchboard.local

import app.switchboard.data.Provider
import app.switchboard.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs GGUF models on the phone with llama.cpp's llama-server (shipped in the APK as libllama-server.so), listening on
 * 127.0.0.1 with a random key. Local providers then talk to it like any OpenAI-compatible API, so streaming, tool calls
 * and chat history work the same as for online providers. One model is loaded at a time; it unloads after sitting idle.
 */
object LocalEngine {
    /** Set at startup: the llama-server executable and a folder for its pid file. */
    @Volatile var binary: File? = null
    @Volatile var stateDir: File? = null
    const val PORT = 8737

    private data class Key(val path: String, val ctx: Int, val threads: Int, val apiKey: String, val large: Boolean = false)

    private val mutex = Mutex()
    private var proc: Process? = null
    private var running: Key? = null
    @Volatile private var lastUsed = 0L
    @Volatile private var keepMs = 10 * 60_000L
    private val log = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    private val _status = MutableStateFlow<String?>(null)
    /** "Loading model…" while starting, the loaded model's name while running, null when stopped. */
    val status: StateFlow<String?> = _status

    /** What the engine is doing, for the progress bar. */
    enum class Phase { LOADING, READING, WRITING, IDLE }

    /**
     * [residentBytes] is how much of the model file is actually in memory right now (it grows while a model loads, and
     * for MoE / bigger-than-memory models while it reads weights from storage). [promptDone]/[promptTotal] is the prompt
     * being read (tokens), [tokensPerSec] the writing speed.
     */
    data class Progress(
        val phase: Phase, val name: String, val modelBytes: Long, val residentBytes: Long = 0, val large: Boolean = false,
        val promptDone: Int = 0, val promptTotal: Int = 0, val tokens: Int = 0, val tokensPerSec: Double = 0.0,
    ) {
        val memoryFraction get() = if (modelBytes > 0) (residentBytes.toDouble() / modelBytes).coerceIn(0.0, 1.0) else 0.0
        val promptFraction get() = if (promptTotal > 0) (promptDone.toDouble() / promptTotal).coerceIn(0.0, 1.0) else 0.0
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private var monitorJob: Job? = null
    @Volatile private var writeStart = 0L

    /** A chat request to the local model began (the server reads the prompt first). */
    fun requestStarted() { touch(); _progress.value = _progress.value?.copy(phase = Phase.READING, promptDone = 0, promptTotal = 0, tokens = 0, tokensPerSec = 0.0) }

    /** From the server's prompt_progress updates: [done] of [total] prompt tokens read (cached ones count as read). */
    fun promptProgress(done: Int, total: Int) { touch(); _progress.value = _progress.value?.copy(phase = Phase.READING, promptDone = done, promptTotal = total) }

    /** One more piece of the reply arrived (about one token). */
    fun tokenArrived() {
        touch()
        val cur = _progress.value ?: return
        val now = System.currentTimeMillis()
        if (cur.phase != Phase.WRITING) writeStart = now
        val n = if (cur.phase != Phase.WRITING) 1 else cur.tokens + 1
        val secs = (now - writeStart) / 1000.0
        _progress.value = cur.copy(phase = Phase.WRITING, promptDone = cur.promptTotal, tokens = n, tokensPerSec = if (secs > 0.5) (n - 1) / secs else cur.tokensPerSec)
    }

    fun requestEnded() { _progress.value = _progress.value?.copy(phase = Phase.IDLE) }

    /** Bytes of the model file mapped into the engine's memory (RssFile in /proc), or -1 if unknown. */
    private fun residentOf(pid: Long): Long = runCatching {
        File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("RssFile:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024) ?: -1L
    }.getOrDefault(-1L)

    /** Polls how much of the model is in memory while the engine runs (twice a second while busy, slower when idle). */
    private fun startMonitor(p: Process, pid: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && p.isAlive) {
                val r = residentOf(pid)
                if (r >= 0) _progress.value = _progress.value?.let { it.copy(residentBytes = minOf(r, it.modelBytes)) }
                delay(if (_progress.value?.phase == Phase.IDLE) 3000 else 500)
            }
        }
    }

    val baseUrl get() = "http://127.0.0.1:$PORT/v1"
    val isRunning get() = proc?.isAlive == true

    /** Called while a reply streams, so a long answer doesn't count as idle time. */
    fun touch() { lastUsed = System.currentTimeMillis() }

    fun recentLog(lines: Int = 15): String = synchronized(log) { log.takeLast(lines).joinToString("\n") }

    /** Makes sure [p]'s model is loaded and answering (starting or switching the server if needed). */
    suspend fun ensure(p: Provider) {
        val lm = p.local ?: return
        val bin = binary ?: throw ApiException("Local models aren't available in this build.")
        if (!bin.canExecute()) throw ApiException("Local models need a 64-bit ARM phone (arm64-v8a); this device isn't supported.")
        val file = File(lm.path)
        if (lm.path.isBlank()) throw ApiException("${p.name}: no model file yet. Edit the provider to download a .gguf or choose one on the phone.")
        if (File(lm.path + ".part").exists() && !file.exists()) throw ApiException("${p.name}: the model is still downloading.")
        if (!file.canRead()) throw ApiException("${p.name}: can't read ${lm.path}. Choose the file again (or allow All files access).")
        val key = Key(file.path, lm.contextSize.coerceIn(512, 262_144), lm.threads, p.apiKey, lm.largeMode)
        keepMs = lm.keepMinutes.coerceAtLeast(1) * 60_000L
        touch()
        mutex.withLock {
            if (running == key && proc?.isAlive == true && healthy(key.apiKey)) return
            stopLocked()
            try { start(bin, key, p.model.ifBlank { file.name }, quantCache = key.large) }
            catch (e: ApiException) {
                // Some models can't use the 8-bit conversation cache (head size not a multiple of 32): use the normal one.
                if (key.large && "cache type" in recentLog(40)) start(bin, key, p.model.ifBlank { file.name }, quantCache = false) else throw e
            }
        }
        touch()
    }

    private suspend fun start(bin: File, key: Key, name: String, quantCache: Boolean) = withContext(Dispatchers.IO) {
        killStale()
        synchronized(log) { log.clear() }
        _status.value = "Loading $name…"
        _progress.value = Progress(Phase.LOADING, name, File(key.path).length(), large = key.large)
        val args = mutableListOf(bin.path, "-m", key.path, "--host", "127.0.0.1", "--port", "$PORT", "-c", "${key.ctx}", "-np", "1",
            "--jinja", "--api-key", key.apiKey, "--no-webui", "-a", name)
        if (key.threads > 0) args += listOf("-t", "${key.threads}")
        args += largeModeArgs(key.large, quantCache)
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        bin.parentFile?.let { pb.environment()["LD_LIBRARY_PATH"] = it.path }
        val p = try { pb.start() } catch (e: Exception) { _status.value = null; throw ApiException("Couldn't start the local model engine: ${e.message}") }
        proc = p; running = key
        val pid = pidOf(p)
        runCatching { stateDir?.let { File(it, "llama-server.pid").writeText(pid.toString()) } }
        if (pid > 0) startMonitor(p, pid)
        Thread {
            p.inputStream.bufferedReader().forEachLine { line -> synchronized(log) { log.addLast(line); while (log.size > 200) log.removeFirst() } }
        }.apply { isDaemon = true }.start()
        // Big models take a while to map into memory; the server answers /health with 200 once it's ready.
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (true) {
            if (!p.isAlive) {
                proc = null; running = null; _status.value = null; _progress.value = null
                throw ApiException("The local model stopped while loading (exit ${p.exitValue()}). " + explain(recentLog(25), p.exitValue()))
            }
            if (healthy(key.apiKey)) break
            if (System.currentTimeMillis() > deadline) { stopLocked(); throw ApiException("The local model didn't finish loading within 15 minutes.") }
            delay(300)
        }
        _status.value = name
        _progress.value = _progress.value?.copy(phase = Phase.IDLE)
        startIdleWatch()
    }

    /**
     * Bigger-than-memory mode: map the file and let Android page weights in from storage as they're used (no warm-up pass
     * that would touch every weight), 8-bit conversation cache (needs flash attention), and smaller work buffers.
     */
    fun largeModeArgs(large: Boolean, quantCache: Boolean = true): List<String> = if (!large) emptyList()
        else listOf("-lm", "mmap", "--no-warmup", "-b", "512", "-ub", "128") + if (quantCache) listOf("-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0") else emptyList()

    private fun explain(log: String, exit: Int = 0): String {
        val l = log.lowercase()
        val hint = when {
            exit == 137 || exit == 9 -> "Android stopped the engine to free memory. Try a smaller model or context, turn on \"Bigger than memory\" mode, or close other apps."
            "failed to allocate" in l || "out of memory" in l || "cannot allocate" in l -> "The phone ran out of memory: try a smaller model (fewer parameters or a lower quant such as Q4_K_M) or a smaller context size."
            "unknown model architecture" in l -> "This llama.cpp build doesn't support that model's architecture yet."
            "invalid magic" in l || "failed to read magic" in l || ("failed to load model" in l && "create context" !in l) -> "The file doesn't look like a complete, valid GGUF model (an unfinished download?)."
            else -> ""
        }
        return (hint + "\n" + log.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")).trim()
    }

    private fun healthy(apiKey: String): Boolean = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$PORT/health").header("Authorization", "Bearer $apiKey").build()).execute().use { it.code == 200 }
    }.getOrDefault(false)

    private fun startIdleWatch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (System.currentTimeMillis() - lastUsed > keepMs) { stop(); break }
            }
        }
    }

    /** Unloads the model and frees its memory. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        idleJob?.cancel(); idleJob = null
        proc?.let { p ->
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        proc = null; running = null; _status.value = null
        monitorJob?.cancel(); monitorJob = null; _progress.value = null
        runCatching { stateDir?.let { File(it, "llama-server.pid").delete() } }
    }

    /** A server left over from an earlier app process (Android killed the app but not its child) still holds memory and the port. */
    private fun killStale() {
        val pidFile = stateDir?.let { File(it, "llama-server.pid") } ?: return
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
        val cmd = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (cmd != null && "llama-server" in cmd) runCatching { android.os.Process.killProcess(pid) }.onFailure { runCatching { ProcessBuilder("kill", "$pid").start().waitFor() } }
        pidFile.delete()
        Thread.sleep(300)
    }

    private fun pidOf(p: Process): Long = runCatching { Process::class.java.getMethod("pid").invoke(p) as Long }.getOrElse {
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong() }.getOrElse { findEnginePid() }
    }

    /** Last resort (older Android): find our engine in /proc by its command line (same user, so readable). */
    private fun findEnginePid(): Long {
        val bin = binary?.path ?: return -1
        return File("/proc").listFiles()?.asSequence()?.mapNotNull { it.name.toLongOrNull() }?.firstOrNull { pid ->
            runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()?.let { it.startsWith(bin) && "--port\u0000$PORT" in it } == true
        } ?: -1
    }
}
