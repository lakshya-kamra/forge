package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.Phases
import org.junit.Test

class PlanBuilderTest {

    private fun input(
        phase: String,
        sex: Sex = Sex.MALE,
        weightKg: Double = 80.0,
        trainingAge: TrainingAge = TrainingAge.INTERMEDIATE,
        tdee: Double = 2600.0,
        bodyFat: BodyFatRange? = null,
        kidney: Boolean = false,
        breastfeeding: Boolean = false
    ) = BuildInput(
        phase = phase, sex = sex, age = 30, weightKg = weightKg, trainingAge = trainingAge, tdee = tdee,
        bodyFat = bodyFat, kidneyDisease = kidney, breastfeeding = breastfeeding, baselineSteps = null, fatPerKg = 0.8
    )

    @Test
    fun cut_deficitNeverExceeds500kcal() {
        val t = PlanBuilder.build(input(Phases.CUT, bodyFat = BodyFatRange(20.0, 30.0, 25.0, BodyFatMethod.NAVY)))
        assertThat(t.kcal).isEqualTo(2100)
        assertThat(t.ratePctPerWeek).isLessThan(0.0)
        assertThat(t.ratePctPerWeek).isGreaterThan(-0.7)
        assertThat(t.proteinG).isEqualTo(176) // 2.2 g/kg in a deficit
    }

    @Test
    fun cut_respectsTheCalorieFloor() {
        val t = PlanBuilder.build(input(Phases.CUT, sex = Sex.FEMALE, weightKg = 50.0, tdee = 1300.0))
        assertThat(t.kcal).isAtLeast(1200)
    }

    @Test
    fun cutRateCap_followsBodyFatTierOnTheLowerBound() {
        assertThat(PlanBuilder.cutRateCapPct(Sex.MALE, null)).isEqualTo(0.7)
        assertThat(PlanBuilder.cutRateCapPct(Sex.MALE, BodyFatRange(21.0, 31.0, 26.0, BodyFatMethod.NAVY))).isEqualTo(1.0)
        assertThat(PlanBuilder.cutRateCapPct(Sex.MALE, BodyFatRange(13.0, 23.0, 18.0, BodyFatMethod.NAVY))).isEqualTo(0.7)
        assertThat(PlanBuilder.cutRateCapPct(Sex.MALE, BodyFatRange(10.0, 20.0, 15.0, BodyFatMethod.NAVY))).isEqualTo(0.5)
        assertThat(PlanBuilder.cutRateCapPct(Sex.FEMALE, BodyFatRange(25.0, 35.0, 30.0, BodyFatMethod.NAVY))).isEqualTo(0.7)
    }

    @Test
    fun leanGain_intermediateSurplusIsCappedByThePyramidRate() {
        val t = PlanBuilder.build(input(Phases.LEAN_GAIN))
        // Midpoint 0.75 %BW/month for intermediates, about 152 kcal/day at 80 kg.
        assertThat(t.kcal - 2600).isIn(140..165)
        assertThat(t.ratePctPerWeek).isWithin(0.01).of(0.75 * 12 / 52)
    }

    @Test
    fun kidneyDisease_overridesProtein() {
        val t = PlanBuilder.build(input(Phases.MAINTAIN, kidney = true))
        assertThat(t.proteinG).isEqualTo(64) // 0.8 g/kg
    }

    @Test
    fun breastfeeding_addsToMaintenance() {
        val t = PlanBuilder.build(input(Phases.MAINTAIN, sex = Sex.FEMALE, weightKg = 62.0, tdee = 2000.0, breastfeeding = true))
        assertThat(t.kcal).isEqualTo(2400)
    }

    @Test
    fun macros_keepFatFloors_andCarbsFillTheRest() {
        val m = PlanBuilder.macros(2000.0, 70.0, 2.2, 0.3, deficit = true)
        // 0.3 g/kg is below both floors: 0.5 g/kg (35 g) beats 15% of energy (33 g).
        assertThat(m.fatG).isEqualTo(35)
        assertThat(m.proteinG).isEqualTo(154)
        assertThat(m.carbsG).isEqualTo(((2000 - 154 * 4 - 35 * 9) / 4.0).toInt())
        assertThat(m.fiberG).isAtLeast(25)
    }

    @Test
    fun steps_buildFromBaselineTowardTheBand() {
        assertThat(PlanBuilder.stepsTarget(30, null)).isEqualTo(8000)
        assertThat(PlanBuilder.stepsTarget(65, null)).isEqualTo(6000)
        assertThat(PlanBuilder.stepsTarget(30, 5000.0)).isEqualTo(6500)
        assertThat(PlanBuilder.stepsTarget(30, 9500.0)).isEqualTo(10000)
        assertThat(PlanBuilder.stepsTarget(30, 12000.0)).isEqualTo(12000)
    }
}
