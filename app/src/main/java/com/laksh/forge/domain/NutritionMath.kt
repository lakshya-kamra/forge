package com.laksh.forge.domain

import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.Sex
import kotlin.math.abs
import kotlin.math.roundToInt

data class MacroTargets(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    /** Where the calorie figure came from, shown to the user so it never feels arbitrary. */
    val basis: String
)

data class TrendPoint(val timestamp: Long, val raw: Double, val smoothed: Double)

data class WeightTrend(
    val points: List<TrendPoint>,
    /** kg per week, from least-squares fit on the smoothed series. Positive = gaining. */
    val kgPerWeek: Double,
    val latestSmoothed: Double?,
    val latestRaw: Double?,
    val changeOverWindow: Double?
)

object NutritionMath {

    /** Roughly the energy in a kg of body mass change; the standard 7700 kcal figure. */
    const val KCAL_PER_KG = 7700.0

    /** Mifflin-St Jeor: the best-validated predictive equation for non-obese adults. */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * age
        return if (sex == Sex.MALE) base + 5 else base - 161
    }

    fun predictedTdee(settings: ForgeSettings, weightKg: Double): Double =
        bmr(settings.sex, weightKg, settings.heightCm, settings.age) * settings.activityFactor

    /**
     * Energy balance solved backwards from reality: whatever you actually ate, plus whatever
     * the scale actually did, tells you what maintenance really is. Far more accurate than any
     * predictive equation once there is a few weeks of honest data.
     *
     * Returns null until there is enough signal to be worth trusting.
     */
    fun adaptiveTdee(
        weights: List<WeightEntry>,
        dayTotals: List<DayTotals>,
        minDays: Int = 14
    ): Double? {
        if (weights.size < 4 || dayTotals.size < minDays) return null

        val sorted = weights.sortedBy { it.timestamp }
        val smoothed = smooth(sorted)
        val first = smoothed.firstOrNull() ?: return null
        val last = smoothed.lastOrNull() ?: return null

        val spanDays = (last.timestamp - first.timestamp) / 86_400_000.0
        if (spanDays < 10) return null

        // Only count intake days inside the weigh-in window, and require decent coverage.
        val fromMs = first.timestamp
        val toMs = last.timestamp
        val logged = dayTotals.filter { it.kcal > 400 && dateInRange(it.localDate, fromMs, toMs) }
        if (logged.size < minDays) return null
        if (logged.size < spanDays * 0.6) return null   // too many gaps to trust

        val avgIntake = logged.sumOf { it.kcal } / logged.size
        val deltaKg = last.smoothed - first.smoothed
        val tdee = avgIntake - (deltaKg * KCAL_PER_KG / spanDays)

        // Guard against nonsense from a bad log or a water-weight swing.
        return tdee.takeIf { it in 1000.0..6000.0 }
    }

    /**
     * Exponentially weighted trend. Daily scale readings are dominated by water, gut
     * content and sodium; the smoothed line is the thing worth reacting to.
     */
    fun smooth(sorted: List<WeightEntry>, alpha: Double = 0.25): List<TrendPoint> {
        if (sorted.isEmpty()) return emptyList()
        var ewma = sorted.first().kg
        return sorted.map { e ->
            ewma += alpha * (e.kg - ewma)
            TrendPoint(e.timestamp, e.kg, ewma)
        }
    }

    fun trend(weights: List<WeightEntry>): WeightTrend {
        val sorted = weights.sortedBy { it.timestamp }
        val pts = smooth(sorted)
        if (pts.size < 2) {
            return WeightTrend(pts, 0.0, pts.lastOrNull()?.smoothed, pts.lastOrNull()?.raw, null)
        }

        // Least squares on (days, smoothed kg).
        val t0 = pts.first().timestamp
        val xs = pts.map { (it.timestamp - t0) / 86_400_000.0 }
        val ys = pts.map { it.smoothed }
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        val slopePerDay = if (den == 0.0) 0.0 else num / den

        return WeightTrend(
            points = pts,
            kgPerWeek = slopePerDay * 7,
            latestSmoothed = pts.last().smoothed,
            latestRaw = pts.last().raw,
            changeOverWindow = pts.last().smoothed - pts.first().smoothed
        )
    }

    /** Recommended weekly rate for a goal, as a fraction of bodyweight per week. */
    fun defaultRate(goal: GoalMode, weightKg: Double): Double = when (goal) {
        // ~0.7%/wk keeps lean mass loss low; faster cuts cost muscle.
        GoalMode.CUT -> -(weightKg * 0.007)
        // Deliberately slow upward drift - the point is to stay lean indefinitely.
        GoalMode.MAINGAIN -> weightKg * 0.0008
        // ~0.25%/wk is about the ceiling for gaining mostly lean tissue in a trained lifter.
        GoalMode.LEAN_GAIN -> weightKg * 0.0025
        GoalMode.MAINTAIN -> 0.0
        GoalMode.RECOMP -> 0.0
    }

    fun targets(settings: ForgeSettings, weightKg: Double, adaptiveTdee: Double?): MacroTargets {
        if (!settings.useAutoTargets) {
            return macrosFor(settings.manualKcal.toDouble(), weightKg, settings, "Set manually")
        }

        val tdee: Double
        val basisPrefix: String
        if (adaptiveTdee != null && adaptiveTdee > 0) {
            tdee = adaptiveTdee
            basisPrefix = "Learned from your data"
        } else {
            tdee = predictedTdee(settings, weightKg)
            basisPrefix = "Estimated (Mifflin-St Jeor)"
        }

        val rate = if (settings.rateKgPerWeek != 0.0) settings.rateKgPerWeek
        else defaultRate(settings.goal, weightKg)

        val dailyAdjust = rate * KCAL_PER_KG / 7.0

        // Never prescribe a dangerously low intake, whatever the arithmetic says.
        val floor = if (settings.sex == Sex.MALE) 1500.0 else 1200.0
        val kcal = (tdee + dailyAdjust).coerceAtLeast(floor)

        val sign = if (dailyAdjust >= 0) "+" else ""
        val basis = "$basisPrefix - TDEE ${tdee.roundToInt()} $sign${dailyAdjust.roundToInt()}"
        return macrosFor(kcal, weightKg, settings, basis)
    }

    private fun macrosFor(kcal: Double, weightKg: Double, s: ForgeSettings, basis: String): MacroTargets {
        // Protein is set first and protected: it is the macro that preserves lean mass in a
        // deficit and the one most often under-eaten.
        val protein = (s.proteinPerKg * weightKg).roundToInt()
        val fat = (s.fatPerKg * weightKg).roundToInt()
        val remaining = kcal - protein * 4 - fat * 9
        val carbs = (remaining / 4).coerceAtLeast(0.0).roundToInt()
        // ~14g per 1000 kcal is the usual adequacy reference point.
        val fiber = (kcal / 1000.0 * 14).roundToInt()
        return MacroTargets(kcal.roundToInt(), protein, carbs, fat, fiber, basis)
    }

    /**
     * Is the scale actually moving the way the goal intends? Used for the nudge on Trends.
     * Returns null while there is not enough data to say anything honest.
     */
    fun goalFeedback(goal: GoalMode, trend: WeightTrend, weightKg: Double): String? {
        if (trend.points.size < 8) return null
        val actual = trend.kgPerWeek
        val intended = defaultRate(goal, weightKg)

        return when (goal) {
            GoalMode.CUT -> when {
                actual > 0.05 -> "You're gaining, not cutting. Intake is likely above maintenance."
                actual > intended * 0.4 -> "Losing, but slower than target. Consider trimming ~150 kcal."
                actual < intended * 1.8 -> "Dropping fast. That pace risks lean mass - consider eating more."
                else -> "On track. Losing about ${fmt(abs(actual))} kg/week."
            }
            GoalMode.LEAN_GAIN -> when {
                actual < 0 -> "You're losing weight on a gain phase. Add ~200 kcal."
                actual < intended * 0.4 -> "Gaining slower than target. Add ~150 kcal."
                actual > intended * 2.0 -> "Gaining fast - more of this will be fat than you want."
                else -> "On track. Gaining about ${fmt(actual)} kg/week."
            }
            GoalMode.MAINGAIN -> when {
                abs(actual) < 0.12 -> "Holding steady with a slight upward drift. Exactly right."
                actual > 0.25 -> "Drifting up faster than a maingain. Ease off ~150 kcal."
                actual < -0.15 -> "Trending down. Add ~150 kcal if you meant to hold."
                else -> "Close enough to flat. Keep going."
            }
            GoalMode.MAINTAIN, GoalMode.RECOMP -> when {
                abs(actual) < 0.15 -> "Bodyweight is stable. Watch strength numbers for progress."
                actual > 0 -> "Drifting up ${fmt(actual)} kg/week."
                else -> "Drifting down ${fmt(abs(actual))} kg/week."
            }
        }
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)

    private fun dateInRange(localDate: String, fromMs: Long, toMs: Long): Boolean {
        val d = runCatching { java.time.LocalDate.parse(localDate) }.getOrNull() ?: return false
        val ms = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ms in (fromMs - 86_400_000)..(toMs + 86_400_000)
    }

    fun kgToDisplay(kg: Double, unit: com.laksh.forge.data.prefs.WeightUnit): Double =
        if (unit == com.laksh.forge.data.prefs.WeightUnit.LB) kg * 2.2046226 else kg

    fun displayToKg(v: Double, unit: com.laksh.forge.data.prefs.WeightUnit): Double =
        if (unit == com.laksh.forge.data.prefs.WeightUnit.LB) v / 2.2046226 else v
}
