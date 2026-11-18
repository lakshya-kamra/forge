package com.laksh.forge.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The prompts and JSON schemas behind Forge's built-in AI jobs, written once so every provider
 * gets identical behaviour. (They used to be Anthropic tool definitions inside AnthropicClient.)
 *
 * Schema rules shared by every provider's structured-output mode: each object sets
 * additionalProperties=false and lists every property in required; no min/max/length
 * keywords; no nullable unions. "Not stated" is expressed with sentinel values the parser
 * maps back to null (empty string, 0, -1), which every provider handles.
 */
object AiPrompts {

    // ---------------------------------------------------------------- food

    const val FOOD_SCHEMA_NAME = "food_estimate"

    val FOOD_SYSTEM = """
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

        COMPONENTS: when the plate has several distinct foods, list each with its own numbers;
        otherwise return an empty list.
    """.trimIndent()

    fun foodPhotoUser(userHint: String?, bodyContext: String): String = buildString {
        append("Estimate the nutrition for this meal. ")
        if (!userHint.isNullOrBlank()) {
            append("The person adds this context, which you should trust over your visual read where they conflict: \"")
            append(userHint.trim())
            append("\". ")
        }
        append(bodyContext)
        append("\nAnswer with the JSON object only.")
    }

    fun foodTextUser(description: String, bodyContext: String): String =
        "Estimate nutrition for: \"${description.trim()}\". $bodyContext\nAnswer with the JSON object only."

    val FOOD_SCHEMA: JsonObject = buildJsonObject {
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
                put("description", "Breakdown when the plate has several distinct foods; otherwise empty.")
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

    // ---------------------------------------------------------------- label

    const val LABEL_SCHEMA_NAME = "nutrition_label"

    val LABEL_SYSTEM = """
        You transcribe nutrition information panels from photographs of packaging.

        This is transcription, not estimation. Report exactly what the panel states.
        Do not round generously, do not pad, do not infer values that are not printed.

        - basis: "PER_100G" if the column you are reading is per 100g/100ml,
          "PER_SERVING" if it is per serving or per piece.
        - If the panel shows both columns, read the per-100g column and set basis to PER_100G.
        - serving_size_g: the stated serving weight in grams if printed, else 0.
        - brand: the brand if visible, else an empty string.
        - Energy: report kcal. If only kJ is printed, divide by 4.184.
        - If a macro is genuinely not printed, report 0 and say so in the note.
        - If the panel is unreadable, blurred, or not a nutrition panel, set every number to
          0 and explain in the note.
    """.trimIndent()

    const val LABEL_USER = "Read this nutrition panel. Answer with the JSON object only."

    val LABEL_SCHEMA: JsonObject = buildJsonObject {
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

    // ---------------------------------------------------------------- program

    const val PROGRAM_SCHEMA_NAME = "training_program"

    /** Upper bound on extracted PDF text sent in one request. */
    const val PROGRAM_TEXT_LIMIT = 400_000

    val PROGRAM_SYSTEM = """
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
          state it); use -1 when the document does not state it.
        - rest_seconds is the prescribed rest in seconds if stated, otherwise 0.
        - notes carries per-exercise technique or execution cues from the document, trimmed
          to one or two sentences.
        - substitutions carries listed alternative exercises, comma separated.
        - Use an empty string for any text field the document does not provide (author,
          focus, notes, substitutions).
        - Warm-up protocols and general guidance go in the program-level notes, not as
          exercises.
        - If the text is garbled or clearly not a training program, return zero days.
    """.trimIndent()

    fun programTextUser(rawText: String, fileName: String): String =
        "This is the extracted text of a training program PDF named \"$fileName\". " +
            "Reconstruct it as structured training days. Answer with the JSON object only.\n\n" +
            "<program_text>\n" + rawText.take(PROGRAM_TEXT_LIMIT) + "\n</program_text>"

    fun programImagesUser(fileName: String): String =
        "These are the pages of a scanned training program named \"$fileName\", in order. " +
            "Reconstruct it as structured training days. Answer with the JSON object only."

    val PROGRAM_SCHEMA: JsonObject = buildJsonObject {
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
                                    putJsonObject("rir") {
                                        put("type", "number")
                                        put("description", "-1 when not stated")
                                    }
                                    putJsonObject("rest_seconds") {
                                        put("type", "integer")
                                        put("description", "0 when not stated")
                                    }
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

    // ---------------------------------------------------------------- key test

    const val PING_USER = "Reply with the single word: ready"

    // ---------------------------------------------------------------- schema in the prompt

    /**
     * Instructions appended to the system prompt when the provider can't enforce the schema
     * (json_object-only providers, best-effort json_schema, or after a fallback). DeepSeek
     * requires the word "json" and an example in the prompt; the same text helps everyone else.
     */
    fun schemaInstructions(schema: JsonObject): String = buildString {
        append("\n\nOUTPUT FORMAT: reply with exactly one JSON object and nothing else - no prose, ")
        append("no markdown code fences. It must match this JSON Schema (every listed property present):\n")
        append(schema.toString())
        append("\nExample json shape (placeholder values, replace them with real ones):\n")
        append(exampleFor(schema).toString())
    }

    /** A placeholder instance of [schema]: shows the shape without suggesting real values. */
    fun exampleFor(schema: JsonElement): JsonElement {
        val obj = schema as? JsonObject ?: return JsonNull
        obj["enum"]?.let { e -> return runCatching { e.jsonArray.first() }.getOrDefault(JsonPrimitive("")) }
        val type = runCatching { obj["type"]?.jsonPrimitive?.content }.getOrNull()
        return when (type) {
            "object" -> buildJsonObject {
                val props = runCatching { obj["properties"]?.jsonObject }.getOrNull()
                props?.forEach { (k, v) -> put(k, exampleFor(v)) }
            }
            "array" -> buildJsonArray {
                obj["items"]?.let { add(exampleFor(it)) }
            }
            "string" -> JsonPrimitive("...")
            "integer" -> JsonPrimitive(0)
            "number" -> JsonPrimitive(0.0)
            "boolean" -> JsonPrimitive(false)
            else -> JsonNull
        }
    }
}
