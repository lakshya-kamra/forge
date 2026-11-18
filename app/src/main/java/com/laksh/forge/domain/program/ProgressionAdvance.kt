package com.laksh.forge.domain.program

import kotlin.math.abs

/** One slot as it was performed in a finished session. */
data class FinishedSlot(
    val slot: LibraryExercise,
    /** What was prescribed, in set order. */
    val targets: List<SetTarget>,
    /** What was logged, in set order (working sets only). */
    val sets: List<LoggedSet>,
    /** Key under which a starting weight is stored for this slot. */
    val startKey: String
)

data class AdvanceResult(
    val state: ProgramState,
    /** New training maxes to write to TrainingDao, keyed by lift. */
    val tmUpdates: Map<String, Double>,
    /** Plain-language changes, shown when the session is saved. */
    val messages: List<String>
)

/**
 * What finishing a program session changes. Linear, double-progression and RIR slots need no
 * stored state (their next load is read from history); everything that depends on a stored
 * number - training maxes, 1RM/5RM references, Madcow top sets, wave bases, weeks and cycles -
 * moves here, exactly as each program's rules say.
 */
object ProgressionAdvance {

    fun onSessionFinished(
        config: ProgressionConfig,
        sessionWeek: Int,
        slots: List<FinishedSlot>,
        trainingMaxes: Map<String, Double>,
        now: Long
    ): AdvanceResult {
        val e = config.engine
        var st = config.state
        val step = st.roundingKg.takeIf { it > 0 } ?: ProgressionEngine.DEFAULT_INCREMENT_KG
        val tms = trainingMaxes.toMutableMap()
        val tmUpdates = linkedMapOf<String, Double>()
        val maxes = st.maxes.toMutableMap()
        val starts = st.starts.toMutableMap()
        val missed = st.missed.toMutableSet()
        val pending = st.pendingMaxes.toMutableMap()
        val msgs = mutableListOf<String>()

        fun setTm(key: String, value: Double, why: String) {
            val old = tms[key]
            if (old != null && abs(old - value) < 0.001) return
            tms[key] = value
            tmUpdates[key] = value
            msgs += "${liftName(key)} training max ${old?.let { Strength.fmt(it) + " → " }.orEmpty()}${Strength.fmt(value)} kg ($why)."
        }

        for (fs in slots) {
            val s = fs.slot
            val key = s.maxKey ?: s.exerciseId ?: ProgressionConfig.normalize(s.name)
            if (fs.sets.isEmpty()) continue
            when (e.model) {
                "NSUNS" -> if (s.rule == Rules.PERCENT) {
                    val scheme = s.setScheme.orEmpty()
                    val onePlus = scheme.indexOfFirst { it.amrap && (it.percent ?: 0.0) >= 0.95 }
                    if (onePlus >= 0) {
                        val reps = fs.sets.getOrNull(onePlus)?.reps
                        val cur = tms[key]
                        if (reps != null && cur != null) {
                            val add = e.amrapRules.firstOrNull { reps >= it.minReps && (it.maxReps == null || reps <= it.maxReps) }?.addKg ?: 0.0
                            if (add > 0) setTm(key, Strength.ceilTo(cur + add, step), "$reps reps on the 1+ set")
                            else msgs += "${liftName(key)} training max stays at ${Strength.fmt(cur)} kg ($reps rep${if (reps == 1) "" else "s"} on the 1+ set)."
                        }
                    } else if (key in e.allRepsKeys) {
                        val cur = tms[key]
                        val allDone = fs.sets.size >= fs.targets.size &&
                            fs.sets.zip(fs.targets).all { (l, t) -> l.reps >= t.reps }
                        if (cur != null && allDone) {
                            setTm(key, Strength.ceilTo(cur + (s.incrementKg ?: step), step), "every rep completed")
                        }
                    }
                }
                "WENDLER" -> if (s.rule == Rules.PERCENT) {
                    val short = fs.sets.size < fs.targets.size ||
                        fs.sets.zip(fs.targets).any { (l, t) -> l.reps < t.reps }
                    if (short && key !in missed) {
                        missed += key
                        msgs += "${liftName(key)}: a rep was missed this cycle, so its training max will hold."
                    }
                }
                "CANDITO" -> if (s.rule == Rules.PERCENT && s.setScheme.orEmpty().any { it.of == "1RM" }) {
                    val mk = "1RM:$key"
                    val cur = maxes[mk]
                    val cut = e.missedRepCutPct ?: 0.025
                    if (cur != null) {
                        val amrapIdx = s.setScheme.orEmpty().indexOfFirst { it.amrap }
                        val cutBelow = s.cutBelowReps
                        val missedRequired = fs.sets.zip(fs.targets).any { (l, t) -> !t.amrap && l.reps < t.reps }
                        val amrapShort = cutBelow != null && amrapIdx >= 0 &&
                            (fs.sets.getOrNull(amrapIdx)?.reps ?: cutBelow) < cutBelow
                        if (missedRequired || amrapShort) {
                            val v = Strength.roundTo(cur * (1 - cut), step)
                            maxes[mk] = v
                            msgs += "${liftName(key)} max ${Strength.fmt(cur)} → ${Strength.fmt(v)} kg " +
                                (if (amrapShort) "(under $cutBelow reps on the max-rep set)." else "(a required rep was missed).")
                        }
                        // Week-5 test set: reps achieved project next cycle's max.
                        val scheme = s.setScheme.orEmpty()
                        if (sessionWeek == e.projectionWeek && scheme.size == 1 && (scheme[0].percent ?: 0.0) >= 0.97) {
                            val done = fs.sets.first()
                            val factor = e.projectionFactors[done.reps.coerceAtMost(4).toString()]
                            if (factor != null && done.reps >= 2) {
                                val projected = Strength.roundTo(done.weightKg * factor, step)
                                pending[mk] = projected
                                msgs += "${liftName(key)}: projected max for the next cycle ${Strength.fmt(projected)} kg."
                            }
                        }
                    }
                }
                "JT2" -> if (s.rule == Rules.WORK_UP && (s.repsLow ?: 0) == 1) {
                    val single = fs.sets.filter { it.reps >= 1 }.maxOfOrNull { it.weightKg }
                    if (single != null && single > 0) setTm(key, single, "tested 1RM")
                }
                "MADCOW" -> if (s.rule == Rules.RAMP) {
                    val tk = "TOP:$key"
                    val top = maxes[tk]
                    when (s.role) {
                        "MON" -> if ((fs.sets.lastOrNull()?.reps ?: 0) < 5 || fs.sets.size < fs.targets.size) {
                            missed += key
                            msgs += "${liftName(key)}: top set missed - the weight holds next week."
                        }
                        "FRI" -> {
                            val triple = fs.sets.getOrNull(4)
                            if (top != null && triple != null && triple.reps >= 3 && key !in missed) {
                                val next = Strength.roundTo(top * ProgressionEngine.MADCOW_WEEKLY, step)
                                maxes[tk] = next
                                msgs += "${liftName(key)} top set next week: ${Strength.fmt(next)} kg."
                            } else if (top != null) {
                                msgs += "${liftName(key)} holds at ${Strength.fmt(top)} kg next week."
                            }
                            missed -= key
                        }
                        "WED" -> {
                            val last = fs.sets.lastOrNull()
                            if (top != null && last != null && last.reps >= 5) {
                                val next = Strength.roundTo(top * ProgressionEngine.MADCOW_WEEKLY, step)
                                maxes[tk] = next
                                msgs += "${liftName(key)} top set next week: ${Strength.fmt(next)} kg."
                            }
                        }
                    }
                }
                "TEXAS" -> if (s.rule == Rules.PR_ATTEMPT) {
                    val best = fs.sets.filter { it.reps >= 5 }.maxOfOrNull { it.weightKg }
                    val mk = "5RM:$key"
                    val cur = maxes[mk]
                    if (best != null && (cur == null || best > cur)) {
                        maxes[mk] = best
                        msgs += "New ${liftName(key)} 5RM: ${Strength.fmt(best)} kg."
                    }
                }
                "WAVE" -> if (s.rule == Rules.WAVE && starts[fs.startKey] == null) {
                    val w = fs.sets.first().weightKg
                    if (w > 0) {
                        val offset = ProgressionEngine.waveOffset(sessionWeek, st.cycle, s.incrementKg ?: step, e.optionalWeeks)
                        starts[fs.startKey] = (w - offset).coerceAtLeast(0.0)
                    }
                }
            }
        }

        st = st.copy(
            maxes = maxes,
            starts = starts,
            missed = missed,
            pendingMaxes = pending,
            sessionsInWeek = st.sessionsInWeek + 1,
            sessionsCompleted = st.sessionsCompleted + 1
        )
        val perWeek = config.weekDayCounts.getOrNull(st.week - 1) ?: config.daysPerWeek
        if (st.sessionsInWeek >= perWeek.coerceAtLeast(1)) {
            val wrapped = advanceWeek(config.copy(state = st), tms, now)
            st = wrapped.state
            wrapped.tmUpdates.forEach { (k, v) -> tmUpdates[k] = v }
            msgs += wrapped.messages
        }
        return AdvanceResult(st, tmUpdates, msgs)
    }

    /**
     * Move to the next week; at the end of a cycle, apply what the program does between cycles
     * (5/3/1 TM increases, Candito projected maxes). Also used to skip an optional deload week.
     */
    fun advanceWeek(config: ProgressionConfig, trainingMaxes: Map<String, Double>, now: Long): AdvanceResult {
        val e = config.engine
        val st = config.state
        val total = config.weekDayCounts.size.takeIf { it > 0 } ?: e.cycleWeeks
        val nextWeek = st.week + 1
        val msgs = mutableListOf<String>()
        val tmUpdates = linkedMapOf<String, Double>()
        if (total == null || nextWeek <= total) {
            val label = if (total != null) "Week $nextWeek of $total" else "Week $nextWeek"
            msgs += "$label starts next session."
            return AdvanceResult(st.copy(week = nextWeek, sessionsInWeek = 0, weekStartedAt = now), tmUpdates, msgs)
        }
        var maxes = st.maxes
        when (e.model) {
            "WENDLER" -> {
                config.tmIncrements.forEach { (key, inc) ->
                    val cur = trainingMaxes[key] ?: return@forEach
                    if (key in st.missed) {
                        msgs += "${liftName(key)} training max holds at ${Strength.fmt(cur)} kg (a rep was missed this cycle)."
                    } else {
                        val v = cur + inc
                        tmUpdates[key] = v
                        msgs += "${liftName(key)} training max ${Strength.fmt(cur)} → ${Strength.fmt(v)} kg for the new cycle."
                    }
                }
            }
            "CANDITO" -> if (st.pendingMaxes.isNotEmpty()) {
                maxes = maxes + st.pendingMaxes
                msgs += "New cycle uses your projected maxes."
            }
        }
        msgs += "Cycle ${st.cycle + 1} begins."
        return AdvanceResult(
            st.copy(
                week = 1,
                cycle = st.cycle + 1,
                sessionsInWeek = 0,
                weekStartedAt = now,
                maxes = maxes,
                pendingMaxes = emptyMap(),
                missed = emptySet()
            ),
            tmUpdates,
            msgs
        )
    }

    /** "conventional_deadlift" -> "Conventional deadlift". */
    fun liftName(key: String): String =
        key.substringAfter(':').replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}
