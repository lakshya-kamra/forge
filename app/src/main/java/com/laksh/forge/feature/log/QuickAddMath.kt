package com.laksh.forge.feature.log

import kotlin.math.abs
import kotlin.math.roundToInt

/** Why a quick-add form can't be saved yet. Shown under the offending field, never as a dialog. */
enum class QuickAddProblem(val message: String) {
    NAME_MISSING("Give it a name so you can recognise it later."),
    KCAL_MISSING("Enter the calories, even a rough number."),
    KCAL_TOO_HIGH("That's over ${QuickAddMath.MAX_KCAL} kcal for one entry - check for a typo.")
}

/**
 * The typed kcal and the kcal the macros imply, when they disagree by more than
 * [QuickAddMath.MISMATCH_TOLERANCE]. Purely advisory: the typed kcal is what gets saved.
 */
data class MacroMismatch(val typedKcal: Double, val impliedKcal: Double) {
    /** Signed: positive when the macros add up to more than the typed kcal. */
    val deviation: Double get() = (impliedKcal - typedKcal) / typedKcal
    val impliedRounded: Int get() = impliedKcal.roundToInt()
}

/**
 * Pure maths behind Quick add, kept free of Android types so it runs as a plain JVM test.
 *
 * The consistency check uses the Atwater general factors (4 kcal/g protein and carbohydrate,
 * 9 kcal/g fat). Labels and home estimates drift from these through rounding, fibre (which
 * yields closer to 2 kcal/g) and alcohol (7 kcal/g), so the tolerance is deliberately loose
 * and the result is only ever a hint - a person who knows their burrito is 800 kcal should
 * not be argued with.
 */
object QuickAddMath {
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARB = 4.0
    const val KCAL_PER_G_FAT = 9.0

    /** ~25%: wide enough to absorb label rounding, narrow enough to catch a swapped field. */
    const val MISMATCH_TOLERANCE = 0.25

    /** A single entry above this is almost always an extra zero, and it would skew adaptive TDEE. */
    const val MAX_KCAL = 10_000

    /**
     * Reads a number typed on a phone keyboard. Accepts a decimal comma (many locales' numeric
     * keypads only offer one). Blank, malformed, negative or non-finite input is null, which
     * callers treat as "not given".
     */
    fun parse(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull() ?: return null
        return if (v.isFinite() && v >= 0.0) v else null
    }

    /** Keeps only what a decimal field can hold, so a pasted "800 kcal" becomes "800". */
    fun sanitize(input: String): String {
        val out = StringBuilder()
        var seenSeparator = false
        for (c in input) {
            when {
                c.isDigit() -> out.append(c)
                (c == '.' || c == ',') && !seenSeparator -> { out.append('.'); seenSeparator = true }
            }
        }
        return out.toString().take(7)
    }

    /**
     * Kcal never needs decimals, so its field keeps digits only. A ',' or '.' there is far more
     * likely a thousands separator ("1,250") than a decimal point; treating it as one would
     * save ~1 kcal instead of 1,250. A separator followed by exactly three digits is grouping and
     * is dropped; any other separator starts a fraction, which is cut off ("800.5" -> "800").
     */
    fun sanitizeKcal(input: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c.isDigit()) {
                out.append(c)
            } else if (c == ',' || c == '.') {
                val grouping = out.isNotEmpty() &&
                    (1..3).all { k -> input.getOrNull(i + k)?.isDigit() == true } &&
                    input.getOrNull(i + 4)?.isDigit() != true
                if (!grouping) break
            }
            i++
        }
        return out.toString().take(5)
    }

    fun impliedKcal(proteinG: Double, carbsG: Double, fatG: Double): Double =
        proteinG * KCAL_PER_G_PROTEIN + carbsG * KCAL_PER_G_CARB + fatG * KCAL_PER_G_FAT

    /**
     * Null unless all three macros were given and they imply calories more than the tolerance
     * away from [kcal]. With only some macros typed the rest are unknown, not zero, so no
     * comparison is meaningful.
     */
    fun mismatch(kcal: Double?, proteinG: Double?, carbsG: Double?, fatG: Double?): MacroMismatch? {
        if (kcal == null || kcal <= 0.0) return null
        if (proteinG == null || carbsG == null || fatG == null) return null
        val implied = impliedKcal(proteinG, carbsG, fatG)
        val m = MacroMismatch(typedKcal = kcal, impliedKcal = implied)
        return if (abs(m.deviation) > MISMATCH_TOLERANCE) m else null
    }

    /** String-level convenience for the form, which holds raw text. */
    fun mismatch(kcal: String, protein: String, carbs: String, fat: String): MacroMismatch? =
        mismatch(parse(kcal), parse(protein), parse(carbs), parse(fat))

    fun validateName(name: String): QuickAddProblem? =
        if (name.isBlank()) QuickAddProblem.NAME_MISSING else null

    fun validateKcal(kcal: String): QuickAddProblem? {
        val v = parse(kcal)
        return when {
            v == null || v <= 0.0 -> QuickAddProblem.KCAL_MISSING
            v > MAX_KCAL -> QuickAddProblem.KCAL_TOO_HIGH
            else -> null
        }
    }
}
