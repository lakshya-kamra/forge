package com.laksh.forge.feature.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WarmupGeneratorTest {

    private val gym = GymProfile()

    @Test
    fun `100 kg ramps from the empty bar in four even jumps`() {
        val plan = WarmupGenerator.generate(100.0, gym)
        assertThat(plan.steps.map { it.kg }).containsExactly(20.0, 40.0, 60.0, 80.0, 100.0).inOrder()
        assertThat(plan.steps.map { it.reps }).containsExactly(5, 5, 3, 2, 5).inOrder()
        assertThat(plan.steps.first().sets).isEqualTo(2)
        assertThat(plan.steps.last().isWorkSet).isTrue()
        assertThat(plan.steps.dropLast(1).none { it.isWorkSet }).isTrue()
        assertThat(plan.note).isNull()
    }

    @Test
    fun `warm-ups land on round 2_5 kg steps`() {
        // Even thirds of 20 -> 100 are 46.7 and 73.3; they round down to 45 and 72.5.
        val plan = WarmupGenerator.generate(100.0, gym, jumps = 3)
        assertThat(plan.steps.map { it.kg }).containsExactly(20.0, 45.0, 72.5, 100.0).inOrder()
        assertThat(plan.steps.map { it.reps }).containsExactly(5, 3, 2, 5).inOrder()
    }

    @Test
    fun `every warm-up is loadable, increasing and below the work set`() {
        listOf(57.5, 102.5, 140.0, 182.5).forEach { work ->
            val steps = WarmupGenerator.generate(work, gym).steps
            val kgs = steps.map { it.kg }
            assertThat(kgs).isInStrictOrder()
            steps.forEach { step ->
                assertThat(PlateMath.load(step.kg, gym).exact).isTrue()
                assertThat(step.load.totalKg).isEqualTo(step.kg)
            }
            assertThat(kgs.dropLast(1).all { it < work }).isTrue()
        }
    }

    @Test
    fun `small gaps get fewer jumps`() {
        val plan = WarmupGenerator.generate(30.0, gym)
        assertThat(plan.steps.map { it.kg }).containsExactly(20.0, 25.0, 30.0).inOrder()
        assertThat(plan.steps[1].reps).isEqualTo(3)
    }

    @Test
    fun `a tiny gap is just the bar then the work set`() {
        val plan = WarmupGenerator.generate(22.5, gym)
        assertThat(plan.steps.map { it.kg }).containsExactly(20.0, 22.5).inOrder()
    }

    @Test
    fun `nothing to ramp to at or below the empty bar`() {
        val plan = WarmupGenerator.generate(20.0, gym)
        assertThat(plan.steps).isEmpty()
        assertThat(plan.note).isNotNull()
        assertThat(WarmupGenerator.generate(10.0, gym).steps).isEmpty()
    }

    @Test
    fun `collars raise the starting point`() {
        val plan = WarmupGenerator.generate(105.0, gym.copy(useCollars = true, collarKg = 2.5))
        assertThat(plan.steps.map { it.kg }).containsExactly(25.0, 45.0, 65.0, 85.0, 105.0).inOrder()
    }

    @Test
    fun `an unloadable work weight is rounded down and explained`() {
        val coarse = GymProfile(plates = listOf(PlateStock(20.0, 2), PlateStock(10.0, 2), PlateStock(5.0, 2)))
        val plan = WarmupGenerator.generate(101.0, coarse)
        assertThat(plan.steps.last().kg).isEqualTo(100.0)
        assertThat(plan.note).contains("100")
    }

    @Test
    fun `work reps and sets are carried through`() {
        val plan = WarmupGenerator.generate(100.0, gym, workReps = 8, workSets = 3)
        val work = plan.steps.last()
        assertThat(work.reps).isEqualTo(8)
        assertThat(work.sets).isEqualTo(3)
    }
}
