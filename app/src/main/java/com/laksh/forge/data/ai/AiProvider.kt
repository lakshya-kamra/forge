package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** One message in a conversation sent to a provider. */
data class AiTurn(val role: Role, val parts: List<AiPart>) {
    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }

    companion object {
        fun user(text: String) = AiTurn(Role.USER, listOf(AiPart.Text(text)))
        fun user(parts: List<AiPart>) = AiTurn(Role.USER, parts)
        fun assistant(text: String) = AiTurn(Role.ASSISTANT, listOf(AiPart.Text(text)))

        /** History pairs as the coach stores them: ("user" | "assistant", text). */
        fun fromHistory(role: String, text: String): AiTurn =
            if (role.equals("assistant", ignoreCase = true)) assistant(text) else user(text)
    }
}

/** Ask for a JSON object matching [schema] (see [AiPrompts] for the schema rules). */
data class JsonSpec(val name: String, val schema: JsonObject)

/** A provider-neutral request. Each provider translates it into its own wire format. */
data class AiRequest(
    val task: Task,
    val model: String,
    /** Registry entry for [model], or null for an id Forge doesn't know (override/custom). */
    val modelSpec: ModelSpec?,
    val system: String,
    val turns: List<AiTurn>,
    val maxTokens: Int,
    val json: JsonSpec? = null
) {
    val imageCount: Int get() = turns.sumOf { t -> t.parts.count { it is AiPart.Image } }
}

data class Completion(
    val text: String,
    /** True when the provider stopped at the output limit, so the text is incomplete. */
    val truncated: Boolean
)

/** One vendor's wire protocol. Stateless apart from configuration, so cheap to construct per call. */
interface AiProvider {
    val spec: ProviderSpec

    suspend fun complete(key: String, request: AiRequest): Completion

    /** Incremental text deltas. Errors (including refusals) surface as [AiException]. */
    fun stream(key: String, request: AiRequest): Flow<String>

    suspend fun listModels(key: String): List<String>

    /** No-cost key check (GET models): true when accepted, false on 401/403; throws otherwise. */
    suspend fun probe(key: String): Boolean
}

/** Build the right [AiProvider] for a registry entry. */
object AiProviders {
    /** @param baseUrlOverride the Advanced/Custom base URL, already normalised; blank = provider default. */
    fun create(spec: ProviderSpec, baseUrlOverride: String = ""): AiProvider = when (spec.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(spec)
        // Hybrid gateway; its address is fixed (allowsBaseUrlOverride = false).
        ProviderKind.COMMAND_CODE -> CommandCodeProvider(spec)
        ProviderKind.OPENAI_COMPATIBLE -> {
            val base = baseUrlOverride.takeIf { it.isNotBlank() && spec.allowsBaseUrlOverride } ?: spec.baseUrl
            if (base.isBlank()) {
                throw AiException("Set the base URL for the custom endpoint in Settings → AI.")
            }
            OpenAiCompatProvider(spec, base)
        }
    }
}
