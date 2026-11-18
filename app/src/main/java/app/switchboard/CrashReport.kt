package app.switchboard

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Why Switchboard closed last time, shown on the next launch: a crash (with its stack trace), Android stopping it
 * for lack of memory (e.g. while a big local model loads), or it not responding. Nothing leaves the phone; the user
 * can copy the details to share them.
 */
object CrashReport {
    data class Report(val title: String, val explanation: String, val details: String)

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** Writes uncaught exceptions to a file before the app dies (the normal crash handling still runs). */
    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(ctx).writeText("Switchboard ${versionOf(ctx)} · Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n" +
                    "${java.util.Date()} · thread ${t.name}\n\n${e.stackTraceToString().take(20_000)}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun versionOf(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** The report for the last unexpected exit that hasn't been shown yet, or null. */
    fun takeLast(ctx: Context): Report? {
        val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0)
        val crash = crashFile(ctx).takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } }
        var report: Report? = null
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).firstOrNull { it.processName == ctx.packageName }
            if (info != null && info.timestamp > seen) {
                prefs.edit().putLong("seen", info.timestamp).apply()
                val mem = "using ${"%.1f".format(info.pss / 1048576.0)} GB"
                val base = "Switchboard ${versionOf(ctx)} · Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n${java.util.Date(info.timestamp)} · exit reason ${info.reason}, importance ${info.importance}, $mem\n${info.description.orEmpty()}"
                report = when (info.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> Report("Android closed Switchboard to free memory",
                        "The phone ran out of memory ($mem). This usually happens while a local model loads or answers, when the model plus its context needs more memory than is free. " +
                            "Try \"Bigger than memory\" mode, a smaller context (4096), a smaller file (e.g. Q4_K_M or fewer parameters), or closing other apps.", base)
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> Report("Switchboard crashed",
                        "Sorry about that. Tap Copy details and send them over so it can be fixed.", base + "\n\n" + (crash ?: "(no stack trace saved)"))
                    ApplicationExitInfo.REASON_ANR -> Report("Switchboard stopped responding",
                        "Android closed it because the screen didn't respond for a while. Tap Copy details and send them over so it can be fixed.",
                        base + "\n\n" + (runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText().take(6000) } }.getOrNull() ?: ""))
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Report("Switchboard was closed by Android",
                        "Android stopped it (reason ${info.reason}). If this keeps happening, tap Copy details and send them over.", base + (crash?.let { "\n\n$it" } ?: ""))
                    else -> null // closed normally, swiped away, updated, ...
                }
            }
        }
        if (report == null && crash != null) report = Report("Switchboard crashed", "Sorry about that. Tap Copy details and send them over so it can be fixed.", crash)
        return report
    }
}
