package com.laksh.forge.feature.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OneRepMaxTest {

    @Test
    fun `epley and brzycki closed forms`() {
        val e = OneRepMax.estimate(100.0, 5)
        assertThat(e.epleyKg).isWithin(1e-9).of(100.0 * (1 + 5 / 30.0))
        assertThat(e.brzyckiKg).isWithin(1e-9).of(100.0 * 36 / 32.0)
    }

    @Test
    fun `a single to failure is the 1RM under both formulas`() {
        val e = OneRepMax.estimate(150.0, 1)
        assertThat(e.epleyKg).isEqualTo(150.0)
        assertThat(e.brzyckiKg).isEqualTo(150.0)
    }

    @Test
    fun `rpe adds reps in reserve`() {
        // 5 reps at RPE 8 = 7 reps to failure on the RIR-based scale.
        val atRpe8 = OneRepMax.estimate(100.0, 5, rpe = 8.0)
        val sevenToFailure = OneRepMax.estimate(100.0, 7)
        assertThat(atRpe8.repsToFailure).isEqualTo(7.0)
        assertThat(atRpe8.epleyKg).isWithin(1e-9).of(sevenToFailure.epleyKg)
        assertThat(atRpe8.brzyckiKg).isWithin(1e-9).of(sevenToFailure.brzyckiKg)
    }

    @Test
    fun `rpe table is the inverse of the e1rm factor and falls with reps and effort`() {
        val t = OneRepMax.rpeTable(E1rmFormula.BRZYCKI)
        assertThat(t.cells[0][0]).isEqualTo(1.0) // 1 rep @ RPE 10
        t.cells.forEach { row -> assertThat(row).isInStrictOrder(Comparator.reverseOrder<Double>()) }
        val oneRepColumn = t.cells.map { it[0] }
        assertThat(oneRepColumn).isInStrictOrder(Comparator.reverseOrder<Double>())
        assertThat(OneRepMax.fractionOf1rm(5, 10.0, E1rmFormula.BRZYCKI)).isWithin(1e-9).of(32.0 / 36.0)
    }

    @Test
    fun `percentage table rounds to loadable weights`() {
        val rows = OneRepMax.percentTable(143.0, GymProfile())
        assertThat(rows.first().percent).isEqualTo(100)
        assertThat(rows.last().percent).isEqualTo(50)
        rows.forEach { assertThat(PlateMath.load(it.loadKg, GymProfile()).exact).isTrue() }
    }
}
