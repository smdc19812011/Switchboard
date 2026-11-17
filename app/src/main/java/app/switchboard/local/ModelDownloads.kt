package app.switchboard.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Model downloads and copies, run for the whole app (not tied to a screen), so they keep going when the dialog closes,
 * the screen turns off or the phone switches displays. [hooks] keeps a foreground service with a progress notification
 * running while any are in progress.
 */
object ModelDownloads {
    data class Transfer(val path: String, val label: String, val done: Long = 0, val total: Long = -1, val error: String? = null, val finished: Boolean = false) {
        val active get() = !finished && error == null
        val percent get() = if (total > 0) (done * 100 / total).toInt() else -1
    }

    /** Set by the app: show/update the background notification for a transfer, end it, and announce the result. */
    interface Hooks {
        fun progress(key: String, text: String)
        fun end(key: String, result: String?)
    }
    @Volatile var hooks: Hooks? = null

    private val _all = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    /** Transfers by destination path (finished and failed ones stay until dismissed or restarted). */
    val all: StateFlow<Map<String, Transfer>> = _all
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(dest: File, label: String, work: suspend ((ModelFiles.Progress) -> Unit) -> Unit) {
        val path = dest.path
        jobs.remove(path)?.cancel()
        _all.update { it + (path to Transfer(path, label)) }
        val key = "model:$path"
        hooks?.progress(key, "$label…")
        jobs[path] = scope.launch {
            var lastNote = 0L
            var result: String? = null
            try {
                work { pr ->
                    _all.update { m -> m[path]?.let { m + (path to it.copy(done = pr.done, total = pr.total)) } ?: m }
                    if (System.currentTimeMillis() - lastNote > 2000) {
                        lastNote = System.currentTimeMillis()
                        hooks?.progress(key, "$label: " + (if (pr.total > 0) "${pr.done * 100 / pr.total}% · ${ModelFiles.human(pr.done)} of ${ModelFiles.human(pr.total)}" else ModelFiles.human(pr.done)))
                    }
                }
                _all.update { it + (path to Transfer(path, label, dest.length(), dest.length(), finished = true)) }
                result = "${dest.name} is ready to use."
            } catch (e: kotlinx.coroutines.CancellationException) {
                _all.update { it - path }
                throw e
            } catch (e: Exception) {
                _all.update { m -> m[path]?.let { m + (path to it.copy(error = e.message ?: e.javaClass.simpleName)) } ?: m }
                result = "${dest.name}: ${e.message ?: "failed"}. Open Switchboard to try again (it resumes)."
            } finally {
                jobs.remove(path)
                hooks?.end(key, result)
            }
        }
    }

    fun cancel(path: String) { jobs.remove(path)?.cancel(); _all.update { it - path } }

    fun get(path: String): Transfer? = _all.value[path]
}
