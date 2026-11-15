package app.switchboard.net

import app.switchboard.data.ApiKind
import app.switchboard.data.ChatMessage
import app.switchboard.data.Provider
import app.switchboard.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/** Streams replies from either an Anthropic Messages endpoint or any OpenAI-compatible /chat/completions endpoint. */
class ChatClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // long thinking pauses between SSE events
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    fun stream(p: Provider, history: List<ChatMessage>, system: String, maxTokens: Int): Flow<String> = callbackFlow {
        val request = when (p.kind) {
            ApiKind.ANTHROPIC -> anthropicRequest(p, history, system, maxTokens)
            ApiKind.OPENAI_COMPATIBLE -> openAiRequest(p, history, system)
        }
        val call = http.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(errorText(resp))
                val source = resp.body?.source() ?: throw ApiException("Empty response")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val text = when (p.kind) {
                        ApiKind.ANTHROPIC -> parseAnthropicEvent(obj)
                        ApiKind.OPENAI_COMPATIBLE -> parseOpenAiChunk(obj)
                    }
                    if (!text.isNullOrEmpty()) trySend(text)
                }
            }
            close()
        } catch (e: Exception) {
            close(if (call.isCanceled()) null else e)
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(p: Provider): List<String> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/models?limit=1000").get()
        authHeaders(b, p)
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(errorText(resp))
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            root["data"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .sorted()
        }
    }

    // --- Anthropic Messages API ---

    private fun anthropicRequest(p: Provider, history: List<ChatMessage>, system: String, maxTokens: Int): Request {
        val body = buildJsonObject {
            put("model", p.model)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                for (m in mergeTurns(history)) addJsonObject {
                    put("role", if (m.role == Role.USER) "user" else "assistant")
                    put("content", m.text)
                }
            }
        }
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/messages").post(body.toString().toRequestBody(jsonType))
        authHeaders(b, p)
        return b.build()
    }

    private fun parseAnthropicEvent(o: JsonObject): String? = when (o.str("type")) {
        "content_block_delta" -> o["delta"]?.jsonObject?.takeIf { it.str("type") == "text_delta" }?.str("text")
        "message_delta" -> if (o["delta"]?.jsonObject?.str("stop_reason") == "refusal") "\n\n_[The model declined this request.]_" else null
        "error" -> throw ApiException(o["error"]?.jsonObject?.str("message") ?: "Stream error")
        else -> null
    }

    // --- OpenAI-compatible Chat Completions ---

    private fun openAiRequest(p: Provider, history: List<ChatMessage>, system: String): Request {
        val body = buildJsonObject {
            put("model", p.model)
            put("stream", true)
            putJsonArray("messages") {
                if (system.isNotBlank()) addJsonObject { put("role", "system"); put("content", system) }
                for (m in mergeTurns(history)) addJsonObject {
                    put("role", if (m.role == Role.USER) "user" else "assistant")
                    put("content", m.text)
                }
            }
        }
        val b = Request.Builder().url(p.baseUrl.trimEnd('/') + "/chat/completions").post(body.toString().toRequestBody(jsonType))
        authHeaders(b, p)
        return b.build()
    }

    private fun parseOpenAiChunk(o: JsonObject): String? {
        o["error"]?.let { throw ApiException(it.jsonObject.str("message") ?: "Stream error") }
        val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return choice["delta"]?.jsonObject?.str("content")
    }

    // --- shared ---

    private fun authHeaders(b: Request.Builder, p: Provider) {
        when (p.kind) {
            ApiKind.ANTHROPIC -> {
                b.header("x-api-key", p.apiKey)
                b.header("anthropic-version", "2026-10-21")
            }
            ApiKind.OPENAI_COMPATIBLE -> {
                if (p.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${p.apiKey}")
                if ("openrouter.ai" in p.baseUrl) b.header("X-Title", "Switchboard")
            }
        }
    }

    /** Drops failed replies and merges back-to-back same-role turns, which strict APIs reject. */
    private fun mergeTurns(history: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (m in history) {
            if (m.isError || m.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) out[out.lastIndex] = last.copy(text = last.text + "\n\n" + m.text)
            else out += m
        }
        return out
    }

    private fun errorText(resp: Response): String {
        val raw = resp.body?.string().orEmpty()
        val msg = runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            o["error"]?.let { e -> if (e is JsonObject) e.str("message") else e.jsonPrimitive.contentOrNull }
                ?: o.str("message")
        }.getOrNull()
        return "HTTP ${resp.code}: ${msg ?: raw.take(300).ifBlank { resp.message }}"
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
