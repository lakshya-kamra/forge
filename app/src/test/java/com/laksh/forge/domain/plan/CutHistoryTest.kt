package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.domain.guardrails.Phases
import com.laksh.forge.domain.guardrails.Severity
import org.junit.Test
import java.time.LocalDate

class CutHistoryTest {

    private val today = LocalDate.of(2026, 11, 18)
    private var t = 0L

    private fun plan(phase: String, start: LocalDate, active: Boolean = false) = PhasePlan(
        createdAt = ++t, phase = phase, startDate = start.toString(), kcal = 2000, proteinG = 150,
        carbsG = 200, fatG = 60, fiberG = 30, rateKgPerWeek = 0.0, rationale = "", planJson = "{}",
        source = "RULES", isActive = active
    )

    @Test
    fun `a cut past 16 weeks must end even without a check-in`() {
        val h = CutHistory.of(listOf(plan(Phases.CUT, today.minusWeeks(17), active = true)), today)
        assertThat(h.cutLimitReached).isTrue()
    }

    @Test
    fun `re-planning a cut does not restart the 16-week clock`() {
        val h = CutHistory.of(
            listOf(
                plan(Phases.CUT, today.minusWeeks(15)),
                plan(Phases.CUT, today.minusWeeks(1), active = true)
            ),
            today
        )
        assertThat(h.activeCutWeeks).isEqualTo(15)
        val later = CutHistory.of(
            listOf(plan(Phases.CUT, today.minusWeeks(17)), plan(Phases.CUT, today.minusWeeks(2), active = true)),
            today
        )
        assertThat(later.cutLimitReached).isTrue()
    }

    @Test
    fun `a new cut is blocked until 4 maintenance weeks follow a 16-week cut`() {
        val ended = today.minusWeeks(1)
        val h = CutHistory.of(
            listOf(plan(Phases.CUT, ended.minusWeeks(16)), plan(Phases.MAINTAIN, ended, active = true)),
            today
        )
        assertThat(h.newCutFlag?.severity).isEqualTo(Severity.BLOCK)

        val rested = today.minusWeeks(5)
        val ok = CutHistory.of(
            listOf(plan(Phases.CUT, rested.minusWeeks(16)), plan(Phases.MAINTAIN, rested, active = true)),
            today
        )
        assertThat(ok.newCutFlag).isNull()
    }

    @Test
    fun `a short cut never blocks the next one`() {
        val h = CutHistory.of(
            listOf(plan(Phases.CUT, today.minusWeeks(8)), plan(Phases.MAINTAIN, today, active = true)),
            today
        )
        assertThat(h.newCutFlag).isNull()
    }
}
