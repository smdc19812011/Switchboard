package app.switchboard

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.switchboard.local.EngineHandle
import app.switchboard.local.EngineLauncher
import app.switchboard.local.InProcessLauncher
import app.switchboard.local.findEnginePid
import java.io.File

/**
 * Runs the llama.cpp engine in Switchboard's separate ":engine" process. Android's low-memory killer only tracks app
 * processes, not programs they start; with the engine under the main app, running out of memory closed Switchboard
 * itself. In its own lower-priority process, Android stops the engine first and the app stays open to say why.
 */
class EngineService : Service() {
    private var child: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                stopChild()
                val args = intent.getStringArrayListExtra(EXTRA_ARGS).orEmpty()
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val env = (intent.getSerializableExtra(EXTRA_ENV) as? HashMap<String, String>).orEmpty()
                val d = dir(this).apply { mkdirs() }
                listOf("pid", "exit", "log").forEach { File(d, it).delete() }
                val p = try {
                    ProcessBuilder(args).redirectErrorStream(true).apply { environment().putAll(env) }.start()
                } catch (e: Exception) {
                    File(d, "log").writeText("Couldn't start the engine: ${e.message}\n"); File(d, "exit").writeText("-1"); stopSelf(startId); return START_NOT_STICKY
                }
                child = p
                val pid = InProcessLauncher.pidOf(p).takeIf { it > 0 } ?: findEnginePid(args.firstOrNull().orEmpty(), args.portArg())
                File(d, "pid").writeText("$pid")
                Thread {
                    val log = File(d, "log")
                    runCatching {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            log.appendText(line + "\n")
                            if (log.length() > 512 * 1024) log.writeText(log.readText().takeLast(128 * 1024)) // keep the tail
                        }
                    }
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    File(d, "exit").writeText("$code")
                    if (child === p) { child = null; stopSelf() }
                }.apply { isDaemon = true }.start()
            }
            ACTION_STOP -> { stopChild(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopChild(); super.onDestroy() }

    private fun stopChild() {
        child?.let { p -> p.destroy(); if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        child = null
    }

    private fun List<String>.portArg() = indexOf("--port").takeIf { it >= 0 }?.let { getOrNull(it + 1)?.toIntOrNull() } ?: 0

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_ARGS = "args"
        const val EXTRA_ENV = "env"
        fun dir(ctx: Context) = File(ctx.filesDir, "engine")
    }
}

/** Starts the engine through [EngineService]; falls back to running it in-process when Android won't start the service (app in the background). */
class ServiceLauncher(private val ctx: Context) : EngineLauncher {
    override fun start(args: List<String>, env: Map<String, String>): EngineHandle {
        val d = EngineService.dir(ctx).apply { mkdirs() }
        listOf("pid", "exit", "log").forEach { File(d, it).delete() }
        val started = System.currentTimeMillis()
        val intent = Intent(ctx, EngineService::class.java).setAction(EngineService.ACTION_START)
            .putStringArrayListExtra(EngineService.EXTRA_ARGS, ArrayList(args)).putExtra(EngineService.EXTRA_ENV, HashMap(env))
        try { ctx.startService(intent) } catch (e: IllegalStateException) { return InProcessLauncher.start(args, env) }
        // The engine process starts and reports the program's process id.
        var pid = -1L
        for (i in 0 until 150) {
            pid = File(d, "pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1
            if (pid != -1L || File(d, "exit").exists()) break
            Thread.sleep(100)
        }
        return Handle(pid, started, d)
    }

    private inner class Handle(override val pid: Long, private val started: Long, private val d: File) : EngineHandle {
        private val exitFile get() = File(d, "exit")
        override val isAlive get() = !exitFile.exists() && pid > 0 && File("/proc/$pid").exists()
        // No exit file but gone: its process was stopped by Android (the engine goes with it), like SIGKILL.
        override val exitCode get() = if (isAlive) null else exitFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 137
        override fun log(lines: Int) = runCatching { File(d, "log").readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
        override fun stop() {
            runCatching { ctx.startService(Intent(ctx, EngineService::class.java).setAction(EngineService.ACTION_STOP)) }
            if (pid > 0) {
                runCatching { android.os.Process.sendSignal(pid.toInt(), 15) }
                for (i in 0 until 30) { if (!File("/proc/$pid").exists()) return; Thread.sleep(100) }
                runCatching { android.os.Process.killProcess(pid.toInt()) }
            }
        }
        override fun stopReason(): String? {
            if (Build.VERSION.SDK_INT < 30) return null
            return runCatching {
                val am = ctx.getSystemService(ActivityManager::class.java)
                val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                    .firstOrNull { it.processName == "${ctx.packageName}:engine" && it.timestamp >= started } ?: return null
                when (info.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Android stopped the engine to free memory: this model needs more memory than the phone has free."
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH -> "The engine crashed."
                    else -> null
                }
            }.getOrNull()
        }
    }
}
