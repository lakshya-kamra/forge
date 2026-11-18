package com.laksh.forge.data.wearables.whoop

import com.laksh.forge.data.health.HealthOrigins
import com.laksh.forge.data.health.SourceRank
import com.laksh.forge.data.wearables.DayValues
import com.laksh.forge.data.wearables.HealthField
import com.laksh.forge.data.wearables.LayerValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Turns WHOOP's sleeps, recoveries, cycles and workouts into one value per field per local day.
 * Recovery, HRV, strain and skin temperature are stored exactly as WHOOP reports them: Forge
 * never recomputes a vendor score.
 */
internal object WhoopSync {

    data class Result(val days: Map<String, DayValues>, val window: Set<String>, val records: Int)

    /** Fetch [fromDate, today] and map it. Starts two days early so the first night is complete. */
    suspend fun fetch(fromDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Result {
        val today = LocalDate.now(zone)
        val start = fromDate.minusDays(2).atStartOfDay(zone).toInstant()
        val end = Instant.now()

        val sleeps = WhoopClient.collection("/v2/activity/sleep", start, end)
        val recoveries = WhoopClient.collection("/v2/recovery", start, end)
        val cycles = WhoopClient.collection("/v2/cycle", start, end)
        val workouts = WhoopClient.collection("/v2/activity/workout", start, end)

        val window = generateSequence(fromDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it.toString() }
            .toSet()
        val mapped = mapToDays(sleeps, recoveries, cycles, workouts, zone)
            .filterKeys { it in window }
        return Result(mapped, window, sleeps.size + recoveries.size + cycles.size + workouts.size)
    }

    /**
     * Day assignment, because WHOOP thinks in physiological cycles rather than calendar days:
     *  - a sleep belongs to the local date it ended (the morning you woke up);
     *  - a recovery belongs to the day of the sleep it was scored from;
     *  - a cycle's strain belongs to the same day as its recovery; a cycle with no recovery
     *    (e.g. not scored yet) falls back to its start date, moved to the next day when it
     *    started in the evening, since cycles begin when you fall asleep;
     *  - a workout belongs to the date it started.
     */
    fun mapToDays(
        sleeps: List<JsonObject>,
        recoveries: List<JsonObject>,
        cycles: List<JsonObject>,
        workouts: List<JsonObject>,
        zone: ZoneId
    ): Map<String, DayValues> {
        val acc = HashMap<LocalDate, DayAcc>()
        fun day(d: LocalDate) = acc.getOrPut(d) { DayAcc() }

        val wakeDayBySleep = HashMap<String, LocalDate>()
        sleeps.forEach { s ->
            val end = s.instant("end") ?: return@forEach
            val wake = localDate(end, s.offset(), zone)
            s.str("id")?.let { wakeDayBySleep[it] = wake }
            if (s.bool("nap") == true || !s.scored()) return@forEach
            val score = s.obj("score") ?: return@forEach
            val stages = score.obj("stage_summary")
            if (stages != null) {
                val asleepMs = listOfNotNull(
                    stages.num("total_light_sleep_time_milli"),
                    stages.num("total_slow_wave_sleep_time_milli"),
                    stages.num("total_rem_sleep_time_milli")
                ).sum()
                if (asleepMs > 0) day(wake).sleepMinutes += asleepMs / 60_000.0
            }
            score.num("respiratory_rate")?.let { day(wake).respiratory += it }
        }

        val dayByCycle = HashMap<String, LocalDate>()
        recoveries.forEach { r ->
            if (!r.scored()) return@forEach
            val score = r.obj("score") ?: return@forEach
            val date = r.str("sleep_id")?.let { wakeDayBySleep[it] }
                ?: r.instant("created_at")?.let { localDate(it, null, zone) }
                ?: return@forEach
            r.str("cycle_id")?.let { dayByCycle[it] = date }
            val d = day(date)
            score.num("recovery_score")?.let { d.recovery = it }
            score.num("hrv_rmssd_milli")?.let { d.hrv = it }
            score.num("resting_heart_rate")?.let { d.restingHr = it }
            score.num("spo2_percentage")?.let { d.spo2 = it }
            score.num("skin_temp_celsius")?.let { d.skinTempAbs = it }
        }

        cycles.forEach { c ->
            if (!c.scored()) return@forEach
            val score = c.obj("score") ?: return@forEach
            val date = c.str("id")?.let { dayByCycle[it] } ?: run {
                val start = c.instant("start") ?: return@forEach
                val local = start.atOffset(c.offset() ?: zone.rules.getOffset(start))
                if (local.hour >= 18) local.toLocalDate().plusDays(1) else local.toLocalDate()
            }
            val d = day(date)
            score.num("strain")?.let { d.strain = it }
            score.num("kilojoule")?.let { d.totalKcal = it / 4.184 }
            score.num("average_heart_rate")?.let { d.avgHr = it }
        }

        workouts.forEach { w ->
            val start = w.instant("start") ?: return@forEach
            val end = w.instant("end") ?: return@forEach
            val minutes = (end.toEpochMilli() - start.toEpochMilli()) / 60_000.0
            if (minutes <= 0) return@forEach
            day(localDate(start, w.offset(), zone)).exerciseMinutes += minutes
        }

        val device = SourceRank.DEVICE.value
        val origin = HealthOrigins.WHOOP_API
        fun lv(v: Double) = LayerValue(v, device, origin)

        return acc.mapKeys { it.key.toString() }.mapValues { (_, d) ->
            buildMap<HealthField, LayerValue> {
                d.recovery?.let { put(HealthField.RECOVERY, lv(it)) }
                d.hrv?.let { put(HealthField.HRV_RMSSD, lv(it)) }
                d.restingHr?.let { put(HealthField.RESTING_HR, lv(it)) }
                d.spo2?.let { put(HealthField.SPO2, lv(it)) }
                d.skinTempAbs?.let { put(HealthField.SKIN_TEMP_ABS, lv(it)) }
                if (d.sleepMinutes > 0) put(HealthField.SLEEP_MIN, lv(d.sleepMinutes.coerceAtMost(24 * 60.0)))
                if (d.respiratory.isNotEmpty()) put(HealthField.RESP_RATE, lv(d.respiratory.average()))
                d.strain?.let { put(HealthField.STRAIN, lv(it)) }
                d.totalKcal?.let { put(HealthField.TOTAL_KCAL, lv(it)) }
                d.avgHr?.let { put(HealthField.AVG_HR, lv(it)) }
                if (d.exerciseMinutes > 0) put(HealthField.EXERCISE_MIN, lv(d.exerciseMinutes))
            }
        }.filterValues { it.isNotEmpty() }
    }

    private class DayAcc {
        var sleepMinutes = 0.0
        val respiratory = ArrayList<Double>()
        var recovery: Double? = null
        var hrv: Double? = null
        var restingHr: Double? = null
        var spo2: Double? = null
        var skinTempAbs: Double? = null
        var strain: Double? = null
        var totalKcal: Double? = null
        var avgHr: Double? = null
        var exerciseMinutes = 0.0
    }

    // ------------------------------------------------------------------ JSON helpers
    // WHOOP documents only SCORED in its examples, so anything else (or a missing score) is
    // skipped rather than guessed at.

    private fun JsonObject.scored(): Boolean =
        str("score_state") == "SCORED" && this["score"] is JsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(key: String): Double? = prim(key)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.instant(key: String): Instant? = str(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun JsonObject.offset(): ZoneOffset? =
        str("timezone_offset")?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }

    private fun localDate(instant: Instant, offset: ZoneOffset?, zone: ZoneId): LocalDate =
        instant.atOffset(offset ?: zone.rules.getOffset(instant)).toLocalDate()

}
