package com.laksh.forge.domain

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.ForgeSettings
import org.junit.Test

class NutritionMathKidneyTest {

    @Test
    fun kidneyDiseaseKeepsProteinAtPointEightWithoutAPlan() {
        // A v1 user with no active plan: Profile stored 0.8 g/kg, which the maintenance protein
        // floor used to raise to 1.6 g/kg (64 g -> 128 g for 80 kg).
        val s = ForgeSettings().copy(proteinPerKg = 0.8)
        val withKidney = NutritionMath.targets(s, 80.0, 2600.0, kidneyDisease = true)
        assertThat(withKidney.proteinG).isAtMost(64)
        val without = NutritionMath.targets(s, 80.0, 2600.0)
        assertThat(without.proteinG).isGreaterThan(64)
    }
}
