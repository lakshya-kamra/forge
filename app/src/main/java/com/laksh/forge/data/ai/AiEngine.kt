package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.FoodEstimate
import com.laksh.forge.data.remote.LabelReading
import com.laksh.forge.data.remote.ParsedProgram
import com.laksh.forge.data.remote.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * The provider-neutral half of [ForgeAi]: the built-in jobs expressed as prompts + schemas
 * ([AiPrompts]), JSON recovery ([JsonExtract]) and parsing ([AiParsers]). Subclasses only
 * decide where a task goes ([route]) and where the key comes from ([apiKey]).
 */
abstract class AiEngine : ForgeAi {

    data class Route(val provider: AiProvider, val model: String, val modelSpec: ModelSpec?)

    /**
     * Pick provider and model for [task]. Synchronous so [describe] can use it.
     * @param imageCount images in this call (0 for text-only).
     * @throws AiException when nothing suitable is configured.
     */
    protected abstract fun route(task: Task, imageCount: Int): Route

    /** The API key, or an [AiException] telling the user to add one. */
    protected abstract suspend fun apiKey(): String

    override fun describe(task: Task): String = try {
        val r = route(task, if (task.visionByDefault) 1 else 0)
        "${r.provider.spec.shortName} · ${r.model}"
    } catch (e: AiException) {
        e.message ?: "AI isn't set up"
    }

    // ---------------------------------------------------------------- food

    /**
     * Estimate calories and macros from a meal photo. The prompt deliberately biases high:
     * under-counting is the failure mode that silently wrecks a cut, so the model is asked for
     * the upper end of the plausible range and the caller applies a further fixed margin.
     */
    override suspend fun estimateFoodFromPhoto(
        imageBase64: String,
        mediaType: String,
        userHint: String?,
        bodyContext: String
    ): FoodEstimate {
        val obj = runJson(
            task = Task.FOOD_PHOTO,
            system = AiPrompts.FOOD_SYSTEM,
            parts = listOf(
                AiPart.Image(imageBase64, mediaType),
                AiPart.Text(AiPrompts.foodPhotoUser(userHint, bodyContext))
            ),
            schemaName = AiPrompts.FOOD_SCHEMA_NAME,
            schema = AiPrompts.FOOD_SCHEMA,
            maxTokens = 8192
        )
        return AiParsers.food(obj, fallbackName = "Meal")
    }

    /** Same estimator (same upward bias), from a text description instead of a photo. */
    override suspend fun estimateFoodFromText(description: String, bodyContext: String): FoodEstimate {
        val obj = runJson(
            task = Task.FOOD_PHOTO,
            system = AiPrompts.FOOD_SYSTEM,
            parts = listOf(AiPart.Text(AiPrompts.foodTextUser(description, bodyContext))),
            schemaName = AiPrompts.FOOD_SCHEMA_NAME,
            schema = AiPrompts.FOOD_SCHEMA,
            maxTokens = 8192
        )
        return AiParsers.food(obj, fallbackName = description.trim().take(40).ifBlank { "Meal" })
    }

    // ---------------------------------------------------------------- label

    /**
     * Transcribe a nutrition panel. The fallback when a barcode is not in the database, which
     * is common for regional and store-brand products.
     */
    override suspend fun readNutritionLabel(imageBase64: String, mediaType: String): LabelReading {
        val obj = runJson(
            task = Task.LABEL,
            system = AiPrompts.LABEL_SYSTEM,
            parts = listOf(AiPart.Image(imageBase64, mediaType), AiPart.Text(AiPrompts.LABEL_USER)),
            schemaName = AiPrompts.LABEL_SCHEMA_NAME,
            schema = AiPrompts.LABEL_SCHEMA,
            maxTokens = 4096,
            unreadable = "Could not read that label. Try a straighter, closer photo."
        )
        return AiParsers.label(obj)
    }

    // ---------------------------------------------------------------- program

    override suspend fun parseProgram(rawText: String, fileName: String): ParsedProgram {
        val obj = runJson(
            task = Task.PROGRAM,
            system = AiPrompts.PROGRAM_SYSTEM,
            parts = listOf(AiPart.Text(AiPrompts.programTextUser(rawText, fileName))),
            schemaName = AiPrompts.PROGRAM_SCHEMA_NAME,
            schema = AiPrompts.PROGRAM_SCHEMA,
            maxTokens = 32000,
            unreadable = "Could not read a program from the response."
        )
        return AiParsers.program(obj, fileName)
    }

    /** Scanned PDFs arrive as page images. */
    override suspend fun parseProgramFromImages(imagesBase64: List<String>, fileName: String): ParsedProgram {
        if (imagesBase64.isEmpty()) throw AiException("That PDF has no pages to read.")
        val obj = runJson(
            task = Task.PROGRAM,
            system = AiPrompts.PROGRAM_SYSTEM,
            parts = buildList<AiPart> {
                imagesBase64.forEach { add(AiPart.Image(it, "image/jpeg")) }
                add(AiPart.Text(AiPrompts.programImagesUser(fileName)))
            },
            schemaName = AiPrompts.PROGRAM_SCHEMA_NAME,
            schema = AiPrompts.PROGRAM_SCHEMA,
            maxTokens = 32000,
            unreadable = "Could not read a program from the response."
        )
        return AiParsers.program(obj, fileName)
    }

    // ---------------------------------------------------------------- coach

    override fun chatStream(
        system: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String> = flow {
        val key = apiKey()
        val route = route(Task.COACH, 0)
        val request = AiRequest(
            task = Task.COACH,
            model = route.model,
            modelSpec = route.modelSpec,
            system = system,
            turns = history.map { (role, text) -> AiTurn.fromHistory(role, text) } + AiTurn.user(userMessage),
            maxTokens = 8000
        )
        emitAll(route.provider.stream(key, request))
    }

    // ---------------------------------------------------------------- generic

    override suspend fun structured(
        task: Task,
        system: String,
        parts: List<AiPart>,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int
    ): JsonObject = runJson(task, system, parts, schemaName, schema, maxTokens)

    override suspend fun testKey(): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        val route = route(Task.PING, 0)
        val request = AiRequest(
            task = Task.PING,
            model = route.model,
            modelSpec = route.modelSpec,
            system = "",
            turns = listOf(AiTurn.user(AiPrompts.PING_USER)),
            // Generous for a one-word reply because reasoning models spend tokens before answering.
            maxTokens = 256
        )
        route.provider.complete(key, request)
        "Key works · ${route.provider.spec.shortName} answered with ${route.model}."
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey()
        route(Task.PING, 0).provider.listModels(key)
    }

    /**
     * One structured call: route, send, recover the JSON. A reply that can't be read as JSON
     * gets one retry, because non-enforcing providers (DeepSeek blanks invalid JSON, others
     * wrap it in prose) usually succeed on a second attempt.
     */
    protected suspend fun runJson(
        task: Task,
        system: String,
        parts: List<AiPart>,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int,
        unreadable: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val key = apiKey()
        val images = parts.count { it is AiPart.Image }
        val route = route(task, images)
        val request = AiRequest(
            task = task,
            model = route.model,
            modelSpec = route.modelSpec,
            system = system,
            turns = listOf(AiTurn.user(parts)),
            maxTokens = maxTokens,
            json = JsonSpec(schemaName, schema)
        )
        val required = JsonExtract.requiredKeys(schema)
        repeat(2) {
            val completion = route.provider.complete(key, request)
            JsonExtract.extractObject(completion.text, required)?.let { return@withContext it }
            if (completion.truncated) {
                throw AiException(
                    "${route.provider.spec.shortName}'s answer was cut off before it finished. " +
                        "Try again, or choose a model with a longer output limit."
                )
            }
        }
        throw AiException(
            unreadable ?: ("${route.provider.spec.shortName} (${route.model}) didn't return readable JSON. " +
                "Try again, or pick another model in Settings → AI → Advanced.")
        )
    }
}
