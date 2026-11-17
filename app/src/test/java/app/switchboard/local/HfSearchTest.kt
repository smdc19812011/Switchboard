package app.switchboard.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against a stand-in for the Hugging Face API that answers like the real one. */
class HfSearchTest {
    private fun serve(routes: Map<String, String>): java.net.ServerSocket {
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val c = runCatching { srv.accept() }.getOrNull() ?: break
                c.use {
                    val first = it.getInputStream().bufferedReader().readLine().orEmpty()
                    val target = first.split(" ").getOrElse(1) { "" }
                    val body = routes.entries.firstOrNull { (k, _) -> target.startsWith(k) }?.value
                    val (code, b) = if (body != null) "200 OK" to body.toByteArray() else "404 Not Found" to "{}".toByteArray()
                    it.getOutputStream().apply { write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
                }
            }
        }.apply { isDaemon = true }.start()
        return srv
    }

    @Test fun searchesAndGroupsFiles() = runBlocking {
        val srv = serve(mapOf(
            "/api/models?search=qwen" to """[{"id":"org/Qwen-3B-GGUF","downloads":120000,"likes":50,"gated":false},{"modelId":"x/gated-GGUF","downloads":5,"likes":1,"gated":"manual"}]""",
            "/api/models/org/Qwen-3B-GGUF/tree/main" to """[
              {"type":"file","path":"README.md","size":100},
              {"type":"file","path":"qwen-3b-Q8_0.gguf","size":134,"lfs":{"size":3300000000}},
              {"type":"file","path":"qwen-3b-Q4_K_M.gguf","size":134,"lfs":{"size":1900000000}},
              {"type":"file","path":"mmproj-qwen-f16.gguf","size":134,"lfs":{"size":600000000}},
              {"type":"file","path":"big/qwen-3b-F16-00002-of-00002.gguf","size":1,"lfs":{"size":3000000000}},
              {"type":"file","path":"big/qwen-3b-F16-00001-of-00002.gguf","size":1,"lfs":{"size":3200000000}},
              {"type":"directory","path":"big"}]""",
        ))
        try {
            val hf = HfSearch("http://127.0.0.1:${srv.localPort}")
            val repos = hf.search("qwen 3b".substringBefore(' '))
            assertEquals(listOf("org/Qwen-3B-GGUF", "x/gated-GGUF"), repos.map { it.id })
            assertTrue(!repos[0].gated && repos[1].gated)
            val files = hf.files("org/Qwen-3B-GGUF")
            assertEquals(listOf("Q4_K_M", "Q8_0", "F16"), files.map { it.quant })
            val split = files.last()
            assertEquals(2, split.parts); assertEquals(6_200_000_000, split.size)
            assertTrue(split.urls.first().endsWith("/org/Qwen-3B-GGUF/resolve/main/big/qwen-3b-F16-00001-of-00002.gguf"))
            assertEquals("Q4_K_M", HfSearch.recommended(files)?.quant)
        } finally { srv.close() }
    }

    @Test fun quantNames() {
        assertEquals("Q4_K_M", HfSearch.quantOf("Llama-3.2-1B-Instruct-Q4_K_M.gguf"))
        assertEquals("IQ3_XS", HfSearch.quantOf("model.IQ3_XS.gguf"))
        assertEquals("Q8_0", HfSearch.quantOf("tiny-q8_0.gguf"))
        assertEquals("", HfSearch.quantOf("model.gguf"))
    }
}
