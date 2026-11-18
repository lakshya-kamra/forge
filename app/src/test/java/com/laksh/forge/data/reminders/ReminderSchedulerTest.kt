package com.laksh.forge.data.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderSchedulerTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, 18, h, m, s, 0, zone)

    @Test
    fun `later today when the time is still ahead`() {
        val next = ReminderScheduler.nextOccurrence(at(6, 0), 7 * 60 + 30)
        assertThat(next).isEqualTo(at(7, 30))
    }

    @Test
    fun `tomorrow once the time has passed`() {
        val next = ReminderScheduler.nextOccurrence(at(21, 0), 20 * 60)
        assertThat(next).isEqualTo(at(20, 0).plusDays(1))
    }

    @Test
    fun `a run that fires a few seconds early books tomorrow, not another one today`() {
        val next = ReminderScheduler.nextOccurrence(at(7, 29, 40), 7 * 60 + 30)
        assertThat(next).isEqualTo(at(7, 30).plusDays(1))
    }

    @Test
    fun `daylight saving gaps resolve to a real instant`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:30 doesn't exist in New York; java.time shifts it forward.
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny)
        val next = ReminderScheduler.nextOccurrence(now, 2 * 60 + 30)
        assertThat(next.isAfter(now)).isTrue()
        assertThat(next.toLocalDate()).isEqualTo(now.toLocalDate())
    }
}
