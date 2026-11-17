package app.switchboard.local

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Downloads run outside any screen, report progress to the notification hooks, and announce the result. */
class ModelDownloadsTest {
    @Test fun runsInBackgroundWithNotificationHooks() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        ModelDownloads.hooks = object : ModelDownloads.Hooks {
            override fun progress(key: String, text: String) { events += "progress:$text" }
            override fun end(key: String, result: String?) { events += "end:$result" }
        }
        val dir = File.createTempFile("downloads", "").apply { delete(); mkdirs() }
        try {
            val dest = File(dir, "m.gguf")
            ModelDownloads.run(dest, "Downloading m.gguf") { onP ->
                File(dest.path + ".part").writeBytes("GGUF".toByteArray() + ByteArray(1000))
                onP(ModelFiles.Progress(500, 1004)); delay(50); onP(ModelFiles.Progress(1004, 1004))
                File(dest.path + ".part").renameTo(dest)
            }
            withTimeout(5000) { while (ModelDownloads.get(dest.path)?.finished != true) delay(20) }
            assertEquals(1004L, ModelDownloads.get(dest.path)!!.done)
            withTimeout(5000) { while (events.none { it.startsWith("end:") }) delay(20) }
            assertTrue(events.toString(), events.first() == "progress:Downloading m.gguf…" && events.last() == "end:m.gguf is ready to use.")

            // A failure is kept (for "Try again") and announced; cancel removes it.
            val bad = File(dir, "bad.gguf")
            ModelDownloads.run(bad, "Downloading bad.gguf") { throw app.switchboard.net.ApiException("HTTP 404") }
            withTimeout(5000) { while (ModelDownloads.get(bad.path)?.error == null) delay(20) }
            assertEquals("HTTP 404", ModelDownloads.get(bad.path)!!.error)
            ModelDownloads.cancel(bad.path); assertEquals(null, ModelDownloads.get(bad.path))
        } finally { dir.deleteRecursively(); ModelDownloads.hooks = null }
    }
}
