package com.laksh.forge.feature.tools

import kotlin.math.abs
import kotlin.math.roundToInt

/** How many plates of one denomination the gym has, counted per side of the bar. */
data class PlateStock(val kg: Double, val perSide: Int)

/**
 * The equipment a barbell can be loaded with. Everything is in kilograms because the gym
 * profile is a kg plate inventory; lb is only ever a display conversion on top.
 */
data class GymProfile(
    val barKg: Double = 20.0,
    val plates: List<PlateStock> = DEFAULT_PLATES,
    val useCollars: Boolean = false,
    /** Weight of ONE collar. Only counted when [useCollars] is on. */
    val collarKg: Double = 2.5
) {
    val collarsTotalKg: Double get() = if (useCollars) collarKg * 2 else 0.0

    /** The lightest load possible: the bar plus collars, no plates. */
    val emptyKg: Double get() = barKg + collarsTotalKg

    /** Denominations that are actually available, heaviest first. */
    val availablePlates: List<PlateStock>
        get() = plates.filter { it.perSide > 0 && it.kg > 0 }.sortedByDescending { it.kg }

    companion object {
        val BAR_OPTIONS = listOf(20.0, 15.0, 10.0)

        /** Every denomination the profile editor offers, heaviest first. */
        val DENOMINATIONS = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)

        val DEFAULT_PLATES = listOf(
            PlateStock(25.0, 4),
            PlateStock(20.0, 2),
            PlateStock(15.0, 2),
            PlateStock(10.0, 2),
            PlateStock(5.0, 2),
            PlateStock(2.5, 2),
            PlateStock(1.25, 2),
            PlateStock(0.5, 2)
        )
    }
}

/**
 * The answer to "what goes on the bar for [targetKg]". When the exact load can't be built
 * from the inventory, [totalKg] is the next lower achievable load (never heavier than asked,
 * so a prescription is never silently exceeded) and the neighbours are offered too.
 */
data class PlateLoad(
    val targetKg: Double,
    val totalKg: Double,
    /** Plates for ONE side, heaviest first (the order they go on the sleeve). */
    val perSide: List<Double>,
    val exact: Boolean,
    val nextLowerKg: Double?,
    val nextHigherKg: Double?,
    /** The target is lighter than the empty bar (plus collars). */
    val belowBar: Boolean
) {
    val plateCount: Int get() = perSide.size

    /** Plates per side grouped by denomination, heaviest first: [(25.0, 2), (5.0, 1)]. */
    val grouped: List<Pair<Double, Int>>
        get() = perSide.groupingBy { it }.eachCount().toList().sortedByDescending { it.first }
}

/**
 * Plate arithmetic against a real inventory. A greedy "biggest plate first" pass fails on
 * limited inventories (one 20 and two 15s per side can make 30 per side, greedy can't), so
 * this solves it exactly with a bounded knapsack over every reachable per-side sum. The sums
 * are tiny (a few hundred steps), so the whole table costs well under a millisecond and is
 * cached per profile.
 */
object PlateMath {

    private const val EPS = 1e-6

    /** Every reachable per-side sum for a profile, with the fewest-plates way to make it. */
    private class Table(
        /** Grams per DP step: the gcd of all plate weights. */
        val unitGrams: Int,
        /** Denominations in grams, heaviest first, aligned with each counts array. */
        val denomGrams: IntArray,
        /** best[s] = plate counts per denomination for a per-side sum of s units, or null. */
        val best: Array<IntArray?>
    ) {
        val reachable: IntArray by lazy { best.indices.filter { best[it] != null }.toIntArray() }
    }

    @Volatile private var cached: Pair<GymProfile, Table>? = null

    private fun table(profile: GymProfile): Table {
        cached?.let { (p, t) -> if (p == profile) return t }
        val built = build(profile)
        cached = profile to built
        return built
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun grams(kg: Double): Int = (kg * 1000.0).roundToInt()

    private fun build(profile: GymProfile): Table {
        val stock = profile.availablePlates
        val denom = stock.map { grams(it.kg) }.toIntArray()
        if (denom.isEmpty()) {
            return Table(1, denom, arrayOf(IntArray(0)))
        }
        val unit = denom.fold(0) { acc, g -> gcd(acc, g) }.coerceAtLeast(1)
        val w = IntArray(denom.size) { denom[it] / unit }
        val maxSum = stock.indices.sumOf { w[it] * stock[it].perSide }

        val best = arrayOfNulls<IntArray>(maxSum + 1)
        best[0] = IntArray(denom.size)

        // Bounded knapsack: each physical plate is its own 0/1 item. Iterating sums downward
        // within one plate's pass guarantees that plate is used at most once in that pass.
        for (i in denom.indices) {
            repeat(stock[i].perSide) {
                for (s in maxSum downTo w[i]) {
                    val prev = best[s - w[i]] ?: continue
                    val candidate = prev.copyOf().also { it[i]++ }
                    if (isBetter(candidate, best[s])) best[s] = candidate
                }
            }
        }
        return Table(unit, denom, best)
    }

    /** Fewer plates wins; on a tie, heavier plates win (less to carry, shorter stack). */
    private fun isBetter(candidate: IntArray, current: IntArray?): Boolean {
        if (current == null) return true
        val a = candidate.sum()
        val b = current.sum()
        if (a != b) return a < b
        for (i in candidate.indices) {
            if (candidate[i] != current[i]) return candidate[i] > current[i]
        }
        return false
    }

    private fun platesOf(t: Table, counts: IntArray): List<Double> = buildList {
        counts.forEachIndexed { i, n -> repeat(n) { add(t.denomGrams[i] / 1000.0) } }
    }

    private fun totalFor(profile: GymProfile, t: Table, units: Int): Double =
        roundKg(profile.emptyKg + 2.0 * units * t.unitGrams / 1000.0)

    /** Plates per side for [targetKg], or the next lower achievable load. */
    fun load(targetKg: Double, profile: GymProfile): PlateLoad {
        val t = table(profile)
        val empty = roundKg(profile.emptyKg)
        if (targetKg < empty - EPS) {
            return PlateLoad(
                targetKg = targetKg,
                totalKg = empty,
                perSide = emptyList(),
                exact = false,
                nextLowerKg = null,
                nextHigherKg = empty,
                belowBar = true
            )
        }

        // Per-side target in DP units. Within half a gram of a whole unit counts as that unit
        // (float noise from kg <-> lb round trips); otherwise a fraction can't be loaded, so floor.
        val perSideGrams = (targetKg - profile.emptyKg) / 2.0 * 1000.0
        val raw = perSideGrams / t.unitGrams
        val nearestUnit = kotlin.math.round(raw)
        val exactUnits = abs(raw - nearestUnit) * t.unitGrams < 0.5
        val targetUnits = if (exactUnits) nearestUnit.toInt() else kotlin.math.floor(raw).toInt()

        val reachable = t.reachable
        // Largest buildable sum not above the target. Sum 0 (empty bar) is always reachable.
        val chosen = reachable.lastOrNull { it <= targetUnits } ?: 0
        val isExact = exactUnits && chosen == targetUnits
        // Because `chosen` is the largest reachable sum <= target, the first sum above it is
        // also the first one above the target.
        val above = reachable.firstOrNull { it > chosen }
        val below = reachable.lastOrNull { it < chosen }

        val counts = t.best.getOrNull(chosen) ?: IntArray(t.denomGrams.size)
        return PlateLoad(
            targetKg = targetKg,
            totalKg = totalFor(profile, t, chosen),
            perSide = platesOf(t, counts),
            exact = isExact,
            // Exact: the neighbours either side of the target. Not exact: "lower" is the load
            // we fell back to, so the UI can say "nearest lower: X, next up: Y".
            nextLowerKg = if (isExact) below?.let { totalFor(profile, t, it) } else totalFor(profile, t, chosen),
            nextHigherKg = above?.let { totalFor(profile, t, it) },
            belowBar = false
        )
    }

    /** The heaviest achievable load that is not above [kg] (the empty bar at minimum). */
    fun roundDown(kg: Double, profile: GymProfile): Double = load(kg, profile).totalKg

    /** The achievable load closest to [kg]; ties go to the lighter one. */
    fun roundNearest(kg: Double, profile: GymProfile): Double {
        val l = load(kg, profile)
        if (l.belowBar || l.exact) return l.totalKg
        val up = l.nextHigherKg ?: return l.totalKg
        return if (up - kg < kg - l.totalKg) up else l.totalKg
    }

    /** The heaviest load the inventory can build at all. */
    fun maxLoadKg(profile: GymProfile): Double {
        val t = table(profile)
        return totalFor(profile, t, t.reachable.lastOrNull() ?: 0)
    }

    /** The smallest step between two loads (two of the lightest plate). */
    fun smallestStepKg(profile: GymProfile): Double? =
        profile.availablePlates.minByOrNull { it.kg }?.let { roundKg(it.kg * 2) }

    /** Strip float noise so 0.1 + 0.2 style artefacts never reach the UI or comparisons. */
    fun roundKg(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
}
