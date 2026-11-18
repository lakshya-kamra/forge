package com.laksh.forge.domain.training

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.domain.guardrails.Guardrails
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TrainingWeeksTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 18, 18, 0, 0, 0, zone)

    private fun weeksAgo(n: Long) = now.minusWeeks(n).toInstant().toEpochMilli()

    @Test
    fun `ten straight training weeks trigger the deload nudge`() {
        val starts = (0L until 10L).map { weeksAgo(it) }
        val weeks = TrainingWeeks.consecutiveWeeks(starts, now.toInstant().toEpochMilli(), zone)
        assertThat(weeks).isEqualTo(10)
        assertThat(Guardrails.deloadFlag(weeks)).isNotNull()
    }

    @Test
    fun `a week off restarts the count`() {
        val starts = listOf(0L, 1L, 3L, 4L, 5L).map { weeksAgo(it) }
        assertThat(TrainingWeeks.consecutiveWeeks(starts, now.toInstant().toEpochMilli(), zone)).isEqualTo(2)
    }

    @Test
    fun `high-rep sets far from failure and repeated barbell failure are flagged`() {
        assertThat(Guardrails.repRangeFlag(25, 4.0)).isNotNull()
        assertThat(Guardrails.repRangeFlag(25, 1.0)).isNull()
        assertThat(Guardrails.barbellFailureFlag(3)).isNotNull()
    }
}
