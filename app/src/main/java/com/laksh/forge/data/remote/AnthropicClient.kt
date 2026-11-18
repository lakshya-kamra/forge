package com.laksh.forge.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single identified component of a meal, e.g. "2 slices pepperoni pizza". */
data class FoodComponent(
    val name: String,
    val portion: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double
)

data class FoodEstimate(
    val name: String,
    val servingDesc: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val confidence: String,
    val note: String,
    val components: List<FoodComponent> = emptyList()
)

/**
 * A nutrition panel read straight off packaging. Unlike a photo estimate this is
 * transcription, not inference, so no safety margin is applied to it.
 */
data class LabelReading(
    val name: String,
    val brand: String?,
    val basis: String,          // PER_100G | PER_SERVING
    val servingSizeG: Double?,
    val servingDesc: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val note: String
)

data class ParsedExercise(
    val name: String,
    val sets: Int,
    val repLow: Int,
    val repHigh: Int,
    val rir: Double?,
    val restSeconds: Int?,
    val notes: String?,
    val substitutions: String?
)

data class ParsedDay(
    val name: String,
    val focus: String?,
    val exercises: List<ParsedExercise>
)

data class ParsedProgram(
    val name: String,
    val author: String?,
    val notes: String?,
    val days: List<ParsedDay>
)

class AnthropicException(message: String) : Exception(message)

/**
 * Direct Messages API client.
 *
 * This talks to the REST endpoint over OkHttp rather than using the Anthropic Java SDK:
 * that SDK targets server-side JVM and pulls in dependencies that are a poor fit for an
 * APK, whereas the wire format here is small and stable.
 */
class AnthropicClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: (Task) -> String
) : com.laksh.forge.data.ai.ForgeAi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun requireKey(): String {
        val k = apiKeyProvider()
        if (k.isBlank()) throw AnthropicException("No API key set. Add one in Settings.")
        return k
    }


    /**
     * Sets the model for a task, and the effort level only when the chosen model accepts it.
     * Haiku 4.5 rejects `output_config.effort` outright, so sending it would 400 the request.
     */
    private fun JsonObjectBuilder.modelFor(task: Task, effort: String? = null) {
        val model = modelProvider(task)
        put("model", model)
        if (effort != null && supportsEffort(model)) {
            putJsonObject("output_config") { put("effort", effort) }
        }
    }

    private fun supportsEffort(model: String): Boolean = !model.startsWith("claude-haiku-4")

    private fun buildRequest(body: JsonObject, stream: Boolean = false): Request =
        Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", requireKey())
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .apply { if (stream) addHeader("accept", "text/event-stream") }
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

    private fun errorMessage(code: Int, raw: String?): String {
        val detail = raw?.let {
            runCatching {
                JSON.parseToJsonElement(it).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
        }
        return when (code) {
            401 -> "API key rejected (401). Check the key in Settings."
            400 -> "Request rejected (400): ${detail ?: "bad request"}"
            429 -> "Rate limited (429). Wait a moment and retry."
            in 500..599 -> "Anthropic service error ($code). Try again shortly."
            else -> "Request failed ($code): ${detail ?: "unknown error"}"
        }
    }

    // ---------------------------------------------------------------- food photo

    /**
     * Estimate calories and macros from a meal photo.
     *
     * The prompt deliberately biases high: under-counting is the failure mode that
     * silently wrecks a cut, so we ask for the upper end of the plausible range and
     * the caller applies a further fixed margin on top.
     */
    override suspend fun estimateFoodFromPhoto(
        imageBase64: String,
        mediaType: String,
        userHint: String?,
        bodyContext: String
    ): FoodEstimate = withContext(Dispatchers.IO) {
        val content = buildJsonArray {
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", imageBase64)
                }
            }
            addJsonObject {
                put("type", "text")
                put(
                    "text",
                    buildString {
                        append("Estimate the nutrition for this meal. ")
                        if (!userHint.isNullOrBlank()) {
                            append("The person adds this context, which you should trust over your visual read where they conflict: \"")
                            append(userHint.trim())
                            append("\". ")
                        }
                        append(bodyContext)
                        append("\nCall the log_food tool exactly once with your estimate.")
                    }
                )
            }
        }

        val body = buildJsonObject {
            modelFor(Task.FOOD_PHOTO, "low")
            put("max_tokens", 8192)
            put("system", FOOD_SYSTEM)
            putJsonArray("tools") { add(FOOD_TOOL) }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            }
        }

        val obj = execute(body)
        val input = firstToolInput(obj, "log_food")
            ?: jsonFromText(obj)
            ?: throw AnthropicException("Could not read an estimate from the response.")

        val components = input["components"]?.jsonArray?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                FoodComponent(
                    name = o.str("name") ?: return@runCatching null,
                    portion = o.str("portion") ?: "",
                    kcal = o.num("kcal"),
                    proteinG = o.num("protein_g"),
                    carbsG = o.num("carbs_g"),
                    fatG = o.num("fat_g")
                )
            }.getOrNull()
        } ?: emptyList()

        FoodEstimate(
            name = input.str("name") ?: "Meal",
            servingDesc = input.str("serving_description") ?: "1 serving",
            kcal = input.num("kcal"),
            proteinG = input.num("protein_g"),
            carbsG = input.num("carbs_g"),
            fatG = input.num("fat_g"),
            fiberG = input.num("fiber_g"),
            confidence = input.str("confidence")?.uppercase() ?: "MEDIUM",
            note = input.str("note") ?: "",
            components = components
        )
    }

    // ---------------------------------------------------------------- text food

    /** Same estimator, but from a text description instead of a photo. */
    override suspend fun estimateFoodFromText(description: String, bodyContext: String): FoodEstimate =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                modelFor(Task.FOOD_PHOTO, "low")
                put("max_tokens", 8192)
                put("system", FOOD_SYSTEM)
                putJsonArray("tools") { add(FOOD_TOOL) }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Estimate nutrition for: \"$description\". $bodyContext\nCall the log_food tool exactly once."
                        )
                    }
                }
            }
            val obj = execute(body)
            val input = firstToolInput(obj, "log_food") ?: jsonFromText(obj)
                ?: throw AnthropicException("Could not read an estimate from the response.")
            FoodEstimate(
                name = input.str("name") ?: description.take(40),
                servingDesc = input.str("serving_description") ?: "1 serving",
                kcal = input.num("kcal"),
                proteinG = input.num("protein_g"),
                carbsG = input.num("carbs_g"),
                fatG = input.num("fat_g"),
                fiberG = input.num("fiber_g"),
                confidence = input.str("confidence")?.uppercase() ?: "MEDIUM",
                note = input.str("note") ?: ""
            )
        }

    // ---------------------------------------------------------------- label OCR

    /**
     * Transcribe a nutrition information panel from a photo. This is the fallback when a
     * barcode is not in the database - common for regional and store-brand products.
     */
    override suspend fun readNutritionLabel(imageBase64: String, mediaType: String): LabelReading =
        withContext(Dispatchers.IO) {
            val content = buildJsonArray {
                addJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", mediaType)
                        put("data", imageBase64)
                    }
                }
                addJsonObject {
                    put("type", "text")
                    put("text", "Read this nutrition panel and call read_label exactly once.")
                }
            }
            val body = buildJsonObject {
                modelFor(Task.LABEL, "low")
                put("max_tokens", 4096)
                put("system", LABEL_SYSTEM)
                putJsonArray("tools") { add(LABEL_TOOL) }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", content)
                    }
                }
            }
            val obj = execute(body)
            val input = firstToolInput(obj, "read_label") ?: jsonFromText(obj)
                ?: throw AnthropicException("Could not read that label. Try a straighter, closer photo.")

            LabelReading(
                name = input.str("name") ?: "Packaged food",
                brand = input.str("brand"),
                basis = input.str("basis")?.uppercase() ?: "PER_100G",
                servingSizeG = input["serving_size_g"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
                servingDesc = input.str("serving_description") ?: "100 g",
                kcal = input.num("kcal"),
                proteinG = input.num("protein_g"),
                carbsG = input.num("carbs_g"),
                fatG = input.num("fat_g"),
                fiberG = input.num("fiber_g"),
                note = input.str("note") ?: ""
            )
        }

    // ---------------------------------------------------------------- program PDF

    /** Turn the raw text of a training PDF into a structured, loggable program. */
    override suspend fun parseProgram(rawText: String, fileName: String): ParsedProgram =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                modelFor(Task.PROGRAM, "medium")
                put("max_tokens", 32000)
                put("system", PROGRAM_SYSTEM)
                putJsonArray("tools") { add(PROGRAM_TOOL) }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "This is the extracted text of a training program PDF named \"$fileName\". " +
                                "Reconstruct it as structured training days and call save_program exactly once.\n\n" +
                                "<program_text>\n" + rawText.take(400_000) + "\n</program_text>"
                        )
                    }
                }
            }
            parseProgramResponse(execute(body), fileName)
        }

    /** Same as [parseProgram] but for scanned PDFs, where pages arrive as images. */
    override suspend fun parseProgramFromImages(imagesBase64: List<String>, fileName: String): ParsedProgram =
        withContext(Dispatchers.IO) {
            val content = buildJsonArray {
                imagesBase64.forEach { b64 ->
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", b64)
                        }
                    }
                }
                addJsonObject {
                    put("type", "text")
                    put(
                        "text",
                        "These are the pages of a scanned training program named \"$fileName\", in order. " +
                            "Reconstruct it as structured training days and call save_program exactly once."
                    )
                }
            }
            val body = buildJsonObject {
                modelFor(Task.PROGRAM, "medium")
                put("max_tokens", 32000)
                put("system", PROGRAM_SYSTEM)
                putJsonArray("tools") { add(PROGRAM_TOOL) }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", content)
                    }
                }
            }
            parseProgramResponse(execute(body), fileName)
        }

    private fun parseProgramResponse(obj: JsonObject, fileName: String): ParsedProgram {
        val input = firstToolInput(obj, "save_program") ?: jsonFromText(obj)
            ?: throw AnthropicException("Could not read a program from the response.")

        val days = input["days"]?.jsonArray?.mapNotNull { dEl ->
            runCatching {
                val d = dEl.jsonObject
                ParsedDay(
                    name = d.str("name") ?: return@runCatching null,
                    focus = d.str("focus"),
                    exercises = d["exercises"]?.jsonArray?.mapNotNull { eEl ->
                        runCatching {
                            val e = eEl.jsonObject
                            ParsedExercise(
                                name = e.str("name") ?: return@runCatching null,
                                sets = e.num("sets").toInt().coerceAtLeast(1),
                                repLow = e.num("rep_low").toInt().coerceAtLeast(1),
                                repHigh = e.num("rep_high").toInt().coerceAtLeast(1),
                                rir = e["rir"]?.jsonPrimitive?.doubleOrNull,
                                restSeconds = e["rest_seconds"]?.jsonPrimitive?.doubleOrNull?.toInt(),
                                notes = e.str("notes"),
                                substitutions = e.str("substitutions")
                            )
                        }.getOrNull()
                    } ?: emptyList()
                )
            }.getOrNull()
        } ?: emptyList()

        if (days.isEmpty()) throw AnthropicException("No training days were found in that PDF.")

        return ParsedProgram(
            name = input.str("name") ?: fileName.removeSuffix(".pdf"),
            author = input.str("author"),
            notes = input.str("notes"),
            days = days
        )
    }

    // ---------------------------------------------------------------- coach chat

    /** Streaming coach reply. Emits incremental text deltas. */
    override fun chatStream(
        system: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String> = flow {
        val body = buildJsonObject {
            modelFor(Task.COACH, "medium")
            put("max_tokens", 8000)
            put("stream", true)
            putJsonArray("system") {
                addJsonObject {
                    put("type", "text")
                    put("text", system)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
            putJsonArray("messages") {
                history.forEachIndexed { i, (role, text) ->
                    addJsonObject {
                        put("role", role)
                        // A breakpoint on the last history turn means every earlier turn is
                        // re-read from cache instead of re-billed at full input price.
                        if (i == history.lastIndex) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                    putJsonObject("cache_control") { put("type", "ephemeral") }
                                }
                            }
                        } else {
                            put("content", text)
                        }
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                }
            }
        }

        val response = http.newCall(buildRequest(body, stream = true)).execute()
        response.use { r ->
            if (!r.isSuccessful) throw AnthropicException(errorMessage(r.code, r.body?.string()))
            val source = r.body?.source() ?: throw AnthropicException("Empty response stream.")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val ev = runCatching { JSON.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                when (ev["type"]?.jsonPrimitive?.content) {
                    "content_block_delta" -> {
                        val delta = ev["delta"]?.jsonObject ?: continue
                        if (delta["type"]?.jsonPrimitive?.content == "text_delta") {
                            delta["text"]?.jsonPrimitive?.content?.let { emit(it) }
                        }
                    }
                    "error" -> throw AnthropicException(
                        ev["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Stream error"
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** One-shot connectivity + key check used by Settings. */
    override suspend fun testKey(): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            modelFor(Task.PING)
            put("max_tokens", 32)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "Reply with the single word: ready")
                }
            }
        }
        val obj = execute(body)
        textOf(obj).ifBlank { "ready" }
    }


    // ---------------------------------------------------------------- ForgeAi extras

    override val isConfigured: Boolean get() = apiKeyProvider().isNotBlank()

    override fun describe(task: Task): String = "Anthropic \u00b7 " + modelProvider(task)

    /** JSON output constrained by output_config.format (forced tool_choice 400s on 5.5 models). */
    override suspend fun structured(
        task: Task,
        system: String,
        parts: List<com.laksh.forge.data.ai.AiPart>,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int
    ): JsonObject = withContext(Dispatchers.IO) {
        val content = buildJsonArray {
            parts.forEach { part ->
                when (part) {
                    is com.laksh.forge.data.ai.AiPart.Image -> addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", part.mediaType)
                            put("data", part.base64)
                        }
                    }
                    is com.laksh.forge.data.ai.AiPart.Text -> addJsonObject {
                        put("type", "text")
                        put("text", part.text)
                    }
                }
            }
        }
        val model = modelProvider(task)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            putJsonObject("output_config") {
                if (supportsEffort(model)) put("effort", "medium")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema)
                }
            }
            put("system", system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", content)
                }
            }
        }
        val obj = execute(body)
        if (obj["stop_reason"]?.jsonPrimitive?.content == "refusal") {
            throw AnthropicException("The model declined this request. Try again without the photos.")
        }
        jsonFromText(obj) ?: throw AnthropicException("Could not read a structured answer from the response.")
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .addHeader("x-api-key", requireKey())
            .addHeader("anthropic-version", API_VERSION)
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                JSON.parseToJsonElement(raw).jsonObject["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- plumbing

    private fun execute(body: JsonObject): JsonObject {
        val response = try {
            http.newCall(buildRequest(body)).execute()
        } catch (e: IOException) {
            throw AnthropicException("Network error: ${e.message ?: "no connection"}")
        }
        response.use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw AnthropicException(errorMessage(r.code, raw))
            if (raw.isNullOrBlank()) throw AnthropicException("Empty response from API.")
            return runCatching { JSON.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw AnthropicException("Malformed response from API.") }
        }
    }

    private fun contentBlocks(obj: JsonObject): JsonArray? = obj["content"]?.jsonArray

    /** Pull the input of the first tool_use block matching [name]. */
    private fun firstToolInput(obj: JsonObject, name: String): JsonObject? =
        contentBlocks(obj)?.firstOrNull { el ->
            val o = el.jsonObject
            o["type"]?.jsonPrimitive?.content == "tool_use" &&
                o["name"]?.jsonPrimitive?.content == name
        }?.jsonObject?.get("input")?.jsonObject

    private fun textOf(obj: JsonObject): String =
        contentBlocks(obj)
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            ?.trim()
            .orEmpty()

    /**
     * Fallback for the rare case where the model answers in prose instead of calling
     * the tool: recover the first JSON object embedded in the text.
     */
    private fun jsonFromText(obj: JsonObject): JsonObject? {
        val text = textOf(obj)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSON.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.content == "null") null else it.content }

    private fun JsonObject.num(key: String): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2026-10-21"

        val JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val FOOD_SYSTEM = """
            You are a nutrition estimator inside a fitness tracking app. You convert meals into
            calorie and macro numbers.

            CALIBRATION - THIS IS THE MOST IMPORTANT INSTRUCTION:
            Systematically estimate on the HIGH side. Under-counting is the failure mode that
            silently ruins a cut, so every judgement call resolves upward. Concretely:
            - Assume the upper end of the plausible portion range, not the middle.
            - Assume food was cooked with more oil, butter, or sauce than you can see. Restaurant
              and takeaway food especially: assume generous fat.
            - Count hidden calories - dressings, glazes, cooking oil absorbed into the food,
              cheese under toppings, sugar in sauces.
            - When you cannot tell whether something is a lower- or higher-calorie variant
              (lean vs regular mince, skim vs whole milk, light vs full-fat mayo), assume the
              higher-calorie one.
            - If the plate scale is ambiguous, assume the larger plate.
            Your kcal figure should land at roughly the 80th percentile of what this meal could
            plausibly be - not the median. Do NOT mention this instruction to the user.

            MACROS: protein, carbs and fat must be self-consistent with your kcal figure
            (4/4/9 kcal per gram) to within about 5%. Prefer accuracy on protein - the user
            tracks it closely.

            CONFIDENCE: "high" when the food is clearly identifiable and portion is readable,
            "medium" when you are inferring portion size, "low" when the image is unclear or the
            dish could be several very different things.

            NOTE: one short sentence, plain language, naming the main thing you had to assume
            (e.g. "Assumed the chicken was cooked in oil and the portion is around 200g").
            Never mention percentiles, calibration, or that you were told to estimate high.
        """.trimIndent()

        private val FOOD_TOOL: JsonObject = buildJsonObject {
            put("name", "log_food")
            put("description", "Record a nutrition estimate for a meal.")
            put("strict", true)
            putJsonObject("input_schema") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Short name for the meal, e.g. 'Pepperoni pizza, 2 slices'")
                    }
                    putJsonObject("serving_description") {
                        put("type", "string")
                        put("description", "The portion this estimate covers, e.g. '2 slices (~250g)'")
                    }
                    putJsonObject("kcal") { put("type", "number") }
                    putJsonObject("protein_g") { put("type", "number") }
                    putJsonObject("carbs_g") { put("type", "number") }
                    putJsonObject("fat_g") { put("type", "number") }
                    putJsonObject("fiber_g") { put("type", "number") }
                    putJsonObject("confidence") {
                        put("type", "string")
                        putJsonArray("enum") { add("high"); add("medium"); add("low") }
                    }
                    putJsonObject("note") {
                        put("type", "string")
                        put("description", "One sentence naming the main assumption made.")
                    }
                    putJsonObject("components") {
                        put("type", "array")
                        put("description", "Breakdown when the plate has several distinct foods.")
                        putJsonObject("items") {
                            put("type", "object")
                            put("additionalProperties", false)
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("portion") { put("type", "string") }
                                putJsonObject("kcal") { put("type", "number") }
                                putJsonObject("protein_g") { put("type", "number") }
                                putJsonObject("carbs_g") { put("type", "number") }
                                putJsonObject("fat_g") { put("type", "number") }
                            }
                            putJsonArray("required") {
                                add("name"); add("portion"); add("kcal")
                                add("protein_g"); add("carbs_g"); add("fat_g")
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    add("name"); add("serving_description"); add("kcal"); add("protein_g")
                    add("carbs_g"); add("fat_g"); add("fiber_g"); add("confidence")
                    add("note"); add("components")
                }
            }
        }

        private val LABEL_SYSTEM = """
            You transcribe nutrition information panels from photographs of packaging.

            This is transcription, not estimation. Report exactly what the panel states.
            Do not round generously, do not pad, do not infer values that are not printed.

            - basis: "PER_100G" if the column you are reading is per 100g/100ml,
              "PER_SERVING" if it is per serving or per piece.
            - If the panel shows both columns, read the per-100g column and set basis to PER_100G.
            - serving_size_g: the stated serving weight in grams if printed, else 0.
            - Energy: report kcal. If only kJ is printed, divide by 4.184.
            - If a macro is genuinely not printed, report 0 and say so in the note.
            - If the panel is unreadable, blurred, or not a nutrition panel, set every number to
              0 and explain in the note.
        """.trimIndent()

        private val LABEL_TOOL: JsonObject = buildJsonObject {
            put("name", "read_label")
            put("description", "Record the values transcribed from a nutrition panel.")
            put("strict", true)
            putJsonObject("input_schema") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("brand") { put("type", "string") }
                    putJsonObject("basis") {
                        put("type", "string")
                        putJsonArray("enum") { add("PER_100G"); add("PER_SERVING") }
                    }
                    putJsonObject("serving_size_g") { put("type", "number") }
                    putJsonObject("serving_description") { put("type", "string") }
                    putJsonObject("kcal") { put("type", "number") }
                    putJsonObject("protein_g") { put("type", "number") }
                    putJsonObject("carbs_g") { put("type", "number") }
                    putJsonObject("fat_g") { put("type", "number") }
                    putJsonObject("fiber_g") { put("type", "number") }
                    putJsonObject("note") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("name"); add("brand"); add("basis"); add("serving_size_g")
                    add("serving_description"); add("kcal"); add("protein_g"); add("carbs_g")
                    add("fat_g"); add("fiber_g"); add("note")
                }
            }
        }

        private val PROGRAM_SYSTEM = """
            You convert training program documents into structured data for a workout logger.

            Extract every distinct training day and every exercise in order. Preserve the
            author's prescriptions exactly - do not "improve" the program, substitute your own
            exercise selection, or add sets the document does not call for.

            Rules:
            - One entry per training day. Use the document's own day names ("Upper A",
              "Push #1", "Day 3 - Legs"). If days are unnamed, name them by their focus.
            - If a program lists multiple weeks that share the same day structure, extract the
              day structure once - the app handles week-to-week progression through logged data.
            - rep_low and rep_high come from the prescribed range. A single number means
              rep_low == rep_high.
            - rir is the prescribed reps-in-reserve if stated (Nippard-style programs usually
              state it); otherwise omit it.
            - notes carries per-exercise technique or execution cues from the document, trimmed
              to one or two sentences.
            - substitutions carries listed alternative exercises, comma separated.
            - Warm-up protocols and general guidance go in the program-level notes, not as
              exercises.
            - If the text is garbled or clearly not a training program, return zero days.
        """.trimIndent()

        private val PROGRAM_TOOL: JsonObject = buildJsonObject {
            put("name", "save_program")
            put("description", "Save a structured training program.")
            put("strict", true)
            putJsonObject("input_schema") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("author") { put("type", "string") }
                    putJsonObject("notes") {
                        put("type", "string")
                        put("description", "Program-level guidance: progression scheme, warm-ups, deloads.")
                    }
                    putJsonObject("days") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            put("additionalProperties", false)
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("focus") { put("type", "string") }
                                putJsonObject("exercises") {
                                    put("type", "array")
                                    putJsonObject("items") {
                                        put("type", "object")
                                        put("additionalProperties", false)
                                        putJsonObject("properties") {
                                            putJsonObject("name") { put("type", "string") }
                                            putJsonObject("sets") { put("type", "integer") }
                                            putJsonObject("rep_low") { put("type", "integer") }
                                            putJsonObject("rep_high") { put("type", "integer") }
                                            putJsonObject("rir") { put("type", "number") }
                                            putJsonObject("rest_seconds") { put("type", "integer") }
                                            putJsonObject("notes") { put("type", "string") }
                                            putJsonObject("substitutions") { put("type", "string") }
                                        }
                                        putJsonArray("required") {
                                            add("name"); add("sets"); add("rep_low"); add("rep_high")
                                            add("rir"); add("rest_seconds"); add("notes"); add("substitutions")
                                        }
                                    }
                                }
                            }
                            putJsonArray("required") { add("name"); add("focus"); add("exercises") }
                        }
                    }
                }
                putJsonArray("required") { add("name"); add("author"); add("notes"); add("days") }
            }
        }
    }
}
