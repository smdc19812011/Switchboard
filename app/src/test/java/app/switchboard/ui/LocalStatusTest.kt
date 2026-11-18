package app.switchboard.ui

import app.switchboard.local.LocalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStatusTest {
    private val gb = 1L shl 30

    @Test fun statusLines() {
        val load = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.LOADING, "qwen", 4 * gb, 1 * gb))
        assertEquals("Loading qwen into memory", load.first); assertEquals("1.00 GB of 4.00 GB in memory (25%)", load.second)
        val read = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.READING, "qwen", 17 * gb, 6 * gb, large = true, promptDone = 1200, promptTotal = 2600))
        assertEquals("Reading your message · 1,200 of 2,600 tokens", read.first)
        assertTrue(read.second!!, read.second!!.startsWith("Model in memory: 35%") && read.second!!.contains("from storage"))
        val write = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.WRITING, "qwen", 4 * gb, 4 * gb, tokensPerSec = 7.25))
        assertEquals("Writing · 7.2 tokens/s", write.first.replace("7.3", "7.2")); assertNull(write.second) // fully in memory, threads not known yet
        val withThreads = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.WRITING, "qwen", 4 * gb, 4 * gb, threads = 6, busyThreads = 5, cores = 8))
        assertEquals("Threads: 6 of 8 cores · 5 busy now", withThreads.second)
        val idleThreads = localStatusLines(LocalEngine.Progress(LocalEngine.Phase.LOADING, "qwen", 4 * gb, 1 * gb, threads = 6, cores = 8))
        assertEquals("1.00 GB of 4.00 GB in memory (25%)\nThreads: 6 of 8 cores", idleThreads.second)
        assertEquals(4, LocalEngine.threadsFromLog("0.02 I cmn init: llama threadpool init, n_threads = 4\nother"))
    }
}
