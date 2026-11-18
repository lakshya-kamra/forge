package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.Sex
import org.junit.Test

/**
 * Reference values from onboarding_science.json navyFormula.derivationNote, which reproduces
 * cells of the official 2016 Navy BCA tables:
 *  - male 70 in tall, abdomen - neck 20 in -> 19.43
 *  - female 64 in tall, waist + hip - neck 56 in -> 30.49
 */
class NavyBodyFatTest {

    private val inch = 2.54

    @Test
    fun male_matchesOfficialTableCell() {
        val height = 70 * inch
        val neck = 15 * inch
        val waist = neck + 20 * inch
        val bf = NavyBodyFat.male(waist, neck, height)
        assertThat(bf).isNotNull()
        assertThat(bf!!).isWithin(0.01).of(19.43)
        // The official table rounds to whole percent.
        assertThat(Math.round(bf)).isEqualTo(19L)
    }

    @Test
    fun female_matchesOfficialTableCell() {
        val height = 64 * inch
        val waist = 28 * inch
        val hips = 40 * inch
        val neck = waist + hips - 56 * inch // waist + hips - neck = 56 in
        val bf = NavyBodyFat.female(waist, hips, neck, height)
        assertThat(bf).isNotNull()
        assertThat(bf!!).isWithin(0.01).of(30.49)
        assertThat(Math.round(bf)).isEqualTo(30L)
    }

    @Test
    fun estimate_dispatchesBySex_andNeedsHipsForWomen() {
        val male = NavyBodyFat.estimate(Sex.MALE, waistCm = 88.9, neckCm = 38.1, hipsCm = null, heightCm = 177.8)
        assertThat(male).isNotNull()
        val femaleWithoutHips = NavyBodyFat.estimate(Sex.FEMALE, waistCm = 71.0, neckCm = 32.0, hipsCm = null, heightCm = 162.0)
        assertThat(femaleWithoutHips).isNull()
    }

    @Test
    fun invalidInputs_returnNull() {
        // Waist must exceed neck, otherwise log10 of a non-positive number.
        assertThat(NavyBodyFat.male(waistNavelCm = 38.0, neckCm = 40.0, heightCm = 178.0)).isNull()
        assertThat(NavyBodyFat.estimate(Sex.MALE, null, 38.0, null, 178.0)).isNull()
        assertThat(NavyBodyFat.estimate(Sex.MALE, 85.0, 38.0, null, 0.0)).isNull()
    }

    @Test
    fun range_appliesSexSpecificBiasCorrection_andFivePointHalfWidth() {
        val m = NavyBodyFat.range(Sex.MALE, 18.0)
        assertThat(m.mid).isWithin(1e-9).of(20.5)
        assertThat(m.low).isWithin(1e-9).of(15.5)
        assertThat(m.high).isWithin(1e-9).of(25.5)

        val f = NavyBodyFat.range(Sex.FEMALE, 30.0)
        assertThat(f.mid).isWithin(1e-9).of(28.0)
        assertThat(f.low).isWithin(1e-9).of(23.0)
        assertThat(f.high).isWithin(1e-9).of(33.0)
    }

    @Test
    fun label_roundsOutward() {
        val r = BodyFatRange(15.4, 25.6, 20.5, BodyFatMethod.NAVY)
        assertThat(r.label).isEqualTo("15-26%")
    }

    @Test
    fun combine_overlappingRanges_givesNarrowerWeightedRange() {
        val navy = BodyFatRange(15.0, 25.0, 20.0, BodyFatMethod.NAVY)          // half-width 5
        val photo = BodyFatCombiner.photoRange(22.0, null, null)               // half-width 7
        val c = BodyFatCombiner.combine(navy, photo)
        assertThat(c.disagreement).isFalse()
        val combined = c.combined!!
        // Inverse-variance weighting pulls toward the tighter (tape) estimate.
        assertThat(combined.mid).isGreaterThan(20.0)
        assertThat(combined.mid).isLessThan(21.0)
        assertThat(combined.high - combined.low).isLessThan(10.0)
    }

    @Test
    fun combine_disjointRanges_flagsDisagreement_andDecidesOnTheUnion() {
        val navy = BodyFatRange(10.0, 20.0, 15.0, BodyFatMethod.NAVY)
        val photo = BodyFatRange(28.0, 42.0, 35.0, BodyFatMethod.VISUAL_AI)
        val c = BodyFatCombiner.combine(navy, photo)
        assertThat(c.disagreement).isTrue()
        assertThat(c.combined).isNull()
        val union = c.forDecision!!
        assertThat(union.low).isWithin(1e-9).of(10.0)
        assertThat(union.high).isWithin(1e-9).of(42.0)
    }

    @Test
    fun photoRange_isNeverNarrowerThanSevenPoints() {
        val r = BodyFatCombiner.photoRange(20.0, modelLow = 18.0, modelHigh = 22.0)
        assertThat(r.low).isWithin(1e-9).of(13.0)
        assertThat(r.high).isWithin(1e-9).of(27.0)
        val wider = BodyFatCombiner.photoRange(20.0, modelLow = 10.0, modelHigh = 30.0)
        assertThat(wider.low).isWithin(1e-9).of(10.0)
        assertThat(wider.high).isWithin(1e-9).of(30.0)
    }
}
