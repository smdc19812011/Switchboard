package app.switchboard.local

import app.switchboard.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Finds GGUF models on Hugging Face (public API, no account needed) and lists the files each one offers. */
class HfSearch(private val base: String = "https://huggingface.co") {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Repo(val id: String, val downloads: Long, val likes: Long, val gated: Boolean)

    /** One downloadable model: a single .gguf, or all parts of a split model (…-00001-of-00003.gguf). */
    data class GgufFile(val name: String, val urls: List<String>, val size: Long, val quant: String) {
        val parts get() = urls.size
    }

    private suspend fun get(url: okhttp3.HttpUrl, token: String?): kotlinx.serialization.json.JsonElement = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).header("User-Agent", "Switchboard")
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("Hugging Face answered HTTP ${r.code}")
            json.parseToJsonElement(r.body!!.string())
        }
    }

    /** Reads the model header from the start of a remote .gguf (first 2 MB, ranged request) to see e.g. whether it's MoE. */
    suspend fun peek(url: String, token: String? = null): GgufInfo = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).header("User-Agent", "Switchboard").header("Range", "bytes=0-2097151")
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("HTTP ${r.code}")
            // Never read more than 2 MB, even from a server that ignores Range.
            GgufInfo.read(object : java.io.FilterInputStream(r.body!!.byteStream()) {
                var left = 2L * 1024 * 1024
                override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
                override fun read(b: ByteArray, off: Int, len: Int): Int = if (left <= 0) -1 else super.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
            })
        }
    }

    /** Most-downloaded GGUF repos matching [query]. */
    suspend fun search(query: String, token: String? = null, limit: Int = 30): List<Repo> {
        val url = "$base/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim()).addQueryParameter("filter", "gguf")
            .addQueryParameter("sort", "downloads").addQueryParameter("direction", "-1").addQueryParameter("limit", "$limit").build()
        return (get(url, token) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.s("id") ?: o.s("modelId") ?: return@mapNotNull null
            Repo(id, o.l("downloads"), o.l("likes"), o["gated"]?.let { (it as? JsonPrimitive)?.contentOrNull?.let { g -> g != "false" } } ?: false)
        }
    }

    /** The .gguf files in [repo] (split models grouped into one entry), smallest first. Vision projector files are left out. */
    suspend fun files(repo: String, token: String? = null): List<GgufFile> {
        val url = "$base/api/models/$repo/tree/main".toHttpUrl().newBuilder().addQueryParameter("recursive", "true").build()
        val all = (get(url, token) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.s("type") != "file") return@mapNotNull null
            val path = o.s("path") ?: return@mapNotNull null
            if (!path.endsWith(".gguf", true) || "mmproj" in path.lowercase()) return@mapNotNull null
            val size = (o["lfs"] as? JsonObject)?.l("size")?.takeIf { it > 0 } ?: o.l("size")
            path to size
        }
        val split = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
        val out = mutableListOf<GgufFile>()
        all.groupBy { (p, _) -> split.find(p)?.groupValues?.get(1) ?: p }.forEach { (key, items) ->
            val sorted = items.sortedBy { it.first }
            val first = sorted.first().first
            val isSplit = split.matches(first)
            if (isSplit && split.find(first)!!.groupValues[3].toInt() != sorted.size) return@forEach // incomplete listing
            out += GgufFile(first.substringAfterLast('/'), sorted.map { (p, _) -> "$base/$repo/resolve/main/${p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}" },
                sorted.sumOf { it.second }, quantOf(key))
        }
        return out.sortedBy { it.size }
    }

    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

        /** "Q4_K_M", "IQ3_XS", "F16"… from a file name, or "". */
        fun quantOf(name: String): String =
            Regex("(?i)(IQ\\d_[A-Z]+|Q\\d_K_[SML]|Q\\d_K|Q\\d_\\d|Q\\d|BF16|F16|F32)(?![A-Za-z0-9])").findAll(name).lastOrNull()?.value?.uppercase() ?: ""

        /** A good default for phones: Q4_K_M, else the closest 4-bit quant. */
        fun recommended(files: List<GgufFile>): GgufFile? =
            files.firstOrNull { it.quant == "Q4_K_M" } ?: files.firstOrNull { it.quant.startsWith("Q4") || it.quant.startsWith("IQ4") }
    }
}
