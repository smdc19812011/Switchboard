package app.switchboard.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.switchboard.data.AppSettings
import app.switchboard.data.ChatMessage
import app.switchboard.data.Conversation
import app.switchboard.data.Provider
import app.switchboard.data.Role
import app.switchboard.data.Store
import app.switchboard.net.ChatClient
import app.switchboard.net.RequestOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val client = ChatClient()

    var settings by mutableStateOf(AppSettings()); private set
    var conversations by mutableStateOf(listOf<Conversation>()); private set
    var current by mutableStateOf(Conversation()); private set
    var streaming by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set

    private var job: Job? = null

    val activeProvider: Provider?
        get() = settings.providers.firstOrNull { it.id == settings.activeProviderId } ?: settings.providers.firstOrNull()

    init {
        viewModelScope.launch {
            val (s, c) = withContext(Dispatchers.IO) { store.loadSettings() to store.loadConversations() }
            settings = s; conversations = c; loaded = true
        }
    }

    // --- conversations ---

    fun newChat() { stop(); current = Conversation() }

    fun open(id: String) { stop(); conversations.firstOrNull { it.id == id }?.let { current = it } }

    fun delete(id: String) {
        conversations = conversations.filterNot { it.id == id }
        if (current.id == id) current = Conversation()
        io { store.delete(id) }
    }

    fun send(text: String) {
        val provider = activeProvider ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || streaming) return
        val title = if (current.messages.isEmpty()) trimmed.lineSequence().first().take(48) else current.title
        current = current.copy(title = title, messages = current.messages + ChatMessage(role = Role.USER, text = trimmed))
        respond(provider)
    }

    /** Re-run the last reply, optionally on whichever provider/model is active now. */
    fun regenerate() {
        val provider = activeProvider ?: return
        if (streaming) return
        val msgs = current.messages
        if (msgs.lastOrNull()?.role != Role.ASSISTANT) return
        current = current.copy(messages = msgs.dropLast(1))
        respond(provider)
    }

    fun stop() { job?.cancel() }

    private fun respond(provider: Provider) {
        val history = current.messages
        val reply = ChatMessage(role = Role.ASSISTANT, text = "", source = "${provider.name} · ${provider.model}")
        current = current.copy(messages = history + reply)
        streaming = true
        job = viewModelScope.launch {
            val buf = StringBuilder()
            try {
                client.stream(provider, history, requestOptions()).collect { chunk ->
                    buf.append(chunk)
                    updateReply(reply.id) { it.copy(text = buf.toString()) }
                }
                if (buf.isEmpty()) updateReply(reply.id) { it.copy(text = "(empty response)", isError = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (buf.isEmpty()) updateReply(reply.id) { it.copy(text = "(stopped)", isError = true) }
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                updateReply(reply.id) { it.copy(text = (buf.toString() + "\n\n⚠ $msg").trim(), isError = true) }
            } finally {
                streaming = false
                persistCurrent()
            }
        }
    }

    private fun updateReply(id: String, f: (ChatMessage) -> ChatMessage) {
        current = current.copy(messages = current.messages.map { if (it.id == id) f(it) else it })
    }

    private fun persistCurrent() {
        val c = current.copy(updatedAt = System.currentTimeMillis())
        current = c
        conversations = (listOf(c) + conversations.filterNot { it.id == c.id })
        io { store.save(c) }
    }

    // --- settings ---

    fun selectProvider(id: String) = updateSettings { it.copy(activeProviderId = id) }

    fun setModel(model: String) {
        val p = activeProvider ?: return
        upsertProvider(p.copy(model = model))
    }

    fun upsertProvider(p: Provider) = updateSettings { s ->
        val exists = s.providers.any { it.id == p.id }
        val list = if (exists) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
        s.copy(providers = list, activeProviderId = s.activeProviderId ?: p.id)
    }

    fun removeProvider(id: String) = updateSettings { s ->
        val list = s.providers.filterNot { it.id == id }
        s.copy(providers = list, activeProviderId = if (s.activeProviderId == id) list.firstOrNull()?.id else s.activeProviderId)
    }

    fun setSystemPrompt(v: String) = updateSettings { it.copy(systemPrompt = v) }
    fun setMaxTokens(v: Int) = updateSettings { it.copy(maxTokens = v.coerceIn(1, maxTokenLimit)) }

    val maxTokenLimit get() = if (settings.unrestricted) 1_000_000 else 128_000

    fun setUnrestricted(on: Boolean) = updateSettings {
        it.copy(unrestricted = on, maxTokens = if (on) it.maxTokens else it.maxTokens.coerceAtMost(128_000))
    }

    fun setExtraBody(v: String) = updateSettings { it.copy(extraBody = v) }

    /** Null when blank; throws with a readable message when not a JSON object. */
    fun parseExtraBody(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw IllegalArgumentException("Extra body params must be a JSON object, e.g. {\"temperature\": 0.7}") }
    }

    private fun requestOptions() = RequestOptions(
        system = settings.systemPrompt,
        maxTokens = settings.maxTokens,
        unrestricted = settings.unrestricted,
        extraBody = if (settings.unrestricted) parseExtraBody(settings.extraBody) else null,
    )

    suspend fun fetchModels(p: Provider): Result<List<String>> = runCatching { client.listModels(p, settings.unrestricted) }

    private fun updateSettings(f: (AppSettings) -> AppSettings) {
        settings = f(settings)
        val snapshot = settings
        io { store.saveSettings(snapshot) }
    }

    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }
}
