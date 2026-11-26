package com.laksh.forge.domain.plan

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class BodyPhotoChangeTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    /** An adult who answered the eating screen (negative) ten days ago. */
    private val adult = ForgeSettings(age = 30, sex = Sex.MALE, edScreenPositive = false, edScreenAt = now - 10 * day)
    private val noHistory = PlanProfile()

    // ------------------------------------------------------------------ gate

    @Test
    fun `adult with a recent negative screen may run an estimate`() {
        val gate = BodyPhotoGate.evaluate(adult, noHistory, now)
        assertThat(gate.allowed).isTrue()
        assertThat(gate.reason).isNull()
    }

    @Test
    fun `under 18 is blocked and not sent to re-screen`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(age = 17), noHistory, now)
        assertThat(gate.allowed).isFalse()
        assertThat(gate.needsRescreen).isFalse()
    }

    @Test
    fun `pregnancy blocks`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(sex = Sex.FEMALE, pregnancyStatus = PregnancyStatus.PREGNANT), noHistory, now)
        assertThat(gate.allowed).isFalse()
        assertThat(gate.needsRescreen).isFalse()
    }

    @Test
    fun `a positive SCOFF inside 30 days blocks`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(edScreenPositive = true, edScreenAt = now - 5 * day), noHistory, now)
        assertThat(gate.allowed).isFalse()
    }

    @Test
    fun `a positive SCOFF past 30 days still blocks until a negative re-screen`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(edScreenPositive = true, edScreenAt = now - 40 * day), noHistory, now)
        assertThat(gate.allowed).isFalse()
    }

    @Test
    fun `a crisis disclosure inside its 30-day lock blocks`() {
        val gate = BodyPhotoGate.evaluate(adult, PlanProfile(crisisFlaggedAt = now - 3 * day), now)
        assertThat(gate.allowed).isFalse()
    }

    @Test
    fun `an open low-intake safety event blocks`() {
        val gate = BodyPhotoGate.evaluate(adult, PlanProfile(safetyEventAt = now - 2 * day), now)
        assertThat(gate.allowed).isFalse()
    }

    @Test
    fun `never screened asks for the screen first`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(edScreenAt = 0L), noHistory, now)
        assertThat(gate.allowed).isFalse()
        assertThat(gate.needsRescreen).isTrue()
    }

    @Test
    fun `a screen older than 12 weeks asks again`() {
        val gate = BodyPhotoGate.evaluate(adult.copy(edScreenAt = now - 90 * day), noHistory, now)
        assertThat(gate.allowed).isFalse()
        assertThat(gate.needsRescreen).isTrue()
    }

    @Test
    fun `an estimate within 14 days blocks without a re-screen`() {
        val gate = BodyPhotoGate.evaluate(adult, PlanProfile(lastPhotoEstimateAt = now - 3 * day), now)
        assertThat(gate.allowed).isFalse()
        assertThat(gate.needsRescreen).isFalse()
        assertThat(BodyPhotoGate.evaluate(adult, PlanProfile(lastPhotoEstimateAt = now - 15 * day), now).allowed).isTrue()
    }

    @Test
    fun `hidden numbers are respected`() {
        assertThat(BodyPhotoGate.evaluate(adult.copy(hideNumbers = true), noHistory, now).allowed).isFalse()
    }

    // ------------------------------------------------------------------ +/-6 widening

    @Test
    fun `range is the estimate plus or minus 6, rounded outward`() {
        assertThat(PhotoBodyFat.range(20.0)).isEqualTo(14..26)
        assertThat(PhotoBodyFat.range(20.4)).isEqualTo(14..27)
        assertThat(PhotoBodyFat.range(19.6)).isEqualTo(13..26)
    }

    @Test
    fun `a narrower model range never narrows it`() {
        assertThat(PhotoBodyFat.range(20.0, 18.0, 22.0)).isEqualTo(14..26)
    }

    @Test
    fun `a wider model range widens it, within reason`() {
        assertThat(PhotoBodyFat.range(20.0, 12.5, 29.2)).isEqualTo(12..30)
        // A 2-60% "range" is a non-answer; it is capped at 12 points either side.
        assertThat(PhotoBodyFat.range(20.0, 2.0, 60.0)).isEqualTo(8..32)
    }

    @Test
    fun `range never goes below 1 percent`() {
        assertThat(PhotoBodyFat.range(5.0).first).isEqualTo(1)
        assertThat(PhotoBodyFat.range(5.0).last).isEqualTo(11)
    }

    // ------------------------------------------------------------------ dates

    private val jan = LocalDate.of(2026, 1, 10)
    private val mar = LocalDate.of(2026, 3, 8)
    private val jun = LocalDate.of(2026, 6, 7)

    @Test
    fun `photos group by date, oldest first`() {
        val groups = PhotoSelection.groupByDate(listOf(jun to "jun-front", jan to "jan-front", jun to "jun-side", mar to "mar-front"))
        assertThat(groups.map { it.date }).containsExactly(jan, mar, jun).inOrder()
        assertThat(groups.last().items).containsExactly("jun-front", "jun-side").inOrder()
    }

    @Test
    fun `selection needs two dates, every date set, none in the future, at most 8`() {
        val today = LocalDate.of(2026, 11, 25)
        assertThat(PhotoSelection.problem(listOf(jan, mar), today)).isNull()
        assertThat(PhotoSelection.problem(listOf(jan, jan), today)).contains("two different dates")
        assertThat(PhotoSelection.problem(listOf(jan, null), today)).contains("date")
        assertThat(PhotoSelection.problem(listOf(jan, today.plusDays(1)), today)).contains("future")
        assertThat(PhotoSelection.problem(List(9) { jan.plusMonths(it.toLong()) }, today)).contains("8")
    }

    private fun answer(direction: String, vararg rows: Pair<String, Double>): JsonObject = buildJsonObject {
        put("usable", true)
        putJsonArray("per_date") {
            rows.forEach { (date, est) ->
                addJsonObject {
                    put("date", date)
                    put("estimate_pct", est)
                    put("low_pct", est - 3)
                    put("high_pct", est + 3)
                }
            }
        }
        put("direction", direction)
        put("change_points", 0.0)
        put("confidence", "MEDIUM")
        put("photo_issues", "")
    }

    @Test
    fun `parsed estimates come back oldest first whatever order the model used`() {
        val r = BodyChangeAi.parse(answer("DOWN", "2026-06-07" to 18.0, "2026-01-10" to 23.0, "2026-03-08" to 21.0), listOf(jun, jan, mar))
        assertThat(r.perDate.map { it.date }).containsExactly(jan, mar, jun).inOrder()
        assertThat(r.changePoints).isEqualTo(-5)
        assertThat(r.direction).isEqualTo(ChangeDirection.DOWN)
        assertThat(r.perDate.first().range).isEqualTo(17..29)
    }

    @Test
    fun `a direction that contradicts the numbers is reported as unclear`() {
        val r = BodyChangeAi.parse(answer("UP", "2026-01-10" to 23.0, "2026-06-07" to 18.0), listOf(jan, jun))
        assertThat(r.direction).isEqualTo(ChangeDirection.UNCLEAR)
    }

    @Test
    fun `a change inside the noise reads as about the same`() {
        val r = BodyChangeAi.parse(answer("STABLE", "2026-01-10" to 20.0, "2026-06-07" to 19.2), listOf(jan, jun))
        assertThat(r.direction).isEqualTo(ChangeDirection.STABLE)
    }

    @Test
    fun `a missing date rejects the whole answer`() {
        assertThrows(PlanAiRejected::class.java) {
            BodyChangeAi.parse(answer("DOWN", "2026-01-10" to 23.0), listOf(jan, jun))
        }
    }

    @Test
    fun `an implausible estimate rejects the answer`() {
        assertThrows(PlanAiRejected::class.java) {
            BodyChangeAi.parse(answer("DOWN", "2026-01-10" to 90.0, "2026-06-07" to 18.0), listOf(jan, jun))
        }
    }
}
