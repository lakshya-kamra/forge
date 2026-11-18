package com.laksh.forge.domain.readiness

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

class ReadinessTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 17)

    /**
     * [days] days ending today. Baseline values alternate around a centre so the standard
     * deviation is known and non-zero; overrides replace individual days.
     */
    private fun history(
        days: Int = 70,
        hrv: (Int) -> Double? = { i -> if (i % 2 == 0) 48.0 else 52.0 },
        rhr: (Int) -> Double? = { i -> if (i % 2 == 0) 54.0 else 58.0 },
        sleep: (Int) -> Double? = { i -> if (i % 2 == 0) 400.0 else 440.0 }
    ): List<ReadinessDay> = (0 until days).map { ago ->
        ReadinessDay(
            date = today.minusDays(ago.toLong()),
            rmssdMs = hrv(ago),
            restingHr = rhr(ago),
            sleepMinutes = sleep(ago)
        )
    }

    @Test
    fun steadyHistory_isWithinUsualRange() {
        val days = history(
            rhr = { i -> if (i == 0) 56.0 else if (i % 2 == 0) 54.0 else 58.0 },
            sleep = { i -> if (i == 0) 420.0 else if (i % 2 == 0) 400.0 else 440.0 }
        )
        val est = Readiness.estimate(days, today)

        assertThat(est.missing).isEmpty()
        assertThat(est.components).hasSize(3)
        assertThat(est.components.map { it.signal }).containsExactly(Signal.USUAL, Signal.USUAL, Signal.USUAL)
        assertThat(est.level).isEqualTo(ReadinessLevel.USUAL)
    }

    @Test
    fun hrvUsesLogScaleAndSevenDayMean() {
        val est = Readiness.estimate(history(), today)
        val hrv = est.components.first { it.metric == ReadinessMetric.HRV }

        // 7 recent days: 4 x 48 ms (even offsets 0,2,4,6) and 3 x 52 ms -> geometric mean.
        val expected = exp((4 * ln(48.0) + 3 * ln(52.0)) / 7)
        assertThat(hrv.current).isWithin(1e-9).of(expected)
        assertThat(hrv.currentDays).isEqualTo(7)
        // Baseline is the 60 days before this week, not overlapping it.
        assertThat(hrv.baselineDays).isEqualTo(60)
        assertThat(hrv.usualLow).isLessThan(hrv.usual)
        assertThat(hrv.usualHigh).isGreaterThan(hrv.usual)
    }

    @Test
    fun suppressedHrvHighRhrShortSleep_isBelowUsual() {
        val days = history(
            hrv = { i -> if (i < 7) 35.0 else if (i % 2 == 0) 48.0 else 52.0 },
            rhr = { i -> if (i == 0) 66.0 else if (i % 2 == 0) 54.0 else 58.0 },
            sleep = { i -> if (i == 0) 300.0 else if (i % 2 == 0) 400.0 else 440.0 }
        )
        val est = Readiness.estimate(days, today)

        val byMetric = est.components.associateBy { it.metric }
        assertThat(byMetric.getValue(ReadinessMetric.HRV).signal).isEqualTo(Signal.UNFAVOURABLE)
        assertThat(byMetric.getValue(ReadinessMetric.RESTING_HR).signal).isEqualTo(Signal.UNFAVOURABLE)
        assertThat(byMetric.getValue(ReadinessMetric.SLEEP).signal).isEqualTo(Signal.UNFAVOURABLE)
        assertThat(est.level).isEqualTo(ReadinessLevel.BELOW_USUAL)
    }

    @Test
    fun highHrvLowRhr_isAboveUsual() {
        val days = history(
            hrv = { i -> if (i < 7) 70.0 else if (i % 2 == 0) 48.0 else 52.0 },
            rhr = { i -> if (i == 0) 49.0 else if (i % 2 == 0) 54.0 else 58.0 },
            sleep = { i -> if (i == 0) 420.0 else if (i % 2 == 0) 400.0 else 440.0 }
        )
        val est = Readiness.estimate(days, today)
        assertThat(est.level).isEqualTo(ReadinessLevel.ABOVE_USUAL)
    }

    @Test
    fun longSleepIsNeverCountedAsFavourable() {
        val days = history(
            rhr = { i -> if (i == 0) 56.0 else if (i % 2 == 0) 54.0 else 58.0 },
            sleep = { i -> if (i == 0) 600.0 else if (i % 2 == 0) 400.0 else 440.0 }
        )
        val sleep = Readiness.estimate(days, today).components.first { it.metric == ReadinessMetric.SLEEP }
        assertThat(sleep.z).isGreaterThan(1.0)
        assertThat(sleep.signal).isEqualTo(Signal.USUAL)
    }

    @Test
    fun shortHistory_reportsWhatIsMissingAndNoLevel() {
        val est = Readiness.estimate(history(days = 5), today)

        assertThat(est.level).isNull()
        assertThat(est.components).isEmpty()
        assertThat(est.missing.map { it.metric })
            .containsExactly(ReadinessMetric.HRV, ReadinessMetric.RESTING_HR, ReadinessMetric.SLEEP)
        assertThat(est.missing.first { it.metric == ReadinessMetric.HRV }.reason).contains("baseline")
    }

    @Test
    fun noHrvAtAll_stillEstimatesFromTwoComponents() {
        val days = history(
            hrv = { null },
            rhr = { i -> if (i == 0) 56.0 else if (i % 2 == 0) 54.0 else 58.0 },
            sleep = { i -> if (i == 0) 420.0 else if (i % 2 == 0) 400.0 else 440.0 }
        )
        val est = Readiness.estimate(days, today)

        assertThat(est.components.map { it.metric })
            .containsExactly(ReadinessMetric.RESTING_HR, ReadinessMetric.SLEEP)
        assertThat(est.missing.single().metric).isEqualTo(ReadinessMetric.HRV)
        assertThat(est.missing.single().reason).contains("No HRV")
        assertThat(est.level).isEqualTo(ReadinessLevel.USUAL)
    }

    @Test
    fun tooFewRecentHrvNights_isReportedNotGuessed() {
        // Plenty of baseline, but only 2 of the last 7 days have HRV.
        val days = history(hrv = { i -> if (i in 2..6) null else if (i % 2 == 0) 48.0 else 52.0 })
        val est = Readiness.estimate(days, today)

        assertThat(est.components.none { it.metric == ReadinessMetric.HRV }).isTrue()
        assertThat(est.missing.first { it.metric == ReadinessMetric.HRV }.reason).contains("has 2")
    }

    @Test
    fun yesterdaysRestingHrIsUsedWhenTodayIsMissing() {
        val days = history(rhr = { i -> if (i == 0) null else if (i % 2 == 0) 54.0 else 58.0 })
        val rhr = Readiness.estimate(days, today).components.first { it.metric == ReadinessMetric.RESTING_HR }
        assertThat(rhr.currentDate).isEqualTo(today.minusDays(1))
    }
}
