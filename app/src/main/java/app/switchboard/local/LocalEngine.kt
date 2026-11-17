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

    private data class Key(val path: String, val ctx: Int, val threads: Int, val apiKey: String)

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
        val key = Key(file.path, lm.contextSize.coerceIn(512, 262_144), lm.threads, p.apiKey)
        keepMs = lm.keepMinutes.coerceAtLeast(1) * 60_000L
        touch()
        mutex.withLock {
            if (running == key && proc?.isAlive == true && healthy(key.apiKey)) return
            stopLocked()
            start(bin, key, p.model.ifBlank { file.name })
        }
        touch()
    }

    private suspend fun start(bin: File, key: Key, name: String) = withContext(Dispatchers.IO) {
        killStale()
        synchronized(log) { log.clear() }
        _status.value = "Loading $name…"
        val args = mutableListOf(bin.path, "-m", key.path, "--host", "127.0.0.1", "--port", "$PORT", "-c", "${key.ctx}", "-np", "1",
            "--jinja", "--api-key", key.apiKey, "--no-webui", "-a", name)
        if (key.threads > 0) args += listOf("-t", "${key.threads}")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        bin.parentFile?.let { pb.environment()["LD_LIBRARY_PATH"] = it.path }
        val p = try { pb.start() } catch (e: Exception) { _status.value = null; throw ApiException("Couldn't start the local model engine: ${e.message}") }
        proc = p; running = key
        runCatching { stateDir?.let { File(it, "llama-server.pid").writeText(pidOf(p).toString()) } }
        Thread {
            p.inputStream.bufferedReader().forEachLine { line -> synchronized(log) { log.addLast(line); while (log.size > 200) log.removeFirst() } }
        }.apply { isDaemon = true }.start()
        // Big models take a while to map into memory; the server answers /health with 200 once it's ready.
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (true) {
            if (!p.isAlive) {
                proc = null; running = null; _status.value = null
                throw ApiException("The local model stopped while loading (exit ${p.exitValue()}). " + explain(recentLog(25)))
            }
            if (healthy(key.apiKey)) break
            if (System.currentTimeMillis() > deadline) { stopLocked(); throw ApiException("The local model didn't finish loading within 15 minutes.") }
            delay(300)
        }
        _status.value = name
        startIdleWatch()
    }

    private fun explain(log: String): String {
        val l = log.lowercase()
        val hint = when {
            "failed to allocate" in l || "out of memory" in l || "cannot allocate" in l -> "The phone ran out of memory: try a smaller model (fewer parameters or a lower quant such as Q4_K_M) or a smaller context size."
            "unknown model architecture" in l -> "This llama.cpp build doesn't support that model's architecture yet."
            "failed to load model" in l || "invalid magic" in l || "gguf" in l && "error" in l -> "The file doesn't look like a complete, valid GGUF model (an unfinished download?)."
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

    private fun pidOf(p: Process): Long = runCatching { p.javaClass.getMethod("pid").invoke(p) as Long }.getOrElse {
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong() }.getOrDefault(-1)
    }
}
