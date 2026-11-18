package com.laksh.forge.domain.training

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Week counting for training_deload_triggers. */
object TrainingWeeks {

    /**
     * Consecutive calendar weeks (Monday-based), counting back from the week of [now], that
     * contain at least one session. A week with no session at all is treated as the last
     * lighter week, so the count restarts after it.
     */
    fun consecutiveWeeks(sessionStarts: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        fun weekOf(ms: Long): LocalDate =
            Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = sessionStarts.map { weekOf(it) }.toSet()
        var cursor = weekOf(now)
        var count = 0
        while (cursor in weeks) {
            count++
            cursor = cursor.minus(1, ChronoUnit.WEEKS)
        }
        return count
    }
}
