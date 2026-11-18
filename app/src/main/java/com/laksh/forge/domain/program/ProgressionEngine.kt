package com.laksh.forge.domain.program

import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Severity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Everything the engine needs to prescribe one slot of one session. */
data class SlotInput(
    val slot: LibraryExercise,
    val engine: EngineConfig,
    val state: ProgramState,
    /** The session's week (1-based, within the cycle for wave programs). */
    val week: Int,
    /** Earlier finished sessions of this slot, newest first, already scoped by the caller. */
    val history: List<SessionPerformance>,
    val trainingMaxes: Map<String, Double> = emptyMap(),
    /** Starting working weight for this slot, when one was entered at adoption. */
    val startKg: Double? = null,
    /** Heaviest set logged today for [LibraryExercise.ref] (back-off and drop sets). */
    val todayTopKg: Double? = null,
    /** Heaviest set of [LibraryExercise.ref] in its most recent earlier session. */
    val lastTopKgForRef: Double? = null,
    /** Heaviest recent 3-5 rep set of [LibraryExercise.ref] (PHAT speed work). */
    val recentRmKg: Double? = null,
    val loadJumpCheck: (Double, Double, String) -> GuardrailFlag? = { prev, next, key ->
        Guardrails.loadJumpFlag(prev, next, key)
    }
)

/**
 * Turns a program's stated rules plus the lifter's history into concrete set targets.
 *
 * Each slot carries a rule picked by the asset converter from the program's own text:
 * - LINEAR: add the slot's increment after a session where every prescribed rep was hit
 *   (per session, or per week for weekly programs); repeat on a miss; after the program's
 *   number of consecutive misses at one weight, cut by its deload percentage.
 * - DOUBLE: same weight until every set reaches the top of the rep range, then add load.
 * - RIR: hold the load and steer it by the reps-in-reserve the lifter logged against the target.
 * - PERCENT: per-set percentages of a training max, 1RM or 5RM (5/3/1, nSuns, Candito, Texas).
 * - TOP_SET / RECENT_RM: a share of today's top set or of a recent 3-5RM.
 * - STAGES: GZCLP's T1/T2/T3 stage ladder. RAMP: Madcow's ramped 5x5. PR_ATTEMPT: Texas Friday.
 * - WAVE: Helms' weekly wave loading. WORK_UP: find a rep max in the session. NONE: log only.
 *
 * Every prescribed jump is passed through [Guardrails.loadJumpFlag]; a BLOCK holds the load.
 */
object ProgressionEngine {

    const val DEFAULT_INCREMENT_KG = 2.5

    fun prescribe(input: SlotInput): Prescription {
        val raw = when (input.slot.rule) {
            Rules.LINEAR -> linear(input)
            Rules.DOUBLE -> double(input)
            Rules.RIR -> rir(input)
            Rules.PERCENT -> percent(input)
            Rules.TOP_SET -> shareOf(input, input.todayTopKg ?: input.lastTopKgForRef, "top set")
            Rules.RECENT_RM -> shareOf(input, input.recentRmKg, "your recent 3-5RM")
            Rules.WORK_UP -> workUp(input)
            Rules.STAGES -> stages(input)
            Rules.RAMP -> ramp(input)
            Rules.PR_ATTEMPT -> prAttempt(input)
            Rules.WAVE -> wave(input)
            else -> logOnly(input)
        }
        return deloadWeek(input, guard(input, raw))
    }

    // ------------------------------------------------------------------ shared helpers

    private fun step(i: SlotInput) = i.state.roundingKg.takeIf { it > 0 } ?: DEFAULT_INCREMENT_KG

    private fun inc(i: SlotInput) = i.slot.incrementKg ?: DEFAULT_INCREMENT_KG

    private fun r(i: SlotInput, kg: Double) = Strength.roundTo(kg, step(i)).coerceAtLeast(0.0)

    private fun lowReps(s: LibraryExercise) = s.repsLow ?: s.repsHigh ?: s.setScheme?.firstOrNull()?.reps ?: 1

    private fun highReps(s: LibraryExercise) = s.repsHigh ?: lowReps(s)

    private fun kg(v: Double) = "${Strength.fmt(v)} kg"

    /** Every prescribed set present and at or above its target reps. */
    fun hit(p: SessionPerformance, plannedSets: Int, defaultReps: Int): Boolean =
        p.sets.size >= plannedSets && p.sets.isNotEmpty() &&
            p.sets.all { it.reps >= (it.targetReps ?: defaultReps) }

    /** Consecutive most-recent misses at the same prescribed weight. */
    fun consecutiveMisses(history: List<SessionPerformance>, plannedSets: Int, reps: Int): Int {
        val first = history.firstOrNull() ?: return 0
        val weight = first.targetWeight
        var k = 0
        for (p in history) {
            if (!hit(p, plannedSets, reps) && abs(p.targetWeight - weight) < 0.01) k++ else break
        }
        return k
    }

    private fun straightSets(
        s: LibraryExercise,
        n: Int,
        weight: Double?,
        reps: Int,
        repsHigh: Int? = null,
        label: String? = null
    ): List<SetTarget> = List(n) { idx ->
        SetTarget(
            weightKg = weight,
            reps = reps,
            repsHigh = repsHigh?.takeIf { it > reps },
            amrap = s.lastSetAmrap && idx == n - 1,
            rir = s.rirTarget,
            label = label
        )
    }

    // ------------------------------------------------------------------ LINEAR

    private fun linear(i: SlotInput): Prescription {
        val s = i.slot
        val n = s.setCount
        val reps = lowReps(s)
        val h = i.history
        if (h.isEmpty()) {
            val w = i.startKg?.let { r(i, it) }
            return Prescription(
                sets = straightSets(s, n, w, reps),
                reason = if (w == null) {
                    "Pick a weight you can lift for all $n×$reps with good form - it becomes your starting point."
                } else "Starting weight."
            )
        }
        val e = i.engine
        val last = h.first()
        val lastT = last.targetWeight
        val ok = hit(last, n, reps)
        val misses = consecutiveMisses(h, n, reps)
        val failLimit = e.failLimit
        val deloadPct = e.deloadPct ?: 0.10
        val deload = !ok && failLimit != null && misses >= failLimit

        fun deloaded(): Prescription {
            val w = r(i, lastT * (1 - deloadPct))
            return Prescription(
                straightSets(s, n, w, reps),
                "−${(deloadPct * 100).toInt()}%: missed reps $misses sessions in a row at ${kg(lastT)}. Work back up from here."
            )
        }

        if (e.cadence == "WEEK") {
            val sameWeek = i.state.weekStartedAt > 0 && last.startedAt >= i.state.weekStartedAt
            if (deload) return deloaded()
            if (sameWeek) {
                return Prescription(straightSets(s, n, r(i, lastT), reps), "Same weight all week.")
            }
            val every = s.everyWeeks ?: 1
            val due = every <= 1 || (i.week - 1) % every == 0
            return when {
                ok && due -> Prescription(
                    straightSets(s, n, r(i, lastT + inc(i)), reps, label = "+${Strength.fmt(inc(i))}"),
                    "New week: +${kg(inc(i))}."
                )
                ok -> Prescription(
                    straightSets(s, n, r(i, lastT), reps),
                    "This lift goes up every $every weeks."
                )
                else -> Prescription(
                    straightSets(s, n, r(i, lastT), reps),
                    "Repeat ${kg(lastT)}: reps were missed last time."
                )
            }
        }

        if (ok) {
            val previous = h.getOrNull(1)
            if (e.bounceBack && previous != null && !hit(previous, n, reps) && lastT < previous.targetWeight - 0.01) {
                val back = previous.targetWeight
                return Prescription(
                    straightSets(s, n, r(i, back), reps),
                    "Back to ${kg(back)} after the lighter session."
                )
            }
            return Prescription(
                straightSets(s, n, r(i, lastT + inc(i)), reps, label = "+${Strength.fmt(inc(i))}"),
                "+${kg(inc(i))}: every rep done last time."
            )
        }
        if (deload) return deloaded()
        val left = failLimit?.let { " (${misses} of $it before a deload)" }.orEmpty()
        return Prescription(
            straightSets(s, n, r(i, lastT), reps),
            "Repeat ${kg(lastT)}: missed reps last time$left."
        )
    }

    // ------------------------------------------------------------------ DOUBLE

    private fun double(i: SlotInput): Prescription {
        val s = i.slot
        val scheme = s.setScheme.orEmpty()
        return if (s.perSet && scheme.isNotEmpty()) doublePerSet(i, scheme) else doubleStraight(i)
    }

    private fun doubleStraight(i: SlotInput): Prescription {
        val s = i.slot
        val n = s.setCount
        val lo = lowReps(s)
        val hi = highReps(s)
        val last = i.history.firstOrNull()
        if (last == null || last.sets.isEmpty()) {
            val w = i.startKg?.let { r(i, it) }
            return Prescription(
                straightSets(s, n, w, lo, hi),
                reason = "Find a weight you can do for $lo-$hi reps; add reps each session until every set reaches $hi.",
                suggestedKg = null
            )
        }
        val w = last.sets.first().weightKg
        val allTop = last.sets.size >= n && last.sets.take(n).all { it.reps >= hi }
        val allBelow = s.dropBelowLow && last.sets.all { it.reps < lo }
        if (w <= 0.0) {
            // Bodyweight: progress reps; load is the lifter's call.
            return Prescription(
                List(n) { idx ->
                    val prev = last.sets.getOrNull(idx)?.reps ?: lo
                    SetTarget(null, (prev + 1).coerceIn(lo, hi), hi, s.lastSetAmrap && idx == n - 1, s.rirTarget)
                },
                reason = if (allTop) "Top of the range on every set - add load or a harder variation." else "Aim for one more rep per set."
            )
        }
        return when {
            allTop -> Prescription(
                straightSets(s, n, r(i, w + inc(i)), lo, hi, label = "+${Strength.fmt(inc(i))}"),
                "+${kg(inc(i))}: every set reached $hi last time. Start back at $lo+ reps."
            )
            allBelow -> Prescription(
                straightSets(s, n, r(i, max(0.0, w - inc(i))), lo, hi),
                "Lighter: reps fell below $lo on every set."
            )
            else -> Prescription(
                List(n) { idx ->
                    val prev = last.sets.getOrNull(idx)?.reps
                    val target = if (prev == null) lo else (prev + 1).coerceIn(lo, hi)
                    SetTarget(r(i, w), target, hi, s.lastSetAmrap && idx == n - 1, s.rirTarget)
                },
                "Same ${kg(w)}: one more rep per set until all reach $hi."
            )
        }
    }

    private fun doublePerSet(i: SlotInput, scheme: List<SetSpec>): Prescription {
        val s = i.slot
        val last = i.history.firstOrNull()
        var raised = 0
        val sets = scheme.mapIndexed { idx, spec ->
            val lo = spec.repsLow ?: spec.reps ?: lowReps(s)
            val hi = spec.repsHigh ?: spec.reps ?: highReps(s)
            val prev = last?.sets?.getOrNull(idx)
            when {
                prev == null || prev.weightKg <= 0.0 -> SetTarget(
                    weightKg = if (prev == null) i.startKg?.let { r(i, it) } else null,
                    reps = lo, repsHigh = hi, amrap = spec.amrap, rir = s.rirTarget
                )
                prev.reps >= hi -> {
                    raised++
                    SetTarget(r(i, prev.weightKg + inc(i)), lo, hi, spec.amrap, s.rirTarget, "+${Strength.fmt(inc(i))}")
                }
                else -> SetTarget(r(i, prev.weightKg), (prev.reps + 1).coerceIn(lo, hi), hi, spec.amrap, s.rirTarget)
            }
        }
        val reason = when {
            last == null -> "Each set has its own rep range; find a weight for each."
            raised > 0 -> "+${kg(inc(i))} on $raised set${if (raised == 1) "" else "s"} that reached the top of the range."
            else -> "Same weights: one more rep per set."
        }
        return Prescription(sets, reason)
    }

    // ------------------------------------------------------------------ RIR

    private fun rir(i: SlotInput): Prescription {
        val s = i.slot
        val n = s.setCount
        val lo = lowReps(s)
        val hi = highReps(s)
        val target = s.rirTarget ?: 2.0
        val last = i.history.firstOrNull()
        if (last == null || last.sets.isEmpty()) {
            val w = i.startKg?.let { r(i, it) }
            return Prescription(
                straightSets(s, n, w, lo, hi),
                "Pick a load that leaves about ${Strength.fmt(target)} reps in reserve at $lo-$hi reps."
            )
        }
        val logged = last.sets.mapNotNull { it.rir }
        if (logged.isEmpty()) return doubleStraight(i)
        val w = last.sets.first().weightKg
        if (w <= 0.0) return doubleStraight(i)
        val mean = logged.average()
        val delta = mean - target
        val belowLow = last.sets.count { it.reps < lo } * 2 > last.sets.size
        val step = inc(i)
        return when {
            belowLow -> Prescription(
                straightSets(s, n, r(i, max(0.0, w - step)), lo, hi),
                "Lighter: reps fell below $lo."
            )
            delta >= 1.0 -> {
                val jumps = min(2.0, floor(delta))
                Prescription(
                    straightSets(s, n, r(i, w + step * jumps), lo, hi, label = "+${Strength.fmt(step * jumps)}"),
                    "You logged ~${Strength.fmt(mean)} in reserve vs a target of ${Strength.fmt(target)}: heavier."
                )
            }
            delta <= -1.0 -> Prescription(
                straightSets(s, n, r(i, max(0.0, w - step)), lo, hi),
                "Closer to failure than planned (~${Strength.fmt(mean)} in reserve): a little lighter."
            )
            last.sets.size >= n && last.sets.all { it.reps >= hi } -> Prescription(
                straightSets(s, n, r(i, w + step), lo, hi, label = "+${Strength.fmt(step)}"),
                "On target and at the top of the range: +${kg(step)}."
            )
            else -> Prescription(
                straightSets(s, n, r(i, w), lo, hi),
                "On target: same ${kg(w)}, aim for one more rep."
            )
        }
    }

    // ------------------------------------------------------------------ PERCENT

    private fun maxFor(i: SlotInput, basis: String, key: String): Double? = when (basis) {
        "TM" -> i.trainingMaxes[key]
        else -> i.state.maxes["$basis:$key"]
    }

    private fun percent(i: SlotInput): Prescription {
        val s = i.slot
        val key = s.maxKey ?: s.exerciseId ?: ProgressionConfig.normalize(s.name)
        val scheme = s.setScheme.orEmpty()
        if (scheme.isEmpty()) return logOnly(i)
        var missingBasis: String? = null
        val sets = scheme.mapIndexed { idx, spec ->
            val basis = spec.of ?: "TM"
            val pct = spec.percent ?: 0.0
            val m = maxFor(i, basis, key)
            if (m == null) missingBasis = basis
            val add = spec.addKg ?: 0.0
            val reps = spec.reps ?: lowReps(s)
            val repsHigh = if (!spec.amrap && scheme.size == s.setCount && (s.repsHigh ?: reps) > reps &&
                s.repsLow == reps) s.repsHigh else null
            SetTarget(
                weightKg = m?.let { r(i, it * pct + add) },
                reps = reps,
                repsHigh = repsHigh,
                amrap = spec.amrap || (s.lastSetAmrap && idx == scheme.lastIndex && scheme.none { it.amrap }),
                rir = null,
                label = buildString {
                    append(Strength.fmt(pct * 100)).append("% ").append(basis)
                    if (add != 0.0) append(if (add > 0) " +" else " −").append(Strength.fmt(abs(add)))
                }
            )
        }
        val reason = missingBasis?.let { "Set your ${basisName(it)} for ${s.name} to see weights." }
            ?: maxFor(i, scheme.first().of ?: "TM", key)?.let { "${basisName(scheme.first().of ?: "TM")} ${kg(it)}." }
        return Prescription(sets, reason)
    }

    fun basisName(basis: String) = when (basis) {
        "TM" -> "training max"
        "1RM" -> "1-rep max"
        "5RM" -> "5-rep max"
        else -> basis
    }

    private fun shareOf(i: SlotInput, base: Double?, what: String): Prescription {
        val s = i.slot
        val scheme = s.setScheme.orEmpty().ifEmpty {
            List(s.setCount) { SetSpec(reps = lowReps(s), percent = 1.0) }
        }
        val sets = scheme.mapIndexed { idx, spec ->
            val pct = spec.percent ?: 1.0
            val add = spec.addKg ?: 0.0
            SetTarget(
                weightKg = base?.let { r(i, it * pct + add) },
                reps = spec.reps ?: lowReps(s),
                amrap = spec.amrap,
                rir = s.rirTarget,
                label = buildString {
                    if (pct != 1.0) append(Strength.fmt(pct * 100)).append("% of ").append(what)
                    else append(what)
                    if (add != 0.0) append(if (add > 0) " +" else " −").append(Strength.fmt(abs(add)))
                }
            )
        }
        val reason = if (base == null) "Log the $what first - these sets are worked out from it."
        else "Based on ${kg(base)} ($what)."
        return Prescription(sets, reason, suggestedKg = i.history.firstOrNull()?.sets?.firstOrNull()?.weightKg)
    }

    // ------------------------------------------------------------------ WORK_UP / NONE

    private fun workUp(i: SlotInput): Prescription {
        val s = i.slot
        val reps = lowReps(s)
        val lastBest = i.history.firstOrNull()?.sets?.maxByOrNull { it.weightKg }
        return Prescription(
            sets = listOf(SetTarget(null, reps, label = if (reps == 1) "1RM" else "${reps}RM")),
            reason = buildString {
                append("Work up in jumps to a heavy set of $reps.")
                lastBest?.let { append(" Last time: ${kg(it.weightKg)} × ${it.reps}.") }
            },
            suggestedKg = lastBest?.weightKg,
            workUp = true
        )
    }

    private fun logOnly(i: SlotInput): Prescription {
        val s = i.slot
        val n = s.setCount
        val lo = s.repsLow ?: s.repsHigh
        val last = i.history.firstOrNull()
        return Prescription(
            sets = List(n) { idx ->
                SetTarget(
                    weightKg = null,
                    reps = lo ?: (last?.sets?.getOrNull(idx)?.reps ?: 0),
                    repsHigh = s.repsHigh?.takeIf { lo != null && it > lo },
                    amrap = s.lastSetAmrap && idx == n - 1,
                    rir = s.rirTarget
                )
            },
            reason = null,
            suggestedKg = last?.sets?.firstOrNull()?.weightKg
        )
    }

    // ------------------------------------------------------------------ STAGES (GZCLP)

    private data class Stage(val sets: Int, val reps: Int)

    private val t1Stages = listOf(Stage(5, 3), Stage(6, 2), Stage(10, 1))
    private val t2Stages = listOf(Stage(3, 10), Stage(3, 8), Stage(3, 6))
    private val t3Stage = Stage(3, 15)

    private fun stageSets(stage: Stage, weight: Double?, amrapLast: Boolean, label: String) =
        List(stage.sets) { idx ->
            SetTarget(weight, stage.reps, amrap = amrapLast && idx == stage.sets - 1, label = label)
        }

    private fun stages(i: SlotInput): Prescription {
        val s = i.slot
        val h = i.history
        val tier = s.tier ?: "T3"
        val last = h.firstOrNull()
        if (tier == "T3") {
            val w = last?.sets?.firstOrNull()?.weightKg ?: i.startKg
            val lastRep = last?.sets?.lastOrNull()?.reps ?: 0
            return if (last != null && lastRep >= 25 && w != null) {
                Prescription(
                    stageSets(t3Stage, r(i, w + inc(i)), true, "3×15+"),
                    "+${kg(inc(i))}: the last set reached $lastRep reps."
                )
            } else {
                Prescription(
                    stageSets(t3Stage, w?.let { r(i, it) }, true, "3×15+"),
                    "Add weight once the last set reaches 25 reps."
                )
            }
        }
        val ladder = if (tier == "T1") t1Stages else t2Stages
        val amrap = tier == "T1"
        if (last == null || last.sets.isEmpty()) {
            val w = i.startKg?.let { r(i, it) }
            return Prescription(
                stageSets(ladder[0], w, amrap, stageLabel(ladder[0], amrap)),
                if (w == null) "Start with the bar or your last weight from a previous program." else "Stage 1."
            )
        }
        val lastTargetReps = last.sets.first().targetReps ?: last.sets.first().reps
        val wasTest = tier == "T1" && last.sets.size == 1 && lastTargetReps == 5
        if (wasTest) {
            val tested = last.sets.filter { it.reps >= 5 }.maxOfOrNull { it.weightKg } ?: last.topWeight
            val w = r(i, tested * 0.85)
            return Prescription(
                stageSets(ladder[0], w, amrap, stageLabel(ladder[0], amrap)),
                "New cycle at 85% of your ${kg(tested)} 5RM."
            )
        }
        val stageIdx = ladder.indexOfFirst { it.reps == lastTargetReps }.let { if (it < 0) 0 else it }
        val stage = ladder[stageIdx]
        val lastW = last.targetWeight
        val total = last.sets.sumOf { it.reps }
        val base = stage.sets * stage.reps
        if (total >= base) {
            return Prescription(
                stageSets(stage, r(i, lastW + inc(i)), amrap, stageLabel(stage, amrap)),
                "+${kg(inc(i))}: $total of $base reps done."
            )
        }
        if (stageIdx < ladder.lastIndex) {
            val next = ladder[stageIdx + 1]
            return Prescription(
                stageSets(next, r(i, lastW), amrap, stageLabel(next, amrap)),
                "Missed the base volume ($total of $base): same weight, next stage."
            )
        }
        if (tier == "T1") {
            return Prescription(
                listOf(SetTarget(null, 5, label = "New 5RM")),
                "All three stages done. Rest 2-3 days, then work up to a new 5RM; the next cycle starts at 85% of it.",
                suggestedKg = lastW,
                workUp = true
            )
        }
        // T2 restart: slightly heavier than the last time 3x10 was run.
        val lastTen = h.firstOrNull { (it.sets.firstOrNull()?.targetReps ?: 0) == ladder[0].reps }?.targetWeight
        val restart = r(i, (lastTen ?: lastW) + inc(i))
        return Prescription(
            stageSets(ladder[0], restart, amrap, stageLabel(ladder[0], amrap)),
            "Stage ladder finished: back to 3×10, slightly heavier than last time."
        )
    }

    private fun stageLabel(stage: Stage, amrap: Boolean) =
        "${stage.sets}×${stage.reps}${if (amrap) "+" else ""}"

    // ------------------------------------------------------------------ RAMP (Madcow)

    private const val MADCOW_STEP = 0.125
    const val MADCOW_WEEKLY = 1.025

    private fun ramp(i: SlotInput): Prescription {
        val s = i.slot
        val key = s.maxKey ?: s.exerciseId ?: ProgressionConfig.normalize(s.name)
        val top = i.state.maxes["TOP:$key"]
        val n = s.setCount
        fun rampW(idx: Int, count: Int): Double? = top?.let { r(i, it * (1 - MADCOW_STEP * (count - 1 - idx))) }
        val sets: List<SetTarget> = when (s.role) {
            "WED_SQUAT" -> List(n) { idx ->
                // First three sets match Monday's ramp; the fourth repeats the third.
                val w = rampW(min(idx, 2), 5)
                SetTarget(w, 5, label = if (idx == n - 1) "repeat" else null)
            }
            "FRI" -> List(n) { idx ->
                when (idx) {
                    4 -> SetTarget(top?.let { r(i, it * MADCOW_WEEKLY) }, 3, label = "+2.5% triple")
                    5 -> SetTarget(rampW(2, 5), 8, label = "back-off")
                    else -> SetTarget(rampW(idx, 5), 5)
                }
            }
            else -> List(n) { idx -> SetTarget(rampW(idx, n), 5, label = if (idx == n - 1) "top set" else null) }
        }
        val reason = if (top == null) "Set your 5RM for ${s.name} to see the ramp." else when (s.role) {
            "FRI" -> "Ramp to Monday's top set, then a triple 2.5% above it."
            "WED_SQUAT" -> "Light day: Monday's first three sets, then repeat the third."
            else -> "Ramp in 12.5% steps to a top set of ${kg(top)}."
        }
        return Prescription(sets, reason)
    }

    // ------------------------------------------------------------------ PR_ATTEMPT (Texas)

    private fun prAttempt(i: SlotInput): Prescription {
        val s = i.slot
        val key = s.maxKey ?: s.exerciseId ?: ProgressionConfig.normalize(s.name)
        val five = i.state.maxes["5RM:$key"]
        val w = five?.let { r(i, it + inc(i)) }
        return Prescription(
            sets = List(s.setCount) { SetTarget(w, 5, label = "new 5RM") },
            reason = if (five == null) "Set your 5RM for ${s.name} first." else "Last 5RM ${kg(five)}: go for ${kg(w ?: five)} (within 2% is fine if form slips)."
        )
    }

    // ------------------------------------------------------------------ WAVE (Helms)

    /** Load offset of a wave week from the block-1 week-1 base. */
    fun waveOffset(week: Int, cycle: Int, step: Double, optionalWeeks: List<Int>): Double {
        val withinBlock = if (week in optionalWeeks) 0 else (week - 1).coerceAtLeast(0)
        return ((cycle - 1).coerceAtLeast(0) + withinBlock) * step
    }

    private fun wave(i: SlotInput): Prescription {
        val s = i.slot
        val n = s.setCount
        val reps = lowReps(s)
        val base = i.startKg
        val offset = waveOffset(i.week, i.state.cycle, inc(i), i.engine.optionalWeeks)
        val w = base?.let { r(i, it + offset) }
        val reason = when {
            base == null -> "Week ${i.week}: pick a weight that leaves ~2 reps in reserve on the first set; the wave builds from it."
            i.week in i.engine.optionalWeeks -> "Deload: week-1 load with fewer sets."
            else -> "Wave week ${i.week}: ${kg(w ?: 0.0)}."
        }
        return Prescription(straightSets(s, n, w, reps), reason)
    }

    // ------------------------------------------------------------------ post-processing

    /** GVS-style deload weeks: one set per exercise at the same intensity. */
    private fun deloadWeek(i: SlotInput, p: Prescription): Prescription {
        val sets = i.engine.deloadSets ?: return p
        if (i.week !in i.engine.deloadWeeks || p.sets.size <= sets) return p
        return p.copy(sets = p.sets.take(sets), reason = "Deload week: $sets set${if (sets == 1) "" else "s"} at the same intensity.")
    }

    /** Sessions of a slot the load-jump check looks back over (covers a full wave + deload). */
    private const val GUARD_LOOKBACK = 6

    /**
     * A load the lifter has already shown they can handle, which loads worked out from it are
     * not a "jump" past: the max a percentage is taken of, a Texas/Madcow 5RM, or the top set
     * a back-off is based on.
     */
    private fun basisReference(i: SlotInput): Double? {
        val s = i.slot
        val key = s.maxKey ?: s.exerciseId ?: ProgressionConfig.normalize(s.name)
        return when (s.rule) {
            Rules.PERCENT -> s.setScheme.orEmpty().map { it.of ?: "TM" }.distinct()
                .mapNotNull { maxFor(i, it, key) }.maxOrNull()
            Rules.RAMP -> listOfNotNull(i.state.maxes["5RM:$key"], i.state.maxes["TOP:$key"]).maxOrNull()
            Rules.PR_ATTEMPT -> i.state.maxes["5RM:$key"]
            Rules.TOP_SET -> i.todayTopKg ?: i.lastTopKgForRef
            Rules.RECENT_RM -> i.recentRmKg
            else -> null
        }
    }

    /**
     * Guardrails cap a session-to-session jump at ~10%. "Previous" is the heaviest load of the
     * last few sessions (or the max the sets derive from), not just the last session: waved
     * programs deliberately follow a light day or deload week with a heavy one (5/3/1 week 1
     * after the deload, Texas Friday after the light Wednesday), and returning to a weight
     * already lifted is not a jump.
     */
    private fun guard(i: SlotInput, p: Prescription): Prescription {
        val proposed = p.sets.mapNotNull { it.weightKg }.maxOrNull() ?: return p
        val recent = i.history.take(GUARD_LOOKBACK).maxOfOrNull { it.topWeight }?.takeIf { it > 0 }
        val previous = listOfNotNull(recent, basisReference(i)?.takeIf { it > 0 }).maxOrNull() ?: return p
        if (proposed <= previous) return p
        val key = i.slot.exerciseId ?: ProgressionConfig.normalize(i.slot.name)
        val flag = i.loadJumpCheck(previous, proposed, key) ?: return p
        if (flag.severity != Severity.BLOCK) return p.copy(flag = flag)
        return p.copy(
            sets = p.sets.map { t -> t.copy(weightKg = t.weightKg?.let { min(it, previous) }) },
            reason = "Held at ${kg(previous)}: ${flag.message}",
            flag = flag
        )
    }
}
