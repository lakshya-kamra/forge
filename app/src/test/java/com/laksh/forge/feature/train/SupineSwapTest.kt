package com.laksh.forge.feature.train

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SupineSwapTest {

    @Test
    fun flatSupineLiftsAreFlaggedAndUprightVariantsAreNot() {
        assertThat(SessionViewModel.isSupine("Bench Press")).isTrue()
        assertThat(SessionViewModel.isSupine("Lying Leg Raise")).isTrue()
        assertThat(SessionViewModel.isSupine("Incline Bench Press")).isFalse()
        assertThat(SessionViewModel.isSupine("Seated Dumbbell Press")).isFalse()
        assertThat(SessionViewModel.isSupine("Back Squat")).isFalse()
    }
}
