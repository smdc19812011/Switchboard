package app.switchboard.local

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.LocalModel
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.net.ApiException
import app.switchboard.net.ChatClient
import app.switchboard.net.RequestOptions
import app.switchboard.net.StreamEvent
import app.switchboard.net.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LocalModelTest {
    @Test fun huggingFaceLinksAndNames() {
        assertEquals("https://huggingface.co/a/b/resolve/main/m-Q4_K_M.gguf", ModelFiles.directUrl("https://huggingface.co/a/b/blob/main/m-Q4_K_M.gguf"))
        assertEquals("m-Q4_K_M.gguf", ModelFiles.nameFor("https://huggingface.co/a/b/resolve/main/m-Q4_K_M.gguf?download=true"))
        assertEquals("my_model.gguf", ModelFiles.nameFor("https://x.org/files/my%20model"))
    }

    /** Resumable download from a local server that honors Range, and rejection of a non-GGUF page. */
    @Test fun downloadResumesAndChecksMagic() = runBlocking {
        val payload = "GGUF".toByteArray() + ByteArray(300_000) { (it % 251).toByte() }
        // Minimal HTTP server: /m.gguf honors Range, /page returns HTML.
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val lines = c.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine()?.takeIf { it.isNotEmpty() } }.toList() }
                    val path = lines.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
                    val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter("bytes=")?.removeSuffix("-")?.trim()?.toInt()
                    val out = c.getOutputStream()
                    val (code, body, extra) = when {
                        path == "/page" -> Triple("200 OK", "<html>login</html>".toByteArray(), "")
                        range != null -> Triple("206 Partial Content", payload.copyOfRange(range, payload.size), "Content-Range: bytes $range-${payload.size - 1}/${payload.size}\r\n")
                        else -> Triple("200 OK", payload, "")
                    }
                    out.write("HTTP/1.1 $code\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        val dir = File.createTempFile("models", "").apply { delete(); mkdirs() }
        try {
            val dest = File(dir, "m.gguf")
            File(dest.path + ".part").writeBytes(payload.copyOfRange(0, 1000)) // an interrupted earlier download
            var last: ModelFiles.Progress? = null
            ModelFiles.download("http://127.0.0.1:${srv.localPort}/m.gguf", dest) { last = it }
            assertTrue(dest.readBytes().contentEquals(payload)); assertEquals(payload.size.toLong(), last!!.total)
            assertFalse(File(dest.path + ".part").exists())
            val bad = runCatching { ModelFiles.download("http://127.0.0.1:${srv.localPort}/page", File(dir, "p.gguf")) {} }.exceptionOrNull()
            assertTrue(bad?.message, bad is ApiException && bad.message!!.contains("web page"))
        } finally { srv.close(); dir.deleteRecursively() }
    }

    /**
     * End to end with a real llama-server and a tiny GGUF, when available (set LLAMA_SERVER and TINY_GGUF):
     * the engine loads the model, the normal chat client streams a reply (with tools offered), and stop frees it.
     */
    @Test fun engineServesChatThroughNormalClient() = runBlocking {
        val bin = System.getenv("LLAMA_SERVER")?.let(::File); val model = System.getenv("TINY_GGUF")?.let(::File)
        assumeTrue(bin?.canExecute() == true && model?.exists() == true)
        LocalEngine.binary = bin; LocalEngine.stateDir = File.createTempFile("state", "").apply { delete(); mkdirs() }
        val p = Provider(name = "Local", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = LocalEngine.baseUrl, apiKey = "k123", model = "tiny",
            local = LocalModel(path = model!!.path, contextSize = 2048, keepMinutes = 5))
        try {
            assertEquals("tiny", ChatClient().listModels(p).single())
            // Record what the status bar would show while the model loads and answers.
            val seen = java.util.Collections.synchronizedList(mutableListOf<LocalEngine.Progress>())
            val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { LocalEngine.progress.collect { it?.let(seen::add) } }
            val tool = ToolSpec("get_time", "Get the time", buildJsonObject { put("type", "object") })
            val events = ChatClient().stream(p, listOf(ChatMessage(role = Role.USER, text = "Hello")),
                RequestOptions(system = "Be brief.", maxTokens = 16, tools = listOf(tool))).toList()
            val end = events.last() as StreamEvent.TurnEnd
            assertTrue(events.any { it is StreamEvent.TextDelta } || end.toolCalls.isNotEmpty())
            assertTrue(LocalEngine.isRunning && LocalEngine.status.value == "tiny")
            kotlinx.coroutines.delay(1200) // let the memory monitor take a reading
            watcher.cancel()
            assertTrue(seen.toString(), seen.any { it.phase == LocalEngine.Phase.LOADING && it.modelBytes == model.length() })
            // Reading a 21-token prompt takes ~60 ms here, so the READING state itself can be skipped over by the collector;
            // its prompt counts carry into the later states.
            assertTrue(seen.toString(), seen.any { it.phase != LocalEngine.Phase.LOADING && it.promptTotal > 0 && it.promptDone <= it.promptTotal })
            assertTrue(seen.toString(), seen.any { it.phase == LocalEngine.Phase.WRITING && it.tokens > 0 })
            assertEquals(LocalEngine.Phase.IDLE, LocalEngine.progress.value!!.phase)
            assertTrue("resident ${LocalEngine.progress.value}", LocalEngine.progress.value!!.residentBytes > 0)
            assertTrue("threads ${LocalEngine.progress.value}", LocalEngine.progress.value!!.threads > 0)
            // Bigger-than-memory mode restarts the engine with its options and still answers. This tiny model's head size (16)
            // can't use the 8-bit cache, so this also covers the automatic retry with the normal cache.
            val large = p.copy(local = p.local!!.copy(largeMode = true))
            val e2 = ChatClient().stream(large, listOf(ChatMessage(role = Role.USER, text = "Hi")), RequestOptions(maxTokens = 8)).toList()
            assertTrue(e2.last() is StreamEvent.TurnEnd)
            assertEquals(listOf("-lm", "mmap", "--no-warmup", "-b", "512", "-ub", "128", "-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0"), LocalEngine.largeModeArgs(true))
            ChatClient().stream(p, listOf(ChatMessage(role = Role.USER, text = "Back")), RequestOptions(maxTokens = 4)).toList()
            // A second request reuses the loaded model.
            ChatClient().stream(p, listOf(ChatMessage(role = Role.USER, text = "Again")), RequestOptions(maxTokens = 4)).toList()
            // A wrong key is refused by the server, so other apps on the phone can't use the model.
            val other = p.copy(apiKey = "wrong", local = null)
            assertTrue(runCatching { ChatClient().stream(other, listOf(ChatMessage(role = Role.USER, text = "x")), RequestOptions(maxTokens = 2)).toList() }.isFailure)
            // If the engine dies (as when Android stops it for memory), the reason is reported instead of a connection error.
            val pid = File(LocalEngine.stateDir, "llama-server.pid").readText().trim()
            ProcessBuilder("kill", "-9", pid).start().waitFor(); Thread.sleep(500)
            assertTrue(LocalEngine.whyStopped(), LocalEngine.whyStopped()!!.contains("Android stopped the engine to free memory"))
            val dead = runCatching { ChatClient().stream(p.copy(local = p.local!!.copy(contextSize = 2048)), listOf(ChatMessage(role = Role.USER, text = "x")), RequestOptions(maxTokens = 2)).toList() }
            assertTrue(dead.isSuccess) // the next request simply starts the engine again
        } finally { LocalEngine.stop() }
        assertFalse(LocalEngine.isRunning)
        // A file that isn't a model fails with a readable reason instead of hanging.
        val junk = File.createTempFile("junk", ".gguf").apply { writeText("not a model") }
        val err = runCatching { LocalEngine.ensure(p.copy(local = p.local!!.copy(path = junk.path))) }.exceptionOrNull()
        assertTrue(err?.message, err is ApiException && err.message!!.contains("stopped while loading"))
    }
}
