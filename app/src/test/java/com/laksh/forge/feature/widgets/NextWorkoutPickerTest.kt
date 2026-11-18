package com.laksh.forge.feature.widgets

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.WorkoutSession
import org.junit.Test

class NextWorkoutPickerTest {

    private val days = listOf(
        ProgramDay(id = 11, programId = 1, name = "Upper A", orderIdx = 0),
        ProgramDay(id = 12, programId = 1, name = "Lower A", orderIdx = 1),
        ProgramDay(id = 13, programId = 1, name = "Upper B", orderIdx = 2)
    )

    private fun session(dayId: Long?, startedAt: Long) = WorkoutSession(
        programDayId = dayId,
        title = "s",
        startedAt = startedAt,
        endedAt = startedAt + 1,
        localDate = "2026-11-01"
    )

    @Test
    fun `no history starts at day one`() {
        assertThat(NextWorkoutPicker.pick(days, emptyList())?.name).isEqualTo("Upper A")
    }

    @Test
    fun `picks the day after the most recent one`() {
        val sessions = listOf(session(11, 100), session(12, 200))
        assertThat(NextWorkoutPicker.pick(days, sessions)?.name).isEqualTo("Upper B")
    }

    @Test
    fun `wraps round after the last day`() {
        assertThat(NextWorkoutPicker.pick(days, listOf(session(13, 300)))?.name).isEqualTo("Upper A")
    }

    @Test
    fun `free sessions and other programs are ignored`() {
        val sessions = listOf(session(11, 100), session(null, 500), session(99, 600))
        assertThat(NextWorkoutPicker.pick(days, sessions)?.name).isEqualTo("Lower A")
        assertThat(NextWorkoutPicker.lastProgramSession(days, sessions)?.startedAt).isEqualTo(100L)
    }

    @Test
    fun `order index wins over list order`() {
        val shuffled = days.reversed()
        assertThat(NextWorkoutPicker.pick(shuffled, listOf(session(11, 1)))?.name).isEqualTo("Lower A")
    }

    @Test
    fun `no days means nothing to pick`() {
        assertThat(NextWorkoutPicker.pick(emptyList(), listOf(session(11, 1)))).isNull()
    }
}
