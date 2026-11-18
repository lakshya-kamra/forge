package com.laksh.forge.domain.guardrails

import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge

// CONTRACT - owned by the guardrails feature, which replaces the bodies with the
// evidence-based rules in docs/research/guardrails.final.json. Keep these signatures.

/** Everything the guardrails need to know about the person. */
data class GuardrailProfile(
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val bodyFatPct: Double?,
    val trainingAge: TrainingAge,
    val pregnancy: PregnancyStatus,
    val edScreenPositive: Boolean
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}

enum class Severity { INFO, CAUTION, BLOCK }

data class GuardrailFlag(
    val id: String,
    val severity: Severity,
    /** Calm, plain-language message shown to the user as-is. */
    val message: String,
    val citation: String? = null
)

/** A nutrition prescription after guardrails: possibly clamped, with the reasons. */
data class NutritionCheck(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val rateKgPerWeek: Double,
    val flags: List<GuardrailFlag>
) {
    val wasAdjusted: Boolean get() = flags.any { it.severity != Severity.INFO }
}

/** Phase ids, matching PhasePlan.phase. */
object Phases {
    const val CUT = "CUT"
    const val LEAN_GAIN = "LEAN_GAIN"
    const val MAINGAIN = "MAINGAIN"
    const val MAINTAIN = "MAINTAIN"
    const val RECOMP = "RECOMP"
    const val REVERSE_DIET = "REVERSE_DIET"
    const val DIET_BREAK = "DIET_BREAK"
    val ALL = listOf(CUT, LEAN_GAIN, MAINGAIN, MAINTAIN, RECOMP, REVERSE_DIET, DIET_BREAK)
}

object Guardrails {

    /** Phases this person may be put in. A deficit is never offered when unsafe. */
    fun allowedPhases(p: GuardrailProfile): Set<String> {
        val noDeficit = p.age < 18 || p.pregnancy != PregnancyStatus.NONE ||
            p.edScreenPositive || p.bmi < 18.5
        return if (noDeficit) Phases.ALL.toSet() - Phases.CUT else Phases.ALL.toSet()
    }

    /** Clamp a proposed prescription into safe bounds and explain every change. */
    fun checkNutrition(
        p: GuardrailProfile,
        kcal: Int,
        proteinG: Int,
        carbsG: Int,
        fatG: Int,
        rateKgPerWeek: Double,
        tdee: Double
    ): NutritionCheck {
        val floor = if (p.sex == Sex.MALE) 1500 else 1200
        val flags = mutableListOf<GuardrailFlag>()
        var k = kcal
        if (k < floor) {
            k = floor
            flags += GuardrailFlag("kcal_floor", Severity.CAUTION, "Calories raised to a safe minimum of $floor.")
        }
        return NutritionCheck(k, proteinG, carbsG, fatG, rateKgPerWeek, flags)
    }

    /** SCOFF: five yes/no questions; two or more "yes" answers is a positive screen. */
    val SCOFF_QUESTIONS: List<String> = listOf(
        "Do you make yourself Sick because you feel uncomfortably full?",
        "Do you worry you have lost Control over how much you eat?",
        "Have you recently lost more than One stone (about 6 kg) in a 3 month period?",
        "Do you believe yourself to be Fat when others say you are too thin?",
        "Would you say that Food dominates your life?"
    )

    fun scoffPositive(answers: List<Boolean>): Boolean = answers.count { it } >= 2

    /** Warnings for weekly hard sets per muscle (keys are muscle ids from the catalog). */
    fun trainingVolumeFlags(weeklySetsByMuscle: Map<String, Double>, trainingAge: TrainingAge): List<GuardrailFlag> =
        emptyList()

    /** Flag a session-to-session load jump that is too large. Null when fine. */
    fun loadJumpFlag(previousKg: Double, proposedKg: Double, exerciseKey: String): GuardrailFlag? = null

    /** Rules appended to every AI system prompt that can produce advice or numbers. */
    fun promptRules(p: GuardrailProfile?): String =
        "Never recommend a calorie deficit to anyone under 18, pregnant or breastfeeding, " +
            "with a BMI under 18.5, or with a positive eating-disorder screen. " +
            "Never comment on attractiveness. Refer pain, injury and medical questions to a professional."
}
