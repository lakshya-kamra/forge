package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.FoodEstimate
import com.laksh.forge.data.remote.LabelReading
import com.laksh.forge.data.remote.ParsedProgram
import com.laksh.forge.data.remote.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** One piece of a multimodal user message. */
sealed interface AiPart {
    data class Text(val text: String) : AiPart
    /** [base64] has no data-URI prefix and no line breaks. */
    data class Image(val base64: String, val mediaType: String = "image/jpeg") : AiPart
}

/**
 * Thrown for every AI failure; [message] is always safe to show to the user.
 * [httpCode] is set when the provider answered with an HTTP error, so callers (and the
 * OpenAI-compatible provider's parameter fallback) can tell "bad request" from "bad key".
 */
open class AiException(
    message: String,
    val retryable: Boolean = false,
    val httpCode: Int? = null
) : Exception(message)

/**
 * The single AI surface the rest of the app codes against. Implementations route each
 * [Task] to a provider and model (Anthropic natively, or any OpenAI-compatible provider),
 * so features never know which vendor answered.
 */
interface ForgeAi {
    /** Whether a key is configured at all. Features check this before offering AI actions. */
    val isConfigured: Boolean

    /** Human-readable "Provider · model" for [task], for the UI and for auditing estimates. */
    fun describe(task: Task): String

    suspend fun estimateFoodFromPhoto(
        imageBase64: String,
        mediaType: String,
        userHint: String?,
        bodyContext: String
    ): FoodEstimate

    suspend fun estimateFoodFromText(description: String, bodyContext: String): FoodEstimate

    suspend fun readNutritionLabel(imageBase64: String, mediaType: String): LabelReading

    suspend fun parseProgram(rawText: String, fileName: String): ParsedProgram

    suspend fun parseProgramFromImages(imagesBase64: List<String>, fileName: String): ParsedProgram

    /** Streaming chat. [history] is (role, text) pairs with role "user" or "assistant". */
    fun chatStream(
        system: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String>

    /**
     * Generic structured call for new features: returns a JSON object that conforms to
     * [schema] (a JSON Schema object; every object must set additionalProperties=false and
     * list all properties in required). Feature code owns its own prompts and schemas.
     */
    suspend fun structured(
        task: Task,
        system: String,
        parts: List<AiPart>,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int = 8000
    ): JsonObject

    /** Cheap round-trip to confirm the key works. Returns a short status string. */
    suspend fun testKey(): String

    /** Model ids the configured key can use (for the advanced per-task override picker). */
    suspend fun listModels(): List<String>
}
