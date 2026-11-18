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
import kotlinx.serialization.json.JsonElement
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
 * One implementation of the OpenAI Chat Completions shape, parameterised by a [ProviderSpec]
 * (OpenAI, Gemini's compat endpoint, OpenRouter, Groq, xAI, Mistral, DeepSeek, Together and
 * any custom endpoint).
 *
 * The providers differ in which optional parameters they accept (strict json_schema,
 * reasoning_effort, the max-tokens field name) and several of those differences are
 * unverified in Forge's research. Rather than guess per provider, a request that 400s is
 * retried with fewer optional parameters: full -> json_object without extras -> plain text
 * with the schema in the prompt. The answer is then parsed tolerantly by the caller.
 */
class OpenAiCompatProvider(
    override val spec: ProviderSpec,
    private val baseUrl: String
) : AiProvider {

    private val name get() = spec.shortName

    /** How many optional parameters a request carries. Each step drops some. */
    private enum class Level { FULL, LOOSE, PLAIN }

    override suspend fun complete(key: String, request: AiRequest): Completion = withContext(Dispatchers.IO) {
        checkImages(request)
        val levels = if (request.json != null) Level.entries else listOf(Level.FULL, Level.LOOSE)
        var lastError: AiException? = null
        for (level in levels) {
            val payload = body(request, level, stream = false)
            val raw = try {
                AiHttp.execute(name) { post(key, payload, stream = false) }
            } catch (e: AiException) {
                if (isParameterRejection(e)) {
                    lastError = e
                    continue
                }
                throw e
            }
            return@withContext parseCompletion(raw)
        }
        throw lastError ?: AiException("$name rejected the request.")
    }

    /**
     * Errors worth retrying with fewer optional parameters. OpenRouter answers 404 when no
     * upstream endpoint supports every parameter sent (require_parameters), so it counts too.
     */
    private fun isParameterRejection(e: AiException): Boolean =
        e.httpCode == 400 || e.httpCode == 422 || (spec.requireParameters && e.httpCode == 404)

    override fun stream(key: String, request: AiRequest): Flow<String> = flow {
        checkImages(request)
        val response = try {
            AiHttp.openStream(name) { post(key, body(request, Level.FULL, stream = true), stream = true) }
        } catch (e: AiException) {
            if (!isParameterRejection(e)) throw e
            AiHttp.openStream(name) { post(key, body(request, Level.LOOSE, stream = true), stream = true) }
        }
        response.use { r ->
            val source = r.body?.source() ?: throw AiException("Empty response stream from $name.")
            try {
                AiHttp.readSse(source) { _, data ->
                    if (data.trim() == "[DONE]") return@readSse false
                    val chunk = runCatching { AiJson.json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: return@readSse true
                    // OpenRouter reports mid-stream failures as a 200 chunk with a top-level error.
                    chunk["error"]?.let { throw AiException("$name: ${errorText(it)}") }
                    val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                        ?: return@readSse true
                    val delta = choice["delta"] as? JsonObject
                    contentText(delta?.get("content"))?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                    if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "error") {
                        throw AiException("$name stopped with an error. Try again.", retryable = true)
                    }
                    true
                }
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                throw AiHttp.networkError(name, e)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val raw = AiHttp.execute(name) { get(key, AiHttp.joinUrl(baseUrl, spec.modelsPath)) }
        val root = runCatching { AiJson.json.parseToJsonElement(raw) }.getOrNull()
        // OpenAI shape is {"data":[...]}; Together returns a bare array.
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: emptyList()
            else -> emptyList()
        }
        items.mapNotNull { item ->
            val id = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            spec.modelIdPrefixToStrip?.let { id.removePrefix(it) } ?: id
        }.distinct().sorted()
    }

    override suspend fun probe(key: String): Boolean = withContext(Dispatchers.IO) {
        AiHttp.probe(name) { get(key, AiHttp.joinUrl(baseUrl, spec.modelsPath)) }
    }

    // ---------------------------------------------------------------- request building

    private fun checkImages(request: AiRequest) {
        val images = request.turns.flatMap { t -> t.parts.filterIsInstance<AiPart.Image>() }
        if (images.isEmpty()) return
        val allowed = spec.imageMediaTypes ?: return
        images.firstOrNull { it.mediaType.lowercase() !in allowed }?.let {
            throw AiException("$name doesn't accept ${it.mediaType} images.")
        }
    }

    private fun body(req: AiRequest, level: Level, stream: Boolean): JsonObject = buildJsonObject {
        put("model", req.model)
        if (stream) put("stream", true)

        val json = req.json
        val enforced = req.modelSpec?.schemaEnforced == true
        val useSchemaFormat = json != null && level == Level.FULL && spec.jsonMode == JsonMode.JSON_SCHEMA
        val useObjectFormat = json != null && !useSchemaFormat && level != Level.PLAIN
        // When the provider can't guarantee the shape, the prompt has to carry it.
        val schemaInPrompt = json != null && !(useSchemaFormat && enforced)

        if (useSchemaFormat && json != null) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", json.name)
                    put("schema", json.schema)
                    if (spec.sendStrict) put("strict", true)
                }
            }
        } else if (useObjectFormat) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        if (spec.requireParameters && (useSchemaFormat || useObjectFormat)) {
            putJsonObject("provider") { put("require_parameters", true) }
        }

        if (level == Level.FULL) {
            spec.maxTokensParam?.let { put(it, req.maxTokens) }
            // Only for models Forge knows accept the value; an unknown override might not.
            val fast = req.task == Task.LABEL || req.task == Task.PING
            if (fast && req.modelSpec != null) spec.fastEffort?.let { put("reasoning_effort", it) }
        }

        putJsonArray("messages") {
            val system = req.system + if (schemaInPrompt && json != null) AiPrompts.schemaInstructions(json.schema) else ""
            if (system.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", system.trim())
                }
            }
            req.turns.forEach { turn ->
                val images = turn.parts.filterIsInstance<AiPart.Image>()
                val text = turn.parts.filterIsInstance<AiPart.Text>().joinToString("\n\n") { it.text }
                if (images.isEmpty() && text.isBlank()) return@forEach
                addJsonObject {
                    put("role", turn.role.wire)
                    if (images.isEmpty()) {
                        // Plain string content is the most widely supported form (custom servers included).
                        put("content", text)
                    } else {
                        putJsonArray("content") {
                            images.forEach { img ->
                                val dataUri = "data:${img.mediaType};base64,${img.base64}"
                                addJsonObject {
                                    put("type", "image_url")
                                    when (spec.imageStyle) {
                                        ImageStyle.PLAIN_STRING -> put("image_url", dataUri)
                                        else -> putJsonObject("image_url") { put("url", dataUri) }
                                    }
                                }
                            }
                            if (text.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun post(key: String, body: JsonObject, stream: Boolean): Request =
        Request.Builder()
            .url(AiHttp.joinUrl(baseUrl, spec.chatPath))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("content-type", "application/json")
            .apply {
                spec.extraHeaders.forEach { (k, v) -> addHeader(k, v) }
                if (stream) addHeader("accept", "text/event-stream")
            }
            .post(body.toString().toRequestBody(AiHttp.jsonMedia))
            .build()

    private fun get(key: String, url: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .apply { spec.extraHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()

    // ---------------------------------------------------------------- response parsing

    private fun parseCompletion(raw: String): Completion {
        val obj = runCatching { AiJson.json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw AiException("Malformed response from $name.") }
        // A 200 can still carry an error (OpenRouter upstream failures).
        obj["error"]?.let { throw AiException("$name: ${errorText(it)}", retryable = true) }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AiException("$name returned no answer.")
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "error") throw AiException("$name stopped with an error. Try again.", retryable = true)
        val message = choice["message"] as? JsonObject
        val refusal = (message?.get("refusal") as? JsonPrimitive)?.contentOrNull
        if (!refusal.isNullOrBlank()) throw AiException("$name declined this request: ${refusal.take(200)}")
        val text = contentText(message?.get("content")).orEmpty().trim()
        return Completion(text, truncated = finish == "length")
    }

    /** Content is usually a string; some providers return a list of typed parts instead. */
    private fun contentText(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { part ->
            val o = part as? JsonObject ?: return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.contentOrNull
            if (type == null || type == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("")
        else -> null
    }

    private fun errorText(el: JsonElement): String = when (el) {
        is JsonObject -> (el["message"] as? JsonPrimitive)?.contentOrNull ?: el.toString().take(200)
        is JsonPrimitive -> el.contentOrNull ?: "unknown error"
        else -> el.toString().take(200)
    }
}
