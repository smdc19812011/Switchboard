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
    /** Where the engine runs: the app sets its separate engine process (see EngineService); tests run it in-process. */
    @Volatile var launcher: EngineLauncher = InProcessLauncher
    const val PORT = 8737

    private data class Key(val path: String, val ctx: Int, val threads: Int, val apiKey: String, val large: Boolean = false)

    private val mutex = Mutex()
    private var proc: EngineHandle? = null
    private var running: Key? = null
    @Volatile private var lastUsed = 0L
    @Volatile private var keepMs = 10 * 60_000L
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
        /** Compute threads the engine uses, how many are running right now, and the phone's CPU cores. */
        val threads: Int = 0, val busyThreads: Int = 0, val cores: Int = Runtime.getRuntime().availableProcessors(),
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
    fun promptProgress(done: Int, total: Int) { touch(); if (_progress.value?.let { it.promptDone == done && it.promptTotal == total } == true) return; _progress.value = _progress.value?.copy(phase = Phase.READING, promptDone = done, promptTotal = total) }

    /** One more piece of the reply arrived (about one token). */
    @Volatile private var tokenCount = 0
    @Volatile private var lastPublish = 0L

    fun tokenArrived() {
        touch()
        val cur = _progress.value ?: return
        val now = System.currentTimeMillis()
        if (cur.phase != Phase.WRITING) { writeStart = now; tokenCount = 0; lastPublish = 0 }
        tokenCount++
        // The status bar only needs a few updates a second, not one per token.
        if (cur.phase == Phase.WRITING && now - lastPublish < 250) return
        lastPublish = now
        val secs = (now - writeStart) / 1000.0
        _progress.value = cur.copy(phase = Phase.WRITING, promptDone = cur.promptTotal, tokens = tokenCount,
            tokensPerSec = if (secs > 0.5) (tokenCount - 1) / secs else cur.tokensPerSec)
    }

    fun requestEnded() { _progress.value = _progress.value?.copy(phase = Phase.IDLE, tokens = tokenCount) }

    /** Compute threads from the engine's log, or 0 if it hasn't said yet. */
    fun threadsFromLog(log: String): Int = Regex("n_threads = (\\d+)").findAll(log).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Threads of the engine that are running on a CPU right now (state R in /proc/<pid>/task/<tid>/stat). */
    private fun runningThreads(pid: Long): Int = runCatching {
        File("/proc/$pid/task").listFiles()?.count { t ->
            runCatching { File(t, "stat").readText().substringAfterLast(") ").firstOrNull() == 'R' }.getOrDefault(false)
        } ?: 0
    }.getOrDefault(0)

    /** Bytes of the model file mapped into the engine's memory (RssFile in /proc), or -1 if unknown. */
    private fun residentOf(pid: Long): Long = runCatching {
        File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("RssFile:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024) ?: -1L
    }.getOrDefault(-1L)

    /** Polls how much of the model is in memory while the engine runs (twice a second while busy, slower when idle). */
    private fun startMonitor(p: EngineHandle, pid: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var threads = 0
            while (isActive && p.isAlive) {
                val r = residentOf(pid)
                // The engine logs its thread count once ("llama threadpool init, n_threads = 8").
                if (threads == 0) threads = threadsFromLog(p.log(80))
                val busy = runningThreads(pid)
                _progress.value = _progress.value?.let { pr ->
                    pr.copy(residentBytes = if (r >= 0) minOf(r, pr.modelBytes) else pr.residentBytes, threads = threads,
                        busyThreads = if (threads > 0) busy.coerceAtMost(threads) else busy)
                }
                delay(if (_progress.value?.phase == Phase.IDLE) 3000 else 500)
            }
        }
    }

    val baseUrl get() = "http://127.0.0.1:$PORT/v1"
    val isRunning get() = proc?.isAlive == true

    /** Called while a reply streams, so a long answer doesn't count as idle time. */
    fun touch() { lastUsed = System.currentTimeMillis() }

    fun recentLog(lines: Int = 15): String = proc?.log(lines) ?: lastLog.lines().takeLast(lines).joinToString("\n")
    @Volatile private var lastLog = ""

    /**
     * Why the engine isn't running any more (it died during a reply, e.g. Android stopped it to free memory), for the
     * chat's error message; null if it's running or was stopped on purpose.
     */
    fun whyStopped(): String? {
        val p = proc ?: return null
        if (p.isAlive) return null
        val code = p.exitCode ?: -1
        return "The local model stopped (exit $code). " + (p.stopReason()?.let { "$it " } ?: "") + explain(p.log(25), code)
    }

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
        // All engine work (health checks, waiting for an old engine to exit, starting) stays off the main thread.
        withContext(Dispatchers.IO) { mutex.withLock {
            if (running == key && proc?.isAlive == true && healthy(key.apiKey)) return@withLock
            stopLocked()
            val name = p.model.ifBlank { file.name }
            try { start(bin, key, name, quantCache = key.large) }
            catch (e: ApiException) {
                val log = recentLog(40).lowercase()
                when {
                    // Some models can't use the 8-bit conversation cache (head size not a multiple of 32): use the normal one.
                    key.large && "cache type" in log -> start(bin, key, name, quantCache = false)
                    // The previous engine hadn't released the port yet.
                    "bind" in log || "address already in use" in log -> { delay(1500); start(bin, key, name, quantCache = key.large) }
                    else -> throw e
                }
            }
        } }
        touch()
    }

    private suspend fun start(bin: File, key: Key, name: String, quantCache: Boolean) = withContext(Dispatchers.IO) {
        killStale()
        _status.value = "Loading $name…"
        _progress.value = Progress(Phase.LOADING, name, File(key.path).length(), large = key.large)
        val args = mutableListOf(bin.path, "-m", key.path, "--host", "127.0.0.1", "--port", "$PORT", "-c", "${key.ctx}", "-np", "1",
            "--jinja", "--api-key", key.apiKey, "--no-webui", "-a", name)
        if (key.threads > 0) args += listOf("-t", "${key.threads}")
        args += largeModeArgs(key.large, quantCache)
        val env = bin.parentFile?.let { mapOf("LD_LIBRARY_PATH" to it.path) }.orEmpty()
        val p = try { launcher.start(args, env) } catch (e: Exception) { _status.value = null; _progress.value = null; throw ApiException("Couldn't start the local model engine: ${e.message}") }
        proc = p; running = key
        val pid = p.pid.takeIf { it > 0 } ?: findEnginePid(bin.path, PORT)
        runCatching { stateDir?.let { File(it, "llama-server.pid").writeText(pid.toString()) } }
        if (pid > 0) startMonitor(p, pid)
        // Big models take a while to map into memory; the server answers /health with 200 once it's ready.
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (true) {
            if (!p.isAlive) {
                val code = p.exitCode ?: -1
                val log = p.log(25)
                proc = null; running = null; _status.value = null; _progress.value = null; lastLog = log
                throw ApiException("The local model stopped while loading (exit $code). " + (p.stopReason()?.let { "$it\n" } ?: "") + explain(log, code))
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

    /** Current free memory, plus the RAM Plus tip on Samsung phones (the app sets [isSamsung]). */
    @Volatile var isSamsung = false
    private fun memoryTip(): String {
        val m = MemoryStatus.read()
        val free = m?.let { " (${ModelFiles.human(it.freeRam)} of ${ModelFiles.human(it.totalRam)} free now)" } ?: ""
        return (if (isSamsung) "; on this phone, also set RAM Plus to its largest size (Settings > Battery and device care > Memory > RAM Plus) so Android can park other apps on storage" else "") + "$free."
    }

    private fun explain(log: String, exit: Int = 0): String {
        val l = log.lowercase()
        val hint = when {
            exit == 137 || exit == 9 -> "Android stopped the engine to free memory. Try a smaller model or context, turn on \"Bigger than memory\" mode, or close other apps" + memoryTip()
            "failed to allocate" in l || "out of memory" in l || "cannot allocate" in l -> "The phone ran out of memory: try a smaller model (fewer parameters or a lower quant such as Q4_K_M) or a smaller context size" + memoryTip()
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
    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { stopLocked() } }

    private fun stopLocked() {
        idleJob?.cancel(); idleJob = null
        proc?.let { p -> lastLog = p.log(40); p.stop() }
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
        // The engine process (its own Android process) may also still be holding the port.
        binary?.path?.let { b -> findEnginePid(b, PORT).takeIf { it > 0 }?.let { runCatching { ProcessBuilder("kill", "$it").start().waitFor() } } }
        pidFile.delete()
        Thread.sleep(300)
    }
}
