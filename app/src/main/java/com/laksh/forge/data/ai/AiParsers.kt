package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.FoodComponent
import com.laksh.forge.data.remote.FoodEstimate
import com.laksh.forge.data.remote.LabelReading
import com.laksh.forge.data.remote.ParsedDay
import com.laksh.forge.data.remote.ParsedExercise
import com.laksh.forge.data.remote.ParsedProgram
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns the JSON objects from [AiPrompts]' schemas into Forge's data classes.
 *
 * Written to survive non-enforcing providers: numbers may arrive as strings ("450" or
 * "450 kcal"), keys may be missing, and the "not stated" sentinels (-1, 0, "") are mapped
 * back to null so the rest of the app never sees them.
 */
object AiParsers {

    fun food(obj: JsonObject, fallbackName: String): FoodEstimate {
        val components = (obj["components"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            FoodComponent(
                name = name,
                portion = o.str("portion") ?: "",
                kcal = o.num("kcal"),
                proteinG = o.num("protein_g"),
                carbsG = o.num("carbs_g"),
                fatG = o.num("fat_g")
            )
        } ?: emptyList()

        val protein = obj.num("protein_g")
        val carbs = obj.num("carbs_g")
        val fat = obj.num("fat_g")
        // A model that drops kcal but gives macros still gave us the answer: 4/4/9 recovers it.
        val kcal = obj.num("kcal").takeIf { it > 0 } ?: (protein * 4 + carbs * 4 + fat * 9)

        return FoodEstimate(
            name = obj.str("name") ?: fallbackName,
            servingDesc = obj.str("serving_description") ?: "1 serving",
            kcal = kcal,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            fiberG = obj.num("fiber_g"),
            confidence = when (obj.str("confidence")?.lowercase()) {
                "high" -> "HIGH"
                "low" -> "LOW"
                else -> "MEDIUM"
            },
            note = obj.str("note") ?: "",
            components = components
        )
    }

    fun label(obj: JsonObject): LabelReading {
        val basis = obj.str("basis")?.uppercase()?.let { if ("SERV" in it) "PER_SERVING" else "PER_100G" } ?: "PER_100G"
        return LabelReading(
            name = obj.str("name") ?: "Packaged food",
            brand = obj.str("brand"),
            basis = basis,
            servingSizeG = obj.num("serving_size_g").takeIf { it > 0 },
            servingDesc = obj.str("serving_description") ?: if (basis == "PER_100G") "100 g" else "1 serving",
            kcal = obj.num("kcal"),
            proteinG = obj.num("protein_g"),
            carbsG = obj.num("carbs_g"),
            fatG = obj.num("fat_g"),
            fiberG = obj.num("fiber_g"),
            note = obj.str("note") ?: ""
        )
    }

    /** @throws AiException when no training days came back (garbled or non-program document). */
    fun program(obj: JsonObject, fileName: String): ParsedProgram {
        val days = (obj["days"] as? JsonArray)?.mapNotNull { dEl ->
            val d = dEl as? JsonObject ?: return@mapNotNull null
            val dayName = d.str("name") ?: return@mapNotNull null
            val exercises = (d["exercises"] as? JsonArray)?.mapNotNull { eEl ->
                val e = eEl as? JsonObject ?: return@mapNotNull null
                val name = e.str("name") ?: return@mapNotNull null
                val low = e.num("rep_low").toInt().coerceAtLeast(1)
                val high = e.num("rep_high").toInt().coerceAtLeast(1)
                ParsedExercise(
                    name = name,
                    sets = e.num("sets").toInt().coerceAtLeast(1),
                    // A reversed range ("12-8") is still the same prescription.
                    repLow = minOf(low, high),
                    repHigh = maxOf(low, high),
                    rir = e.numOrNull("rir")?.takeIf { it >= 0 },
                    restSeconds = e.numOrNull("rest_seconds")?.toInt()?.takeIf { it > 0 },
                    notes = e.str("notes"),
                    substitutions = e.str("substitutions")
                )
            } ?: emptyList()
            ParsedDay(name = dayName, focus = d.str("focus"), exercises = exercises)
        } ?: emptyList()

        if (days.isEmpty()) throw AiException("No training days were found in that PDF.")

        return ParsedProgram(
            name = obj.str("name") ?: fileName.removeSuffix(".pdf").removeSuffix(".PDF"),
            author = obj.str("author"),
            notes = obj.str("notes"),
            days = days
        )
    }

    // ---------------------------------------------------------------- tolerant field access

    private val firstNumber = Regex("-?\\d+(?:[.,]\\d+)?")

    /** Non-blank string value; JSON null, "null" and "" all mean "not stated". */
    internal fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        val s = p.contentOrNull?.trim() ?: return null
        return s.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    internal fun JsonObject.numOrNull(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        val content = p.contentOrNull ?: return null
        content.toDoubleOrNull()?.let { return it.takeIf { v -> v.isFinite() } }
        // "450 kcal", "12,5" - take the first number in the text.
        return firstNumber.find(content)?.value?.replace(',', '.')?.toDoubleOrNull()
    }

    internal fun JsonObject.num(key: String): Double = numOrNull(key)?.coerceAtLeast(0.0) ?: 0.0
}
