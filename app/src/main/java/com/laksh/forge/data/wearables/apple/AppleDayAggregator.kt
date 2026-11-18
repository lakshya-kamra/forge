package com.laksh.forge.data.wearables.apple

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One local day of an Apple Health export, reduced to Forge's daily fields. */
data class AppleDay(
    val date: LocalDate,
    val steps: Double? = null,
    val activeKcal: Double? = null,
    val distanceM: Double? = null,
    val avgHr: Double? = null,
    val restingHr: Double? = null,
    /** Apple's HRV is SDNN, not RMSSD. Kept apart and never written into the RMSSD column. */
    val hrvSdnnMs: Double? = null,
    val vo2max: Double? = null,
    val spo2Pct: Double? = null,
    val respiratoryRate: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    val sleepMinutes: Double? = null,
    val exerciseMinutes: Double? = null,
    /** Fields whose chosen source looked like an Apple Watch (vs the iPhone itself). */
    val watchFields: Set<String> = emptySet()
)

data class AppleWeight(val timeMillis: Long, val date: LocalDate, val kg: Double)

data class AppleAggregate(
    val days: List<AppleDay>,
    /** The first weigh-in of each day; one per day is what the weight trend expects. */
    val weights: List<AppleWeight>,
    val recordsSeen: Long,
    val recordsUsed: Long
)

/**
 * Streams Apple Health records into per-day totals without keeping the records themselves, so a
 * multi-gigabyte export costs memory proportional to the number of days, not records.
 *
 * Day = the local date in the offset Apple stored with each record (the wearer's clock at the
 * time), so a trip abroad doesn't shift days around. Additive metrics (steps, energy, distance,
 * sleep, workouts) are summed per source and the largest source wins the day: an iPhone and a
 * Watch both count the same steps, and adding them would double the day.
 */
class AppleDayAggregator {

    private class Avg { var sum = 0.0; var n = 0; var watch = false }

    private class DayAcc {
        val steps = HashMap<String, Double>()
        val activeKcal = HashMap<String, Double>()
        val distance = HashMap<String, Double>()
        val sleepMs = HashMap<String, Double>()
        val exerciseMin = HashMap<String, Double>()
        val hr = Avg()
        val restingHr = Avg()
        val sdnn = Avg()
        val vo2 = Avg()
        val spo2 = Avg()
        val resp = Avg()
        val bodyFat = Avg()
        val lean = Avg()
        val watchSources = HashSet<String>()
    }

    private val days = HashMap<LocalDate, DayAcc>()
    private val weights = HashMap<LocalDate, AppleWeight>()
    var recordsSeen = 0L
        private set
    var recordsUsed = 0L
        private set

    private fun day(d: LocalDate) = days.getOrPut(d) { DayAcc() }

    fun addRecord(
        type: String?,
        sourceName: String?,
        device: String?,
        unit: String?,
        startDate: String?,
        endDate: String?,
        value: String?
    ) {
        recordsSeen++
        if (type == null) return
        val source = sourceName?.takeIf { it.isNotBlank() } ?: "unknown"
        val fromWatch = isWatch(sourceName, device)

        if (type == SLEEP) {
            if (value == null || !value.contains("Asleep")) return
            val start = parse(startDate) ?: return
            val end = parse(endDate) ?: return
            val ms = (end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli()).toDouble()
            if (ms <= 0) return
            val d = day(end.toLocalDate())
            d.sleepMs.merge(source, ms) { a, b -> a + b }
            if (fromWatch) d.watchSources += source
            recordsUsed++
            return
        }

        val v = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return
        val start = parse(startDate) ?: return
        val date = start.toLocalDate()

        val used = when (type) {
            STEPS -> sum(date, source, fromWatch, v) { it.steps }
            ACTIVE_ENERGY -> energyKcal(v, unit)?.let { k -> sum(date, source, fromWatch, k) { it.activeKcal } } ?: false
            DISTANCE -> meters(v, unit)?.let { m -> sum(date, source, fromWatch, m) { it.distance } } ?: false
            HEART_RATE -> avg(date, fromWatch, v) { it.hr }
            RESTING_HR -> avg(date, fromWatch, v) { it.restingHr }
            HRV_SDNN -> avg(date, fromWatch, v) { it.sdnn }
            VO2_MAX -> avg(date, fromWatch, v) { it.vo2 }
            SPO2 -> avg(date, fromWatch, percent(v)) { it.spo2 }
            RESP_RATE -> avg(date, fromWatch, v) { it.resp }
            BODY_FAT -> avg(date, fromWatch, percent(v)) { it.bodyFat }
            LEAN_MASS -> kilograms(v, unit)?.let { kg -> avg(date, fromWatch, kg) { it.lean } } ?: false
            BODY_MASS -> kilograms(v, unit)?.let { kg -> addWeight(start, kg) } ?: false
            else -> false
        }
        if (used) recordsUsed++
    }

    fun addWorkout(
        sourceName: String?,
        device: String?,
        duration: String?,
        durationUnit: String?,
        startDate: String?,
        endDate: String?
    ) {
        recordsSeen++
        val start = parse(startDate) ?: return
        val minutes = duration?.toDoubleOrNull()?.let { minutes(it, durationUnit) }
            ?: parse(endDate)?.let { end ->
                (end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli()) / 60_000.0
            }
            ?: return
        if (!minutes.isFinite() || minutes <= 0) return
        val source = sourceName?.takeIf { it.isNotBlank() } ?: "unknown"
        if (sum(start.toLocalDate(), source, isWatch(sourceName, device), minutes) { it.exerciseMin }) recordsUsed++
    }

    fun result(): AppleAggregate {
        val out = days.entries.sortedBy { it.key }.map { (date, d) ->
            val watch = HashSet<String>()
            fun pickSum(map: Map<String, Double>, field: String): Double? {
                val best = map.maxByOrNull { it.value } ?: return null
                if (best.key in d.watchSources) watch += field
                return best.value.takeIf { it > 0 }
            }
            fun mean(a: Avg, field: String): Double? {
                if (a.n == 0) return null
                if (a.watch) watch += field
                return a.sum / a.n
            }
            AppleDay(
                date = date,
                steps = pickSum(d.steps, "steps"),
                activeKcal = pickSum(d.activeKcal, "activeKcal"),
                distanceM = pickSum(d.distance, "distance"),
                avgHr = mean(d.hr, "avgHr"),
                restingHr = mean(d.restingHr, "restingHr"),
                hrvSdnnMs = mean(d.sdnn, "hrvSdnn"),
                vo2max = mean(d.vo2, "vo2max"),
                spo2Pct = mean(d.spo2, "spo2"),
                respiratoryRate = mean(d.resp, "respiratoryRate"),
                bodyFatPct = mean(d.bodyFat, "bodyFat"),
                leanMassKg = mean(d.lean, "leanMass"),
                sleepMinutes = pickSum(d.sleepMs, "sleep")?.let { (it / 60_000.0).coerceAtMost(24 * 60.0) },
                exerciseMinutes = pickSum(d.exerciseMin, "exercise"),
                watchFields = watch
            )
        }
        return AppleAggregate(
            days = out,
            weights = weights.values.sortedBy { it.timeMillis },
            recordsSeen = recordsSeen,
            recordsUsed = recordsUsed
        )
    }

    // ------------------------------------------------------------------ accumulation

    private inline fun sum(
        date: LocalDate,
        source: String,
        fromWatch: Boolean,
        v: Double,
        select: (DayAcc) -> HashMap<String, Double>
    ): Boolean {
        if (v < 0) return false
        val d = day(date)
        select(d).merge(source, v) { a, b -> a + b }
        if (fromWatch) d.watchSources += source
        return true
    }

    private inline fun avg(date: LocalDate, fromWatch: Boolean, v: Double, select: (DayAcc) -> Avg): Boolean {
        if (v <= 0) return false
        val a = select(day(date))
        a.sum += v
        a.n++
        if (fromWatch) a.watch = true
        return true
    }

    private fun addWeight(at: OffsetDateTime, kg: Double): Boolean {
        if (kg <= 0) return false
        val date = at.toLocalDate()
        val millis = at.toInstant().toEpochMilli()
        val existing = weights[date]
        if (existing == null || millis < existing.timeMillis) weights[date] = AppleWeight(millis, date, kg)
        return true
    }

    companion object {
        const val STEPS = "HKQuantityTypeIdentifierStepCount"
        const val ACTIVE_ENERGY = "HKQuantityTypeIdentifierActiveEnergyBurned"
        const val DISTANCE = "HKQuantityTypeIdentifierDistanceWalkingRunning"
        const val HEART_RATE = "HKQuantityTypeIdentifierHeartRate"
        const val RESTING_HR = "HKQuantityTypeIdentifierRestingHeartRate"
        const val HRV_SDNN = "HKQuantityTypeIdentifierHeartRateVariabilitySDNN"
        const val VO2_MAX = "HKQuantityTypeIdentifierVO2Max"
        const val SPO2 = "HKQuantityTypeIdentifierOxygenSaturation"
        const val RESP_RATE = "HKQuantityTypeIdentifierRespiratoryRate"
        const val BODY_FAT = "HKQuantityTypeIdentifierBodyFatPercentage"
        const val LEAN_MASS = "HKQuantityTypeIdentifierLeanBodyMass"
        const val BODY_MASS = "HKQuantityTypeIdentifierBodyMass"
        const val SLEEP = "HKCategoryTypeIdentifierSleepAnalysis"

        /** Apple's export date format, e.g. "2026-11-17 06:30:00 +0530". */
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US)

        fun parse(s: String?): OffsetDateTime? =
            s?.let { runCatching { OffsetDateTime.parse(it.trim(), DATE) }.getOrNull() }

        /** Watch samples outrank iPhone ones when merging with other sources. */
        private fun isWatch(sourceName: String?, device: String?): Boolean =
            sourceName?.contains("Watch", ignoreCase = true) == true ||
                device?.contains("Watch", ignoreCase = true) == true

        /** Apple stores percentages as 0-1 fractions; tolerate an export that already used 0-100. */
        private fun percent(v: Double): Double = if (v <= 1.0) v * 100.0 else v

        // Apple's "Cal" is the food calorie (= kcal); lower-case "cal" is the small calorie.
        private fun energyKcal(v: Double, unit: String?): Double? = when (unit) {
            null, "kcal", "Cal" -> v
            "kJ" -> v / 4.184
            "cal" -> v / 1000.0
            "J" -> v / 4184.0
            else -> null
        }

        private fun meters(v: Double, unit: String?): Double? = when (unit) {
            "m" -> v
            null, "km" -> v * 1000.0
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            "cm" -> v / 100.0
            else -> null
        }

        private fun kilograms(v: Double, unit: String?): Double? = when (unit) {
            null, "kg" -> v
            "lb" -> v * 0.45359237
            "g" -> v / 1000.0
            "st" -> v * 6.35029318
            else -> null
        }

        private fun minutes(v: Double, unit: String?): Double? = when (unit) {
            null, "min" -> v
            "s" -> v / 60.0
            "hr", "h" -> v * 60.0
            else -> null
        }
    }
}
