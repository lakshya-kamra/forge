package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic's native Messages API (POST /v1/messages, anthropic-version 2026-10-21).
 *
 * Rules this follows, all of which 400 or misbehave otherwise on the 5.5 models:
 * - effort is always sent explicitly in output_config (Opus and Haiku default to medium,
 *   which is wasteful for a label read and thin for a plan);
 * - no `thinking` field (omitting it means adaptive thinking; disabling it 400s on Opus/Sonnet);
 * - no temperature / top_p / top_k (400 on this API version);
 * - JSON via output_config.format json_schema, never forced tool_choice (400 on Opus/Sonnet 5.5);
 * - images go before the text block in the user turn.
 *
 * The same code also speaks to gateways that relay the Messages format (Command Code's
 * /messages): [baseUrl]/[messagesPath] replace Anthropic's address, [bearerAuth] sends the key
 * as `Authorization: Bearer`, and [spec]'s extra headers (Command Code's `x-cmd-zdr: 1`) go on
 * every request.
 *
 * @param gateway true for a relay. A relay may not pass every Messages field through, so a 400
 *   is retried once "lean": no output_config (effort, json_schema) and no server-side fallback,
 *   with the schema carried in the system prompt instead and the answer parsed tolerantly.
 */
class AnthropicProvider(
    override val spec: ProviderSpec = ProviderRegistry.anthropic,
    private val baseUrl: String = spec.baseUrl,
    private val messagesPath: String = spec.chatPath,
    private val modelsPath: String = spec.modelsPath,
    private val bearerAuth: Boolean = false,
    private val gateway: Boolean = false
) : AiProvider {

    private val name get() = spec.shortName

    override suspend fun complete(key: String, request: AiRequest): Completion = withContext(Dispatchers.IO) {
        val raw = try {
            AiHttp.execute(name) { post(key, request.model, body(request, stream = false), stream = false) }
        } catch (e: AiException) {
            if (!retryLean(e)) throw e
            AiHttp.execute(name) {
                post(key, request.model, body(request, stream = false, lean = true), stream = false)
            }
        }
        val obj = runCatching { AiJson.json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw AiException("Malformed response from $name.") }

        val stop = (obj["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stop == "refusal") throw refusal(request.task)

        val text = (obj["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .trim()
        Completion(text, truncated = stop == "max_tokens")
    }

    override fun stream(key: String, request: AiRequest): Flow<String> = flow {
        val response = try {
            AiHttp.openStream(name) { post(key, request.model, body(request, stream = true), stream = true) }
        } catch (e: AiException) {
            if (!retryLean(e)) throw e
            AiHttp.openStream(name) {
                post(key, request.model, body(request, stream = true, lean = true), stream = true)
            }
        }
        var stopReason: String? = null
        response.use { r ->
            val source = r.body?.source() ?: throw AiException("Empty response stream from $name.")
            try {
                AiHttp.readSse(source) { _, data ->
                    val ev = runCatching { AiJson.json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: return@readSse true
                    when ((ev["type"] as? JsonPrimitive)?.contentOrNull) {
                        "content_block_delta" -> {
                            val delta = ev["delta"] as? JsonObject
                            if ((delta?.get("type") as? JsonPrimitive)?.contentOrNull == "text_delta") {
                                (delta?.get("text") as? JsonPrimitive)?.contentOrNull?.let { emit(it) }
                            }
                            true
                        }
                        "message_delta" -> {
                            val delta = ev["delta"] as? JsonObject
                            (delta?.get("stop_reason") as? JsonPrimitive)?.contentOrNull?.let { stopReason = it }
                            true
                        }
                        "message_stop" -> false
                        "error" -> {
                            val err = ev["error"] as? JsonObject
                            val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull
                            val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Stream error"
                            throw AiException(
                                if (type == "overloaded_error") "$name is overloaded right now. Try again shortly."
                                else "$name stream error: $msg",
                                retryable = type == "overloaded_error"
                            )
                        }
                        else -> true
                    }
                }
            } catch (e: java.io.IOException) {
                // Leaving the screen cancels the call, which surfaces here as an IOException;
                // that is a cancellation, not a network error to show.
                currentCoroutineContext().ensureActive()
                throw AiHttp.networkError(name, e)
            }
        }
        if (stopReason == "refusal") throw refusal(request.task)
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val raw = AiHttp.execute(name) { get(key, AiHttp.joinUrl(baseUrl, modelsPath) + "?limit=1000") }
        val root = runCatching { AiJson.json.parseToJsonElement(raw).jsonObject }.getOrNull()
        (root?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            .distinct()
            .sorted()
    }

    override suspend fun probe(key: String): Boolean = withContext(Dispatchers.IO) {
        AiHttp.probe(name) { get(key, AiHttp.joinUrl(baseUrl, modelsPath) + "?limit=1") }
    }

    // ---------------------------------------------------------------- request building

    /** Only a relay gets the lean retry; Anthropic itself documents every field Forge sends. */
    private fun retryLean(e: AiException): Boolean =
        gateway && e.httpCode == 400 && e.errorCode != AiHttp.ZDR_NO_PROVIDERS

    /** The request [complete] would send first (or its lean retry). For tests of the wire format. */
    internal fun buildRequest(key: String, request: AiRequest, stream: Boolean = false, lean: Boolean = false): Request =
        post(key, request.model, body(request, stream, lean), stream)

    private fun body(req: AiRequest, stream: Boolean, lean: Boolean = false): JsonObject = buildJsonObject {
        put("model", req.model)
        put("max_tokens", req.maxTokens)
        val effort = !lean && supportsEffort(req.model, req.modelSpec != null)
        val schema = req.json?.takeIf { !lean }
        if (effort || schema != null) putJsonObject("output_config") {
            // Older models (Sonnet/Haiku 4.5 from the model list) reject effort with a 400.
            if (effort) put("effort", effortFor(req.task))
            schema?.let { j ->
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", j.schema)
                }
            }
        }
        if (sendsServerFallback(req.model)) put("fallbacks", "default")
        if (stream) put("stream", true)

        // The coach resends the same long context every turn, so its system block and the
        // last history turn are cache breakpoints: earlier turns are then re-read from cache
        // instead of billed at full input price. One-shot jobs don't reuse a prefix, so
        // marking them would only pay the cache-write premium.
        val cache = req.task == Task.COACH
        // A lean request loses the enforced schema, so the prompt has to carry it.
        val leanSchema = if (lean) req.json?.let { AiPrompts.schemaInstructions(it.schema) } else null
        val system = (req.system + leanSchema.orEmpty()).trim()
        if (system.isNotEmpty()) {
            putJsonArray("system") {
                addJsonObject {
                    put("type", "text")
                    put("text", system)
                    if (cache) putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
        }

        // An empty content array 400s, so drop turns with nothing in them (e.g. a blank reply
        // left behind by an interrupted stream).
        val turns = req.turns.filter { t ->
            t.parts.any { it is AiPart.Image || (it is AiPart.Text && it.text.isNotBlank()) }
        }
        val lastHistoryIndex = turns.lastIndex - 1
        putJsonArray("messages") {
            turns.forEachIndexed { i, turn ->
                addJsonObject {
                    put("role", turn.role.wire)
                    putJsonArray("content") {
                        // Images first, then text: the documented order for vision prompts.
                        turn.parts.filterIsInstance<AiPart.Image>().forEach { img ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", img.mediaType)
                                    put("data", img.base64)
                                }
                            }
                        }
                        val texts = turn.parts.filterIsInstance<AiPart.Text>().filter { it.text.isNotBlank() }
                        texts.forEachIndexed { ti, t ->
                            addJsonObject {
                                put("type", "text")
                                put("text", t.text)
                                if (cache && i == lastHistoryIndex && ti == texts.lastIndex) {
                                    putJsonObject("cache_control") { put("type", "ephemeral") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun post(key: String, model: String, body: JsonObject, stream: Boolean): Request =
        Request.Builder()
            .url(AiHttp.joinUrl(baseUrl, messagesPath))
            .auth(key)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .apply {
                // The beta header travels only with the field it enables (never on a lean retry).
                if (body.containsKey("fallbacks")) addHeader("anthropic-beta", FALLBACK_BETA)
                if (stream) addHeader("accept", "text/event-stream")
            }
            .post(body.toString().toRequestBody(AiHttp.jsonMedia))
            .build()

    private fun get(key: String, url: String): Request =
        Request.Builder()
            .url(url)
            .auth(key)
            .addHeader("anthropic-version", API_VERSION)
            .get()
            .build()

    private fun Request.Builder.auth(key: String): Request.Builder = apply {
        if (bearerAuth) addHeader("Authorization", "Bearer $key") else addHeader("x-api-key", key)
        spec.extraHeaders.forEach { (k, v) -> addHeader(k, v) }
    }

    /**
     * Effort per job: transcription and quick estimates need little, coaching and document
     * parsing a moderate amount, and the plan (rare, consequential) the most.
     */
    private fun effortFor(task: Task): String = when (task) {
        Task.FOOD_PHOTO, Task.LABEL, Task.PING -> "low"
        Task.PROGRAM, Task.COACH, Task.BODY -> "medium"
        Task.PLAN -> "high"
    }

    /** Opus and Sonnet 5.5 can retry a refused request on a fallback model server-side; Haiku 5.5 can't. */
    private fun supportsEffort(model: String, inRegistry: Boolean): Boolean =
        inRegistry || effortCapable(model)

    private fun hasServerFallback(model: String): Boolean =
        model.startsWith("claude-opus-5-5") || model.startsWith("claude-sonnet-5-5")

    /** The fallback is an Anthropic beta; a relay isn't documented to honour it. */
    private fun sendsServerFallback(model: String): Boolean = !gateway && hasServerFallback(model)

    private fun refusal(task: Task): AiException = AiRefusal(
        when (task) {
            Task.BODY -> "Claude declined to assess this. Try again without the photos, using measurements only."
            Task.FOOD_PHOTO, Task.LABEL -> "Claude declined to read this photo. Try a different photo, or log it by text."
            else -> "Claude declined to answer that. Try rewording the request."
        }
    )

    companion object {
        private val VERSIONED = Regex("""^claude-(opus|sonnet|haiku)-(\d+)(?:-(\d+))?(?:$|-)""")

        /**
         * output_config.effort is accepted by Opus 4.5 and every 4.6+ family model; earlier
         * models (and unrecognised ids) get no effort field.
         */
        fun effortCapable(model: String): Boolean {
            val m = VERSIONED.find(model) ?: return false
            val family = m.groupValues[1]
            val major = m.groupValues[2].toIntOrNull() ?: return false
            // A date suffix (claude-sonnet-4-20250514) is not a minor version.
            val minor = m.groupValues[3].takeIf { it.length <= 2 }?.toIntOrNull() ?: 0
            return when {
                major >= 5 -> true
                major == 4 -> minor >= 6 || (family == "opus" && minor >= 5)
                else -> false
            }
        }

        const val API_VERSION = "2026-10-21"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
