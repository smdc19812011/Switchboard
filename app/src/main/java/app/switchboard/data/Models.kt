package app.switchboard.data

import kotlinx.serialization.Serializable
import java.util.UUID

/** Wire protocol a provider speaks. Most hosted gateways (OpenRouter, Groq, Together, Ollama, LM Studio) are OpenAI-compatible. */
@Serializable
enum class ApiKind { ANTHROPIC, OPENAI_COMPATIBLE }

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ApiKind,
    val baseUrl: String,
    /** Encrypted at rest; see [SecretBox]. Never logged. */
    val apiKey: String = "",
    val model: String,
)

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    /** Which provider/model produced an assistant message, shown under the bubble. */
    val source: String? = null,
    val isError: Boolean = false,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppSettings(
    val providers: List<Provider> = emptyList(),
    val activeProviderId: String? = null,
    val systemPrompt: String = "",
    val maxTokens: Int = 8192,
    /** Lifts the app's own limits: plain HTTP to any host, 1M token cap, max_tokens sent everywhere, raw extra body params. */
    val unrestricted: Boolean = false,
    /** JSON object merged into every request body while unrestricted, e.g. {"temperature":0.7}. */
    val extraBody: String = "",
)

/** Starting points offered in the "Add provider" dialog. Users can edit every field. */
object Presets {
    val all = listOf(
        Provider(name = "Anthropic", kind = ApiKind.ANTHROPIC, baseUrl = "https://api.anthropic.com/v1", model = "claude-opus-5-5"),
        Provider(name = "OpenRouter", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://openrouter.ai/api/v1", model = "anthropic/claude-opus-5-5"),
        Provider(name = "OpenAI", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com/v1", model = ""),
        Provider(name = "Ollama (emulator host)", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "http://10.0.2.2:11434/v1", model = "llama3.2"),
        Provider(name = "Custom (OpenAI-compatible)", kind = ApiKind.OPENAI_COMPATIBLE, baseUrl = "https://", model = ""),
    )
}
