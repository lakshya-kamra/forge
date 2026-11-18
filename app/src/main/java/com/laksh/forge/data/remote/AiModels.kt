package com.laksh.forge.data.remote

/*
 * Provider-neutral results of the AI jobs. These used to live inside AnthropicClient; they
 * stay in this package because features import them from here, but nothing about them is
 * specific to one vendor any more (see data/ai for the providers that produce them).
 */

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

/**
 * v1 name for an AI failure. Every provider now throws [com.laksh.forge.data.ai.AiException]
 * (message always safe to show); the alias keeps any `catch (e: AnthropicException)` working.
 */
typealias AnthropicException = com.laksh.forge.data.ai.AiException
