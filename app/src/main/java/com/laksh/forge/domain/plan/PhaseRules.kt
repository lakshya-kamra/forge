package com.laksh.forge.domain.plan

import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.Phases
import kotlin.math.roundToInt

/** Reasons the plan must hold at maintenance regardless of body composition or preference. */
enum class SafetyHold(val reason: String) {
    MINOR("you're under 18"),
    PREGNANT("you're pregnant"),
    BREASTFEEDING("you're breastfeeding"),
    EATING_CONCERN("some of your answers suggest food may be causing you stress"),
    MEDICAL("you have a medical condition that affects weight or nutrition")
}

/** Everything the deterministic phase rules look at. Pure data so the rules unit-test on the JVM. */
data class PhaseInputs(
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val trainingAge: TrainingAge,
    /** Returning after a long layoff - recomp is realistic again (MacroFactor; Nippard). */
    val detrained: Boolean,
    val goal: TrainingGoal,
    val preference: PhasePreference,
    /** The range to decide with (see CombinedBodyFat.forDecision); null when nothing was measured. */
    val bodyFat: BodyFatRange?,
    /** WHO-site waist, used only for the Asian Indian abdominal-obesity cut-off. */
    val healthWaistCm: Double?,
    val southAsian: Boolean,
    /** Finished a deficit of ~8+ weeks within the last month, or reports strong diet fatigue. */
    val recentlyFinishedDiet: Boolean,
    val safetyHolds: Set<SafetyHold>
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
    val isNoviceOrDetrained: Boolean
        get() = detrained || trainingAge == TrainingAge.NOVICE || trainingAge == TrainingAge.UNKNOWN
}

/** Which decision rule in onboarding_science.json produced the recommendation. */
enum class DecisionRule(val citations: List<String>) {
    SAFETY_HOLD(listOf("mfPhotos")),
    POST_DIET(listOf("mfReverse", "rpMass", "trexler2014")),
    UNDERWEIGHT(listOf("rippedbody")),
    NO_ESTIMATE(emptyList()),
    HIGH_BODY_FAT(listOf("rippedbody", "nippardRecomp", "rpFaq", "misra2009", "sbsPratio")),
    GREY_ZONE_NOVICE(listOf("mfRecomp", "nippardRecomp", "rippedbody", "barakat2020")),
    GREY_ZONE_TRAINED(listOf("rpFaq", "rippedbody", "mfRecomp")),
    LEAN_BAND_GAIN(listOf("rpMinicut", "nippardRecomp", "iraki2019")),
    LEAN_BAND_MAINGAIN(listOf("nippardGaintain", "iraki2019")),
    PERFORMANCE(listOf("murphy2022", "helms2023")),
    PREFERENCE(listOf("sbsPratio", "rpMinicut")),
    VERY_LEAN(listOf("rpFaq", "rippedbody", "sbsPratio"))
}

data class PhaseDecision(
    /** Phases the person (or the AI) may choose from, recommended first, all guardrail-allowed. */
    val candidates: List<String>,
    val recommended: String,
    val rule: DecisionRule,
    /** The body-fat range spans more than one decision band. */
    val straddles: Boolean,
    /** Plain-language reason: which rule fired, with the range and its uncertainty. */
    val explanation: String
)

/**
 * Deterministic phase selection from onboarding_science.json "decisionRules". The AI may only
 * ever pick among [PhaseDecision.candidates]; when it is unavailable this decision is the plan.
 */
object PhaseRules {

    /** Body-fat bands. Female values are the explicit figures given alongside the male ones. */
    private enum class Band { VERY_LEAN, LEAN, GREY, HIGH }

    private data class Thresholds(val leanBelow: Double, val greyFrom: Double, val highFrom: Double)

    private fun thresholds(sex: Sex) =
        if (sex == Sex.MALE) Thresholds(leanBelow = 10.0, greyFrom = 15.0, highFrom = 20.0)
        else Thresholds(leanBelow = 18.0, greyFrom = 25.0, highFrom = 30.0)

    /** Misra 2009 consensus: abdominal obesity in Asian Indians at >=90 cm (men) / >=80 cm (women). */
    fun waistRuleFires(sex: Sex, healthWaistCm: Double?, southAsian: Boolean): Boolean {
        if (!southAsian || healthWaistCm == null) return false
        return healthWaistCm >= if (sex == Sex.MALE) 90.0 else 80.0
    }

    private fun band(t: Thresholds, v: Double): Band = when {
        v < t.leanBelow -> Band.VERY_LEAN
        v < t.greyFrom -> Band.LEAN
        v < t.highFrom -> Band.GREY
        else -> Band.HIGH
    }

    fun decide(input: PhaseInputs, allowed: Set<String>): PhaseDecision {
        val raw = rawDecision(input)
        return applyAllowed(raw, allowed)
    }

    private fun rawDecision(i: PhaseInputs): PhaseDecision {
        val bf = i.bodyFat
        val rangeText = bf?.let { "about ${it.label} (${it.method.phrase}, a likely range rather than an exact figure)" }

        // 1. Safety guard: no deficit and no aggressive surplus.
        if (i.safetyHolds.isNotEmpty()) {
            val reasons = i.safetyHolds.joinToString(" and ") { it.reason }
            return PhaseDecision(
                candidates = listOf(Phases.MAINTAIN),
                recommended = Phases.MAINTAIN,
                rule = DecisionRule.SAFETY_HOLD,
                straddles = false,
                explanation = "Because $reasons, Forge holds your calories at maintenance and puts the focus on " +
                    "training and eating well. Please talk to your doctor or another qualified professional " +
                    "before changing your weight on purpose."
            )
        }

        // 2. Just came off a long diet: leave the deficit first.
        if (i.recentlyFinishedDiet) {
            return PhaseDecision(
                candidates = listOf(Phases.MAINTAIN),
                recommended = Phases.MAINTAIN,
                rule = DecisionRule.POST_DIET,
                straddles = false,
                explanation = "You've recently finished a long diet (or it has been wearing you down). Most of the " +
                    "benefit after a diet comes from simply leaving the deficit, so Forge moves you to " +
                    "maintenance for a few weeks before any new phase."
            )
        }

        // 3. Underweight: gain.
        if (i.bmi < 18.5) {
            return PhaseDecision(
                candidates = listOf(Phases.LEAN_GAIN, Phases.MAINGAIN, Phases.MAINTAIN),
                recommended = Phases.LEAN_GAIN,
                rule = DecisionRule.UNDERWEIGHT,
                straddles = false,
                explanation = "Your weight is in the low range for your height, so the evidence-based starting point " +
                    "is a gaining phase. Forge will not set a calorie deficit."
            )
        }

        val waistRule = waistRuleFires(i.sex, i.healthWaistCm, i.southAsian)
        val waistKnownBelow = i.southAsian && i.healthWaistCm != null && !waistRule

        // 4. No body-fat estimate at all.
        if (bf == null) {
            if (waistRule) return highBodyFat(i, null, straddles = false, waistRule = true)
            val candidates = buildList {
                add(Phases.MAINTAIN)
                add(Phases.RECOMP)
                add(Phases.MAINGAIN)
                if (i.preference == PhasePreference.LOSE_FAT) add(Phases.CUT)
                if (i.preference == PhasePreference.BUILD_MUSCLE) add(Phases.LEAN_GAIN)
            }
            return PhaseDecision(
                candidates = candidates,
                recommended = Phases.MAINTAIN,
                rule = DecisionRule.NO_ESTIMATE,
                straddles = false,
                explanation = "Without tape measurements Forge can't place your body fat in a range, so it starts " +
                    "you at maintenance and lets your weight trend, waist and strength guide the next step. " +
                    "Adding waist and neck measurements later unlocks a specific recommendation."
            )
        }

        val t = thresholds(i.sex)
        val lowBand = band(t, bf.low)
        val highBand = band(t, bf.high)
        val straddles = lowBand != highBand

        // 5. Upper bound at or above the high threshold (or the waist cut-off): lose fat first.
        if (highBand == Band.HIGH || waistRule) {
            // Straddle tie-breaker: a waist measured below the health cut-off tips a borderline
            // range toward the grey-zone rule instead of flipping on one estimate.
            if (straddles && waistKnownBelow && lowBand != Band.HIGH) {
                return greyZone(i, rangeText, straddles = true, tieBreak = true)
            }
            return highBodyFat(i, rangeText, straddles, waistRule)
        }

        // 6. Very lean: gain, never cut further.
        if (band(t, bf.mid) == Band.VERY_LEAN) {
            val stayLean = i.preference == PhasePreference.STAY_LEAN
            return PhaseDecision(
                candidates = listOf(Phases.LEAN_GAIN, Phases.MAINGAIN, Phases.MAINTAIN),
                recommended = if (stayLean) Phases.MAINGAIN else Phases.LEAN_GAIN,
                rule = DecisionRule.VERY_LEAN,
                straddles = straddles,
                explanation = "Your estimated body fat is $rangeText, which is already lean. Coaches agree this is the " +
                    "point to build muscle rather than diet further" +
                    (if (stayLean) ", and since you'd like to stay lean, a slow maingain fits." else ".")
            )
        }

        // 7. Grey zone.
        if (band(t, bf.mid) == Band.GREY) return greyZone(i, rangeText, straddles, tieBreak = false)

        // 8. Lean band (~10-15% men / ~18-25% women).
        return leanBand(i, rangeText, straddles, cutAllowed = band(t, bf.low) != Band.VERY_LEAN)
    }

    private fun highBodyFat(i: PhaseInputs, rangeText: String?, straddles: Boolean, waistRule: Boolean): PhaseDecision {
        val recompOk = i.isNoviceOrDetrained
        val prefersRecomp = recompOk && i.preference == PhasePreference.BUILD_MUSCLE
        val threshold = if (i.sex == Sex.MALE) "20%" else "30%"
        val reason = buildString {
            if (rangeText != null) append("Your estimated body fat is $rangeText, reaching about $threshold or more. ")
            if (waistRule) {
                append("Your health-site waist is at or above the cut-off used for South Asian adults ")
                append(if (i.sex == Sex.MALE) "(90 cm). " else "(80 cm). ")
            }
            append("At this level coaches broadly agree on losing fat first - mainly for health and comfort, ")
            append("not because muscle gain would be wasted.")
            if (prefersRecomp) append(" As a newer or returning lifter who'd rather build muscle, a recomp is a reasonable alternative.")
            if (straddles) append(" Your range crosses a threshold, so treat this as a starting point to revisit at your review.")
        }
        return PhaseDecision(
            candidates = buildList {
                add(if (prefersRecomp) Phases.RECOMP else Phases.CUT)
                add(if (prefersRecomp) Phases.CUT else if (recompOk) Phases.RECOMP else Phases.MAINTAIN)
                if (recompOk) add(Phases.MAINTAIN)
            }.distinct(),
            recommended = if (prefersRecomp) Phases.RECOMP else Phases.CUT,
            rule = DecisionRule.HIGH_BODY_FAT,
            straddles = straddles,
            explanation = reason
        )
    }

    private fun greyZone(i: PhaseInputs, rangeText: String?, straddles: Boolean, tieBreak: Boolean): PhaseDecision {
        val prefix = buildString {
            if (rangeText != null) append("Your estimated body fat is $rangeText. ")
            if (tieBreak) append("It sits across a threshold, and your health-site waist is below the cut-off, so Forge reads it as the middle zone. ")
        }
        return if (i.isNoviceOrDetrained) {
            val wantsCut = i.preference == PhasePreference.LOSE_FAT
            PhaseDecision(
                candidates = if (wantsCut) listOf(Phases.CUT, Phases.RECOMP, Phases.MAINTAIN)
                else listOf(Phases.RECOMP, Phases.CUT, Phases.MAINTAIN),
                recommended = if (wantsCut) Phases.CUT else Phases.RECOMP,
                rule = if (wantsCut) DecisionRule.PREFERENCE else DecisionRule.GREY_ZONE_NOVICE,
                straddles = straddles,
                explanation = prefix + "In this middle zone, newer and returning lifters can usually lose fat and build " +
                    "muscle at the same time, so a recomp is the default." +
                    (if (wantsCut) " You said you'd rather lose fat first, which is just as reasonable here." else "")
            )
        } else {
            val wantsGain = i.preference == PhasePreference.BUILD_MUSCLE
            PhaseDecision(
                candidates = buildList {
                    add(Phases.CUT)
                    add(Phases.MAINTAIN)
                    if (wantsGain) add(Phases.LEAN_GAIN)
                },
                recommended = Phases.CUT,
                rule = DecisionRule.GREY_ZONE_TRAINED,
                straddles = straddles,
                explanation = prefix + "In this middle zone it's partly your call, but for experienced lifters the usual " +
                    "advice is to lose fat first - recomposition is slower once you're well trained." +
                    (if (wantsGain) " A lean gain is possible if you'd rather gain now and accept a cut later." else "")
            )
        }
    }

    private fun leanBand(i: PhaseInputs, rangeText: String?, straddles: Boolean, cutAllowed: Boolean): PhaseDecision {
        val prefix = if (rangeText != null) "Your estimated body fat is $rangeText. " else ""
        val advanced = i.trainingAge == TrainingAge.ADVANCED
        val (phase, rule, why) = when {
            i.preference == PhasePreference.LOSE_FAT && cutAllowed -> Triple(
                Phases.CUT, DecisionRule.PREFERENCE,
                "You're already fairly lean, so either direction is reasonable; you asked to lose fat first, so Forge keeps the rate gentle."
            )
            i.preference == PhasePreference.STAY_LEAN || advanced -> Triple(
                Phases.MAINGAIN, DecisionRule.LEAN_BAND_MAINGAIN,
                if (advanced) "You're lean and well trained, so a conservative surplus - a slow maingain - is the better fit."
                else "You're lean and want to stay that way, so a slow maingain trades some speed for staying lean."
            )
            i.goal == TrainingGoal.PERFORMANCE -> Triple(
                Phases.MAINTAIN, DecisionRule.PERFORMANCE,
                "You're focused on performance and already in a healthy range, so Forge avoids a deficit during your performance block."
            )
            i.goal == TrainingGoal.STRENGTH || i.goal == TrainingGoal.LOOK ||
                i.preference == PhasePreference.BUILD_MUSCLE -> Triple(
                Phases.LEAN_GAIN, DecisionRule.LEAN_BAND_GAIN,
                "This is the range where muscle gain typically goes best, so a lean gain with a small surplus is the default."
            )
            else -> Triple(
                Phases.MAINTAIN, DecisionRule.PREFERENCE,
                "You're in a healthy range and your goal is health, so maintenance with consistent training is a solid default."
            )
        }
        return PhaseDecision(
            candidates = buildList {
                add(phase)
                add(Phases.LEAN_GAIN)
                add(Phases.MAINGAIN)
                add(Phases.MAINTAIN)
                if (cutAllowed && i.preference == PhasePreference.LOSE_FAT) add(Phases.CUT)
            }.distinct(),
            recommended = phase,
            rule = rule,
            straddles = straddles,
            explanation = prefix + why
        )
    }

    /**
     * Guardrails have the last word: drop disallowed phases, and if the recommendation itself
     * is disallowed fall back to the first allowed candidate, then to maintenance.
     */
    private fun applyAllowed(d: PhaseDecision, allowed: Set<String>): PhaseDecision {
        val kept = d.candidates.filter { it in allowed }
        if (d.recommended in allowed) return d.copy(candidates = kept)
        val fallback = kept.firstOrNull() ?: Phases.MAINTAIN
        return d.copy(
            candidates = (listOf(fallback) + kept).distinct(),
            recommended = fallback,
            explanation = d.explanation + " Forge's safety rules don't allow a ${PhaseCopy.name(d.recommended).lowercase()} " +
                "for you right now, so it starts with ${PhaseCopy.name(fallback).lowercase()} instead."
        )
    }

    /** "17-27%"-style midpoint text for logs and prompts. */
    fun describe(range: BodyFatRange?): String =
        range?.let { "${it.label} (centre ${it.mid.roundToInt()}%, ${it.method.phrase})" } ?: "no estimate"
}
