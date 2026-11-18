package app.switchboard.studio

import app.switchboard.net.ApiException
import app.switchboard.net.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Open Higgsfield AI, embedded: image, video and lip-sync generation through kie.ai, ported from the open-source
 * Open-Higgsfield-AI studio (MIT). Jobs are created, polled until done, and their results downloaded, so the chat's
 * model (even a text-only one) only has to make one tool call.
 */
object Studio {
    enum class Kind { IMAGE, EDIT, VIDEO, LIPSYNC }

    /**
     * One kie.ai model. [input] says how reference media is passed: image edits use image_input / input_urls / image_url;
     * videos use image_urls or first_frame_url; Veo has its own endpoint.
     */
    data class Model(val id: String, val label: String, val kind: Kind, val note: String, val input: String = "", val i2v: String? = null)

    /** The models the studio's kie.ai client uses. Video models with [Model.i2v] switch to that model when given a start image. */
    val models = listOf(
        Model("nano-banana-2", "Nano Banana 2", Kind.IMAGE, "fast, good all-rounder; also edits with reference images", "image_input"),
        Model("nano-banana-pro", "Nano Banana Pro", Kind.IMAGE, "highest quality Nano Banana; text in images", "image_input"),
        Model("nano-banana", "Nano Banana", Kind.IMAGE, "original Nano Banana", "image_input"),
        Model("gpt-image-2-text-to-image", "GPT Image 2", Kind.IMAGE, "strong prompt following and typography"),
        Model("grok-imagine/text-to-image", "Grok Imagine", Kind.IMAGE, "stylised, quick"),
        Model("nano-banana-edit", "Nano Banana Edit", Kind.EDIT, "edit or combine up to 14 reference images", "image_input"),
        Model("gpt-image-2-image-to-image", "GPT Image 2 Edit", Kind.EDIT, "precise edits from reference images", "input_urls"),
        Model("qwen/image-to-image", "Qwen Image Edit", Kind.EDIT, "edits one image", "image_url"),
        Model("kling/v2-1-pro", "Kling 2.1 Pro", Kind.VIDEO, "cinematic motion; text or start image", "image_urls"),
        Model("kling/v2-1-standard", "Kling 2.1 Standard", Kind.VIDEO, "cheaper Kling", "image_urls"),
        Model("bytedance/seedance-2", "Seedance 2", Kind.VIDEO, "smooth, coherent shots", "image_urls"),
        Model("bytedance/seedance-2-fast", "Seedance 2 Fast", Kind.VIDEO, "faster, cheaper Seedance", "image_urls"),
        Model("bytedance/seedance-1.5-pro", "Seedance 1.5 Pro", Kind.VIDEO, "previous Seedance", "image_urls"),
        Model("wan/3-0-video", "Wan 3", Kind.VIDEO, "open model; start frame supported", "first_frame_url"),
        Model("sora-2-text-to-video", "Sora 2", Kind.VIDEO, "realistic scenes with sound", "image_urls", i2v = "sora-2-image-to-video"),
        Model("sora-2-pro-text-to-video", "Sora 2 Pro", Kind.VIDEO, "best Sora quality", "image_urls", i2v = "sora-2-pro-image-to-video"),
        Model("veo3", "Veo 3", Kind.VIDEO, "Google Veo 3 with audio; start/end frames", "veo"),
        Model("veo3_fast", "Veo 3 Fast", Kind.VIDEO, "faster, cheaper Veo 3", "veo"),
        Model("veo3_lite", "Veo 3 Lite", Kind.VIDEO, "lightest Veo 3", "veo"),
        Model("omnihuman-1-5", "OmniHuman 1.5", Kind.LIPSYNC, "portrait image + audio → talking video"),
        Model("volcengine/video-to-video-lip-sync", "Volcengine Lip Sync", Kind.LIPSYNC, "video + audio → re-synced video"),
    )

    fun model(id: String?): Model? = id?.let { i -> models.firstOrNull { it.id.equals(i, true) } ?: models.firstOrNull { it.label.equals(i, true) } }
    fun of(kind: Kind) = models.filter { it.kind == kind }

    const val IMAGE_TOOL = "studio_image"
    const val VIDEO_TOOL = "studio_video"
    const val LIPSYNC_TOOL = "studio_lip_sync"
    val toolNames = setOf(IMAGE_TOOL, VIDEO_TOOL, LIPSYNC_TOOL)

    private fun schema(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object"); putJsonObject("properties", props); put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    private fun list(kind: Kind) = of(kind).joinToString("; ") { "${it.id} (${it.note})" }

    fun tools(defaultImage: String, defaultVideo: String) = listOf(
        ToolSpec(IMAGE_TOOL, "Make an image with the Open Higgsfield studio (kie.ai). Works for any chat model: just describe the picture. " +
            "With reference_files it edits or combines those images (files attached in this chat, by name, or paths on the tool machine). " +
            "Text-to-image models: ${list(Kind.IMAGE)}. Edit models: ${list(Kind.EDIT)}. Default: $defaultImage. The image is attached to the chat.",
            schema(listOf("prompt")) {
                putJsonObject("prompt") { put("type", "string"); put("description", "Detailed description: subject, style, lighting, composition, camera/lens if relevant") }
                putJsonObject("model") { put("type", "string"); put("description", "Model id from the list (optional)") }
                putJsonObject("aspect_ratio") { put("type", "string"); put("description", "e.g. 1:1, 16:9, 9:16, 4:3, 3:2, 21:9") }
                putJsonObject("resolution") { put("type", "string"); put("description", "1K, 2K or 4K where the model supports it") }
                putJsonObject("reference_files") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Images to edit or combine (up to 14)") }
            }),
        ToolSpec(VIDEO_TOOL, "Make a video with the Open Higgsfield studio (kie.ai): from text, or animate a start image (start_image: a file in this chat or on the tool machine). " +
            "Takes a few minutes. Models: ${list(Kind.VIDEO)}. Default: $defaultVideo. The video is attached to the chat.",
            schema(listOf("prompt")) {
                putJsonObject("prompt") { put("type", "string"); put("description", "Scene, action, camera movement, mood, sound") }
                putJsonObject("model") { put("type", "string") }
                putJsonObject("start_image") { put("type", "string"); put("description", "Image to animate (optional)") }
                putJsonObject("aspect_ratio") { put("type", "string"); put("description", "16:9, 9:16 or 1:1") }
                putJsonObject("resolution") { put("type", "string"); put("description", "e.g. 720p or 1080p") }
                putJsonObject("duration") { put("type", "string"); put("description", "Seconds, e.g. 5 or 10, where supported") }
            }),
        ToolSpec(LIPSYNC_TOOL, "Lip sync with the Open Higgsfield studio (kie.ai): a portrait image + audio becomes a talking video (OmniHuman 1.5), " +
            "or a video + audio gets re-synced lips. Files are names of files in this chat or paths on the tool machine (text_to_speech can make the audio).",
            schema(listOf("audio")) {
                putJsonObject("image") { put("type", "string"); put("description", "Portrait image (or give video)") }
                putJsonObject("video") { put("type", "string"); put("description", "Video to re-sync (or give image)") }
                putJsonObject("audio") { put("type", "string"); put("description", "Speech or song audio") }
                putJsonObject("prompt") { put("type", "string"); put("description", "Optional direction for the performance") }
            }),
    )
}

/** Talks to kie.ai: create a job, poll it, upload reference files, read credits. */
class KieClient(private val key: String, private val base: String = "https://api.kie.ai") {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    class Result(val urls: List<String>, val model: String)

    private fun call(req: Request.Builder): JsonObject {
        val r = http.newCall(req.header("Authorization", "Bearer $key").build()).execute()
        r.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 401 || it.code == 403) throw ApiException("kie.ai rejected the API key (HTTP ${it.code}). Check it in Settings > Embedded tools.", it.code)
            if (!it.isSuccessful) throw ApiException("kie.ai HTTP ${it.code}: ${body.take(300)}", it.code)
            val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw ApiException("kie.ai sent an unexpected reply: ${body.take(200)}") }
            val code = (o["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (code != null && code != 200) throw ApiException("kie.ai error $code: ${(o["msg"] as? JsonPrimitive)?.contentOrNull ?: "unknown"}" +
                if (code == 402) " (out of credits: top up at kie.ai)" else "", code)
            return o
        }
    }

    private fun post(path: String, body: JsonObject) = call(Request.Builder().url(base + path).post(body.toString().toRequestBody(jsonType)))
    private fun get(path: String) = call(Request.Builder().url(base + path).get())
    private fun JsonObject.data() = this["data"] as? JsonObject
    private fun JsonElement?.s() = (this as? JsonPrimitive)?.contentOrNull

    /** Remaining credits (also checks the key). */
    suspend fun credits(): Double = withContext(Dispatchers.IO) {
        val o = get("/api/v1/chat/credit")
        (o["data"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: o.data()?.get("credits").s()?.toDoubleOrNull() ?: 0.0
    }

    /** Uploads a local file; kie.ai keeps it for 24 hours and returns its URL. */
    suspend fun upload(file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("uploadPath", "switchboard").addFormDataPart("fileName", file.name).build()
        val d = call(Request.Builder().url("$base/api/v1/file-stream-upload").post(body)).data()
        d?.get("fileUrl").s() ?: d?.get("downloadUrl").s() ?: d?.get("url").s() ?: throw ApiException("kie.ai didn't return a URL for ${file.name}.")
    }

    /** Result URLs from a finished job, wherever kie.ai put them (resultUrls, response.resultUrls, or the resultJson string). */
    fun urlsOf(d: JsonObject): List<String> {
        fun arr(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { it.s() }.orEmpty()
        arr(d["resultUrls"]).ifEmpty { arr((d["response"] as? JsonObject)?.get("resultUrls")) }.takeIf { it.isNotEmpty() }?.let { return it }
        d["resultJson"].s()?.let { s -> runCatching { arr(json.parseToJsonElement(s).jsonObject["resultUrls"]) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it } }
        return emptyList()
    }

    /** Creates a job and waits for it ([poll] ms between checks, up to [timeoutMs]). [onWait] gets elapsed seconds. */
    suspend fun run(model: String, input: JsonObject, timeoutMs: Long, poll: Long = 3000, onWait: (Int) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val task = post("/api/v1/jobs/createTask", buildJsonObject { put("model", model); put("input", input) }).data()?.get("taskId").s()
            ?: throw ApiException("kie.ai didn't start the job.")
        val start = System.currentTimeMillis()
        while (true) {
            delay(poll)
            val elapsed = System.currentTimeMillis() - start
            onWait((elapsed / 1000).toInt())
            val d = runCatching { get("/api/v1/jobs/recordInfo?taskId=$task").data() }.getOrElse { e -> if ((e as? ApiException)?.httpCode?.let { it >= 500 } == true) null else throw e }
            if (d != null) when (d["state"].s()?.lowercase()) {
                "success" -> return@withContext Result(urlsOf(d).ifEmpty { throw ApiException("The job finished but returned no file.") }, model)
                "fail", "error" -> throw ApiException("Generation failed: ${d["failMsg"].s() ?: d["failCode"].s() ?: "unknown reason"}")
            }
            if (elapsed > timeoutMs) throw ApiException("Still not finished after ${timeoutMs / 60000} minutes (task $task).")
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Veo 3 has its own endpoint and status format. */
    suspend fun runVeo(model: String, prompt: String, imageUrl: String?, aspect: String?, resolution: String?, timeoutMs: Long, poll: Long = 3000, onWait: (Int) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("prompt", prompt); put("model", model)
            put("generationType", if (imageUrl != null) "FIRST_AND_LAST_FRAMES_2_VIDEO" else "TEXT_2_VIDEO")
            put("aspect_ratio", aspect ?: "16:9"); put("resolution", resolution ?: "720p"); put("enableFallback", false)
            if (imageUrl != null) putJsonArray("imageUrls") { add(JsonPrimitive(imageUrl)) }
        }
        val task = post("/api/v1/veo/generate", body).data()?.get("taskId").s() ?: throw ApiException("kie.ai didn't start the Veo job.")
        val start = System.currentTimeMillis()
        while (true) {
            delay(poll)
            val elapsed = System.currentTimeMillis() - start
            onWait((elapsed / 1000).toInt())
            val d = runCatching { get("/api/v1/veo/record-info?taskId=$task").data() }.getOrElse { e -> if ((e as? ApiException)?.httpCode?.let { it >= 500 } == true) null else throw e }
            when (d?.get("successFlag").s()) {
                "1" -> return@withContext Result(urlsOf(d!!).ifEmpty { throw ApiException("Veo finished but returned no file.") }, model)
                "2", "3" -> throw ApiException("Veo generation failed: ${d?.get("errorMessage").s() ?: "unknown reason"}")
            }
            if (elapsed > timeoutMs) throw ApiException("Veo is still not finished after ${timeoutMs / 60000} minutes (task $task).")
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Downloads a result into [dest]. */
    suspend fun download(url: String, dest: File): File = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("Couldn't download the result (HTTP ${r.code}).")
            dest.parentFile?.mkdirs(); dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        dest
    }

    /** Input for an image job: prompt, size, and reference image URLs in the model's own field. */
    fun imageInput(m: Studio.Model, prompt: String, aspect: String?, resolution: String?, refs: List<String>) = buildJsonObject {
        put("prompt", prompt); put("output_format", "jpg")
        aspect?.let { put("aspect_ratio", it) }; resolution?.let { put("resolution", it.uppercase()) }
        if (refs.isNotEmpty()) when (m.input) {
            "image_url" -> put("image_url", refs.first())
            "input_urls" -> putJsonArray("input_urls") { refs.forEach { add(JsonPrimitive(it)) } }
            else -> putJsonArray("image_input") { refs.forEach { add(JsonPrimitive(it)) } }
        }
    }

    fun videoInput(m: Studio.Model, prompt: String, image: String?, aspect: String?, resolution: String?, duration: String?) = buildJsonObject {
        put("prompt", prompt)
        aspect?.let { put("aspect_ratio", it) }
        duration?.let { put("duration", it.filter(Char::isDigit).ifEmpty { it }) }
        if (image != null && m.input == "first_frame_url") { put("first_frame_url", image); resolution?.let { put("resolution", it.uppercase()) } }
        else { resolution?.let { put("resolution", it) }; if (image != null) putJsonArray("image_urls") { add(JsonPrimitive(image)) } }
    }
}
