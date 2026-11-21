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
data class GgufInfo(
    val architecture: String? = null, val name: String? = null, val expertCount: Int = 0, val expertUsed: Int = 0, val contextLength: Int = 0,
    /** llama.cpp's general.file_type (quantization), or -1. */
    val fileType: Int = -1,
    /** Number of transformer layers ({arch}.block_count), or 0. */
    val blockCount: Int = 0,
    /** Links the converter recorded: base model repo, source repo, ... (in order of usefulness). */
    val sourceUrls: List<String> = emptyList(),
) {
    /** The Hugging Face repo this GGUF was converted from ("org/name"), if its header says. */
    val sourceRepo: String? get() = sourceUrls.firstNotNullOfOrNull { hfRepo(it) }

    /** The quantization as llama-quantize names it ("Q4_K_M"), or null when unknown. */
    val quantName: String? get() = QUANTS[fileType]

    /**
     * Whether the Snapdragon NPU (Hexagon backend) can run this file's main weights: the Q* formats, IQ4_NL and MXFP4, not the
     * IQ1/IQ2/IQ3/IQ4_XS ones. Unknown formats are allowed.
     */
    val npuSupportsQuant: Boolean get() = quantName?.let { q -> q.startsWith("Q") || q == "IQ4_NL" || q.startsWith("MXFP4") || q == "F16" || q == "F32" } ?: true

    /** Mixture of Experts: each token only uses [expertUsed] of [expertCount] experts, so the model can run larger than RAM. */
    val isMoe get() = expertCount > 1

    companion object {
        /** Reads key/values until the ones needed are found or the bytes run out (partial data is fine). */
        fun read(input: InputStream): GgufInfo {
            val d = Le(DataInputStream(input.buffered(64 * 1024)))
            val kv = mutableMapOf<String, Any>()
            val urlKeys = listOf("general.base_model.0.repo_url", "general.source.repo_url", "general.source.huggingface.repository", "general.repo_url", "general.base_model.0.url", "general.source.url")
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
                    if (arch != null && "$arch.expert_count" in kv && "$arch.expert_used_count" in kv && "$arch.context_length" in kv && "general.file_type" in kv) break
                    if (key.startsWith("tokenizer.") && arch != null && "$arch.block_count" in kv) break // arch keys come first
                }
            } catch (_: EOFException) { // partial header: use what we have
            } catch (_: Exception) { }
            val arch = kv["general.architecture"] as? String
            fun int(k: String) = (kv["$arch.$k"] as? Number)?.toInt() ?: 0
            return GgufInfo(arch, kv["general.name"] as? String, int("expert_count"), int("expert_used_count"), int("context_length"),
                (kv["general.file_type"] as? Number)?.toInt() ?: -1, int("block_count"), urlKeys.mapNotNull { kv[it] as? String })
        }

        /** "org/name" from a Hugging Face URL (or a bare "org/name"), else null. */
        fun hfRepo(url: String): String? {
            val u = url.trim().removeSuffix("/")
            val path = when {
                "huggingface.co/" in u -> u.substringAfter("huggingface.co/")
                Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(u) -> u
                else -> return null
            }
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size < 2 || parts[0] in setOf("datasets", "spaces")) return null
            return "${parts[0]}/${parts[1]}"
        }

        /**
         * Best guess at the original model for a GGUF downloaded from a "…-GGUF" repo (unsloth/Qwen3-4B-GGUF →
         * unsloth/Qwen3-4B), for when the header doesn't say. Shown to the user to confirm.
         */
        fun guessSourceFromLink(link: String): String? {
            val repo = hfRepo(link.substringBefore("/resolve/").substringBefore("/blob/")) ?: return null
            return if (Regex("(?i)[-_.]gguf$").containsMatchIn(repo)) repo.replace(Regex("(?i)[-_.]gguf$"), "") else null
        }

        /** llama.cpp file types (general.file_type) by the name llama-quantize uses. */
        val QUANTS = mapOf(0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 10 to "Q2_K", 11 to "Q3_K_S",
            12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS",
            21 to "Q2_K_S", 22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M", 28 to "IQ2_S", 29 to "IQ2_M",
            30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 38 to "MXFP4_MOE")

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
