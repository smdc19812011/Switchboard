package app.switchboard.local

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a GGUF file's header says about the model. Read from the start of the file only, so it also works on the first
 * couple of megabytes of a file that hasn't been downloaded (a ranged request to Hugging Face).
 */
data class GgufInfo(val architecture: String? = null, val name: String? = null, val expertCount: Int = 0, val expertUsed: Int = 0, val contextLength: Int = 0) {
    /** Mixture of Experts: each token only uses [expertUsed] of [expertCount] experts, so the model can run larger than RAM. */
    val isMoe get() = expertCount > 1

    companion object {
        /** Reads key/values until the ones needed are found or the bytes run out (partial data is fine). */
        fun read(input: InputStream): GgufInfo {
            val d = Le(DataInputStream(input.buffered(64 * 1024)))
            val kv = mutableMapOf<String, Any>()
            try {
                if (String(d.bytes(4), Charsets.ISO_8859_1) != "GGUF") return GgufInfo()
                val version = d.u32()
                if (version < 2) return GgufInfo()
                d.u64() // tensor count
                val n = d.u64()
                for (i in 0 until n) {
                    val key = d.str()
                    val v = d.value(d.u32().toInt())
                    if (v != null) kv[key] = v
                    val arch = kv["general.architecture"] as? String
                    if (arch != null && "$arch.expert_count" in kv && "$arch.expert_used_count" in kv && "$arch.context_length" in kv) break
                    if (key.startsWith("tokenizer.") && arch != null && "$arch.block_count" in kv) break // arch keys come first
                }
            } catch (_: EOFException) { // partial header: use what we have
            } catch (_: Exception) { }
            val arch = kv["general.architecture"] as? String
            fun int(k: String) = (kv["$arch.$k"] as? Number)?.toInt() ?: 0
            return GgufInfo(arch, kv["general.name"] as? String, int("expert_count"), int("expert_used_count"), int("context_length"))
        }

        /** Guess from a name when the header isn't available (e.g. "Qwen3-30B-A3B", "Mixtral-8x7B", "gpt-oss-20b"). */
        fun looksMoe(name: String): Boolean =
            Regex("(?i)(-|_|\\b)A\\d+(\\.\\d+)?B(\\b|-|_)|\\bmoe\\b|mixtral|\\d+x\\d+(\\.\\d+)?b|gpt-oss|deepseek-v[23]|olmoe|granite.*-a\\d").containsMatchIn(name)
    }

    private class Le(val d: DataInputStream) {
        private val b8 = ByteArray(8)
        fun bytes(n: Int) = ByteArray(n).also { d.readFully(it) }
        fun u32(): Long { d.readFully(b8, 0, 4); return ByteBuffer.wrap(b8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
        fun u64(): Long { d.readFully(b8); return ByteBuffer.wrap(b8).order(ByteOrder.LITTLE_ENDIAN).long }
        fun str(): String { val n = u64(); if (n > 1_000_000) throw IllegalStateException(); return String(bytes(n.toInt()), Charsets.UTF_8) }
        fun skip(n: Long) { var left = n; while (left > 0) { val s = d.skip(left); if (s <= 0) { d.readByte(); left-- } else left -= s } }
        /** Scalar values are returned; arrays are skipped (null). */
        fun value(t: Int): Any? = when (t) {
            0, 1 -> d.readByte().toInt()
            2, 3 -> { d.readFully(b8, 0, 2); ByteBuffer.wrap(b8, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() }
            4 -> u32()
            5 -> u32().toInt()
            6 -> { d.readFully(b8, 0, 4); ByteBuffer.wrap(b8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float }
            7 -> d.readByte().toInt() != 0
            8 -> str()
            9 -> {
                val et = u32().toInt(); val n = u64()
                val size = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)[et]
                if (size != null) skip(n * size) else for (i in 0 until n) value(et)
                null
            }
            10, 11 -> u64()
            12 -> { d.readFully(b8); ByteBuffer.wrap(b8).order(ByteOrder.LITTLE_ENDIAN).double }
            else -> throw IllegalStateException("bad type $t")
        }
    }
}

/** How a model of [size] bytes fits a phone with [ram] bytes of memory. */
enum class Fit { FITS, BIG_MOE, TOO_BIG;
    companion object {
        fun of(size: Long, ram: Long, moe: Boolean): Fit = when {
            ram <= 0 || size < ram * 0.7 -> FITS
            // Only the experts in use need to be in memory; the rest is read from storage. Much beyond RAM, the constant
            // re-reading makes Android's low-memory killer step in, so the margin is modest.
            moe && size < ram * 1.25 -> BIG_MOE
            else -> TOO_BIG
        }
        /** Above this, "bigger than memory" mode is worth turning on. */
        fun wantsLargeMode(size: Long, ram: Long) = ram > 0 && size > ram * 0.55
    }
}
