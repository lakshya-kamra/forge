package com.laksh.forge.domain.program

import com.laksh.forge.domain.guardrails.GuardrailFlag
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Everything an adopted program needs to keep prescribing, stored in Program.progressionJson.
 * The rules are copied from the template at adoption so later asset updates never change a
 * program someone is part-way through.
 */
@Serializable
data class ProgressionConfig(
    val version: Int = 1,
    val libraryId: String,
    val engine: EngineConfig,
    /** Base-day slots keyed by [slotKey] (day + exercise name), for non-wave programs. */
    val slots: Map<String, LibraryExercise> = emptyMap(),
    /** Training-max step per lift, for programs that raise TMs per cycle (5/3/1). */
    val tmIncrements: Map<String, Double> = emptyMap(),
    /** Number of training days in each week of a wave program (index 0 = week 1). */
    val weekDayCounts: List<Int> = emptyList(),
    val daysPerWeek: Int = 3,
    val state: ProgramState = ProgramState()
) {
    companion object {
        fun slotKey(dayName: String, exerciseName: String): String =
            "${normalize(dayName)}|${normalize(exerciseName)}"

        fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

/** Mutable progression state, advanced each time a program session is finished. */
@Serializable
data class ProgramState(
    /** 1-based week within the current cycle (wave programs) or since the start. */
    val week: Int = 1,
    /** 1-based block/cycle count; wave loads and 5/3/1 cycles build on it. */
    val cycle: Int = 1,
    val sessionsInWeek: Int = 0,
    /** When the current program week began, so "same week" is a time test, not a counter. */
    val weekStartedAt: Long = 0L,
    /** Plate step all prescriptions round to: 2.5 kg default, 1.25 kg with fractional plates. */
    val roundingKg: Double = 2.5,
    /** Non-TM reference numbers keyed "1RM:lift", "5RM:lift", "TOP:lift". TMs live in TrainingDao. */
    val maxes: Map<String, Double> = emptyMap(),
    /** Starting working weights by normalized exercise name (or slot key). */
    val starts: Map<String, Double> = emptyMap(),
    /** Lifts that missed a rep this cycle/week (5/3/1 TM hold, Madcow hold). */
    val missed: Set<String> = emptySet(),
    /** Next-cycle maxes projected inside a cycle (Candito week 5), applied when it wraps. */
    val pendingMaxes: Map<String, Double> = emptyMap(),
    val sessionsCompleted: Int = 0,
    /** Cycle in which the lifter answered the deload checklist with "take the deload". */
    val deloadAcceptedCycle: Int? = null
)

/** A logged working set, as the engine sees it. */
data class LoggedSet(
    val weightKg: Double,
    val reps: Int,
    val rir: Double? = null,
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null
)

/** One earlier session's working sets for a slot, oldest set first. */
data class SessionPerformance(
    val sessionId: Long,
    val startedAt: Long,
    val weekIndex: Int? = null,
    val dayId: Long? = null,
    val sets: List<LoggedSet>
) {
    val topWeight: Double get() = sets.maxOfOrNull { it.weightKg } ?: 0.0

    /** The weight that was prescribed (falls back to what was lifted on the first set). */
    val targetWeight: Double
        get() = sets.firstNotNullOfOrNull { it.targetWeightKg } ?: sets.firstOrNull()?.weightKg ?: 0.0
}

/** One prescribed set. Null weight means "your choice" (no basis yet, bodyweight, work-up). */
data class SetTarget(
    val weightKg: Double?,
    val reps: Int,
    val repsHigh: Int? = null,
    val amrap: Boolean = false,
    val rir: Double? = null,
    /** Short basis label, e.g. "85% TM", "+2.5 kg". */
    val label: String? = null
)

data class Prescription(
    val sets: List<SetTarget>,
    /** One line on why the numbers are what they are. */
    val reason: String? = null,
    val flag: GuardrailFlag? = null,
    /** Weight to prefill when nothing is prescribed (last time's working weight). */
    val suggestedKg: Double? = null,
    /** "Work up to a 10RM": weight is found in the session, not prescribed. */
    val workUp: Boolean = false
)

object Strength {
    /** Epley estimated 1RM. A single is its own max. */
    fun e1rm(weightKg: Double, reps: Int): Double =
        if (reps <= 1) weightKg else weightKg * (1.0 + reps / 30.0)

    /** Inverse Epley: the load you could lift for [reps] given an estimated max. */
    fun weightForReps(e1rm: Double, reps: Int): Double =
        if (reps <= 1) e1rm else e1rm / (1.0 + reps / 30.0)

    /**
     * Nearest plate step, halves rounding up like the programs' spreadsheets (MROUND) do -
     * kotlin.math.round would send 106.25 to 105 instead of 107.5. The epsilon absorbs
     * floating-point noise such as 0.95 * 100 = 94.99999...
     */
    fun roundTo(kg: Double, step: Double): Double {
        if (step <= 0.0) return kg
        return floor(kg / step + 0.5 + 1e-9) * step
    }

    /** nSuns' sheet rounds training maxes up (CEILING) to the plate step. */
    fun ceilTo(kg: Double, step: Double): Double {
        if (step <= 0.0) return kg
        return ceil(kg / step - 1e-9) * step
    }

    fun floorTo(kg: Double, step: Double): Double {
        if (step <= 0.0) return kg
        return floor(kg / step + 1e-9) * step
    }

    /** Readable kg: 102.5 -> "102.5", 100.0 -> "100". */
    fun fmt(v: Double): String {
        val r = round(v * 100) / 100
        return if (r % 1.0 == 0.0) r.toLong().toString() else {
            val s = "%.2f".format(java.util.Locale.US, r)
            s.trimEnd('0').trimEnd('.')
        }
    }
}
