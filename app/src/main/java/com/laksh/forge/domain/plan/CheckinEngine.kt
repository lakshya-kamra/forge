package com.laksh.forge.domain.plan

import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Phases
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/** What happened over the last week (and fortnight, for the adjustment rule). */
data class CheckinData(
    val weekStart: String,
    /** 7-day average weight, the figure Forge reacts to instead of single weigh-ins. */
    val avgWeightKg: Double?,
    val prevAvgWeightKg: Double?,
    val weightChangeKg: Double?,
    /** Least-squares slope of the smoothed trend over 14 days. */
    val trendKgPerWeek: Double?,
    val weighIns14: Int,
    val loggedDays7: Int,
    val loggedDays14: Int,
    val avgKcal7: Double?,
    val avgSleepMinutes: Double?,
    val avgSteps: Double?
) {
    val adherence7: Double get() = loggedDays7 / 7.0
    val adherence14: Double get() = loggedDays14 / 14.0
}

enum class AdjustKind { ON_TRACK, INCREASE, DECREASE, STEPS_FIRST, HOLD_TOO_SOON, HOLD_FEW_WEIGH_INS, HOLD_LOW_LOGGING, END_CUT }

data class CheckinProposal(
    val kind: AdjustKind,
    /** Change to daily calories (0 when holding). Guardrails are applied by the caller. */
    val kcalDelta: Int,
    val stepsDelta: Int,
    /** Calm, plain-language explanation shown to the person. */
    val message: String,
    /** Extra prompts (diet-break offer, fast-loss check-in) shown under the proposal. */
    val notes: List<String>
)

/**
 * The weekly check-in rule. Combines planContents "adjustmentRules" (outside the band with
 * good adherence: change calories ~5-10%, on a cut add steps first) with guardrails
 * nutrition_adjustment_cadence (7-day averages, at most one change every 14 days, never more
 * than 10% of the current target, hold with fewer than 5 weigh-ins in the window).
 */
object CheckinEngine {

    const val MIN_DAYS_BETWEEN_CHANGES = 14
    const val MIN_WEIGH_INS = 5
    const val MAX_CHANGE_FRACTION = 0.10

    /**
     * Same coverage bar NutritionMath.adaptiveTdee uses (60% of days logged): below it the
     * intake data is too patchy to say whether the plan or the logging is off.
     */
    const val MIN_LOGGING = 0.6

    /** A day counts as logged above 400 kcal, matching the rest of the app. */
    private const val LOGGED_DAY_KCAL = 400.0

    /** ISO week start (Monday) for [date], the key stored in settings.lastCheckinWeek. */
    fun weekStart(date: LocalDate): String =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    /**
     * 7-day-average weight change in % of bodyweight per week for the last two weeks, oldest
     * first, from the last 21 days of weigh-ins. A week without weigh-ins leaves its change out.
     */
    fun weeklyChangePct(today: LocalDate, weights: List<WeightEntry>): List<Double> {
        fun avg(fromDaysAgo: Long, toDaysAgo: Long): Double? {
            val from = today.minusDays(fromDaysAgo).toString()
            val to = today.minusDays(toDaysAgo).toString()
            return weights.filter { it.localDate in from..to }.map { it.kg }.takeIf { it.isNotEmpty() }?.average()
        }
        val w2 = avg(20, 14)
        val w1 = avg(13, 7)
        val w0 = avg(6, 0)
        return listOfNotNull(
            if (w2 != null && w1 != null) (w1 - w2) / w2 * 100.0 else null,
            if (w1 != null && w0 != null) (w0 - w1) / w1 * 100.0 else null
        )
    }

    /**
     * Safety prompts for the weekly check-in, any phase:
     * safety_low_intake_rapid_loss_checkin triggers (a) and (b), nutrition_diet_breaks after
     * 3 kg lost in a deficit, and safety_pregnancy_no_deficit's falling-average alert.
     * Returns (supportive check-in flag or null, extra notes).
     */
    fun safetyPrompts(
        profile: GuardrailProfile,
        phase: String,
        today: LocalDate,
        weights: List<WeightEntry>,
        totals: List<DayTotals>,
        weightKgAtStart: Double?,
        avgWeightKg: Double?,
        weeksInPhase: Int,
        pregnant: Boolean,
        weightChangeKg: Double?
    ): Pair<GuardrailFlag?, List<String>> {
        val d7 = today.minusDays(6).toString()
        val last7Kcal = totals.filter { it.localDate in d7..today.toString() }.map { it.kcal }
        val check = Guardrails.lowIntakeCheckinFlag(profile, last7Kcal)
            ?: Guardrails.rapidLossFlag(weeklyChangePct(today, weights))
        val notes = buildList {
            if (pregnant && weightChangeKg != null && weightChangeKg < 0) {
                add(
                    "Your 7-day average weight has gone down this week. During pregnancy weight is expected to rise, " +
                        "so you may need to eat more. Please mention it to your doctor or midwife."
                )
            }
            if (phase == Phases.CUT && weeksInPhase < 12 && weightKgAtStart != null && avgWeightKg != null) {
                Guardrails.dietBreakFlag(weightKgAtStart - avgWeightKg, weeksInPhase)?.let { add(it.message) }
            }
        }
        return check to notes
    }

    fun summarize(
        today: LocalDate,
        weights: List<WeightEntry>,
        totals: List<DayTotals>,
        health: List<HealthDaily>
    ): CheckinData {
        val d7 = today.minusDays(6).toString()
        val d14 = today.minusDays(13).toString()
        val todayS = today.toString()

        val last7 = weights.filter { it.localDate in d7..todayS }
        val prev7 = weights.filter { it.localDate >= d14 && it.localDate < d7 }
        val avg = last7.takeIf { it.isNotEmpty() }?.map { it.kg }?.average()
        val prevAvg = prev7.takeIf { it.isNotEmpty() }?.map { it.kg }?.average()

        val window14 = weights.filter { it.localDate in d14..todayS }
        val trend = if (window14.size >= 2) NutritionMath.trend(window14).kgPerWeek else null

        val logged7 = totals.filter { it.localDate in d7..todayS && it.kcal > LOGGED_DAY_KCAL }
        val logged14 = totals.filter { it.localDate in d14..todayS && it.kcal > LOGGED_DAY_KCAL }
        val h7 = health.filter { it.localDate in d7..todayS }

        return CheckinData(
            weekStart = weekStart(today),
            avgWeightKg = avg,
            prevAvgWeightKg = prevAvg,
            weightChangeKg = if (avg != null && prevAvg != null) avg - prevAvg else null,
            trendKgPerWeek = trend,
            weighIns14 = window14.size,
            loggedDays7 = logged7.size,
            loggedDays14 = logged14.size,
            avgKcal7 = logged7.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average(),
            avgSleepMinutes = h7.mapNotNull { it.sleepMinutes?.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
            avgSteps = h7.mapNotNull { it.steps?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
        )
    }

    fun propose(
        phase: String,
        currentKcal: Int,
        intendedRateKgPerWeek: Double,
        band: RateBand,
        weightKg: Double,
        learnedTdee: Double?,
        daysSinceLastChange: Long,
        weeksInPhase: Int,
        stepsTarget: Int,
        age: Int,
        data: CheckinData
    ): CheckinProposal {
        val notes = mutableListOf<String>()
        if (phase == Phases.CUT && weeksInPhase in 12 until 16) {
            notes += "You've been in a deficit for $weeksInPhase weeks. This is a good time for a week at " +
                "maintenance. It doesn't undo your progress, and it often makes the next stretch easier."
        }

        // nutrition_max_cut_length: end the deficit at 16 weeks.
        if (phase == Phases.CUT && weeksInPhase >= 16) {
            return CheckinProposal(
                AdjustKind.END_CUT, 0, 0,
                "You've been in a deficit for $weeksInPhase weeks. Forge is switching you to maintenance for at " +
                    "least 4 weeks so your body and mind can recover before any further cutting.",
                notes
            )
        }

        val trend = data.trendKgPerWeek
        val observedPct = trend?.let { it / weightKg * 100.0 }

        // Rapid loss is judged by the caller with Guardrails.rapidLossFlag over two consecutive
        // weekly averages, for every phase (safety_low_intake_rapid_loss_checkin).

        if (daysSinceLastChange < MIN_DAYS_BETWEEN_CHANGES) {
            return CheckinProposal(
                AdjustKind.HOLD_TOO_SOON, 0, 0,
                "Day-to-day weight jumps around with water and food. Forge looks at a 2-week trend before " +
                    "changing anything, so your targets stay the same this week.",
                notes
            )
        }
        if (data.weighIns14 < MIN_WEIGH_INS || observedPct == null) {
            return CheckinProposal(
                AdjustKind.HOLD_FEW_WEIGH_INS, 0, 0,
                "There were ${data.weighIns14} weigh-ins in the last two weeks; Forge needs at least $MIN_WEIGH_INS " +
                    "to read a trend, so your targets stay the same for now.",
                notes
            )
        }
        if (data.adherence14 < MIN_LOGGING) {
            return CheckinProposal(
                AdjustKind.HOLD_LOW_LOGGING, 0, 0,
                "Food was logged on ${data.loggedDays14} of the last 14 days. With gaps it's hard to tell whether the " +
                    "plan or the logging needs a tweak, so your targets stay the same this time.",
                notes
            )
        }
        if (band.contains(observedPct)) {
            return CheckinProposal(
                AdjustKind.ON_TRACK, 0, 0,
                "Your 2-week trend is within the planned range, so nothing needs to change.",
                notes
            )
        }

        val tooSlowOnCut = phase == Phases.CUT && observedPct > maxOf(band.minPct, band.maxPct)
        if (tooSlowOnCut) {
            // Raise steps before cutting calories further (planContents "steps").
            val avgSteps = data.avgSteps
            val aimHigh = if (age >= 60) 8000 else 10000
            if (avgSteps != null && avgSteps < stepsTarget) {
                return CheckinProposal(
                    AdjustKind.STEPS_FIRST, 0, 0,
                    "Your trend is a little slower than planned. You've averaged ${avgSteps.roundToInt()} steps " +
                        "against a $stepsTarget target - reaching the step target comes before any calorie change.",
                    notes
                )
            }
            if (avgSteps != null && stepsTarget < aimHigh) {
                return CheckinProposal(
                    AdjustKind.STEPS_FIRST, 0, 1000,
                    "Your trend is a little slower than planned and you're already hitting your steps, so Forge " +
                        "suggests 1,000 more steps a day before touching calories.",
                    notes
                )
            }
        }

        // Ideal calories: learned maintenance plus the intended rate when it exists, otherwise
        // close the gap between the observed and intended rate on the current target.
        val ideal = if (learnedTdee != null && learnedTdee > 0) {
            learnedTdee + intendedRateKgPerWeek * NutritionMath.KCAL_PER_KG / 7.0
        } else {
            currentKcal + (intendedRateKgPerWeek - (trend ?: 0.0)) * NutritionMath.KCAL_PER_KG / 7.0
        }
        val maxStep = currentKcal * MAX_CHANGE_FRACTION
        val delta = ((ideal - currentKcal).coerceIn(-maxStep, maxStep) / 10.0).roundToInt() * 10
        if (abs(delta) < 10) {
            return CheckinProposal(AdjustKind.ON_TRACK, 0, 0, "Your trend is close enough to plan that no change is needed.", notes)
        }
        val faster = observedPct > maxOf(band.minPct, band.maxPct)
        val direction = when {
            phase == Phases.CUT -> if (faster) "losing more slowly than planned" else "losing faster than planned"
            PhaseCopy.isSurplus(phase) -> if (faster) "gaining faster than planned" else "gaining more slowly than planned"
            else -> if (faster) "drifting up" else "drifting down"
        }
        return CheckinProposal(
            if (delta > 0) AdjustKind.INCREASE else AdjustKind.DECREASE,
            delta, 0,
            "Over two weeks you've been $direction (%+.2f kg a week against a plan of %+.2f). ".format(trend ?: 0.0, intendedRateKgPerWeek) +
                "Forge suggests ${if (delta > 0) "adding" else "removing"} ${abs(delta)} kcal a day - never more than " +
                "10% at once - and will look again in two weeks.",
            notes
        )
    }
}
