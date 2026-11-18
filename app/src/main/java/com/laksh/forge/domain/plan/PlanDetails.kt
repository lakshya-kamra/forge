package com.laksh.forge.domain.plan

import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.domain.MacroTargets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The body-fat picture at plan time, stored so the plan screen can explain itself later. */
@Serializable
data class BodyFatSummary(
    val low: Double,
    val high: Double,
    val method: String,
    val navyLow: Double? = null,
    val navyHigh: Double? = null,
    val photoLow: Double? = null,
    val photoHigh: Double? = null,
    /** The model's own confidence in a photo estimate: LOW | MEDIUM | HIGH. */
    val photoConfidence: String? = null,
    val disagreement: Boolean = false
)

/**
 * Everything in a plan beyond the headline numbers, serialised into PhasePlan.planJson.
 * Unknown keys are ignored on read so later versions can add fields without a migration.
 */
@Serializable
data class PlanDetails(
    val version: Int = 1,
    val plannedWeeks: Int,
    val ratePctPerWeek: Double,
    val bandMinPct: Double,
    val bandMaxPct: Double,
    val tdee: Int,
    val tdeeBasis: String,
    val bodyFat: BodyFatSummary? = null,
    /** Which decision rule fired, for transparency. */
    val rule: String,
    val ruleExplanation: String,
    val candidates: List<String> = emptyList(),
    val stopConditions: List<String> = emptyList(),
    val nextPhase: String = "",
    val training: String = "",
    val trainingDaysPerWeek: Int? = null,
    val equipment: String? = null,
    val checkIns: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val programIds: List<String> = emptyList(),
    val programNames: List<String> = emptyList(),
    /** "Provider · model" when the AI chose the phase. */
    val aiModel: String? = null,
    val citations: List<String> = emptyList(),
    /** Snapshot of the inputs the plan was built from, for re-plans and check-ins. */
    val weightKgAtStart: Double? = null,
    val postDiet: Boolean = false
) {
    val band: RateBand get() = RateBand(bandMinPct, bandMaxPct, ratePctPerWeek)
}

object PlanJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(d: PlanDetails): String = json.encodeToString(PlanDetails.serializer(), d)

    /** Null for an unreadable or legacy blob; screens then fall back to the plan's own columns. */
    fun decode(raw: String): PlanDetails? =
        runCatching { json.decodeFromString(PlanDetails.serializer(), raw) }.getOrNull()
}

fun PhasePlan.details(): PlanDetails? = PlanJson.decode(planJson)

/** The plan's daily targets in the shape Today and Trends already render. */
fun PhasePlan.toMacroTargets(): MacroTargets = MacroTargets(
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    basis = "${PhaseCopy.name(phase)} plan" + when (source) {
        "AI" -> " (AI-assisted)"
        "RULES" -> " (rules-based)"
        else -> " (your choice)"
    }
)

/** Human label for PhasePlan.source. */
fun planSourceLabel(source: String): String = when (source) {
    "AI" -> "AI-assisted"
    "RULES" -> "Rules-based"
    else -> "Your choice"
}
