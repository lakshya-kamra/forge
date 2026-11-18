package com.laksh.forge.data.backup

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

enum class WorkoutCsvSource(val label: String) { HEVY("Hevy"), STRONG("Strong") }

data class ImportedSet(
    /** Exactly as the other app wrote it - Forge never renames the user's history. */
    val exerciseName: String,
    val setIdx: Int,
    val weightKg: Double,
    val reps: Int,
    val isWarmup: Boolean,
    val rpe: Double?
)

data class ImportedWorkout(
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val notes: String?,
    val sets: List<ImportedSet>
) {
    val exerciseNames: List<String> get() = sets.map { it.exerciseName }.distinct()
}

data class WorkoutCsvResult(
    val source: WorkoutCsvSource,
    val workouts: List<ImportedWorkout>,
    /** Rows that carried no loggable set (timed or distance-only sets, empty rows). */
    val skippedRows: Int,
    /** True when the file said which unit its weights are in; false means we assumed one. */
    val unitFromFile: Boolean,
    val warnings: List<String>
) {
    val setCount: Int get() = workouts.sumOf { it.sets.size }
}

class WorkoutCsvException(message: String) : Exception(message)

/**
 * Parses the workout-history CSVs that Hevy and Strong export into Forge sessions and sets.
 *
 * Both parsers are header-driven (column order and minor naming differences don't matter)
 * and tolerant: rows without reps - timed holds, cardio distance rows - are counted as
 * skipped rather than failing the whole import. Pure Kotlin + java.time so it runs in JVM
 * unit tests without Android.
 */
object WorkoutCsvImport {

    private const val LB_TO_KG = 0.45359237

    fun detect(table: CsvTable): WorkoutCsvSource? = when {
        table.has("exercise_title") && table.has("start_time") -> WorkoutCsvSource.HEVY
        table.has("Exercise Name") && table.has("Workout Name") -> WorkoutCsvSource.STRONG
        else -> null
    }

    /**
     * @param weightsInLb for files that don't say their unit (older Strong exports): true if
     *   the person's Strong app was set to lb. Ignored when the file names its unit.
     */
    fun parse(text: String, zone: ZoneId, weightsInLb: Boolean = false): WorkoutCsvResult {
        val rows = Csv.parse(text)
        if (rows.size < 2) throw WorkoutCsvException("That file has no workout rows.")
        val table = CsvTable(rows)
        return when (detect(table)) {
            WorkoutCsvSource.HEVY -> parseHevy(table, zone)
            WorkoutCsvSource.STRONG -> parseStrong(table, zone, weightsInLb)
            null -> throw WorkoutCsvException(
                "This doesn't look like a Hevy or Strong export. Hevy files have " +
                    "\"exercise_title\" and \"start_time\" columns; Strong files have " +
                    "\"Workout Name\" and \"Exercise Name\"."
            )
        }
    }

    // ------------------------------------------------------------------ Hevy

    private fun parseHevy(t: CsvTable, zone: ZoneId): WorkoutCsvResult {
        val cTitle = t.col("title")
        val cStart = t.col("start_time") ?: throw WorkoutCsvException("Missing the start_time column.")
        val cEnd = t.col("end_time")
        val cDesc = t.col("description")
        val cExercise = t.col("exercise_title") ?: throw WorkoutCsvException("Missing the exercise_title column.")
        val cSetIndex = t.col("set_index")
        val cSetType = t.col("set_type")
        val cKg = t.col("weight_kg")
        val cLb = t.col("weight_lbs", "weight_lb")
        val cReps = t.col("reps")
        val cRpe = t.col("rpe")

        val warnings = mutableListOf<String>()
        var skipped = 0
        var badDates = 0
        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        val starts = HashMap<String, Long>()

        t.body.forEach { row ->
            val startRaw = row.cell(cStart)
            val start = startRaw?.let { parseDateTime(it, zone) }
            if (start == null) {
                badDates++
                skipped++
                return@forEach
            }
            val key = "${row.cell(cTitle).orEmpty()}|$startRaw"
            groups.getOrPut(key) { mutableListOf() } += row
            starts[key] = start
        }

        val workouts = groups.map { (key, rows) ->
            val first = rows.first()
            val perExerciseCounter = HashMap<String, Int>()
            val sets = rows.mapNotNull { row ->
                val name = row.cell(cExercise)
                if (name == null) {
                    skipped++
                    return@mapNotNull null
                }
                val reps = row.cell(cReps)?.toDoubleOrNull()?.toInt()
                if (reps == null || reps <= 0) {
                    skipped++
                    return@mapNotNull null
                }
                val kg = row.cell(cKg)?.toNumber()
                    ?: row.cell(cLb)?.toNumber()?.let { it * LB_TO_KG }
                    ?: 0.0
                val type = row.cell(cSetType)?.lowercase()
                val idx = row.cell(cSetIndex)?.toIntOrNull()
                    ?: perExerciseCounter.getOrDefault(name, 0)
                perExerciseCounter[name] = idx + 1
                ImportedSet(
                    exerciseName = name,
                    setIdx = idx,
                    weightKg = roundKg(kg),
                    reps = reps,
                    isWarmup = type == "warmup" || type == "warm_up" || type == "warm-up",
                    rpe = row.cell(cRpe)?.toNumber()?.takeIf { it in 1.0..10.0 }
                )
            }
            ImportedWorkout(
                title = first.cell(cTitle) ?: "Hevy workout",
                startedAt = starts.getValue(key),
                endedAt = first.cell(cEnd)?.let { parseDateTime(it, zone) },
                notes = first.cell(cDesc),
                sets = sets
            )
        }.filter { it.sets.isNotEmpty() }

        if (badDates > 0) warnings += "$badDates rows had a start time Forge couldn't read and were skipped."
        return WorkoutCsvResult(
            source = WorkoutCsvSource.HEVY,
            workouts = workouts.sortedBy { it.startedAt },
            skippedRows = skipped,
            unitFromFile = true,
            warnings = warnings
        )
    }

    // ------------------------------------------------------------------ Strong

    private fun parseStrong(t: CsvTable, zone: ZoneId, weightsInLb: Boolean): WorkoutCsvResult {
        val cDate = t.col("Date") ?: throw WorkoutCsvException("Missing the Date column.")
        val cWorkout = t.col("Workout Name")
        val cDuration = t.col("Duration", "Workout Duration")
        val cExercise = t.col("Exercise Name") ?: throw WorkoutCsvException("Missing the Exercise Name column.")
        val cSetOrder = t.col("Set Order")
        val cWeight = t.col("Weight")
        val cUnit = t.col("Weight Unit")
        val cReps = t.col("Reps")
        val cRpe = t.col("RPE")
        val cWorkoutNotes = t.col("Workout Notes")

        val warnings = mutableListOf<String>()
        var skipped = 0
        var badDates = 0
        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        val starts = HashMap<String, Long>()

        t.body.forEach { row ->
            val dateRaw = row.cell(cDate)
            val start = dateRaw?.let { parseDateTime(it, zone) }
            if (start == null) {
                badDates++
                skipped++
                return@forEach
            }
            val key = "$dateRaw|${row.cell(cWorkout).orEmpty()}"
            groups.getOrPut(key) { mutableListOf() } += row
            starts[key] = start
        }

        val workouts = groups.map { (key, rows) ->
            val first = rows.first()
            val perExerciseCounter = HashMap<String, Int>()
            val sets = rows.mapNotNull { row ->
                val name = row.cell(cExercise)
                if (name == null) {
                    skipped++
                    return@mapNotNull null
                }
                val order = row.cell(cSetOrder)
                val reps = row.cell(cReps)?.toNumber()?.toInt()
                if (reps == null || reps <= 0) {
                    // Rest-timer rows, timed holds and cardio rows carry no reps.
                    skipped++
                    return@mapNotNull null
                }
                val rawWeight = row.cell(cWeight)?.toNumber() ?: 0.0
                val unit = row.cell(cUnit)?.lowercase()
                val inLb = when {
                    unit == null -> weightsInLb
                    unit.startsWith("lb") -> true
                    else -> false
                }
                // Newer Strong exports mark warm-ups with "W" in Set Order; numeric otherwise.
                val warm = order?.uppercase()?.startsWith("W") == true
                val idx = perExerciseCounter.getOrDefault(name, 0)
                perExerciseCounter[name] = idx + 1
                ImportedSet(
                    exerciseName = name,
                    setIdx = idx,
                    weightKg = roundKg(if (inLb) rawWeight * LB_TO_KG else rawWeight),
                    reps = reps,
                    isWarmup = warm,
                    rpe = row.cell(cRpe)?.toNumber()?.takeIf { it in 1.0..10.0 }
                )
            }
            val start = starts.getValue(key)
            ImportedWorkout(
                title = first.cell(cWorkout) ?: "Strong workout",
                startedAt = start,
                endedAt = first.cell(cDuration)?.let(::parseDurationMillis)?.let { start + it },
                notes = first.cell(cWorkoutNotes),
                sets = sets
            )
        }.filter { it.sets.isNotEmpty() }

        if (badDates > 0) warnings += "$badDates rows had a date Forge couldn't read and were skipped."
        return WorkoutCsvResult(
            source = WorkoutCsvSource.STRONG,
            workouts = workouts.sortedBy { it.startedAt },
            skippedRows = skipped,
            unitFromFile = cUnit != null,
            warnings = warnings
        )
    }

    // ------------------------------------------------------------------ helpers

    /** "82,5" (decimal comma) and "82.5" both parse. */
    private fun String.toNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()

    private fun roundKg(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0

    private val localPatterns: List<DateTimeFormatter> = listOf(
        "d MMM yyyy, HH:mm",
        "d MMM yyyy, H:mm",
        "d MMM yyyy HH:mm",
        "MMM d, yyyy, HH:mm",
        "MMM d, yyyy HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm"
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    /** Parse the timestamp formats these apps write, in the device's zone unless one is given. */
    fun parseDateTime(raw: String, zone: ZoneId): Long? {
        val s = raw.trim()
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli() }
        for (f in localPatterns) {
            runCatching { return LocalDateTime.parse(s, f).atZone(zone).toInstant().toEpochMilli() }
        }
        return null
    }

    /** Strong durations: "1h 5m", "45m", "1h", "50s", or a bare number of seconds. */
    fun parseDurationMillis(raw: String): Long? {
        val s = raw.trim().lowercase()
        s.toLongOrNull()?.let { return it * 1000 }
        val h = Regex("(\\d+)\\s*h").find(s)?.groupValues?.get(1)?.toLong() ?: 0
        val m = Regex("(\\d+)\\s*m(?!s)").find(s)?.groupValues?.get(1)?.toLong() ?: 0
        val sec = Regex("(\\d+)\\s*s").find(s)?.groupValues?.get(1)?.toLong() ?: 0
        val total = (h * 3600 + m * 60 + sec) * 1000
        return total.takeIf { it > 0 }
    }

    // ------------------------------------------------------------------ name mapping

    /**
     * Ways an exported name might match Forge's catalog. Both apps write equipment in
     * brackets - "Bench Press (Barbell)" - so try the raw name, "Barbell Bench Press", and the
     * bare movement in that order. The first one the resolver knows wins.
     */
    fun nameCandidates(name: String): List<String> {
        val trimmed = name.trim()
        val m = Regex("^(.*?)\\s*\\(([^)]+)\\)\\s*$").find(trimmed)
        return if (m == null) listOf(trimmed) else {
            val base = m.groupValues[1].trim()
            val equipment = m.groupValues[2].trim()
            listOf(trimmed, "$equipment $base", base).distinct()
        }
    }

    /** Catalog id for an exported name, or null. [resolve] is ExerciseCatalog.resolve in the app. */
    fun resolveId(name: String, resolve: (String) -> String?): String? =
        nameCandidates(name).firstNotNullOfOrNull { resolve(it) }
}
