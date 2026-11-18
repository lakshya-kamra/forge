package com.laksh.forge.feature.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlateMathTest {

    private val gym = GymProfile() // 20 kg bar, default kg inventory, no collars

    @Test
    fun `100 kg on a 20 kg bar is a 25 and a 15 per side`() {
        val load = PlateMath.load(100.0, gym)
        assertThat(load.exact).isTrue()
        assertThat(load.belowBar).isFalse()
        assertThat(load.totalKg).isEqualTo(100.0)
        // 25+15 and 20+20 are both two plates; the heavier-plates tie-break picks 25+15.
        assertThat(load.perSide).containsExactly(25.0, 15.0).inOrder()
    }

    @Test
    fun `single plate loads use one plate`() {
        assertThat(PlateMath.load(60.0, gym).perSide).containsExactly(20.0)
        assertThat(PlateMath.load(70.0, gym).perSide).containsExactly(25.0)
    }

    @Test
    fun `fractional plates are used for micro-loading`() {
        assertThat(PlateMath.load(102.5, gym).perSide).containsExactly(25.0, 15.0, 1.25).inOrder()
        assertThat(PlateMath.load(101.0, gym).perSide).containsExactly(25.0, 15.0, 0.5).inOrder()
    }

    @Test
    fun `impossible target falls back to the next lower load and offers the next higher`() {
        val load = PlateMath.load(100.3, gym)
        assertThat(load.exact).isFalse()
        assertThat(load.totalKg).isEqualTo(100.0)
        assertThat(load.nextLowerKg).isEqualTo(100.0)
        assertThat(load.nextHigherKg).isEqualTo(101.0)
    }

    @Test
    fun `exact load reports the neighbours on both sides`() {
        val load = PlateMath.load(100.0, gym)
        assertThat(load.nextLowerKg).isEqualTo(99.5)
        assertThat(load.nextHigherKg).isEqualTo(101.0)
    }

    @Test
    fun `empty bar is exact with no plates`() {
        val load = PlateMath.load(20.0, gym)
        assertThat(load.exact).isTrue()
        assertThat(load.perSide).isEmpty()
    }

    @Test
    fun `target below the bar is flagged`() {
        val load = PlateMath.load(15.0, gym)
        assertThat(load.belowBar).isTrue()
        assertThat(load.totalKg).isEqualTo(20.0)
        assertThat(load.perSide).isEmpty()
    }

    @Test
    fun `collars are counted in every load`() {
        val withCollars = gym.copy(useCollars = true, collarKg = 2.5)
        val load = PlateMath.load(100.0, withCollars)
        assertThat(load.exact).isTrue()
        // (100 - 20 bar - 5 collars) / 2 = 37.5 per side.
        assertThat(load.perSide).containsExactly(25.0, 10.0, 2.5).inOrder()
        assertThat(PlateMath.load(22.0, withCollars).belowBar).isTrue()
    }

    @Test
    fun `lighter bars change the plate maths`() {
        val load = PlateMath.load(55.0, gym.copy(barKg = 15.0))
        assertThat(load.exact).isTrue()
        assertThat(load.perSide).containsExactly(20.0)
    }

    @Test
    fun `limited inventory is solved exactly where greedy fails`() {
        // One 20 and two 15s per side. Greedy takes the 20 for 30 per side and gets stuck;
        // the exact solver finds 15 + 15.
        val small = GymProfile(plates = listOf(PlateStock(20.0, 1), PlateStock(15.0, 2)))
        val load = PlateMath.load(80.0, small)
        assertThat(load.exact).isTrue()
        assertThat(load.perSide).containsExactly(15.0, 15.0)
    }

    @Test
    fun `inventory limits cap the heaviest load`() {
        val small = GymProfile(plates = listOf(PlateStock(20.0, 1), PlateStock(15.0, 2)))
        assertThat(PlateMath.maxLoadKg(small)).isEqualTo(120.0)
        val tooHeavy = PlateMath.load(200.0, small)
        assertThat(tooHeavy.exact).isFalse()
        assertThat(tooHeavy.totalKg).isEqualTo(120.0)
        assertThat(tooHeavy.nextHigherKg).isNull()
    }

    @Test
    fun `plates with zero stock are never used`() {
        val no25 = GymProfile(plates = GymProfile.DEFAULT_PLATES.map { if (it.kg == 25.0) it.copy(perSide = 0) else it })
        assertThat(PlateMath.load(70.0, no25).perSide).doesNotContain(25.0)
        assertThat(PlateMath.load(70.0, no25).exact).isTrue()
    }

    @Test
    fun `float noise from unit conversion still counts as exact`() {
        val viaLb = Units.lbToKg(Units.kgToLb(102.5))
        val load = PlateMath.load(viaLb, gym)
        assertThat(load.exact).isTrue()
        assertThat(load.totalKg).isEqualTo(102.5)
    }

    @Test
    fun `rounding helpers`() {
        assertThat(PlateMath.roundDown(100.3, gym)).isEqualTo(100.0)
        assertThat(PlateMath.roundNearest(100.8, gym)).isEqualTo(101.0)
        assertThat(PlateMath.roundNearest(100.2, gym)).isEqualTo(100.0)
        assertThat(PlateMath.smallestStepKg(gym)).isEqualTo(1.0)
    }

    @Test
    fun `grouped plates count each denomination`() {
        val load = PlateMath.load(140.0, gym) // 60 per side = 25 + 25 + 10
        assertThat(load.grouped).containsExactly(25.0 to 2, 10.0 to 1).inOrder()
    }

    @Test
    fun `profile plate encoding round-trips`() {
        val encoded = GymProfileStore.encodePlates(GymProfile.DEFAULT_PLATES)
        assertThat(GymProfileStore.decodePlates(encoded)).isEqualTo(GymProfile.DEFAULT_PLATES)
        assertThat(GymProfileStore.decodePlates("garbage")).isNull()
    }
}
