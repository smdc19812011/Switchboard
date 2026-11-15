package app.switchboard.net

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatClientTest {
    private lateinit var server: ServerSocket
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private val seen = mutableMapOf<String, Pair<Map<String, String>, String>>()
    private val base get() = "http://127.0.0.1:${server.localPort}/v1"

    /** Minimal HTTP/1.1 mock: android.jar has no com.sun.net.httpserver. */
    private fun serve() = thread(isDaemon = true) {
        while (!server.isClosed) {
            val sock = runCatching { server.accept() }.getOrNull() ?: break
            sock.use { s ->
                val input = s.getInputStream().bufferedReader()
                val path = input.readLine().split(" ")[1].substringBefore('?')
                val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val len = headers["content-length"]?.toInt() ?: 0
                val body = CharArray(len).also { var r = 0; while (r < len) r += input.read(it, r, len - r) }.concatToString()
                seen[path] = headers to body
                val (code, resp) = routes[path] ?: (404 to "")
                val bytes = resp.toByteArray()
                s.getOutputStream().apply {
                    write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                }
            }
        }
    }

    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        routes["/v1/messages"] = 200 to """
            event: message_start
            data: {"type":"message_start","message":{"id":"m"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        routes["/v1/chat/completions"] = 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"Hi \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\ndata: [DONE]\n\n"
        routes["/v1/models"] = 200 to """{"data":[{"id":"b-model"},{"id":"a-model"}]}"""
        routes["/bad/chat/completions"] = 401 to """{"error":{"message":"invalid key"}}"""
        serve()
    }

    @After fun stop() = server.close()

    private val history = listOf(
        ChatMessage(role = Role.USER, text = "one"),
        ChatMessage(role = Role.ASSISTANT, text = "boom", isError = true),
        ChatMessage(role = Role.USER, text = "two"),
    )

    @Test fun anthropicStreamsAndSendsCorrectRequest() = runBlocking {
        val p = Provider(name = "A", kind = ApiKind.ANTHROPIC, baseUrl = base, apiKey = "sk-test", model = "claude-opus-5-5")
        assertEquals("Hello", ChatClient().stream(p, history, "be brief", 1000).toList().joinToString(""))
        val (h, body) = seen.getValue("/v1/messages")
        assertEquals("sk-test", h["x-api-key"]); assertEquals("2026-10-21", h["anthropic-version"])
        assertTrue(body.contains("\"system\":\"be brief\""))
        // error reply dropped, consecutive user turns merged into one
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"one\\n\\ntwo\"}]"))
        assertTrue(!body.contains("temperature"))
    }

    @Test fun openAiCompatibleStreams() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, apiKey = "or-key", model = "x/y")
        assertEquals("Hi there", ChatClient().stream(p, history, "sys", 1000).toList().joinToString(""))
        val (h, body) = seen.getValue("/v1/chat/completions")
        assertEquals("Bearer or-key", h["authorization"])
        assertTrue(body.startsWith("{\"model\":\"x/y\",\"stream\":true,\"messages\":[{\"role\":\"system\",\"content\":\"sys\"}"))
    }

    @Test fun listsModelsSorted() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = base, model = "")
        assertEquals(listOf("a-model", "b-model"), ChatClient().listModels(p))
        assertTrue(seen.getValue("/v1/models").first["authorization"] == null) // no key -> no auth header
    }

    @Test fun surfacesApiErrorMessage() = runBlocking {
        val p = Provider(name = "O", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://127.0.0.1:${server.localPort}/bad", model = "m")
        val e = runCatching { ChatClient().stream(p, history, "", 10).toList() }.exceptionOrNull()
        assertEquals("HTTP 401: invalid key", e?.message)
    }
}
