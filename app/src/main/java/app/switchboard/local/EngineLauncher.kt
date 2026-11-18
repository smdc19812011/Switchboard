package app.switchboard.local

import java.io.File
import java.util.concurrent.TimeUnit

/** Starts the llama-server program somewhere: in this process (desktop tests) or in Switchboard's separate engine process. */
interface EngineLauncher {
    fun start(args: List<String>, env: Map<String, String>): EngineHandle
}

/** A running (or finished) engine. */
interface EngineHandle {
    val pid: Long
    val isAlive: Boolean
    /** Exit code once it has stopped, else null. */
    val exitCode: Int?
    /** The last [lines] lines it printed. */
    fun log(lines: Int): String
    fun stop()
    /** Why it stopped, if the system knows (e.g. "Android stopped it to free memory"), else null. */
    fun stopReason(): String? = null
}

/** Runs the engine as a child of this process. */
object InProcessLauncher : EngineLauncher {
    override fun start(args: List<String>, env: Map<String, String>): EngineHandle {
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val log = ArrayDeque<String>()
        Thread { p.inputStream.bufferedReader().forEachLine { line -> synchronized(log) { log.addLast(line); while (log.size > 300) log.removeFirst() } } }
            .apply { isDaemon = true }.start()
        return object : EngineHandle {
            override val pid = pidOf(p)
            override val isAlive get() = p.isAlive
            override val exitCode get() = if (p.isAlive) null else p.exitValue()
            override fun log(lines: Int) = synchronized(log) { log.takeLast(lines).joinToString("\n") }
            override fun stop() { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(3, TimeUnit.SECONDS) } }
        }
    }

    /** Process id: Process.pid() where it exists, else the field older Android keeps it in, else -1. */
    fun pidOf(p: Process): Long = runCatching { Process::class.java.getMethod("pid").invoke(p) as Long }.getOrElse {
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong() }.getOrDefault(-1)
    }
}

/** Finds a running engine by its command line (programs of the same app user are visible in /proc). */
fun findEnginePid(binary: String, port: Int): Long =
    File("/proc").listFiles()?.asSequence()?.mapNotNull { it.name.toLongOrNull() }?.firstOrNull { pid ->
        runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()?.let { it.startsWith(binary) && "--port\u0000$port" in it } == true
    } ?: -1
