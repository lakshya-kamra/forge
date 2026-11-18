package com.laksh.forge.domain.plan

import com.laksh.forge.data.ai.AiPart
import com.laksh.forge.data.ai.ForgeAi
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** The model's photo read, before Forge widens it into an honest range. */
data class AiBodyEstimate(
    val usable: Boolean,
    val estimatePct: Double,
    val lowPct: Double,
    val highPct: Double,
    /** LOW | MEDIUM | HIGH */
    val confidence: String,
    val photoIssues: String
)

/** The AI's choice among the rules' candidates, plus the words around it. */
data class AiPlanChoice(
    val phase: String,
    val ratePctPerWeek: Double,
    val rationale: String,
    val training: String,
    val stopConditions: List<String>,
    val nextPhase: String,
    val notes: List<String>
)

/** Thrown when an AI answer is well-formed but unusable; the message is safe to show. */
class PlanAiRejected(message: String) : Exception(message)

/**
 * Prompts and schemas for the two AI steps of onboarding. The AI never decides what is safe:
 * the rules produce the candidate phases and rate bands, the model only chooses among them and
 * writes the explanation, and Guardrails.checkNutrition clamps whatever comes back.
 */
object PlanAi {

    /**
     * Words the photo and plan copy must never contain (guardrails.final.json
     * safety_messaging_no_body_shaming and photo_no_attractiveness_commentary).
     */
    private val BLOCKED = listOf(
        "attractive", "attractiveness", "sexy", "ugly", "flabby", "gross", "disgusting",
        "skinny-fat", "skinny fat", "problem area", "cheat meal", "cheat day", "junk food",
        "clean food", "guilt", "earn your food", "burn it off", "ideal body", "perfect body",
        "beach body", "handsome", "beautiful"
    )

    fun hasBlockedLanguage(text: String): Boolean {
        val t = text.lowercase()
        return BLOCKED.any { it in t }
    }

    // ------------------------------------------------------------------ body estimate

    private val BODY_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("usable") { put("type", "boolean") }
            putJsonObject("estimate_pct") { put("type", "number") }
            putJsonObject("low_pct") { put("type", "number") }
            putJsonObject("high_pct") { put("type", "number") }
            putJsonObject("confidence") {
                put("type", "string")
                putJsonArray("enum") { add("LOW"); add("MEDIUM"); add("HIGH") }
            }
            putJsonObject("photo_issues") { put("type", "string") }
        }
        putJsonArray("required") {
            add("usable"); add("estimate_pct"); add("low_pct"); add("high_pct"); add("confidence"); add("photo_issues")
        }
    }

    suspend fun estimateBodyFat(
        ai: ForgeAi,
        profile: GuardrailProfile,
        sex: Sex,
        age: Int,
        heightCm: Double,
        weightKg: Double,
        navy: BodyFatRange?,
        photos: List<Pair<Pose, String>>
    ): AiBodyEstimate {
        val system = buildString {
            appendLine("You estimate body-fat percentage from standardised progress photos for a fitness app.")
            appendLine("Output only: whether the photos are usable, a body-fat estimate with your own low/high range, your confidence, and short neutral notes about photo conditions (lighting, pose, clothing, framing, camera tilt).")
            appendLine("Rules:")
            appendLine("- Never comment on attractiveness, appearance quality, body ideals or comparisons with other people, and never rank or score appearance.")
            appendLine("- Use neutral, size-inclusive wording. photo_issues is only about capture conditions, never about the body.")
            appendLine("- If the photos are not of one adult's whole body in form-fitting clothing, set usable=false.")
            appendLine("- Visual estimates are unvalidated and can be off by 6 or more points; give an honest range, not a precise figure.")
            appendLine(Guardrails.promptRules(profile))
        }
        val context = buildString {
            append("Person: ${sex.name.lowercase()}, age $age, height ${heightCm.roundToInt()} cm, weight ${"%.1f".format(weightKg)} kg.")
            if (navy != null) append(" Their tape-measure (US Navy) estimate is ${navy.label}; judge the photos independently.")
        }
        val parts = buildList<AiPart> {
            add(AiPart.Text(context))
            photos.forEach { (pose, base64) ->
                add(AiPart.Text("${pose.label} view:"))
                add(AiPart.Image(base64, "image/jpeg"))
            }
        }
        val obj = ai.structured(Task.BODY, system, parts, "body_fat_estimate", BODY_SCHEMA, maxTokens = 1200)
        val usable = obj["usable"]?.jsonPrimitive?.booleanOrNull ?: false
        val est = obj["estimate_pct"]?.jsonPrimitive?.doubleOrNull
            ?: throw PlanAiRejected("The photo estimate came back without a number.")
        val low = obj["low_pct"]?.jsonPrimitive?.doubleOrNull ?: est
        val high = obj["high_pct"]?.jsonPrimitive?.doubleOrNull ?: est
        val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull ?: "LOW"
        val issues = obj["photo_issues"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!usable) {
            throw PlanAiRejected(
                "The photos couldn't be used for an estimate" +
                    (if (issues.isNotBlank() && !hasBlockedLanguage(issues)) ": $issues" else ".")
            )
        }
        // Same sanity window as the tape formula: anything outside it is a misread, not a body.
        if (est !in 2.0..70.0) throw PlanAiRejected("The photo estimate was outside a plausible range, so Forge ignored it.")
        return AiBodyEstimate(
            usable = true,
            estimatePct = est,
            lowPct = minOf(low, high),
            highPct = maxOf(low, high),
            confidence = confidence,
            photoIssues = if (hasBlockedLanguage(issues)) "" else issues
        )
    }

    // ------------------------------------------------------------------ plan choice

    private fun planSchema(candidates: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("phase") {
                put("type", "string")
                putJsonArray("enum") { candidates.forEach { add(it) } }
            }
            putJsonObject("rate_pct_per_week") { put("type", "number") }
            putJsonObject("rationale") { put("type", "string") }
            putJsonObject("training") { put("type", "string") }
            putJsonObject("stop_conditions") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("next_phase") { put("type", "string") }
            putJsonObject("notes") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        putJsonArray("required") {
            add("phase"); add("rate_pct_per_week"); add("rationale"); add("training")
            add("stop_conditions"); add("next_phase"); add("notes")
        }
    }

    /** Everything the model is told about the person and the safe option space. */
    data class PlanContext(
        val sex: Sex,
        val age: Int,
        val heightCm: Double,
        val weightKg: Double,
        val trainingAgeLabel: String,
        val detrained: Boolean,
        val goal: TrainingGoal,
        val preference: PhasePreference,
        val daysPerWeek: Int?,
        val equipment: Equipment,
        val bodyFat: CombinedBodyFat?,
        val decision: PhaseDecision,
        val bands: Map<String, RateBand>,
        val tdee: Double,
        val tdeeBasis: String,
        val guardrailNotes: List<String>
    )

    suspend fun choosePlan(ai: ForgeAi, profile: GuardrailProfile, c: PlanContext): AiPlanChoice {
        val candidates = c.decision.candidates
        val system = buildString {
            appendLine("You are the planning step of a fitness app. Evidence-based rules have already produced the only phases this person may start, with a safe weekly-rate band for each.")
            appendLine("Choose ONE phase from the allowed list, pick a weekly rate (percent of bodyweight per week; negative = loss) inside that phase's band, and write the plan's words.")
            appendLine("Write in second person, warm and plain, no jargon. rationale: 2-4 sentences explaining why this phase fits, mentioning that body-fat figures are uncertain ranges. training: 1-3 sentences fitted to their days, equipment and experience; resistance training is part of every phase. stop_conditions: 2-4 short items. notes: 0-3 short practical tips.")
            appendLine("Never comment on appearance or attractiveness, never use 'ideal body' language, never praise eating less, and never use moral food words.")
            appendLine("Respect the person's stated preference when the rules list it as an option; otherwise follow the rules' recommendation.")
            appendLine(Guardrails.promptRules(profile))
        }
        val user = buildString {
            appendLine("Person: ${c.sex.name.lowercase()}, ${c.age} y, ${c.heightCm.roundToInt()} cm, ${"%.1f".format(c.weightKg)} kg.")
            appendLine("Training: ${c.trainingAgeLabel}${if (c.detrained) ", returning after a break" else ""}; ${c.daysPerWeek?.let { "$it days/week available" } ?: "days/week not given"}; equipment: ${c.equipment.label}.")
            appendLine("Main goal: ${c.goal.label}. Preference when both are reasonable: ${c.preference.label}.")
            val bf = c.bodyFat
            when {
                bf == null || (bf.navy == null && bf.photo == null) -> appendLine("Body fat: no estimate.")
                bf.disagreement -> appendLine("Body fat: tape ${bf.navy?.label}, photos ${bf.photo?.label} - these disagree, treat as very uncertain.")
                else -> appendLine("Body fat (likely range): ${bf.combined?.label} from ${bf.combined?.method?.label}.")
            }
            appendLine("Estimated maintenance: ${c.tdee.roundToInt()} kcal (${c.tdeeBasis}).")
            appendLine("Rules' recommendation: ${c.decision.recommended}. Why: ${c.decision.explanation}")
            appendLine("Allowed phases and rate bands:")
            candidates.forEach { p ->
                val b = c.bands[p]
                appendLine("- $p (${PhaseCopy.name(p)}): ${b?.text ?: "0% a week"}")
            }
            if (c.guardrailNotes.isNotEmpty()) appendLine("Safety notes already applied: ${c.guardrailNotes.joinToString(" ")}")
        }
        val obj = ai.structured(Task.PLAN, system, listOf(AiPart.Text(user)), "phase_plan", planSchema(candidates), maxTokens = 2000)

        val phase = obj["phase"]?.jsonPrimitive?.contentOrNull
        if (phase == null || phase !in candidates) {
            throw PlanAiRejected("The AI suggested a phase outside the safe options, so Forge used its rules instead.")
        }
        val rate = obj["rate_pct_per_week"]?.jsonPrimitive?.doubleOrNull ?: c.bands[phase]?.defaultPct ?: 0.0
        val rationale = obj["rationale"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val training = obj["training"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val stops = runCatching { obj["stop_conditions"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() } }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }
        val next = obj["next_phase"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val notes = runCatching { obj["notes"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() } }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }

        if (rationale.isBlank()) throw PlanAiRejected("The AI answer had no explanation, so Forge used its rules instead.")
        val allText = (listOf(rationale, training, next) + stops + notes).joinToString(" ")
        if (hasBlockedLanguage(allText)) {
            throw PlanAiRejected("The AI's wording didn't meet Forge's language rules, so Forge used its own plan instead.")
        }
        return AiPlanChoice(phase, rate, rationale, training, stops, next, notes)
    }
}
