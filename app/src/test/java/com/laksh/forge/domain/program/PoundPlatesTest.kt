package com.laksh.forge.domain.program

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

class PoundPlatesTest {

    private fun isWholeMultiple(lb: Double, of: Double) = abs(lb / of - round(lb / of)) < 1e-6

    @Test
    fun lbLiftersDefaultToFivePoundStepsAndTargetsLandOnLoadablePounds() {
        val step = Strength.defaultStep(WeightUnit.LB)
        for (kg in listOf(105.0, 102.5, 107.5, 52.5)) {
            val lb = Strength.roundTo(kg, step) / Strength.KG_PER_LB
            assertThat(isWholeMultiple(lb, 5.0)).isTrue()
        }
        assertThat(Strength.stepInDisplay(step, WeightUnit.LB)).isWithin(1e-9).of(5.0)
        assertThat(Strength.defaultStep(WeightUnit.KG)).isWithin(1e-9).of(2.5)
    }

    @Test
    fun theExactPrefillRoundTripsToThePrescribedKg() {
        // The editor logs kgToDisplay(target) when the prefill is untouched, not the 0.1-rounded text.
        val back = NutritionMath.displayToKg(NutritionMath.kgToDisplay(52.5, WeightUnit.LB), WeightUnit.LB)
        assertThat(back).isWithin(1e-9).of(52.5)
    }
}
