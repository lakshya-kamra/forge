package com.laksh.forge.domain.exercise

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiftMathTest {

    private val sets = listOf(
        LiftSet(sessionId = 1, weightKg = 100.0, reps = 5, timestamp = 1_000),
        LiftSet(sessionId = 1, weightKg = 100.0, reps = 3, timestamp = 2_000),
        LiftSet(sessionId = 2, weightKg = 105.0, reps = 3, timestamp = 10_000),
        LiftSet(sessionId = 2, weightKg = 60.0, reps = 15, timestamp = 11_000)
    )

    @Test fun epleyIgnoresHighRepSets() {
        assertThat(LiftMath.epley(100.0, 1)).isEqualTo(100.0)
        assertThat(LiftMath.epley(100.0, 5)!!).isWithin(1e-9).of(100.0 * (1 + 5 / 30.0))
        assertThat(LiftMath.epley(60.0, 15)).isNull()
        assertThat(LiftMath.epley(0.0, 5)).isNull()
    }

    @Test fun summaryFindsRecordsAndSessions() {
        val h = LiftMath.summarize(sets)
        assertThat(h.totalSets).isEqualTo(4)
        assertThat(h.sessions).hasSize(2)
        assertThat(h.bestE1rm!!.value).isWithin(1e-6).of(100.0 * (1 + 5 / 30.0))
        assertThat(h.bestE1rm!!.timestamp).isEqualTo(1_000)
        assertThat(h.heaviest!!.weightKg).isEqualTo(105.0)
        assertThat(h.mostReps!!.reps).isEqualTo(15)
        val s1 = h.sessions.first()
        assertThat(s1.topWeightKg).isEqualTo(100.0)
        assertThat(s1.topWeightReps).isEqualTo(5)
        assertThat(h.sessions.last().volumeKg).isWithin(1e-9).of(105.0 * 3 + 60.0 * 15)
        assertThat(h.bodyweightOnly).isFalse()
    }

    @Test fun bodyweightLiftsReportRepsOnly() {
        val h = LiftMath.summarize(listOf(LiftSet(1, 0.0, 8, 1), LiftSet(1, 0.0, 10, 2)))
        assertThat(h.bodyweightOnly).isTrue()
        assertThat(h.bestE1rm).isNull()
        assertThat(h.heaviest).isNull()
        assertThat(h.mostReps!!.reps).isEqualTo(10)
    }

    @Test fun emptyLogIsEmpty() {
        assertThat(LiftMath.summarize(emptyList()).isEmpty).isTrue()
    }
}
