package app.switchboard.studio

import app.switchboard.net.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Against a stand-in for kie.ai that answers like the real API (jobs, Veo, uploads, credits, errors). */
class StudioTest {
    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(handler: (Req) -> Pair<Int, ByteArray>): ServerSocket {
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!srv.isClosed) {
                val c = runCatching { srv.accept() }.getOrNull() ?: break
                Thread {
                    c.use {
                        val inp = java.io.BufferedInputStream(it.getInputStream())
                        fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }; return sb.toString() }
                        val first = line().split(" ")
                        val headers = generateSequence { line().takeIf { l -> l.isNotEmpty() } }.associate { h -> h.substringBefore(':').lowercase() to h.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { b -> var n = 0; while (n < b.size) { val r = inp.read(b, n, b.size - n); if (r < 0) break; n += r } }
                        val (code, out) = handler(Req(first[0], first.getOrElse(1) { "" }, headers, body))
                        it.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(out); flush() }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return srv
    }

    @Test fun jobsUploadsVeoAndErrors() = runBlocking {
        val created = mutableListOf<JsonObject>(); var polls = 0; var veoPolls = 0; var uploaded = ""
        lateinit var base: String
        val srv = serve { r ->
            val ok = r.headers["authorization"] == "Bearer good"
            fun j(s: String) = 200 to s.toByteArray()
            when {
                r.path.startsWith("/files/") -> { assertEquals(null, r.headers["authorization"]); 200 to "PNGDATA".toByteArray() } // results: public, no key sent
                !ok -> 401 to "{}".toByteArray()
                r.path == "/api/v1/chat/credit" -> j("""{"code":200,"msg":"success","data":1234.5}""")
                r.path == "/api/v1/file-stream-upload" -> { uploaded = String(r.body); j("""{"code":200,"data":{"fileUrl":"https://tmp.kie/u/ref.png"}}""") }
                r.path == "/api/v1/jobs/createTask" -> {
                    val o = Json.parseToJsonElement(String(r.body)).jsonObject; created += o
                    if (o["model"].toString().contains("broke")) j("""{"code":402,"msg":"Credits insufficient"}""") else j("""{"code":200,"data":{"taskId":"t1"}}""")
                }
                r.path.startsWith("/api/v1/jobs/recordInfo") -> { polls++
                    if (polls < 2) j("""{"code":200,"data":{"taskId":"t1","state":"generating"}}""")
                    else j("""{"code":200,"data":{"taskId":"t1","state":"success","resultJson":"{\"resultUrls\":[\"$base/files/out.png\"]}"}}""") }
                r.path == "/api/v1/veo/generate" -> j("""{"code":200,"data":{"taskId":"v1"}}""")
                r.path.startsWith("/api/v1/veo/record-info") -> { veoPolls++
                    j(if (veoPolls < 2) """{"code":200,"data":{"successFlag":0}}""" else """{"code":200,"data":{"successFlag":1,"response":{"resultUrls":["$base/files/out.mp4"]}}}""") }
                else -> 404 to "{}".toByteArray()
            }
        }
        base = "http://127.0.0.1:${srv.localPort}"
        val dir = File.createTempFile("studio", "").apply { delete(); mkdirs() }
        try {
            val k = KieClient("good", base)
            assertEquals(1234.5, k.credits(), 0.01)

            // Upload a reference image, then an edit job that uses it; polls until done and reads resultJson.
            val ref = File(dir, "ref.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val url = k.upload(ref)
            assertEquals("https://tmp.kie/u/ref.png", url); assertTrue(uploaded.contains("filename=\"ref.png\"") && uploaded.contains("uploadPath"))
            val edit = Studio.model("nano-banana-edit")!!
            var waited = 0
            val res = k.run(edit.id, k.imageInput(edit, "make it night", "16:9", "2k", listOf(url)), 60_000, poll = 50) { waited = it }
            assertEquals(listOf("$base/files/out.png"), res.urls)
            val sent = created.last()["input"]!!.jsonObject
            assertEquals("\"make it night\"", sent["prompt"].toString()); assertEquals("[\"https://tmp.kie/u/ref.png\"]", sent["image_input"].toString())
            assertEquals("\"2K\"", sent["resolution"].toString()); assertEquals(2, polls)
            assertEquals("PNGDATA", k.download(res.urls.first(), File(dir, "o.png")).readText())

            // Other models put references in their own fields.
            assertEquals("[\"u\"]", k.imageInput(Studio.model("gpt-image-2-image-to-image")!!, "p", null, null, listOf("u"))["input_urls"].toString())
            assertEquals("\"u\"", k.imageInput(Studio.model("qwen/image-to-image")!!, "p", null, null, listOf("u"))["image_url"].toString())
            assertEquals("\"u\"", k.videoInput(Studio.model("wan/3-0-video")!!, "p", "u", null, "720p", "5")["first_frame_url"].toString())
            assertEquals("[\"u\"]", k.videoInput(Studio.model("kling/v2-1-pro")!!, "p", "u", null, null, "10s")["image_urls"].toString())
            assertEquals("\"10\"", k.videoInput(Studio.model("kling/v2-1-pro")!!, "p", null, null, null, "10s")["duration"].toString())

            // Veo has its own endpoint and success flag.
            val veo = k.runVeo("veo3_fast", "a fox in snow", null, "16:9", null, 60_000, poll = 50)
            assertEquals(listOf("$base/files/out.mp4"), veo.urls)

            // Errors are readable: out of credits, and a bad key.
            val broke = runCatching { k.run("broke-model", JsonObject(emptyMap()), 10_000, poll = 50) }.exceptionOrNull()
            assertTrue(broke?.message, broke is ApiException && broke.message!!.contains("out of credits"))
            val bad = runCatching { KieClient("nope", base).credits() }.exceptionOrNull()
            assertTrue(bad?.message, bad!!.message!!.contains("rejected the API key"))
        } finally { srv.close(); dir.deleteRecursively() }
    }

    @Test fun catalogAndTools() {
        assertEquals(Studio.Kind.VIDEO, Studio.model("Kling 2.1 Pro")!!.kind)
        assertEquals("sora-2-image-to-video", Studio.model("sora-2-text-to-video")!!.i2v)
        assertTrue(Studio.of(Studio.Kind.IMAGE).isNotEmpty() && Studio.of(Studio.Kind.LIPSYNC).size == 2)
        val tools = Studio.tools("nano-banana-2", "kling/v2-1-pro")
        assertEquals(Studio.toolNames, tools.map { it.name }.toSet())
        assertTrue(tools.first().description.contains("Default: nano-banana-2"))
    }
}
