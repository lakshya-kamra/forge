package com.laksh.forge.domain.plan

import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Phases
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A target weekly rate band, as % of bodyweight per week (negative = loss). */
data class RateBand(val minPct: Double, val maxPct: Double, val defaultPct: Double) {
    fun clamp(pct: Double): Double = pct.coerceIn(min(minPct, maxPct), max(minPct, maxPct))
    fun contains(pct: Double): Boolean = pct in min(minPct, maxPct)..max(minPct, maxPct)
    val text: String
        get() = if (minPct == maxPct) "%+.2f%% of bodyweight a week".format(minPct)
        else "%+.2f%% to %+.2f%% of bodyweight a week".format(minPct, maxPct)
}

/** Everything needed to turn a phase into numbers. */
data class BuildInput(
    val phase: String,
    val sex: Sex,
    val age: Int,
    val weightKg: Double,
    val trainingAge: TrainingAge,
    val tdee: Double,
    val bodyFat: BodyFatRange?,
    val kidneyDisease: Boolean,
    val breastfeeding: Boolean,
    /** Average daily steps over the last two weeks, when a wearable has synced. */
    val baselineSteps: Double?,
    /** The person's fat preference in g/kg (settings.fatPerKg); floors are applied on top. */
    val fatPerKg: Double,
    /** A rate chosen by the AI or the person; clamped to the phase band. */
    val ratePctOverride: Double? = null,
    val postDiet: Boolean = false,
    /** For the projected-BMI cap on a cut (safety_bmi_underweight_no_deficit); null skips it. */
    val heightCm: Double? = null
)

/** The numeric half of a plan, before guardrails. */
data class BuiltTargets(
    val phase: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val rateKgPerWeek: Double,
    val ratePctPerWeek: Double,
    val band: RateBand,
    val stepsTarget: Int,
    val plannedWeeks: Int,
    /** Plain-language notes on how the numbers were chosen (caps hit, kidney override...). */
    val notes: List<String>
)

/**
 * Rules-based targets per phase from onboarding_science.json "planContents" and the nutrition
 * rules in guardrails.final.json. Guardrails.checkNutrition is still applied afterwards by
 * the caller; this builder already respects the same caps so the clamp rarely has to act.
 */
object PlanBuilder {

    private const val KCAL_PER_KG = NutritionMath.KCAL_PER_KG
    private const val WEEKS_PER_MONTH = 52.0 / 12.0

    /** guardrails.final.json nutrition_calorie_floor_by_sex. */
    fun calorieFloor(sex: Sex): Int = if (sex == Sex.MALE) 1500 else 1200

    /** Lean-tier test used by the deficit-rate and protein rules (male < 12% / female < 22%). */
    private fun leanTier(sex: Sex, bf: BodyFatRange?): Boolean =
        bf != null && bf.low < if (sex == Sex.MALE) 12.0 else 22.0

    /**
     * nutrition_max_deficit_rate_by_body_fat: cap 1.0 / 0.7 / 0.5 %BW/wk by tier, 0.7 when body
     * fat is unknown. The lower bound of the range is used so an uncertain estimate never
     * unlocks a faster rate.
     */
    fun cutRateCapPct(sex: Sex, bf: BodyFatRange?): Double {
        if (bf == null) return 0.7
        val (high, lean) = if (sex == Sex.MALE) 20.0 to 12.0 else 30.0 to 22.0
        return when {
            bf.low >= high -> 1.0
            bf.low >= lean -> 0.7
            else -> 0.5
        }
    }

    /**
     * nutrition_surplus_rate_by_training_age (Muscle and Strength Pyramid monthly ranges):
     * novice 1.0-1.5, intermediate 0.5-1.0, advanced < 0.5 %BW/month, converted to weekly.
     */
    fun gainBandPct(trainingAge: TrainingAge): RateBand {
        val (lo, hi) = when (trainingAge) {
            TrainingAge.ADVANCED -> 0.0 to 0.5
            TrainingAge.INTERMEDIATE -> 0.5 to 1.0
            else -> 1.0 to 1.5
        }
        val loW = lo / WEEKS_PER_MONTH
        val hiW = hi / WEEKS_PER_MONTH
        return RateBand(loW, hiW, (loW + hiW) / 2.0)
    }

    /** Tolerance bands from planContents "rate"; the cut and gain bands are tightened by guardrails. */
    fun band(phase: String, sex: Sex, trainingAge: TrainingAge, bf: BodyFatRange?): RateBand = when (phase) {
        Phases.CUT -> {
            val cap = cutRateCapPct(sex, bf)
            // Already lean: 0.25-0.5 %/wk is the minimum meaningful rate (guardrails).
            val minLoss = if (leanTier(sex, bf)) 0.25 else 0.5
            // Garthe 2011 recommends ~0.7 %/wk for athletes who want to keep gaining lean mass.
            RateBand(-cap, -minLoss, -min(0.7, cap))
        }
        Phases.LEAN_GAIN -> gainBandPct(trainingAge)
        Phases.MAINGAIN -> RateBand(0.0, 0.25, 0.1)
        Phases.RECOMP -> RateBand(-0.25, 0.0, 0.0)
        else -> RateBand(-0.25, 0.25, 0.0)
    }

    /** Fastest loss (% BW/wk) whose projected weight after [weeks] keeps an adult BMI >= 18.5. */
    fun projectedBmiCapPct(weightKg: Double, heightCm: Double, weeks: Int): Double {
        val h = heightCm / 100.0
        return ((weightKg - 18.5 * h * h) / weeks.coerceAtLeast(1) / weightKg * 100.0).coerceAtLeast(0.0)
    }

    /** Planned length before a full reassessment: re-run the rules at phase end or 12 weeks. */
    fun plannedWeeks(phase: String, postDiet: Boolean): Int = when (phase) {
        Phases.DIET_BREAK -> 2
        Phases.MAINTAIN -> if (postDiet) 8 else 12
        else -> 12
    }

    fun build(input: BuildInput): BuiltTargets {
        val notes = mutableListOf<String>()
        val w = input.weightKg
        val tdee = input.tdee
        val band = band(input.phase, input.sex, input.trainingAge, input.bodyFat)
        val floor = calorieFloor(input.sex)

        var ratePct = input.ratePctOverride?.let { band.clamp(it) } ?: band.defaultPct
        var kcal: Double

        when (input.phase) {
            Phases.CUT -> {
                val bmiCap = input.heightCm?.takeIf { input.age >= 18 }?.let { h ->
                    projectedBmiCapPct(w, h, plannedWeeks(input.phase, input.postDiet))
                }
                if (bmiCap != null && abs(ratePct) > bmiCap) {
                    ratePct = -bmiCap
                    notes += "The rate is slowed so your weight at the end of the plan stays in the healthy range for your height."
                }
                var deficit = abs(ratePct) / 100.0 * w * KCAL_PER_KG / 7.0
                // nutrition_max_deficit_size: min(25% of TDEE, 500 kcal, TDEE - floor).
                val cap = minOf(0.25 * tdee, 500.0, max(tdee - floor, 0.0))
                if (deficit > cap) {
                    deficit = cap
                    notes += "The deficit is capped at ${cap.roundToInt()} kcal a day so training stays productive."
                }
                kcal = tdee - deficit
                ratePct = -(deficit * 7.0 / KCAL_PER_KG) / w * 100.0
            }
            Phases.LEAN_GAIN -> {
                // nutrition_surplus_size: start at the bottom of the Iraki band (+10% novice and
                // intermediate, +5% advanced), intermediate/advanced capped at 300 kcal; the
                // Pyramid rate cap overrides it.
                val startPct = if (input.trainingAge == TrainingAge.ADVANCED) 0.05 else 0.10
                var surplus = tdee * startPct
                if (input.trainingAge == TrainingAge.INTERMEDIATE || input.trainingAge == TrainingAge.ADVANCED) {
                    surplus = min(surplus, 300.0)
                }
                val target = input.ratePctOverride?.let { band.clamp(it) } ?: band.defaultPct
                val surplusForRate = target / 100.0 * w * KCAL_PER_KG / 7.0
                if (surplusForRate < surplus) surplus = surplusForRate
                kcal = tdee + surplus
                ratePct = surplus * 7.0 / KCAL_PER_KG / w * 100.0
            }
            Phases.MAINGAIN -> {
                // Maintenance to about +5% (anchored on the Helms 2023 5% arm), never past 0.25 %BW/wk.
                val maxSurplus = band.maxPct / 100.0 * w * KCAL_PER_KG / 7.0
                val surplus = min(tdee * 0.05, maxSurplus)
                kcal = tdee + surplus
                ratePct = surplus * 7.0 / KCAL_PER_KG / w * 100.0
            }
            else -> {
                // Recomp, maintenance and breaks all centre on maintenance.
                kcal = tdee
                ratePct = 0.0
            }
        }

        if (input.breastfeeding) {
            // safety_breastfeeding_no_deficit: maintenance + 330-400 kcal (CDC). The upper end is
            // used so lactation is never under-fuelled; it is labelled as an approximation.
            kcal = max(kcal, tdee + 400.0)
            notes += "While breastfeeding, Forge adds about 400 kcal a day to your estimated maintenance (an approximation)."
        }

        if (kcal < floor) {
            kcal = floor.toDouble()
            notes += "Calories are kept at or above $floor kcal a day."
        }

        val protein = proteinPerKg(input.phase, input.kidneyDisease)
        if (input.kidneyDisease) {
            notes += "Because you reported kidney disease, protein is set to 0.8 g per kg - please confirm any " +
                "target with your nephrologist or renal dietitian."
        }
        val macros = macros(kcal, w, protein, input.fatPerKg, PhaseCopy.isDeficit(input.phase))

        return BuiltTargets(
            phase = input.phase,
            kcal = macros.kcal,
            proteinG = macros.proteinG,
            carbsG = macros.carbsG,
            fatG = macros.fatG,
            fiberG = macros.fiberG,
            rateKgPerWeek = ratePct / 100.0 * w,
            ratePctPerWeek = ratePct,
            band = band,
            stepsTarget = stepsTarget(input.age, input.baselineSteps),
            plannedWeeks = plannedWeeks(input.phase, input.postDiet),
            notes = notes
        )
    }

    /**
     * Protein g/kg bodyweight: deficit default 2.2 (floor 1.8), surplus/maintenance default 1.8
     * (range 1.6-2.2), recomp 2.0 (inside both the recomp and maintenance ranges). The kidney
     * override (0.8, never above 1.3) supersedes all of them.
     */
    fun proteinPerKg(phase: String, kidneyDisease: Boolean): Double = when {
        kidneyDisease -> KIDNEY_PROTEIN_G_PER_KG
        phase == Phases.CUT -> 2.2
        phase == Phases.RECOMP -> 2.0
        else -> 1.8
    }

    const val KIDNEY_PROTEIN_G_PER_KG = 0.8
    const val KIDNEY_PROTEIN_MAX_G_PER_KG = 1.3
    const val PROTEIN_MAX_G_PER_KG = 3.5

    data class Macros(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int, val fiberG: Int)

    /**
     * Protein first, then fat with its floors (>= 0.5 g/kg always; >= 20% of energy at
     * maintenance/surplus, >= 15% and <= 30% in a deficit), carbohydrate fills the rest.
     */
    fun macros(kcal: Double, weightKg: Double, proteinPerKg: Double, fatPerKg: Double, deficit: Boolean): Macros {
        val protein = (proteinPerKg * weightKg).roundToInt()
        val minFatG = max(0.5 * weightKg, (if (deficit) 0.15 else 0.20) * kcal / 9.0)
        var fat = max(fatPerKg * weightKg, minFatG)
        if (deficit) fat = min(fat, max(0.30 * kcal / 9.0, 0.5 * weightKg))
        val fatG = fat.roundToInt()
        val carbs = ((kcal - protein * 4 - fatG * 9) / 4.0).coerceAtLeast(0.0).roundToInt()
        // >= 25 g a day (WHO 2023), or the app's existing 14 g per 1000 kcal if higher.
        val fiber = max(25, (kcal / 1000.0 * 14).roundToInt())
        return Macros(kcal.roundToInt(), protein, carbs, fatG, fiber)
    }

    /**
     * Paluch 2022: benefit levels off around 8,000-10,000 steps (adults under 60) and 6,000-8,000
     * (60+). Forge's design choice is baseline + 1,000-2,000, aiming for that band, rounded to 500.
     */
    fun stepsTarget(age: Int, baselineSteps: Double?): Int {
        val (aimLow, aimHigh) = if (age >= 60) 6000 to 8000 else 8000 to 10000
        if (baselineSteps == null || baselineSteps <= 0) return aimLow
        if (baselineSteps >= aimHigh) return roundTo500(baselineSteps)
        val target = min(baselineSteps + 1500.0, aimHigh.toDouble())
        return roundTo500(target)
    }

    private fun roundTo500(v: Double): Int = ((v / 500.0).roundToInt() * 500).coerceAtLeast(500)

    /** Training guidance from planContents "training", by training age. */
    fun trainingText(trainingAge: TrainingAge, detrained: Boolean, phase: String): String {
        val base = when (trainingAge) {
            TrainingAge.ADVANCED ->
                "4-5 training days a week, 10-20 hard sets per muscle per week, progressing with double progression or RPE."
            TrainingAge.INTERMEDIATE ->
                "3-4 training days a week, for example an upper/lower split, 10-20 hard sets per muscle per week, progressing with double progression or RPE."
            else ->
                "Full-body sessions 2-3 days a week built on compound lifts with simple linear load progression, working up toward about 10 hard sets per muscle per week."
        }
        val returning = if (detrained) " Coming back after a break, start at the lower end and build up." else ""
        val cut = if (phase == Phases.CUT)
            " Keep training volume and intensity up while cutting - strength holds up well in a deficit even though muscle gain slows."
        else ""
        return "Resistance training is part of every phase. $base$returning$cut"
    }

    /** Stop conditions and the likely next phase, from decisionRules and phaseDuration. */
    fun stopConditions(phase: String, sex: Sex): Pair<List<String>, String> {
        val male = sex == Sex.MALE
        return when (phase) {
            Phases.CUT -> listOf(
                "Your body-fat range reaches about ${if (male) "10%" else "18%"}.",
                "Your weekly-average weight stalls for 3+ weeks despite sticking to the plan.",
                "Strength drops markedly.",
                "16 weeks of continuous deficit - Forge then switches you to at least 4 weeks of maintenance."
            ) to "Maintenance, then a lean gain or maingain"
            Phases.LEAN_GAIN -> listOf(
                "Body fat reaches about ${if (male) "15-20%" else "25-28%"}, or your waist rises past the ceiling you're comfortable with.",
                "Your weekly gain runs above the band for 4 weeks."
            ) to "A short minicut (2-6 weeks) or a full cut"
            Phases.MAINGAIN -> listOf(
                "Reassess every 12 weeks, or sooner if your waist climbs."
            ) to "Continue, or a lean gain if you want faster progress"
            Phases.RECOMP -> listOf(
                "Reassess after 8-12 weeks using waist, photos and strength rather than the scale."
            ) to "Another recomp block, a cut or a lean gain"
            else -> listOf(
                "Reassess at the review date with fresh measurements."
            ) to "Re-run the recommendation at your review"
        }
    }

    /** Check-in cadence from planContents "checkIns". */
    val checkInCadence: List<String> = listOf(
        "Daily: morning weight (after the toilet, before food or drink), steps and food.",
        "Weekly: check-in on your 7-day average weight, adherence, training, hunger, sleep and energy.",
        "Every 2-4 weeks: tape measurements.",
        "Monthly: progress photos (optional).",
        "At the review date or 12 weeks: a full reassessment and a fresh recommendation."
    )
}
