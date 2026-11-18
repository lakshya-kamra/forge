package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Phases
import org.junit.Test
import java.time.LocalDate

class CheckinSafetyTest {

    private val today = LocalDate.of(2026, 11, 18)
    private val woman = GuardrailProfile(
        sex = Sex.FEMALE, age = 30, heightCm = 165.0, weightKg = 62.0, bodyFatPct = null,
        trainingAge = TrainingAge.INTERMEDIATE, pregnancy = PregnancyStatus.NONE, edScreenPositive = false
    )

    private fun day(daysAgo: Long, kcal: Double) =
        DayTotals(today.minusDays(daysAgo).toString(), kcal, 50.0, 100.0, 30.0, 10.0, 3)

    private fun weigh(daysAgo: Long, kg: Double) =
        WeightEntry(timestamp = daysAgo, localDate = today.minusDays(daysAgo).toString(), kg = kg)

    private fun prompts(totals: List<DayTotals>, weights: List<WeightEntry>, pregnant: Boolean = false, change: Double? = null) =
        CheckinEngine.safetyPrompts(woman, Phases.CUT, today, weights, totals, null, null, 4, pregnant, change)

    @Test
    fun lowIntakeOnFiveOfSevenDaysOpensTheSupportiveCheckin() {
        val totals = (0L..4L).map { day(it, 900.0) } + listOf(day(5, 1800.0), day(6, 1800.0))
        val (flag, _) = prompts(totals, emptyList())
        assertThat(flag?.id).isEqualTo("safety_low_intake_rapid_loss_checkin")
    }

    @Test
    fun twoWeeksOfFastLossOpenItInAnyPhase() {
        // ~2% a week for two consecutive weeks.
        val weights = (14L..20L).map { weigh(it, 64.0) } + (7L..13L).map { weigh(it, 62.7) } + (0L..6L).map { weigh(it, 61.4) }
        val (flag, _) = prompts(emptyList(), weights)
        assertThat(flag).isNotNull()
    }

    @Test
    fun aFallingAverageDuringPregnancyIsFlagged() {
        val (_, notes) = prompts(emptyList(), emptyList(), pregnant = true, change = -0.4)
        assertThat(notes.joinToString()).contains("pregnancy")
    }
}
