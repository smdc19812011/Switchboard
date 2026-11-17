package app.switchboard.local

import app.switchboard.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Getting GGUF files onto the phone: downloads (resumable) and copies from picked files. */
object ModelFiles {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()

    /** Hugging Face page links (…/blob/main/x.gguf) become direct download links (…/resolve/main/x.gguf). */
    fun directUrl(url: String): String {
        val u = url.trim()
        return if ("huggingface.co/" in u && "/blob/" in u) u.replaceFirst("/blob/", "/resolve/") else u
    }

    /** File name for a link: the last path segment, without query, ending in .gguf. */
    fun nameFor(url: String): String {
        val seg = directUrl(url).substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val clean = java.net.URLDecoder.decode(seg, "UTF-8").replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model" }
        return if (clean.endsWith(".gguf", true)) clean else "$clean.gguf"
    }

    class Progress(val done: Long, val total: Long)

    /**
     * Downloads [url] to [dest], resuming a partial download (dest.part) when the server supports it. [token] is sent as a
     * bearer token (for gated Hugging Face models). Checks the GGUF magic so an HTML error page isn't mistaken for a model.
     */
    suspend fun download(url: String, dest: File, token: String? = null, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        val have = if (part.exists()) part.length() else 0L
        val rb = Request.Builder().url(directUrl(url)).header("User-Agent", "Switchboard")
        if (have > 0) rb.header("Range", "bytes=$have-")
        token?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
        val call = http.newCall(rb.build())
        val resp = try { call.execute() } catch (e: Exception) { throw ApiException("Download failed: ${e.message}") }
        resp.use { r ->
            if (r.code == 401 || r.code == 403) throw ApiException("The server refused the download (HTTP ${r.code}). Gated Hugging Face models need you to accept their license and an access token.")
            if (r.code == 416 && have > 0) { finish(part, dest); return@withContext }
            if (!r.isSuccessful) throw ApiException("Download failed: HTTP ${r.code}")
            val resumed = r.code == 206
            val body = r.body ?: throw ApiException("Empty download")
            val total = body.contentLength().let { if (it < 0) -1 else if (resumed) it + have else it }
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                var done = if (resumed) have else 0L
                val buf = ByteArray(256 * 1024)
                val input = body.byteStream()
                var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (System.currentTimeMillis() - last > 300) { last = System.currentTimeMillis(); onProgress(Progress(done, total)) }
                }
                onProgress(Progress(done, total))
                if (total > 0 && done < total) throw ApiException("Download stopped early ($done of $total bytes). Try again to resume.")
            }
        }
        finish(part, dest)
    }

    private fun finish(part: File, dest: File) {
        checkGguf(part)
        dest.delete()
        if (!part.renameTo(dest)) throw ApiException("Couldn't save ${dest.name}.")
    }

    /** Copies a picked file (content:// stream) into app storage. */
    suspend fun copy(input: InputStream, size: Long, dest: File, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        input.use { inp ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var done = 0L; var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (System.currentTimeMillis() - last > 300) { last = System.currentTimeMillis(); onProgress(Progress(done, size)) }
                }
                onProgress(Progress(done, size))
            }
        }
        finish(part, dest)
    }

    /** Throws unless [f] starts with the GGUF magic bytes. */
    fun checkGguf(f: File) {
        val magic = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(magic) } }.getOrDefault(0)
        if (n < 4 || String(magic, Charsets.ISO_8859_1) != "GGUF") {
            val head = runCatching { f.readBytes().take(200).toByteArray().decodeToString() }.getOrDefault("")
            f.delete()
            throw ApiException("That isn't a GGUF model file" + if (head.contains("<html", true)) " (the link returned a web page; use the file's direct download link)." else ".")
        }
    }

    fun human(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }
}
