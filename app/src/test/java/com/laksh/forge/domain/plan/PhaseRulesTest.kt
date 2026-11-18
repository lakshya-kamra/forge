package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.guardrails.Phases
import org.junit.Test

class PhaseRulesTest {

    private val all = Phases.ALL.toSet()

    private fun inputs(
        sex: Sex = Sex.MALE,
        age: Int = 30,
        heightCm: Double = 175.0,
        weightKg: Double = 75.0,
        trainingAge: TrainingAge = TrainingAge.INTERMEDIATE,
        detrained: Boolean = false,
        goal: TrainingGoal = TrainingGoal.LOOK,
        preference: PhasePreference = PhasePreference.NO_PREFERENCE,
        bodyFat: BodyFatRange? = null,
        healthWaistCm: Double? = null,
        southAsian: Boolean = false,
        recentlyFinishedDiet: Boolean = false,
        holds: Set<SafetyHold> = emptySet()
    ) = PhaseInputs(
        sex, age, heightCm, weightKg, trainingAge, detrained, goal, preference,
        bodyFat, healthWaistCm, southAsian, recentlyFinishedDiet, holds
    )

    private fun range(low: Double, high: Double) = BodyFatRange(low, high, (low + high) / 2, BodyFatMethod.NAVY)

    @Test
    fun safetyHold_onlyMaintenance() {
        SafetyHold.entries.forEach { hold ->
            val d = PhaseRules.decide(inputs(bodyFat = range(25.0, 35.0), holds = setOf(hold)), all)
            assertThat(d.recommended).isEqualTo(Phases.MAINTAIN)
            assertThat(d.candidates).containsExactly(Phases.MAINTAIN)
            assertThat(d.rule).isEqualTo(DecisionRule.SAFETY_HOLD)
        }
    }

    @Test
    fun recentlyFinishedDiet_movesToMaintenanceFirst() {
        val d = PhaseRules.decide(inputs(bodyFat = range(18.0, 28.0), recentlyFinishedDiet = true), all)
        assertThat(d.recommended).isEqualTo(Phases.MAINTAIN)
        assertThat(d.rule).isEqualTo(DecisionRule.POST_DIET)
    }

    @Test
    fun underweight_gains_andNeverCuts() {
        // 175 cm, 55 kg -> BMI ~18.0
        val d = PhaseRules.decide(inputs(weightKg = 55.0, preference = PhasePreference.LOSE_FAT), all)
        assertThat(d.recommended).isEqualTo(Phases.LEAN_GAIN)
        assertThat(d.candidates).doesNotContain(Phases.CUT)
        assertThat(d.rule).isEqualTo(DecisionRule.UNDERWEIGHT)
    }

    @Test
    fun highBodyFat_trainedMale_cuts_withoutRecompOption() {
        val d = PhaseRules.decide(inputs(bodyFat = range(18.0, 28.0)), all)
        assertThat(d.recommended).isEqualTo(Phases.CUT)
        assertThat(d.rule).isEqualTo(DecisionRule.HIGH_BODY_FAT)
        assertThat(d.candidates).doesNotContain(Phases.RECOMP)
    }

    @Test
    fun highBodyFat_noviceWhoPrefersMuscle_recomps() {
        val d = PhaseRules.decide(
            inputs(trainingAge = TrainingAge.NOVICE, preference = PhasePreference.BUILD_MUSCLE, bodyFat = range(18.0, 28.0)),
            all
        )
        assertThat(d.recommended).isEqualTo(Phases.RECOMP)
        assertThat(d.candidates).contains(Phases.CUT)
    }

    @Test
    fun highBodyFat_usesFemaleThresholds() {
        val below = PhaseRules.decide(inputs(sex = Sex.FEMALE, heightCm = 163.0, weightKg = 60.0, bodyFat = range(20.0, 28.0), goal = TrainingGoal.STRENGTH), all)
        assertThat(below.rule).isNotEqualTo(DecisionRule.HIGH_BODY_FAT)
        val above = PhaseRules.decide(inputs(sex = Sex.FEMALE, heightCm = 163.0, weightKg = 70.0, bodyFat = range(27.0, 37.0)), all)
        assertThat(above.recommended).isEqualTo(Phases.CUT)
    }

    @Test
    fun greyZone_noviceRecomps_trainedCuts() {
        // A narrow combined range sitting wholly inside 15-20% (male).
        val grey = BodyFatRange(15.5, 19.0, 17.25, BodyFatMethod.COMBINED)
        val novice = PhaseRules.decide(inputs(trainingAge = TrainingAge.NOVICE, bodyFat = grey), all)
        assertThat(novice.recommended).isEqualTo(Phases.RECOMP)
        assertThat(novice.rule).isEqualTo(DecisionRule.GREY_ZONE_NOVICE)

        val detrained = PhaseRules.decide(inputs(trainingAge = TrainingAge.ADVANCED, detrained = true, bodyFat = grey), all)
        assertThat(detrained.recommended).isEqualTo(Phases.RECOMP)

        val trained = PhaseRules.decide(inputs(trainingAge = TrainingAge.INTERMEDIATE, bodyFat = grey), all)
        assertThat(trained.recommended).isEqualTo(Phases.CUT)
        assertThat(trained.candidates).doesNotContain(Phases.LEAN_GAIN)

        val trainedWantsGain = PhaseRules.decide(
            inputs(trainingAge = TrainingAge.INTERMEDIATE, preference = PhasePreference.BUILD_MUSCLE, bodyFat = grey), all
        )
        assertThat(trainedWantsGain.recommended).isEqualTo(Phases.CUT)
        assertThat(trainedWantsGain.candidates).contains(Phases.LEAN_GAIN)
    }

    @Test
    fun leanBand_followsGoalAndPreference() {
        val lean = range(9.5, 14.5) // centre 12%
        assertThat(PhaseRules.decide(inputs(goal = TrainingGoal.STRENGTH, bodyFat = lean), all).recommended)
            .isEqualTo(Phases.LEAN_GAIN)
        assertThat(PhaseRules.decide(inputs(preference = PhasePreference.STAY_LEAN, bodyFat = lean), all).recommended)
            .isEqualTo(Phases.MAINGAIN)
        assertThat(PhaseRules.decide(inputs(trainingAge = TrainingAge.ADVANCED, bodyFat = lean), all).recommended)
            .isEqualTo(Phases.MAINGAIN)
        assertThat(PhaseRules.decide(inputs(goal = TrainingGoal.PERFORMANCE, bodyFat = lean), all).recommended)
            .isEqualTo(Phases.MAINTAIN)
    }

    @Test
    fun leanBand_neverOffersCutWhenTheLowerBoundIsVeryLean() {
        val d = PhaseRules.decide(inputs(preference = PhasePreference.LOSE_FAT, bodyFat = range(9.0, 15.0)), all)
        assertThat(d.candidates).doesNotContain(Phases.CUT)
    }

    @Test
    fun veryLean_gains_neverCuts() {
        val d = PhaseRules.decide(inputs(preference = PhasePreference.LOSE_FAT, bodyFat = range(5.0, 11.0)), all)
        assertThat(d.recommended).isEqualTo(Phases.LEAN_GAIN)
        assertThat(d.candidates).doesNotContain(Phases.CUT)
        assertThat(d.rule).isEqualTo(DecisionRule.VERY_LEAN)
    }

    @Test
    fun southAsianWaistRule_firesWithoutBodyFat() {
        val d = PhaseRules.decide(inputs(southAsian = true, healthWaistCm = 94.0), all)
        assertThat(d.recommended).isEqualTo(Phases.CUT)
        assertThat(d.rule).isEqualTo(DecisionRule.HIGH_BODY_FAT)

        val notSouthAsian = PhaseRules.decide(inputs(southAsian = false, healthWaistCm = 94.0), all)
        assertThat(notSouthAsian.recommended).isEqualTo(Phases.MAINTAIN)
    }

    @Test
    fun straddle_belowWaistCutoff_tipsToGreyZone() {
        // 17-27% straddles 20%; a health waist under 90 cm tips a novice to the grey-zone recomp.
        val d = PhaseRules.decide(
            inputs(trainingAge = TrainingAge.NOVICE, bodyFat = range(17.0, 27.0), southAsian = true, healthWaistCm = 84.0),
            all
        )
        assertThat(d.straddles).isTrue()
        assertThat(d.recommended).isEqualTo(Phases.RECOMP)
    }

    @Test
    fun noEstimate_startsAtMaintenance() {
        val d = PhaseRules.decide(inputs(), all)
        assertThat(d.recommended).isEqualTo(Phases.MAINTAIN)
        assertThat(d.rule).isEqualTo(DecisionRule.NO_ESTIMATE)
    }

    @Test
    fun guardrailsFilter_removesCut_andFallsBack() {
        val d = PhaseRules.decide(inputs(bodyFat = range(18.0, 28.0)), all - Phases.CUT)
        assertThat(d.candidates).doesNotContain(Phases.CUT)
        assertThat(d.recommended).isEqualTo(Phases.MAINTAIN)
    }
}
