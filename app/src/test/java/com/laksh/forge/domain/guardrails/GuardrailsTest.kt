package com.laksh.forge.domain.guardrails

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import org.junit.Test

class GuardrailsTest {

    private fun person(
        sex: Sex = Sex.MALE,
        age: Int = 30,
        heightCm: Double = 178.0,
        weightKg: Double = 80.0,
        bodyFatPct: Double? = null,
        bodyFatMethod: String? = null,
        trainingAge: TrainingAge = TrainingAge.INTERMEDIATE,
        pregnancy: PregnancyStatus = PregnancyStatus.NONE,
        edScreenPositive: Boolean = false,
        kidneyDisease: Boolean = false
    ) = GuardrailProfile(
        sex = sex,
        age = age,
        heightCm = heightCm,
        weightKg = weightKg,
        bodyFatPct = bodyFatPct,
        trainingAge = trainingAge,
        pregnancy = pregnancy,
        edScreenPositive = edScreenPositive,
        kidneyDisease = kidneyDisease,
        bodyFatMethod = bodyFatMethod
    )

    private fun NutritionCheck.ids() = flags.map { it.id }

    // ------------------------------------------------------------------ calorie floors

    @Test fun maleTargetIsNeverBelow1500() {
        val c = Guardrails.checkNutrition(person(), 1200, 160, 100, 60, -0.5, 1700.0)
        assertThat(c.kcal).isEqualTo(1500)
        assertThat(c.ids()).contains("nutrition_calorie_floor_by_sex")
        assertThat(c.wasAdjusted).isTrue()
    }

    @Test fun femaleTargetIsNeverBelow1200() {
        val c = Guardrails.checkNutrition(
            person(sex = Sex.FEMALE, weightKg = 55.0, heightCm = 160.0), 1000, 100, 100, 40, -0.4, 1500.0
        )
        assertThat(c.kcal).isEqualTo(1200)
        assertThat(c.ids()).contains("nutrition_calorie_floor_by_sex")
    }

    @Test fun floorWinsEvenWhenMaintenanceIsBelowIt() {
        val c = Guardrails.checkNutrition(
            person(weightKg = 60.0, heightCm = 170.0), 1000, 120, 100, 50, -0.5, 1400.0
        )
        assertThat(c.kcal).isEqualTo(1500)
        assertThat(c.ids()).contains("nutrition_calorie_floor_by_sex")
        assertThat(c.ids()).doesNotContain("nutrition_max_deficit_size")
    }

    @Test fun floorsNeverGoBelowTheAbsoluteMinimum() {
        assertThat(Guardrails.calorieFloor(person(sex = Sex.FEMALE))).isAtLeast(Guardrails.ABSOLUTE_MIN_KCAL)
        assertThat(Guardrails.calorieFloor(person(sex = Sex.MALE))).isAtLeast(Guardrails.ABSOLUTE_MIN_KCAL)
    }

    // ------------------------------------------------------------------ deficit caps

    @Test fun deficitIsCappedAt500KcalEvenAtHighBodyFat() {
        val c = Guardrails.checkNutrition(
            person(weightKg = 90.0, bodyFatPct = 25.0, bodyFatMethod = "DEXA"), 2200, 180, 200, 70, -0.9, 3200.0
        )
        assertThat(c.kcal).isEqualTo(2700)
        assertThat(c.ids()).contains("nutrition_max_deficit_size")
        // The stated rate follows the capped deficit: 500 kcal/day is about 0.45 kg/week.
        assertThat(c.rateKgPerWeek).isWithin(0.001).of(-500.0 * 7 / 7700)
    }

    @Test fun lossRateIsCappedByBodyFatTier() {
        // 15% DEXA (male) sits in the 0.7%/week tier.
        assertThat(Guardrails.maxLossPctPerWeek(person(bodyFatPct = 15.0, bodyFatMethod = "DEXA"))).isEqualTo(0.7)
        assertThat(Guardrails.maxLossPctPerWeek(person(bodyFatPct = 25.0, bodyFatMethod = "DEXA"))).isEqualTo(1.0)
        assertThat(Guardrails.maxLossPctPerWeek(person(bodyFatPct = 11.5, bodyFatMethod = "DEXA"))).isEqualTo(0.5)
        // Unknown body fat uses the 0.7 tier.
        assertThat(Guardrails.maxLossPctPerWeek(person())).isEqualTo(0.7)

        val c = Guardrails.checkNutrition(
            person(bodyFatPct = 15.0, bodyFatMethod = "DEXA"), 2700, 170, 280, 70, -1.0, 3000.0
        )
        assertThat(c.ids()).contains("nutrition_max_deficit_rate_by_body_fat")
        assertThat(c.rateKgPerWeek).isAtLeast(-0.56)
    }

    @Test fun photoBodyFatIsJudgedOnItsLowerBound() {
        // 13% from a photo is compared as 7%, below the 8% male block.
        val photo = person(weightKg = 75.0, bodyFatPct = 13.0, bodyFatMethod = "VISUAL_AI")
        assertThat(Guardrails.deficitBlockers(photo).map { it.id }).contains("safety_low_body_fat_no_deficit")
        val c = Guardrails.checkNutrition(photo, 2300, 160, 250, 60, -0.4, 2700.0)
        assertThat(c.kcal).isEqualTo(2700)

        // The same number from DEXA is taken at face value and allows a deficit.
        val dexa = person(weightKg = 75.0, bodyFatPct = 13.0, bodyFatMethod = "DEXA")
        assertThat(Guardrails.deficitBlockers(dexa)).isEmpty()
        assertThat(Guardrails.checkNutrition(dexa, 2300, 160, 250, 60, -0.4, 2700.0).kcal).isEqualTo(2300)
    }

    @Test fun aValueWithoutAMethodIsAlreadyTheCautiousOne() {
        // The plan feature passes the lower bound of its range with no method.
        assertThat(Guardrails.deficitBlockers(person(bodyFatPct = 7.5)).map { it.id })
            .contains("safety_low_body_fat_no_deficit")
        assertThat(Guardrails.deficitBlockers(person(bodyFatPct = 9.0))).isEmpty()
        assertThat(Guardrails.bodyFatSafetyOffset("SCALE")).isEqualTo(5.0)
        assertThat(Guardrails.bodyFatSafetyOffset("MANUAL")).isEqualTo(6.0)
    }

    // ------------------------------------------------------------------ protected groups

    @Test fun minorsGetNoDeficit() {
        val minor = person(age = 16, weightKg = 60.0, heightCm = 170.0)
        assertThat(Guardrails.allowedPhases(minor)).doesNotContain(Phases.CUT)
        assertThat(Guardrails.allowedPhases(minor)).doesNotContain(Phases.RECOMP)

        val c = Guardrails.checkNutrition(minor, 1800, 110, 220, 60, -0.5, 2400.0)
        assertThat(c.kcal).isEqualTo(2400)
        assertThat(c.rateKgPerWeek).isEqualTo(0.0)
        assertThat(c.flags.first { it.id == "safety_under_18" }.severity).isEqualTo(Severity.BLOCK)
    }

    @Test fun minorsHaveBodyFatDroppedFromTheProfile() {
        val p = Guardrails.profileFrom(ForgeSettings(age = 16), 60.0, 12.0, "DEXA")
        assertThat(p.bodyFatPct).isNull()
        assertThat(p.safetyBodyFatPct).isNull()
    }

    @Test fun pregnancyLocksToMaintenance() {
        val pregnant = person(sex = Sex.FEMALE, weightKg = 65.0, heightCm = 165.0, pregnancy = PregnancyStatus.PREGNANT)
        assertThat(Guardrails.allowedPhases(pregnant)).containsExactly(Phases.MAINTAIN)

        val c = Guardrails.checkNutrition(pregnant, 1600, 110, 180, 55, -0.3, 2200.0)
        assertThat(c.kcal).isEqualTo(2200)
        assertThat(c.rateKgPerWeek).isEqualTo(0.0)
        assertThat(c.ids()).contains("safety_pregnancy_no_deficit")
    }

    @Test fun breastfeedingAdds330To400OverMaintenance() {
        val p = person(sex = Sex.FEMALE, weightKg = 65.0, heightCm = 165.0, pregnancy = PregnancyStatus.BREASTFEEDING)
        val c = Guardrails.checkNutrition(p, 1800, 110, 200, 60, -0.3, 2100.0)
        assertThat(c.kcal in 2430..2500).isTrue()
        assertThat(Guardrails.allowedPhases(p)).containsExactly(Phases.MAINTAIN)
    }

    @Test fun positiveEatingScreenLocksToMaintenance() {
        val p = person(edScreenPositive = true)
        assertThat(Guardrails.allowedPhases(p)).containsExactly(Phases.MAINTAIN)
        val c = Guardrails.checkNutrition(p, 2200, 160, 230, 70, -0.5, 2800.0)
        assertThat(c.kcal).isEqualTo(2800)
        assertThat(c.ids()).contains("safety_scoff_positive_response")
    }

    @Test fun underweightBmiBlocksDeficit() {
        val p = person(weightKg = 55.0, heightCm = 178.0)
        assertThat(p.bmi).isLessThan(18.5)
        assertThat(Guardrails.allowedPhases(p)).doesNotContain(Phases.CUT)
        val c = Guardrails.checkNutrition(p, 1900, 100, 250, 50, -0.3, 2300.0)
        assertThat(c.kcal).isEqualTo(2300)
        assertThat(c.ids()).contains("safety_bmi_underweight_no_deficit")
    }

    // ------------------------------------------------------------------ macros

    @Test fun kidneyDiseaseCapsProteinAt0Point8GramsPerKg() {
        val c = Guardrails.checkNutrition(person(kidneyDisease = true), 2800, 160, 300, 80, 0.0, 2800.0)
        assertThat(c.proteinG).isEqualTo(64)
    }

    @Test fun proteinIsRaisedToTheDeficitFloor() {
        val c = Guardrails.checkNutrition(person(), 2400, 100, 300, 70, -0.4, 2800.0)
        assertThat(c.proteinG).isEqualTo(144)   // 1.8 g/kg x 80 kg
        assertThat(c.ids()).contains("nutrition_protein_deficit")
    }

    @Test fun fatIsRaisedToHalfAGramPerKg() {
        val c = Guardrails.checkNutrition(person(), 2400, 150, 300, 20, -0.4, 2800.0)
        assertThat(c.fatG).isEqualTo(40)
        assertThat(c.ids()).contains("nutrition_fat_minimum")
    }

    @Test fun surplusRespectsTheAdvancedGainRate() {
        val c = Guardrails.checkNutrition(
            person(trainingAge = TrainingAge.ADVANCED), 3300, 160, 400, 80, 0.5, 2800.0
        )
        // 0.5% of 80 kg per month is about 0.092 kg/week, about 101 kcal/day.
        assertThat(c.kcal).isAtMost(2902)
        assertThat(c.rateKgPerWeek).isWithin(0.001).of(80 * 0.005 / (52.0 / 12.0))
        assertThat(c.ids()).contains("nutrition_surplus_rate_by_training_age")
    }

    @Test fun safePrescriptionPassesUnchanged() {
        val c = Guardrails.checkNutrition(person(), 2800, 150, 370, 80, 0.0, 2800.0)
        assertThat(c.kcal).isEqualTo(2800)
        assertThat(c.proteinG).isEqualTo(150)
        assertThat(c.fatG).isEqualTo(80)
        assertThat(c.wasAdjusted).isFalse()
    }

    // ------------------------------------------------------------------ SCOFF

    @Test fun scoffHasFiveQuestionsAndPositiveAtTwo() {
        assertThat(Guardrails.SCOFF_QUESTIONS).hasSize(5)
        assertThat(Guardrails.scoffPositive(listOf(false, false, false, false, false))).isFalse()
        assertThat(Guardrails.scoffPositive(listOf(true, false, false, false, false))).isFalse()
        assertThat(Guardrails.scoffPositive(listOf(true, false, true, false, false))).isTrue()
        assertThat(Guardrails.scoffPositive(listOf(true, true, true, true, true))).isTrue()
    }

    // ------------------------------------------------------------------ crisis

    @Test fun crisisPhrasesAreDetected() {
        listOf(
            "I want to kill myself",
            "Sometimes I think about suicide",
            "I've been feeling suicidal lately",
            "I don't want to live anymore",
            "I keep making myself sick after dinner",
            "I made myself throw up after eating",
            "I've been taking laxatives to lose weight",
            "i took diet pills yesterday",
            "I'm on diuretics to drop water",
            "I want to hurt myself",
            "I self-harm when stressed",
            "I wish I was dead",
            "I’ve been throwing up after meals"
        ).forEach { assertWithMessage(it).that(Guardrails.detectCrisis(it)).isTrue() }
    }

    @Test fun trainingSlangAndQuestionsAreNotCrises() {
        listOf(
            "Should I use a suicide grip on bench?",
            "We did suicide sprints at practice",
            "I hurt myself deadlifting",
            "Should I take diuretics before a show?",
            "I'm on a cut, are diet pills worth it?",
            "I cut myself some slack this week",
            "I take creatine. Are laxatives safe?",
            "How much protein today?",
            ""
        ).forEach { assertWithMessage(it).that(Guardrails.detectCrisis(it)).isFalse() }
    }

    @Test fun crisisResourcesLeadWithTeleManas() {
        assertThat(Guardrails.crisisResources.first().contact).isEqualTo("14416")
        assertThat(Guardrails.crisisResources.map { it.contact }).containsAtLeast("14416", "1800-89-14416", "112")
    }

    @Test fun medicalRedFlagsAndPainAreDetected() {
        assertThat(Guardrails.detectMedicalRedFlags("I had chest pain during squats")?.id)
            .isEqualTo("safety_medical_red_flags_stop:emergency")
        assertThat(Guardrails.detectMedicalRedFlags("felt dizzy after my set")?.id)
            .isEqualTo("safety_medical_red_flags_stop")
        assertThat(Guardrails.detectMedicalRedFlags("my chest is sore from bench")).isNull()
        assertThat(Guardrails.detectPainStop("I felt a sharp pain in my shoulder")).isNotNull()
        assertThat(Guardrails.detectPainStop("a bit sore after legs")).isNull()
    }

    // ------------------------------------------------------------------ load jumps

    @Test fun loadJumpsAreCappedAtTenPercent() {
        assertThat(Guardrails.loadJumpFlag(100.0, 105.0, "bench_press")).isNull()
        assertThat(Guardrails.loadJumpFlag(100.0, 110.0, "bench_press")).isNull()
        assertThat(Guardrails.loadJumpFlag(100.0, 115.0, "bench_press")?.severity).isEqualTo(Severity.BLOCK)
        assertThat(Guardrails.loadJumpFlag(100.0, 95.0, "bench_press")).isNull()
        assertThat(Guardrails.loadJumpFlag(0.0, 20.0, "bench_press")).isNull()
        assertThat(Guardrails.maxSafeLoadKg(100.0)).isWithin(1e-9).of(110.0)
    }

    @Test fun smallestIncrementAboveTenPercentIsOnlyInformational() {
        // 2.5 kg on 10 kg dumbbells is 25%, but it is the smallest jump available.
        assertThat(Guardrails.loadJumpFlag(10.0, 12.5, "db_curl")?.severity).isEqualTo(Severity.INFO)
        assertThat(Guardrails.loadJumpFlag(10.0, 15.0, "db_curl")?.severity).isEqualTo(Severity.BLOCK)
    }

    // ------------------------------------------------------------------ training volume

    @Test fun volumeFlagsFollowTheEvidenceBands() {
        val flags = Guardrails.trainingVolumeFlags(
            mapOf("chest" to 2.0, "quads" to 15.0, "lats" to 25.0, "glutes" to 35.0),
            TrainingAge.INTERMEDIATE
        ).associateBy { it.id.substringAfterLast(':') }
        assertThat(flags["chest"]?.severity).isEqualTo(Severity.INFO)
        assertThat(flags).doesNotContainKey("quads")
        assertThat(flags["lats"]?.severity).isEqualTo(Severity.INFO)
        assertThat(flags["glutes"]?.severity).isEqualTo(Severity.CAUTION)
    }

    @Test fun novicesAreNudgedAboveTwelveSets() {
        val flags = Guardrails.trainingVolumeFlags(mapOf("chest" to 14.0), TrainingAge.NOVICE)
        assertThat(flags.single().id).isEqualTo("training_beginner_limits:chest")
    }

    @Test fun hardSetsRequireFourOrFewerRepsInReserve() {
        assertThat(Guardrails.countsAsHardSet(4.0, null)).isTrue()
        assertThat(Guardrails.countsAsHardSet(5.0, null)).isFalse()
        assertThat(Guardrails.countsAsHardSet(null, 6.0)).isTrue()    // RPE 6 = 4 RIR
        assertThat(Guardrails.countsAsHardSet(null, 5.5)).isFalse()
        assertThat(Guardrails.countsAsHardSet(null, null)).isTrue()
    }

    // ------------------------------------------------------------------ prompts and photos

    @Test fun promptRulesCarryCrisisLinesAndPersonalRules() {
        val general = Guardrails.promptRules(null)
        assertThat(general).contains("14416")
        assertThat(general).contains("profile unknown")
        val minor = Guardrails.promptRules(person(age = 16))
        assertThat(minor).contains("Under 18")
        val pregnant = Guardrails.promptRules(person(sex = Sex.FEMALE, pregnancy = PregnancyStatus.PREGNANT))
        assertThat(pregnant).contains("Pregnant")
    }

    @Test fun photoEstimatesAreRangesAndBlockedForProtectedGroups() {
        assertThat(Guardrails.photoBodyFatRange(15.0)).isEqualTo(9..21)
        assertThat(Guardrails.photoBodyFatBlock(person(age = 16), null, 10)).isNotNull()
        assertThat(Guardrails.photoBodyFatBlock(person(), null, 10)).isNull()
        assertThat(Guardrails.photoBodyFatBlock(person(), 5, 10)).isNotNull()
        assertThat(Guardrails.photoBodyFatBlock(person(), null, null)).isNotNull()
    }

    @Test fun dietCultureWordsAreNeutralised() {
        assertThat(Guardrails.neutralizeLanguage("Have a cheat meal on Sunday")).isEqualTo("Have a flexible meal on Sunday")
        assertThat(Guardrails.neutralizeLanguage("Skip the junk food")).isEqualTo("Skip the higher-energy foods")
    }
}
