package com.laksh.forge.domain.readiness

import com.laksh.forge.data.db.HealthDaily
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** The three inputs readiness looks at, per local day. HRV must be RMSSD - never Apple's SDNN. */
data class ReadinessDay(
    val date: LocalDate,
    val rmssdMs: Double? = null,
    val restingHr: Double? = null,
    val sleepMinutes: Double? = null
)

enum class ReadinessMetric { HRV, RESTING_HR, SLEEP }

/** How one component compares with the person's own normal spread. */
enum class Signal { FAVOURABLE, USUAL, UNFAVOURABLE }

enum class ReadinessLevel { ABOVE_USUAL, USUAL, BELOW_USUAL }

data class ReadinessComponent(
    val metric: ReadinessMetric,
    /** HRV: 7-day geometric mean in ms. Resting HR: bpm. Sleep: minutes. */
    val current: Double,
    /** The latest day that went into [current]. */
    val currentDate: LocalDate,
    /** How many days [current] is built from (HRV: up to 7; the others: 1). */
    val currentDays: Int,
    /** Baseline centre in the same unit (HRV: geometric mean). */
    val usual: Double,
    /** Baseline centre minus / plus one standard deviation, in the same unit. */
    val usualLow: Double,
    val usualHigh: Double,
    val baselineDays: Int,
    /** Distance from the baseline in standard deviations (in ln units for HRV). */
    val z: Double,
    val signal: Signal
)

data class MissingInput(val metric: ReadinessMetric, val reason: String)

data class ReadinessEstimate(
    val today: LocalDate,
    val components: List<ReadinessComponent>,
    val missing: List<MissingInput>,
    /** Null until at least [Readiness.MIN_COMPONENTS] components have enough history. */
    val level: ReadinessLevel?
)

/**
 * A deliberately simple, explainable readiness estimate: each input is compared only with the
 * same person's own history, and the result is a coarse label rather than a score, because
 * three noisy signals don't support a two-digit number.
 *
 * - HRV: the mean of ln(RMSSD) over the last 7 days against the 60 days before that week.
 *   RMSSD is log-transformed because it is right-skewed; the 7-day mean damps night-to-night
 *   noise.
 * - Resting HR: the latest night against the 60 days before it. Higher than usual counts against.
 * - Sleep: last night against the 30 nights before it. Only *shorter* than usual counts against;
 *   unusually long sleep is reported but not treated as good or bad.
 *
 * "Usual" is the baseline mean plus or minus one standard deviation of the person's own values.
 * That band is a statistical choice made for this app (wide enough that ordinary variation
 * doesn't flip the label), not a clinical threshold. Not medical advice.
 */
object Readiness {
    const val HRV_WINDOW_DAYS = 7
    const val HRV_WINDOW_MIN = 3
    const val BASELINE_DAYS = 60
    const val HRV_BASELINE_MIN = 14
    const val RHR_BASELINE_MIN = 14
    const val SLEEP_BASELINE_DAYS = 30
    const val SLEEP_BASELINE_MIN = 7
    const val BAND_SD = 1.0
    const val MIN_COMPONENTS = 2

    // Floors only stop a near-constant history from turning a trivial change into a huge z;
    // they are numerical guards, not physiology.
    private const val HRV_SD_FLOOR = 0.02      // ln units, about 2%
    private const val RHR_SD_FLOOR = 1.0       // bpm
    private const val SLEEP_SD_FLOOR = 10.0    // minutes

    fun fromHealthDaily(rows: List<HealthDaily>): List<ReadinessDay> = rows.mapNotNull { r ->
        val date = runCatching { LocalDate.parse(r.localDate) }.getOrNull() ?: return@mapNotNull null
        ReadinessDay(
            date = date,
            rmssdMs = r.hrvMs,
            restingHr = r.restingHr?.toDouble(),
            sleepMinutes = r.sleepMinutes?.toDouble()
        )
    }

    fun estimate(days: List<ReadinessDay>, today: LocalDate): ReadinessEstimate {
        val byDate = days.associateBy { it.date }
        val components = ArrayList<ReadinessComponent>()
        val missing = ArrayList<MissingInput>()

        hrv(byDate, today).let { (c, m) -> c?.let { components.add(it) }; m?.let { missing.add(it) } }
        restingHr(byDate, today).let { (c, m) -> c?.let { components.add(it) }; m?.let { missing.add(it) } }
        sleep(byDate, today).let { (c, m) -> c?.let { components.add(it) }; m?.let { missing.add(it) } }

        val level = if (components.size < MIN_COMPONENTS) null else {
            val favourable = components.count { it.signal == Signal.FAVOURABLE }
            val unfavourable = components.count { it.signal == Signal.UNFAVOURABLE }
            when {
                unfavourable > favourable -> ReadinessLevel.BELOW_USUAL
                favourable > unfavourable -> ReadinessLevel.ABOVE_USUAL
                else -> ReadinessLevel.USUAL
            }
        }
        return ReadinessEstimate(today, components, missing, level)
    }

    // ------------------------------------------------------------------ components

    private fun hrv(days: Map<LocalDate, ReadinessDay>, today: LocalDate): Pair<ReadinessComponent?, MissingInput?> {
        val recent = (0 until HRV_WINDOW_DAYS).mapNotNull { i ->
            val d = today.minusDays(i.toLong())
            days[d]?.rmssdMs?.takeIf { it > 0 && it.isFinite() }?.let { d to ln(it) }
        }
        val windowStart = today.minusDays((HRV_WINDOW_DAYS - 1).toLong())
        val baseEnd = windowStart.minusDays(1)
        val base = values(days, baseEnd.minusDays((BASELINE_DAYS - 1).toLong()), baseEnd) { r ->
            r.rmssdMs?.takeIf { it > 0 && it.isFinite() }?.let { ln(it) }
        }

        if (recent.isEmpty() && base.isEmpty()) {
            return null to MissingInput(ReadinessMetric.HRV, "No HRV (RMSSD) recorded yet.")
        }
        if (recent.size < HRV_WINDOW_MIN) {
            return null to MissingInput(
                ReadinessMetric.HRV,
                "Needs HRV on at least $HRV_WINDOW_MIN of the last $HRV_WINDOW_DAYS days (has ${recent.size})."
            )
        }
        if (base.size < HRV_BASELINE_MIN) {
            return null to MissingInput(
                ReadinessMetric.HRV,
                "Building your HRV baseline: ${base.size} of $HRV_BASELINE_MIN days from the $BASELINE_DAYS days before this week."
            )
        }

        val mean7 = recent.map { it.second }.average()
        val mu = base.average()
        val sd = max(sampleSd(base), HRV_SD_FLOOR)
        val z = (mean7 - mu) / sd
        val signal = when {
            z >= BAND_SD -> Signal.FAVOURABLE
            z <= -BAND_SD -> Signal.UNFAVOURABLE
            else -> Signal.USUAL
        }
        return ReadinessComponent(
            metric = ReadinessMetric.HRV,
            current = exp(mean7),
            currentDate = recent.maxOf { it.first },
            currentDays = recent.size,
            usual = exp(mu),
            usualLow = exp(mu - BAND_SD * sd),
            usualHigh = exp(mu + BAND_SD * sd),
            baselineDays = base.size,
            z = z,
            signal = signal
        ) to null
    }

    private fun restingHr(days: Map<LocalDate, ReadinessDay>, today: LocalDate): Pair<ReadinessComponent?, MissingInput?> {
        val latest = latestOf(days, today) { it.restingHr }
        if (latest == null) {
            val any = values(days, today.minusDays(BASELINE_DAYS.toLong()), today) { it.restingHr?.takeIf { v -> v > 0 } }
            return null to MissingInput(
                ReadinessMetric.RESTING_HR,
                if (any.isEmpty()) "No resting heart rate recorded yet."
                else "No resting heart rate for today or yesterday yet."
            )
        }
        val (date, value) = latest
        val base = values(days, date.minusDays(BASELINE_DAYS.toLong()), date.minusDays(1)) {
            it.restingHr?.takeIf { v -> v > 0 }
        }
        if (base.size < RHR_BASELINE_MIN) {
            return null to MissingInput(
                ReadinessMetric.RESTING_HR,
                "Building your resting-HR baseline: ${base.size} of $RHR_BASELINE_MIN days."
            )
        }
        val mu = base.average()
        val sd = max(sampleSd(base), RHR_SD_FLOOR)
        val z = (value - mu) / sd
        val signal = when {
            z >= BAND_SD -> Signal.UNFAVOURABLE
            z <= -BAND_SD -> Signal.FAVOURABLE
            else -> Signal.USUAL
        }
        return ReadinessComponent(
            metric = ReadinessMetric.RESTING_HR,
            current = value,
            currentDate = date,
            currentDays = 1,
            usual = mu,
            usualLow = mu - BAND_SD * sd,
            usualHigh = mu + BAND_SD * sd,
            baselineDays = base.size,
            z = z,
            signal = signal
        ) to null
    }

    private fun sleep(days: Map<LocalDate, ReadinessDay>, today: LocalDate): Pair<ReadinessComponent?, MissingInput?> {
        val latest = latestOf(days, today) { it.sleepMinutes }
        if (latest == null) {
            val any = values(days, today.minusDays(SLEEP_BASELINE_DAYS.toLong()), today) {
                it.sleepMinutes?.takeIf { v -> v > 0 }
            }
            return null to MissingInput(
                ReadinessMetric.SLEEP,
                if (any.isEmpty()) "No sleep recorded yet." else "No sleep recorded for last night yet."
            )
        }
        val (date, value) = latest
        val base = values(days, date.minusDays(SLEEP_BASELINE_DAYS.toLong()), date.minusDays(1)) {
            it.sleepMinutes?.takeIf { v -> v > 0 }
        }
        if (base.size < SLEEP_BASELINE_MIN) {
            return null to MissingInput(
                ReadinessMetric.SLEEP,
                "Learning your typical sleep: ${base.size} of $SLEEP_BASELINE_MIN nights."
            )
        }
        val mu = base.average()
        val sd = max(sampleSd(base), SLEEP_SD_FLOOR)
        val z = (value - mu) / sd
        val signal = if (z <= -BAND_SD) Signal.UNFAVOURABLE else Signal.USUAL
        return ReadinessComponent(
            metric = ReadinessMetric.SLEEP,
            current = value,
            currentDate = date,
            currentDays = 1,
            usual = mu,
            usualLow = mu - BAND_SD * sd,
            usualHigh = mu + BAND_SD * sd,
            baselineDays = base.size,
            z = z,
            signal = signal
        ) to null
    }

    // ------------------------------------------------------------------ helpers

    /** Today's value, or yesterday's when today's hasn't synced yet. Older is too stale. */
    private fun latestOf(
        days: Map<LocalDate, ReadinessDay>,
        today: LocalDate,
        pick: (ReadinessDay) -> Double?
    ): Pair<LocalDate, Double>? = listOf(today, today.minusDays(1)).firstNotNullOfOrNull { d ->
        days[d]?.let(pick)?.takeIf { it > 0 && it.isFinite() }?.let { d to it }
    }

    private fun values(
        days: Map<LocalDate, ReadinessDay>,
        from: LocalDate,
        to: LocalDate,
        pick: (ReadinessDay) -> Double?
    ): List<Double> {
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<Double>()
        var d = from
        while (!d.isAfter(to)) {
            days[d]?.let(pick)?.takeIf { it.isFinite() }?.let { out.add(it) }
            d = d.plusDays(1)
        }
        return out
    }

    private fun sampleSd(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }
}
