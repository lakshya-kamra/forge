package com.laksh.forge.data.ai

import kotlinx.coroutines.flow.Flow
import okhttp3.Request

/**
 * Command Code's provider API (commandcode.ai/docs/provider-api, read 2026-11-25): one key and
 * one base URL, two wire formats. Claude models are served on POST /messages (Anthropic
 * Messages), open models such as deepseek/deepseek-v4-flash on POST /chat/completions.
 *
 * Rather than a third copy of either protocol, this routes each call to the existing
 * [AnthropicProvider] or [OpenAiCompatProvider], both pointed at Command Code's base URL with
 * Bearer auth. The spec's `x-cmd-zdr: 1` header rides on every request through both, so every
 * call is zero-retention and never used for training; a model without a ZDR upstream is refused
 * with 422 cmd_zdr_no_providers, which [AiHttp.errorFor] turns into "pick another model".
 *
 * json_schema on /chat/completions is undocumented, so those models take the compat provider's
 * usual ladder (json_schema, then json_object, then the schema in the prompt) and the caller's
 * tolerant parsing. /messages gets output_config with a lean retry if the relay rejects it.
 */
class CommandCodeProvider(override val spec: ProviderSpec = ProviderRegistry.commandCode) : AiProvider {

    private val chat = OpenAiCompatProvider(spec, spec.baseUrl)

    private val messages = AnthropicProvider(
        spec = spec,
        baseUrl = spec.baseUrl,
        messagesPath = spec.messagesPath ?: DEFAULT_MESSAGES_PATH,
        modelsPath = spec.modelsPath,
        bearerAuth = true,
        gateway = true
    )

    private fun wireFor(request: AiRequest): AiProvider =
        if (usesMessages(request.model, request.modelSpec)) messages else chat

    override suspend fun complete(key: String, request: AiRequest): Completion =
        wireFor(request).complete(key, request)

    override fun stream(key: String, request: AiRequest): Flow<String> =
        wireFor(request).stream(key, request)

    /** GET /models is one catalog for both wires (each entry lists its supported_endpoints). */
    override suspend fun listModels(key: String): List<String> = chat.listModels(key)

    override suspend fun probe(key: String): Boolean = chat.probe(key)

    /** The first request a call would send, for tests of routing and headers. */
    internal fun buildRequest(key: String, request: AiRequest, stream: Boolean = false): Request =
        if (usesMessages(request.model, request.modelSpec)) messages.buildRequest(key, request, stream)
        else chat.buildRequest(key, request, stream)

    companion object {
        const val DEFAULT_MESSAGES_PATH = "/messages"

        /**
         * Registry models say which wire they use. An id from the live list or typed by hand is
         * judged by name: Command Code keeps Claude on /messages, and the claude- prefix is the
         * form its docs use (claude-sonnet-4-6).
         */
        fun usesMessages(model: String, spec: ModelSpec?): Boolean =
            spec?.anthropicWire ?: model.trim().startsWith("claude-", ignoreCase = true)
    }
}
