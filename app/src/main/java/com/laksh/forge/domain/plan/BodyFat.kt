package com.laksh.forge.domain.plan

import com.laksh.forge.data.prefs.Sex
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where a body-fat range came from. [dbValue] matches BodyMeasurement.bodyFatMethod. */
enum class BodyFatMethod(val label: String, val dbValue: String) {
    NAVY("Tape measure (US Navy formula)", "NAVY"),
    VISUAL_AI("AI photo estimate - unvalidated", "VISUAL_AI"),
    /** Never written to the database; measurements store the raw tape result as NAVY. */
    COMBINED("Tape and photos combined", "COMBINED")
}

/**
 * A body-fat estimate expressed the only honest way: a likely range plus its method.
 * Every method carries roughly +/-4-5 points of individual error, so a single number would
 * imply precision that does not exist (onboarding_science.json conventions.bodyFat).
 */
data class BodyFatRange(
    val low: Double,
    val high: Double,
    val mid: Double,
    val method: BodyFatMethod
) {
    fun overlaps(other: BodyFatRange): Boolean = low <= other.high && other.low <= high

    /** Rounded outward to whole percentages so the shown range never looks narrower than it is. */
    val label: String get() = "${floor(low).toInt()}-${ceil(high).toInt()}%"
}

/**
 * US Navy circumference body-fat equations (Hodgdon & Beckett 1984).
 *
 * The inch forms are used on cm/2.54 inputs because only they reproduce every cell of the
 * official 2016 Navy BCA tables; the widely quoted cm forms drift by up to 0.72 points
 * (onboarding_science.json navyFormula.recommendedImplementation). All logs are base 10.
 */
object NavyBodyFat {

    private const val CM_PER_INCH = 2.54

    /**
     * Results outside this window come from a mis-typed or mis-placed tape, not a body. It is a
     * sanity check on input, not a physiological claim.
     */
    private val PLAUSIBLE = 2.0..70.0

    /** Male: waist is the abdomen at the navel. */
    fun male(waistNavelCm: Double, neckCm: Double, heightCm: Double): Double? {
        val abdomenMinusNeck = (waistNavelCm - neckCm) / CM_PER_INCH
        val heightIn = heightCm / CM_PER_INCH
        if (abdomenMinusNeck <= 0.0 || heightIn <= 0.0) return null
        val bf = 86.010 * log10(abdomenMinusNeck) - 70.041 * log10(heightIn) + 36.76
        return bf.takeIf { it.isFinite() && it in PLAUSIBLE }
    }

    /** Female: waist is the natural waist (narrowest point); the variable is waist + hips - neck. */
    fun female(waistNarrowestCm: Double, hipsCm: Double, neckCm: Double, heightCm: Double): Double? {
        val circumference = (waistNarrowestCm + hipsCm - neckCm) / CM_PER_INCH
        val heightIn = heightCm / CM_PER_INCH
        if (circumference <= 0.0 || heightIn <= 0.0) return null
        val bf = 163.205 * log10(circumference) - 97.684 * log10(heightIn) - 78.387
        return bf.takeIf { it.isFinite() && it in PLAUSIBLE }
    }

    /** Null when a required site is missing or the result is implausible. */
    fun estimate(sex: Sex, waistCm: Double?, neckCm: Double?, hipsCm: Double?, heightCm: Double): Double? {
        if (waistCm == null || neckCm == null || heightCm <= 0.0) return null
        return when (sex) {
            Sex.MALE -> male(waistCm, neckCm, heightCm)
            Sex.FEMALE -> hipsCm?.let { female(waistCm, it, neckCm, heightCm) }
        }
    }

    /**
     * Turn a raw Navy value into Forge's likely range: men +2.5 (bias correction) +/-5 points,
     * women -2 +/-5 points (onboarding_science.json navyFormula.forgeUse, from the Potter 2022
     * bias/SD vs DXA). It covers roughly 70-82% of people, so it is a likely range, not a 95% CI.
     */
    fun range(sex: Sex, raw: Double): BodyFatRange {
        val corrected = if (sex == Sex.MALE) raw + 2.5 else raw - 2.0
        return BodyFatRange(
            low = max(corrected - 5.0, 1.0),
            high = corrected + 5.0,
            mid = corrected,
            method = BodyFatMethod.NAVY
        )
    }
}

/** The body-fat picture after combining every available source, kept transparent. */
data class CombinedBodyFat(
    val navy: BodyFatRange?,
    val photo: BodyFatRange?,
    /** Best single range, or null when there is no estimate at all. */
    val combined: BodyFatRange?,
    /** True when the tape and photo ranges do not overlap - both are shown, neither is trusted. */
    val disagreement: Boolean
) {
    /**
     * The range the phase rules reason with. When the methods disagree, the union is used so
     * the decision falls into the straddle rule instead of averaging two conflicting signals.
     */
    val forDecision: BodyFatRange?
        get() = when {
            disagreement && navy != null && photo != null -> BodyFatRange(
                low = min(navy.low, photo.low),
                high = max(navy.high, photo.high),
                mid = (navy.mid + photo.mid) / 2.0,
                method = BodyFatMethod.COMBINED
            )
            else -> combined
        }
}

object BodyFatCombiner {

    /**
     * General-purpose LLM photo estimates have no peer-reviewed validation against DXA, so they
     * get the wider +/-7 point range (onboarding_science.json visualEstimation.combineHonestly).
     */
    const val LLM_PHOTO_HALF_WIDTH = 7.0

    fun photoRange(estimatePct: Double, modelLow: Double?, modelHigh: Double?): BodyFatRange {
        // Never narrower than +/-7, but widened further if the model itself was less sure.
        val low = min(estimatePct - LLM_PHOTO_HALF_WIDTH, modelLow ?: Double.MAX_VALUE)
        val high = max(estimatePct + LLM_PHOTO_HALF_WIDTH, modelHigh ?: -Double.MAX_VALUE)
        return BodyFatRange(max(low, 1.0), high, estimatePct, BodyFatMethod.VISUAL_AI)
    }

    /**
     * Inverse-variance weighting with each half-width as the spread proxy (a Forge design
     * choice from the research). Non-overlapping ranges are flagged rather than averaged.
     */
    fun combine(navy: BodyFatRange?, photo: BodyFatRange?): CombinedBodyFat {
        if (navy == null || photo == null) {
            return CombinedBodyFat(navy, photo, navy ?: photo, disagreement = false)
        }
        if (!navy.overlaps(photo)) {
            return CombinedBodyFat(navy, photo, combined = null, disagreement = true)
        }
        val s1 = (navy.high - navy.low) / 2.0
        val s2 = (photo.high - photo.low) / 2.0
        val w1 = 1.0 / (s1 * s1)
        val w2 = 1.0 / (s2 * s2)
        val mid = (navy.mid * w1 + photo.mid * w2) / (w1 + w2)
        val half = sqrt(1.0 / (w1 + w2))
        val combined = BodyFatRange(max(mid - half, 1.0), mid + half, mid, BodyFatMethod.COMBINED)
        return CombinedBodyFat(navy, photo, combined, disagreement = false)
    }
}
