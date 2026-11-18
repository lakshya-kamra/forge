package com.laksh.forge.data.remote

import com.laksh.forge.data.ai.AiEngine
import com.laksh.forge.data.ai.AiException
import com.laksh.forge.data.ai.AiJson
import com.laksh.forge.data.ai.AiKeys
import com.laksh.forge.data.ai.AnthropicProvider
import com.laksh.forge.data.ai.KeyDetector
import com.laksh.forge.data.ai.ProviderRegistry
import kotlinx.serialization.json.Json

/**
 * v1's Anthropic-only client, kept as a thin compatibility shim so ForgeRepository keeps
 * compiling until it constructs [com.laksh.forge.data.ai.MultiProviderAi] instead (requested
 * in docs/contracts/requests/ai-providers.md). All behaviour now lives in data/ai: the same
 * prompts and schemas every provider uses, sent through [AnthropicProvider].
 *
 * The key comes from [apiKeyProvider] (v1's plaintext setting) and, once that has been
 * migrated and blanked, from the encrypted [com.laksh.forge.data.ai.SecretStore].
 */
@Deprecated("Use com.laksh.forge.data.ai.MultiProviderAi, which routes any provider's key.")
class AnthropicClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: (Task) -> String
) : AiEngine() {

    private val provider = AnthropicProvider(ProviderRegistry.anthropic)

    override val isConfigured: Boolean
        get() = apiKeyProvider().isNotBlank() || AiKeys.peek() != null

    override fun route(task: Task, imageCount: Int): AiEngine.Route {
        val model = modelProvider(task)
        return AiEngine.Route(provider, model, ProviderRegistry.anthropic.model(model))
    }

    override suspend fun apiKey(): String {
        KeyDetector.clean(apiKeyProvider()).takeIf { it.isNotEmpty() }?.let { return it }
        return AiKeys.current() ?: throw AiException("No API key set. Add one in Settings → AI.")
    }

    companion object {
        /** Lenient JSON shared with OpenFoodFactsClient (which predates data/ai). */
        val JSON: Json get() = AiJson.json
    }
}
