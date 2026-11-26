package com.laksh.forge.data.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgressPhotoReminderTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    @Test
    fun `mid-month books the first Sunday of next month at 8`() {
        // 2026-11-25 is a Wednesday; 2026-12-06 is December's first Sunday.
        assertThat(ProgressPhotoReminder.nextOccurrence(at(2026, 11, 25, 10))).isEqualTo(at(2026, 12, 6, 8))
    }

    @Test
    fun `the first Sunday itself, before 8, books the same morning`() {
        assertThat(ProgressPhotoReminder.nextOccurrence(at(2026, 11, 1, 7))).isEqualTo(at(2026, 11, 1, 8))
    }

    @Test
    fun `a run that fires a few seconds early books next month, not another one today`() {
        assertThat(ProgressPhotoReminder.nextOccurrence(at(2026, 11, 1, 7, 59, 30))).isEqualTo(at(2026, 12, 6, 8))
    }

    @Test
    fun `December rolls over into January`() {
        // 2027-01-01 is a Friday, so January's first Sunday is the 3rd.
        assertThat(ProgressPhotoReminder.nextOccurrence(at(2026, 12, 20, 9))).isEqualTo(at(2027, 1, 3, 8))
    }

    @Test
    fun `always in the future and always a Sunday`() {
        var now = at(2026, 1, 1, 0)
        repeat(400) {
            val next = ProgressPhotoReminder.nextOccurrence(now)
            assertThat(next.isAfter(now)).isTrue()
            assertThat(next.dayOfWeek).isEqualTo(java.time.DayOfWeek.SUNDAY)
            assertThat(next.dayOfMonth).isAtMost(7)
            now = now.plusDays(1).plusHours(5)
        }
    }
}
