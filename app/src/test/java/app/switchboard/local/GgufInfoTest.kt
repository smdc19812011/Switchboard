package app.switchboard.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufInfoTest {
    /** A GGUF header like llama.cpp writes it: general keys, architecture keys, then a big tokenizer array. */
    private fun header(arch: String, experts: Int, used: Int, vocab: Int = 50_000): ByteArray {
        val o = ByteArrayOutputStream()
        fun u32(v: Int) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); o.write(b) }
        val kvs = mutableListOf<() -> Unit>()
        fun kStr(k: String, v: String) = kvs.add { str(k); u32(8); str(v) }
        fun kU32(k: String, v: Int) = kvs.add { str(k); u32(4); u32(v) }
        kStr("general.architecture", arch); kStr("general.name", "Test Model")
        kvs.add { str("general.tags"); u32(9); u32(8); u64(2); str("a"); str("bb") } // string array
        kU32("$arch.context_length", 32768); kU32("$arch.block_count", 48)
        kvs.add { str("$arch.rope.freq_base"); u32(6); o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1e6f).array()) }
        if (experts > 0) { kU32("$arch.expert_count", experts); kU32("$arch.expert_used_count", used) }
        kvs.add { str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(vocab.toLong()); repeat(vocab) { str("tok$it") } }
        kvs.add { str("tokenizer.ggml.scores"); u32(9); u32(6); u64(vocab.toLong()); o.write(ByteArray(vocab * 4)) }
        o.write("GGUF".toByteArray()); u32(3); u64(0); u64(kvs.size.toLong())
        kvs.forEach { it() }
        return o.toByteArray()
    }

    @Test fun readsMoeAndDense() {
        val moe = GgufInfo.read(header("qwen3moe", 128, 8).inputStream())
        assertEquals("qwen3moe", moe.architecture); assertEquals("Test Model", moe.name)
        assertTrue(moe.isMoe); assertEquals(128, moe.expertCount); assertEquals(8, moe.expertUsed); assertEquals(32768, moe.contextLength)
        val dense = GgufInfo.read(header("llama", 0, 0).inputStream())
        assertEquals("llama", dense.architecture); assertFalse(dense.isMoe)
        // Only the first bytes (as a ranged request returns) are enough.
        val partial = header("qwen3moe", 64, 4).copyOfRange(0, 1500)
        assertTrue(GgufInfo.read(partial.inputStream()).isMoe)
        assertEquals(null, GgufInfo.read("not a model".byteInputStream()).architecture)
    }

    /** A header written by llama.cpp's own gguf library with its official key names (source model, file type). */
    @Test fun readsSourceRepoAndQuantization() {
        val info = GgufInfo.read(java.io.File("src/test/resources/source-q4km.gguf").inputStream())
        assertEquals("qwen3", info.architecture)
        assertEquals("Qwen/Qwen3-4B-Instruct-2507", info.sourceRepo)
        assertEquals("Q4_K_M", info.quantName)
        assertEquals("Qwen/Qwen3-4B", GgufInfo.hfRepo("https://huggingface.co/Qwen/Qwen3-4B/tree/main"))
        assertEquals(null, GgufInfo.hfRepo("https://huggingface.co/datasets/x/y"))
        assertEquals("unsloth/Qwen3-4B-Instruct-2507", GgufInfo.guessSourceFromLink("https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"))
        assertEquals(null, GgufInfo.guessSourceFromLink("https://huggingface.co/Qwen/Qwen3-4B/resolve/main/x.gguf"))
        assertEquals(-1, GgufInfo.read(header("llama", 0, 0).inputStream()).fileType) // older files without it
        // The Snapdragon NPU runs Q* / IQ4_NL / MXFP4 files, not IQ1-IQ3 ones (the phone's Qwen3.5 IQ2_M file).
        assertTrue(GgufInfo(fileType = 15).npuSupportsQuant)   // Q4_K_M
        assertTrue(GgufInfo(fileType = 2).npuSupportsQuant)    // Q4_0
        assertTrue(GgufInfo(fileType = 25).npuSupportsQuant)   // IQ4_NL
        assertTrue(!GgufInfo(fileType = 29).npuSupportsQuant)  // IQ2_M
        assertTrue(!GgufInfo(fileType = 23).npuSupportsQuant)  // IQ3_XXS
        assertTrue(GgufInfo().npuSupportsQuant)                // unknown: allowed
    }

    @Test fun moeNamesAndFit() {
        listOf("Qwen3-30B-A3B-Q4_K_M.gguf", "Mixtral-8x7B-Instruct", "gpt-oss-20b-GGUF", "unsloth/Qwen3-235B-A22B-GGUF", "OLMoE-1B-7B").forEach { assertTrue(it, GgufInfo.looksMoe(it)) }
        listOf("Llama-3.2-3B-Instruct-Q4_K_M.gguf", "gemma-3-4b-it", "Qwen2.5-7B").forEach { assertFalse(it, GgufInfo.looksMoe(it)) }
        val gb = 1L shl 30; val ram = 12 * gb
        assertEquals(Fit.FITS, Fit.of(3 * gb, ram, false))
        assertEquals(Fit.TOO_BIG, Fit.of(17 * gb, ram, false))
        assertEquals(Fit.BIG_MOE, Fit.of(10 * gb, ram, true))
        assertEquals(Fit.BIG_MOE, Fit.of(14 * gb, ram, true))
        assertEquals(Fit.TOO_BIG, Fit.of(17 * gb, ram, true))
        assertEquals(Fit.TOO_BIG, Fit.of(30 * gb, ram, true))
        assertTrue(Fit.wantsLargeMode(8 * gb, ram)); assertFalse(Fit.wantsLargeMode(4 * gb, ram))
    }

    /** peek() reads the header of a remote file with a ranged request and never more than 2 MB. */
    @Test fun peeksRemoteHeader() = runBlocking {
        val body = header("qwen3moe", 128, 8, vocab = 300_000) // ~5 MB header; the needed keys are near the start
        var range: String? = null
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            val c = srv.accept()
            c.use {
                val lines = it.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine()?.takeIf { l -> l.isNotEmpty() } }.toList() }
                range = lines.firstOrNull { l -> l.startsWith("Range:", true) }
                // Ignore the Range on purpose: the client must still stop after 2 MB.
                runCatching { it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() } }
            }
        }.apply { isDaemon = true }.start()
        try {
            val info = HfSearch("http://127.0.0.1:${srv.localPort}").peek("http://127.0.0.1:${srv.localPort}/m.gguf")
            assertTrue(info.isMoe); assertEquals(8, info.expertUsed)
            assertEquals("Range: bytes=0-2097151", range)
        } finally { srv.close() }
    }
}
