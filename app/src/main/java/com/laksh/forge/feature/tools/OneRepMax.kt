package com.laksh.forge.feature.tools

/** Estimated-1RM formulas. Both are the published closed forms, nothing fitted by Forge. */
enum class E1rmFormula(val label: String) {
    /** Epley: 1RM = w * (1 + reps / 30). */
    EPLEY("Epley"),

    /** Brzycki: 1RM = w * 36 / (37 - reps). */
    BRZYCKI("Brzycki")
}

data class OneRmEstimate(
    val weightKg: Double,
    val reps: Int,
    val rpe: Double,
    /** Reps the set could have gone to: reps + reps in reserve. */
    val repsToFailure: Double,
    val epleyKg: Double,
    val brzyckiKg: Double
) {
    fun of(f: E1rmFormula): Double = when (f) {
        E1rmFormula.EPLEY -> epleyKg
        E1rmFormula.BRZYCKI -> brzyckiKg
    }
}

data class PercentRow(val percent: Int, val rawKg: Double, val loadKg: Double)

/** RPE table: rows are RPE values, columns rep counts, cells a fraction of 1RM. */
data class RpeTable(
    val formula: E1rmFormula,
    val reps: List<Int>,
    val rpes: List<Double>,
    /** cells[rpeIndex][repIndex] = fraction of 1RM. */
    val cells: List<List<Double>>
)

/**
 * e1RM maths. RPE is folded in through the RIR-based RPE scale (Zourdos et al. 2016, cited in
 * docs/research/feature_ideas.json): RPE 10 means no reps left in reserve and each RPE point
 * below that is one more rep in reserve, so a set of 5 at RPE 8 counts as 7 reps to failure.
 * The RPE table is derived from the chosen formula the same way - no third-party chart.
 */
object OneRepMax {

    val TABLE_REPS: List<Int> = (1..10).toList()
    val TABLE_RPES: List<Double> = listOf(10.0, 9.5, 9.0, 8.5, 8.0, 7.5, 7.0, 6.5, 6.0)
    val RPE_CHOICES: List<Double> = TABLE_RPES

    /** Brzycki's denominator hits zero at 37 reps; the UI never offers anywhere near that. */
    private const val MAX_REPS_TO_FAILURE = 30.0

    /**
     * 1RM divided by the weight lifted, for a set taken [repsToFailure] reps from failure.
     * A true single (one rep to failure) is the 1RM itself under both formulas.
     */
    fun factor(repsToFailure: Double, formula: E1rmFormula): Double {
        val n = repsToFailure.coerceIn(1.0, MAX_REPS_TO_FAILURE)
        if (n <= 1.0) return 1.0
        return when (formula) {
            E1rmFormula.EPLEY -> 1.0 + n / 30.0
            E1rmFormula.BRZYCKI -> 36.0 / (37.0 - n)
        }
    }

    fun repsInReserve(rpe: Double): Double = (10.0 - rpe).coerceAtLeast(0.0)

    fun estimate(weightKg: Double, reps: Int, rpe: Double = 10.0): OneRmEstimate {
        val toFailure = reps + repsInReserve(rpe)
        return OneRmEstimate(
            weightKg = weightKg,
            reps = reps,
            rpe = rpe,
            repsToFailure = toFailure,
            epleyKg = weightKg * factor(toFailure, E1rmFormula.EPLEY),
            brzyckiKg = weightKg * factor(toFailure, E1rmFormula.BRZYCKI)
        )
    }

    /** Fraction of 1RM that [reps] at [rpe] corresponds to. */
    fun fractionOf1rm(reps: Int, rpe: Double, formula: E1rmFormula): Double =
        1.0 / factor(reps + repsInReserve(rpe), formula)

    fun rpeTable(formula: E1rmFormula): RpeTable = RpeTable(
        formula = formula,
        reps = TABLE_REPS,
        rpes = TABLE_RPES,
        cells = TABLE_RPES.map { rpe -> TABLE_REPS.map { r -> fractionOf1rm(r, rpe, formula) } }
    )

    /** 50-100 % of [oneRmKg] in 5 % steps, each rounded to the nearest load the plates can build. */
    fun percentTable(oneRmKg: Double, profile: GymProfile): List<PercentRow> =
        (100 downTo 50 step 5).map { p ->
            val raw = oneRmKg * p / 100.0
            PercentRow(p, raw, PlateMath.roundNearest(raw, profile))
        }
}
