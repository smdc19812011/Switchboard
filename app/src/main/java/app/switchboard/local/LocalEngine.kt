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

    /** [gpu]: devices to offload to, comma-separated (null = CPU only), [gpuLayers] -1 = as many as fit. */
    private data class Key(val path: String, val ctx: Int, val threads: Int, val apiKey: String, val large: Boolean = false,
                           val gpu: String? = null, val gpuLayers: Int = -1)

    /** A GPU the engine can use, as `--list-devices` reports it (e.g. GPUOpenCL: QUALCOMM Adreno(TM) 650). */
    data class GpuDevice(val name: String, val description: String, val totalMiB: Long, val freeMiB: Long) {
        val api get() = when { name.startsWith("Vulkan") -> "Vulkan"; name.contains("OpenCL") -> "OpenCL"; name.startsWith("HTP") -> "Hexagon"; else -> name }
        /** The Snapdragon NPU (Hexagon HTP); everything else here is a GPU. */
        val isNpu get() = name.startsWith("HTP")
    }

    /** Extra environment for the engine (tests use it to expose a software GPU). */
    @Volatile var extraEnv: Map<String, String> = emptyMap()
    @Volatile private var deviceCache: Pair<String, List<GpuDevice>>? = null

    /** Library folders for the engine: its own, then the phone's GPU drivers (the OpenCL driver lives in /vendor). */
    private fun engineEnv(bin: File) = mapOf(
        // The engine's own libraries, then Android's (libvulkan.so, liblog.so live in /system/lib64: a program started by an app
        // only searches the folders listed here), then the GPU/NPU drivers in /vendor.
        "LD_LIBRARY_PATH" to listOfNotNull(bin.parentFile?.path, "/system/lib64", "/vendor/lib64", "/system/vendor/lib64", "/odm/lib64").joinToString(":"),
        // Where the Snapdragon NPU loader finds the NPU-side programs (libggml-htp-v73…v85.so, shipped next to the engine).
        "ADSP_LIBRARY_PATH" to listOfNotNull(bin.parentFile?.path, "/vendor/lib/rfsa/adsp", "/vendor/lib/rfsa/cdsp", "/vendor/dsp/cdsp", "/vendor/dsp", "/system/lib/rfsa/adsp").joinToString(";"),
    ) + extraEnv

    /** Parses `--list-devices` output. */
    fun parseDevices(out: String): List<GpuDevice> = out.lines().mapNotNull { l ->
        val m = Regex("^\\s+([^:\\s]+): (.*) \\((\\d+) MiB, (\\d+) MiB free\\)\\s*$").find(l) ?: return@mapNotNull null
        GpuDevice(m.groupValues[1], m.groupValues[2], m.groupValues[3].toLong(), m.groupValues[4].toLong())
    }

    /** The GPUs this phone exposes to the engine (asked once, then remembered). */
    suspend fun devices(force: Boolean = false): List<GpuDevice> = withContext(Dispatchers.IO) {
        val bin = binary ?: return@withContext emptyList()
        if (!force) deviceCache?.takeIf { it.first == bin.path }?.let { return@withContext it.second }
        val found = runCatching {
            // -lv 4 first, so the backend loader's messages (which library failed and why) are printed too.
            val pb = ProcessBuilder(bin.path, "-lv", "4", "--list-devices").redirectErrorStream(true)
            pb.environment().putAll(engineEnv(bin))
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val exited = p.waitFor(60, TimeUnit.SECONDS)
            if (!exited) p.destroyForcibly()
            lastProbe = "exit ${if (exited) p.exitValue() else "timeout"}\n" + out.trim().takeLast(6000)
            val devs = parseDevices(out)
            // Release builds load GPU/NPU parts silently: when one is missing, load it explicitly once to get the reason.
            if (devs.none { !it.isNpu } || devs.none { it.isNpu }) {
                val dir = bin.parentFile
                val missing = listOf("vulkan" to "Vulkan", "opencl" to "OpenCL", "hexagon" to "Hexagon")
                    .filter { (_, api) -> devs.none { d -> d.api == api } }
                    .mapNotNull { (n, api) -> File(dir, "libggml-$n.so").takeIf { it.exists() }?.let { it to api } }
                for ((lib, api) in missing) {
                    val dp = ProcessBuilder(bin.path, "-lv", "4", "--list-devices").redirectErrorStream(true)
                    dp.environment().putAll(engineEnv(bin) + ("GGML_BACKEND_PATH" to lib.path))
                    val d = dp.start()
                    val dout = d.inputStream.bufferedReader().readText()
                    if (!d.waitFor(60, TimeUnit.SECONDS)) d.destroyForcibly()
                    val why = dout.lines().filter { Regex("(?i)load_backend|failed|error|not found|cannot|ggml_vulkan|ggml_opencl|ggml-hex").containsMatchIn(it) }
                        .map { it.replace(Regex("^[0-9.]+ [A-Z] "), "") }.distinct().take(4)
                    lastProbe += "\n[$api] " + (why.joinToString("\n  ").ifBlank { "no message" })
                }
            }
            devs
        }.getOrElse { lastProbe = "couldn't run the check: ${it.message}"; emptyList() }
        // Only a successful answer is remembered: an empty one is asked again next time.
        if (found.isNotEmpty()) deviceCache = bin.path to found
        found
    }

    @Volatile private var cpuOnlyByChoice: Boolean? = null

    /** The most telling line from the last GPU/NPU check when it found nothing (e.g. which driver library failed to load). */
    fun probeProblem(): String? = lastProbe.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .firstOrNull { Regex("(?i)fail|error|not found|cannot|denied|unable|no device|not accessible").containsMatchIn(it) }
        ?.replace(Regex("^[0-9.]+ [A-Z] "), "")?.take(200)

    /** What the last GPU/NPU check printed (for the "Check GPU/NPU" details). */
    @Volatile var lastProbe: String = ""
        private set

    /** The one GPU to use (the same GPU often appears through both OpenCL and Vulkan; using both would split it in two). */
    fun pickDevice(devices: List<GpuDevice>, backend: String): GpuDevice? {
        val gpus = devices.filter { !it.isNpu }
        return when (backend) {
            "opencl" -> gpus.firstOrNull { it.api == "OpenCL" }
            "vulkan" -> gpus.firstOrNull { it.api == "Vulkan" }
            else -> gpus.firstOrNull { it.api == "OpenCL" } ?: gpus.firstOrNull { it.api == "Vulkan" }
        }
    }

    /**
     * Accelerators for a processor choice: "auto" = NPU + one GPU, "gpu" = one GPU, "npu" = the NPU, "cpu" = none.
     * The CPU always runs whatever the accelerators don't hold.
     */
    fun pickDevices(devices: List<GpuDevice>, processor: String, backend: String): List<GpuDevice> {
        val npu = devices.firstOrNull { it.isNpu }
        val gpu = pickDevice(devices, backend)
        return when (processor) {
            "cpu" -> emptyList()
            "gpu" -> listOfNotNull(gpu)
            "npu" -> listOfNotNull(npu)
            else -> listOfNotNull(npu, gpu)
        }
    }

    /** Engine arguments: the accelerators (layers split between them, the rest on the CPU), or CPU only. */
    fun gpuArgs(device: String?, layers: Int): List<String> =
        if (device.isNullOrBlank()) listOf("-dev", "none") else listOf("-dev", device, "-ngl", if (layers < 0) "auto" else "$layers")

    /** Set when the GPU failed for the loaded model and it fell back to the CPU, for the status line. */
    @Volatile var gpuNote: String? = null; private set

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
        /** The GPU in use and how many layers are on it ("28/36"); null when running on the CPU only. */
        val gpu: String? = null, val gpuLayers: String? = null, val gpuNote: String? = null,
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
        val device = pickDevices(devices(), lm.processor, lm.gpuBackend).joinToString(",") { it.name }.ifBlank { null }
        cpuOnlyByChoice = lm.processor == "cpu"
        val key = Key(file.path, lm.contextSize.coerceIn(512, 262_144), lm.threads, p.apiKey, lm.largeMode, device, lm.gpuLayers)
        keepMs = lm.keepMinutes.coerceAtLeast(1) * 60_000L
        touch()
        // All engine work (health checks, waiting for an old engine to exit, starting) stays off the main thread.
        withContext(Dispatchers.IO) { mutex.withLock {
            if (running == key && proc?.isAlive == true && healthy(key.apiKey)) return@withLock
            stopLocked()
            val name = p.model.ifBlank { file.name }
            gpuNote = null
            suspend fun attempt(k: Key) {
                try { start(bin, k, name, quantCache = k.large) }
                catch (e: ApiException) {
                    val log = recentLog(40).lowercase()
                    when {
                        // Some models can't use the 8-bit conversation cache (head size not a multiple of 32): use the normal one.
                        k.large && "cache type" in log -> start(bin, k, name, quantCache = false)
                        // The previous engine hadn't released the port yet.
                        "bind" in log || "address already in use" in log -> { delay(1500); start(bin, k, name, quantCache = k.large) }
                        else -> throw e
                    }
                }
            }
            // If an accelerator can't run this model (unsupported operation, out of memory, driver error): try without the NPU,
            // then on the CPU alone, and say what happened.
            val fallbacks = listOfNotNull(key, key.gpu?.split(',')?.filterNot { it.startsWith("HTP") }?.takeIf { it.isNotEmpty() && it.size < key.gpu.split(',').size }
                ?.let { key.copy(gpu = it.joinToString(",")) }, key.copy(gpu = null)).distinct()
            var lastError: ApiException? = null
            for ((i, k) in fallbacks.withIndex()) {
                try {
                    attempt(k)
                    if (i > 0) gpuNote = (if (k.gpu == null) "The GPU/NPU couldn't run this model, so it's running on the CPU." else "The NPU couldn't run this model, so it's using the GPU and CPU.") +
                        " (${lastError?.message?.lines()?.firstOrNull()?.take(120)})"
                    lastError = null
                    break
                } catch (e: ApiException) {
                    if ("Android stopped the engine" in (e.message ?: "") || k.gpu == null) throw e
                    lastError = e
                }
            }
            lastError?.let { throw it }
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
        args += gpuArgs(key.gpu, key.gpuLayers)
        args += listOf("-lv", "4") // loading details: which device, how many layers on it
        val env = engineEnv(bin)
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
        val (gpuName, offloaded) = placementFromLog(p.log(5000)) // the whole loading log: big models print hundreds of lines
        _progress.value = _progress.value?.copy(phase = Phase.IDLE, gpu = if (key.gpu == null) null else gpuName ?: key.gpu, gpuLayers = if (key.gpu == null) null else offloaded,
            gpuNote = gpuNote ?: if (key.gpu == null && cpuOnlyByChoice != true)
                "no GPU or NPU was detected" + (probeProblem()?.let { ": $it" } ?: "") + " (details: Check GPU/NPU in the model's settings)" else null)
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

    /** From the engine's loading log: the accelerators it used ("Hexagon HTP0 (Hexagon) + Adreno 830 (OpenCL)") and the layers on them ("28/36"). */
    fun placementFromLog(log: String): Pair<String?, String?> {
        val devs = Regex("using device (\\S+) \\((.+?)\\)(?: \\(| -|$)", RegexOption.MULTILINE).findAll(log).map { m ->
            "${m.groupValues[2]} (${GpuDevice(m.groupValues[1], "", 0, 0).api})"
        }.distinct().toList()
        val layers = Regex("offloaded (\\d+)/(\\d+) layers to GPU").findAll(log).lastOrNull()?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
        return devs.joinToString(" + ").ifBlank { null } to layers
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
