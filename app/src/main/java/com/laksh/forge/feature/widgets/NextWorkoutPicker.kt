package com.laksh.forge.feature.widgets

import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.WorkoutSession

/**
 * Which day of the active program comes next: the day after the most recent session that
 * was run from one of its days (wrapping round), or day one when none has been run yet.
 * Rotation by last-done day rather than by weekday, because a lifter who misses Tuesday
 * still does Tuesday's session next. Shared by the widget and the training-day reminder.
 */
object NextWorkoutPicker {

    fun pick(days: List<ProgramDay>, recentSessions: List<WorkoutSession>): ProgramDay? {
        if (days.isEmpty()) return null
        val ordered = days.sortedBy { it.orderIdx }
        val ids = ordered.map { it.id }
        val last = recentSessions
            .sortedByDescending { it.startedAt }
            .firstOrNull { s -> s.programDayId != null && s.programDayId in ids }
            ?: return ordered.first()
        val i = ids.indexOf(last.programDayId)
        return ordered[(i + 1) % ordered.size]
    }

    /** The most recent session run from this program, for "last trained" labels. */
    fun lastProgramSession(days: List<ProgramDay>, recentSessions: List<WorkoutSession>): WorkoutSession? {
        val ids = days.map { it.id }.toSet()
        return recentSessions
            .sortedByDescending { it.startedAt }
            .firstOrNull { s -> s.programDayId != null && s.programDayId in ids }
    }
}
