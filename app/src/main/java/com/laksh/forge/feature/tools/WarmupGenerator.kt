package com.laksh.forge.feature.tools

import kotlin.math.floor

/** One line of a warm-up ramp. [load] tells the lifter exactly what goes on each side. */
data class WarmupStep(
    val kg: Double,
    val reps: Int,
    val sets: Int,
    val load: PlateLoad,
    val isWorkSet: Boolean = false,
    val label: String
)

data class WarmupPlan(
    val workKg: Double,
    val steps: List<WarmupStep>,
    /** Set when no ramp makes sense (working weight at or below the empty bar). */
    val note: String? = null
)

/**
 * Builds a barbell warm-up from the first working set.
 *
 * Shape of the ramp: the Starting Strength guidance in docs/research/programs/strength.json -
 * "empty bar 2x5 then about four even jumps to the work weight". The last jump lands on the
 * work weight itself, so the default four jumps give three intermediate warm-ups.
 *
 * Forge conventions on top of that (not from a source): reps taper 5 -> 3 -> 2 as the load
 * nears the work set so the warm-ups prime without tiring, jumps smaller than [MIN_JUMP_KG]
 * are merged because they stop being meaningfully different loads, and warm-up loads land on
 * [WARMUP_ROUND_KG] steps. Every load is rounded DOWN to what the gym's plates can build.
 */
object WarmupGenerator {

    const val DEFAULT_JUMPS = 4
    const val MIN_JUMP_KG = 5.0
    const val WARMUP_ROUND_KG = 2.5

    fun generate(
        workKg: Double,
        profile: GymProfile,
        jumps: Int = DEFAULT_JUMPS,
        workReps: Int = 5,
        workSets: Int = 1
    ): WarmupPlan {
        val base = PlateMath.roundKg(profile.emptyKg)
        val work = PlateMath.load(workKg, profile)
        if (work.belowBar || work.totalKg <= base + 1e-6) {
            return WarmupPlan(
                workKg = workKg,
                steps = emptyList(),
                note = "That's the empty bar or lighter, so there's nothing to ramp up to."
            )
        }

        val workLoaded = work.totalKg
        val gap = workLoaded - base
        val effectiveJumps = floor(gap / MIN_JUMP_KG).toInt().coerceIn(1, jumps.coerceAtLeast(1))

        // Intermediate loads at even fractions of the gap: first down to a round 2.5 kg step
        // (warm-ups don't need 61 kg precision, and round numbers are quicker to load), then
        // down to whatever the plates can actually build.
        val intermediates = mutableListOf<Double>()
        for (k in 1 until effectiveJumps) {
            val raw = base + gap * k / effectiveJumps
            val stepped = floor(raw / WARMUP_ROUND_KG + 1e-9) * WARMUP_ROUND_KG
            val rounded = PlateMath.roundDown(stepped, profile)
            val last = intermediates.lastOrNull() ?: base
            if (rounded > last + 1e-6 && rounded < workLoaded - 1e-6) intermediates += rounded
        }

        val steps = mutableListOf<WarmupStep>()
        steps += WarmupStep(
            kg = base,
            reps = 5,
            sets = 2,
            load = PlateMath.load(base, profile),
            label = "Empty bar"
        )
        intermediates.forEachIndexed { i, kg ->
            // Distance from the work set decides reps: the closest warm-up is the lightest in reps.
            val fromTop = intermediates.size - 1 - i
            val reps = when (fromTop) {
                0 -> if (intermediates.size == 1) 3 else 2
                1 -> 3
                else -> 5
            }
            steps += WarmupStep(
                kg = kg,
                reps = reps,
                sets = 1,
                load = PlateMath.load(kg, profile),
                label = "Warm-up ${i + 1}"
            )
        }
        steps += WarmupStep(
            kg = workLoaded,
            reps = workReps.coerceAtLeast(1),
            sets = workSets.coerceAtLeast(1),
            load = work,
            isWorkSet = true,
            label = "Work set"
        )

        val note = if (!work.exact) {
            "Your plates can't make ${Units.fmt(workKg)} kg exactly, so the work set uses ${Units.fmt(workLoaded)} kg."
        } else null
        return WarmupPlan(workKg = workKg, steps = steps, note = note)
    }
}
