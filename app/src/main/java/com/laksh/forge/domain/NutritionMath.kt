package com.laksh.forge.domain

import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Severity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
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

/** Targets plus the guardrail reasoning behind them, for screens that explain adjustments. */
data class CheckedTargets(
    val targets: MacroTargets,
    val rateKgPerWeek: Double,
    val flags: List<GuardrailFlag>
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
     * Exponentially weighted trend. Daily scale readings move with water and food; the
     * smoothed line is the thing worth reacting to.
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

    /** Recommended weekly rate for a goal, in kg per week, when the training stage is unknown. */
    fun defaultRate(goal: GoalMode, weightKg: Double): Double =
        defaultRate(goal, weightKg, TrainingAge.UNKNOWN)

    /**
     * Recommended weekly rate for a goal and training stage, in kg per week.
     *
     * - Cut: 0.7%/wk, the tier the research uses when body fat is unknown and the rate
     *   Garthe 2011 recommend; Guardrails lowers it for lean people and caps the deficit.
     * - Lean gain: the midpoint of the monthly band for the training stage (Pyramid ranges,
     *   nutrition_surplus_rate_by_training_age), converted to a weekly figure.
     * - Maingain: a deliberately slow drift (~0.08%/wk, within the onboarding research's
     *   <= 0.25%/wk), never above the stage's gain cap.
     */
    fun defaultRate(goal: GoalMode, weightKg: Double, trainingAge: TrainingAge): Double = when (goal) {
        GoalMode.CUT -> -(weightKg * 0.007)
        GoalMode.LEAN_GAIN -> {
            val band = Guardrails.gainBand(trainingAge)
            Guardrails.monthlyPctToKgPerWeek(band.midPctPerMonth, weightKg)
        }
        GoalMode.MAINGAIN -> {
            val cap = Guardrails.monthlyPctToKgPerWeek(Guardrails.gainBand(trainingAge).maxPctPerMonth, weightKg)
            min(weightKg * 0.0008, cap)
        }
        GoalMode.MAINTAIN -> 0.0
        GoalMode.RECOMP -> 0.0
    }

    fun targets(settings: ForgeSettings, weightKg: Double, adaptiveTdee: Double?): MacroTargets =
        checkedTargets(settings, weightKg, adaptiveTdee).targets

    /**
     * Daily targets after the guardrails, with the flags that explain any change. Every
     * calorie and macro figure the app shows passes through Guardrails.checkNutrition.
     */
    fun checkedTargets(
        settings: ForgeSettings,
        weightKg: Double,
        adaptiveTdee: Double?,
        bodyFatPct: Double? = null,
        bodyFatMethod: String? = null
    ): CheckedTargets {
        val profile = Guardrails.profileFrom(settings, weightKg, bodyFatPct, bodyFatMethod)

        val tdee: Double
        val basisPrefix: String
        if (adaptiveTdee != null && adaptiveTdee > 0) {
            tdee = adaptiveTdee
            basisPrefix = "Learned from your data"
        } else {
            tdee = predictedTdee(settings, weightKg)
            basisPrefix = "Estimated (Mifflin-St Jeor)"
        }

        val proposedKcal: Double
        val rate: Double
        if (!settings.useAutoTargets) {
            proposedKcal = settings.manualKcal.toDouble()
            // A manual target implies a rate; the guardrails judge the deficit it creates.
            rate = (proposedKcal - tdee) * 7.0 / KCAL_PER_KG
        } else {
            rate = if (settings.rateKgPerWeek != 0.0) settings.rateKgPerWeek
            else defaultRate(settings.goal, weightKg, settings.trainingAge)
            proposedKcal = tdee + rate * KCAL_PER_KG / 7.0
        }

        // Protein is set first and protected: it is the macro that preserves lean mass in a
        // deficit and the one most often under-eaten. The guardrails clamp it into the
        // evidence range for the phase.
        val protein = (settings.proteinPerKg * weightKg).roundToInt()
        val fat = (settings.fatPerKg * weightKg).roundToInt()
        val carbs = ((proposedKcal - protein * 4 - fat * 9) / 4).coerceAtLeast(0.0).roundToInt()

        val check = Guardrails.checkNutrition(
            p = profile,
            kcal = proposedKcal.roundToInt(),
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            rateKgPerWeek = rate,
            tdee = tdee
        )

        val adjusted = if (check.wasAdjusted) " (within Forge's safety limits)" else ""
        val basis = if (!settings.useAutoTargets) {
            "Set manually$adjusted"
        } else {
            val delta = check.kcal - tdee
            val sign = if (delta >= 0) "+" else ""
            "$basisPrefix - TDEE ${tdee.roundToInt()} $sign${delta.roundToInt()}$adjusted"
        }

        return CheckedTargets(
            targets = MacroTargets(
                kcal = check.kcal,
                proteinG = check.proteinG,
                carbsG = check.carbsG,
                fatG = check.fatG,
                // WHO 2023: at least 25 g of fibre a day for adults (nutrition_fibre_minimum).
                fiberG = Guardrails.MIN_FIBRE_G,
                basis = basis
            ),
            rateKgPerWeek = check.rateKgPerWeek,
            flags = check.flags
        )
    }

    /**
     * Is the scale actually moving the way the goal intends? Used for the nudge on Trends.
     * Returns null while there is not enough data to say anything honest.
     *
     * Wording follows the guardrail messaging rules: no praise for fast drops, adjustments
     * framed as small changes (no more than 10% of the target, then two weeks to judge).
     */
    fun goalFeedback(goal: GoalMode, trend: WeightTrend, weightKg: Double): String? {
        if (trend.points.size < 8 || weightKg <= 0) return null
        val actual = trend.kgPerWeek
        val pctPerWeek = actual / weightKg * 100.0
        val intended = defaultRate(goal, weightKg)

        // Rapid loss gets a supportive check-in whatever the goal
        // (safety_low_intake_rapid_loss_checkin uses 1.5% of bodyweight per week).
        if (pctPerWeek < -1.5) {
            return "Your trend is dropping more than 1.5% of bodyweight a week, faster than Forge ever plans for. Eating a bit more protects muscle and energy. If eating has felt stressful lately, it's worth talking to someone."
        }

        return when (goal) {
            GoalMode.CUT -> when {
                actual > 0.05 -> "Your trend is going up, not down, so intake is likely above maintenance. If it holds for two weeks, a small trim (no more than 10% of your target) is the next step."
                pctPerWeek < -Guardrails.ABSOLUTE_MAX_LOSS_PCT_PER_WEEK -> "Losing faster than 1% of bodyweight a week, the upper limit Forge plans for. Eating a little more keeps more muscle and strength."
                actual > intended * 0.4 -> "Losing more slowly than planned. If it holds for two weeks, a small trim (no more than 10% of your target) is the next step."
                else -> "Trend is down about ${fmt(abs(actual))} kg/week, in line with the plan."
            }
            GoalMode.LEAN_GAIN -> {
                val maxWeekly = Guardrails.monthlyPctToKgPerWeek(
                    Guardrails.gainBand(TrainingAge.UNKNOWN).maxPctPerMonth, weightKg
                )
                when {
                    actual < 0 -> "Your trend is going down on a gaining phase. If it holds for two weeks, a small increase (no more than 10% of your target) is the next step."
                    actual < intended * 0.4 -> "Gaining more slowly than planned. Give it two weeks, then consider a small increase."
                    // The rule reduces intake once the observed rate passes the band maximum.
                    actual > maxWeekly ->"Gaining faster than about 1% of bodyweight a month. Faster than that mostly adds fat, not extra muscle, so a small trim helps."
                    else -> "Trend is up about ${fmt(actual)} kg/week, in line with the plan."
                }
            }
            GoalMode.MAINGAIN -> when {
                abs(actual) < 0.12 -> "Holding steady with a slight upward drift. That's what a maingain looks like."
                actual > 0.25 -> "Drifting up faster than a maingain. A small trim (no more than 10% of your target) keeps it slow."
                actual < -0.15 -> "Trending down. If you meant to hold or drift up, a small increase helps."
                else -> "Close enough to flat. Keep going."
            }
            GoalMode.MAINTAIN, GoalMode.RECOMP -> when {
                abs(actual) < 0.15 -> "Bodyweight is stable. Watch strength numbers and waist for progress."
                actual > 0 -> "Drifting up ${fmt(actual)} kg/week."
                else -> "Drifting down ${fmt(abs(actual))} kg/week."
            }
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun dateInRange(localDate: String, fromMs: Long, toMs: Long): Boolean {
        val d = runCatching { java.time.LocalDate.parse(localDate) }.getOrNull() ?: return false
        val ms = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ms in (fromMs - 86_400_000)..(toMs + 86_400_000)
    }

    fun kgToDisplay(kg: Double, unit: com.laksh.forge.data.prefs.WeightUnit): Double =
        if (unit == com.laksh.forge.data.prefs.WeightUnit.LB) kg * 2.2046226 else kg

    fun displayToKg(v: Double, unit: com.laksh.forge.data.prefs.WeightUnit): Double =
        if (unit == com.laksh.forge.data.prefs.WeightUnit.LB) v / 2.2046226 else v

    /** True when a flag list changed anything (used for "adjusted" badges). */
    fun anyAdjustment(flags: List<GuardrailFlag>): Boolean = flags.any { it.severity != Severity.INFO }
}
