package com.laksh.forge.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.laksh.forge.BuildConfig
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.db.WorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/** The four flat exports a spreadsheet user actually wants. */
enum class CsvDataset(val label: String, val fileStem: String) {
    FOOD("Food log", "forge-food"),
    WEIGHTS("Body weight", "forge-weights"),
    WORKOUTS("Workouts & sets", "forge-workouts"),
    MEASUREMENTS("Measurements", "forge-measurements")
}

data class RestoreCounts(
    val food: Int = 0,
    val weights: Int = 0,
    val measurements: Int = 0,
    val sessions: Int = 0,
    val sets: Int = 0,
    val health: Int = 0,
    val plans: Int = 0,
    val checkins: Int = 0,
    val programs: Int = 0,
    val trainingMaxes: Int = 0,
    val chat: Int = 0
) {
    val total: Int
        get() = food + weights + measurements + sessions + sets + health + plans + checkins +
            programs + trainingMaxes + chat

    /** Non-zero lines for the confirmation dialog, in the order people care about. */
    fun lines(): List<Pair<String, Int>> = listOf(
        "Food entries" to food,
        "Weigh-ins" to weights,
        "Measurements" to measurements,
        "Workouts" to sessions,
        "Sets" to sets,
        "Programs" to programs,
        "Training maxes" to trainingMaxes,
        "Phase plans" to plans,
        "Check-ins" to checkins,
        "Wearable days" to health,
        "Coach messages" to chat
    ).filter { it.second > 0 }
}

data class RestorePreview(
    val exportedAt: Long,
    val appVersion: String?,
    val inFile: RestoreCounts,
    val toAdd: RestoreCounts
) {
    val alreadyHere: Int get() = (inFile.total - toAdd.total).coerceAtLeast(0)
}

data class WorkoutImportPreview(
    val source: WorkoutCsvSource,
    val workoutsInFile: Int,
    val newWorkouts: Int,
    val duplicates: Int,
    val newSets: Int,
    val firstStartedAt: Long?,
    val lastStartedAt: Long?,
    val exercises: Int,
    val matchedExercises: Int,
    /** A few names that didn't map to the catalog - they're still imported, just unlinked. */
    val unmatchedSample: List<String>,
    val skippedRows: Int,
    val unitFromFile: Boolean,
    val warnings: List<String>
)

/**
 * Export, restore and third-party import. Everything here is user-initiated through the
 * Storage Access Framework, so files land exactly where the person chose and nothing is
 * ever written to shared storage behind their back.
 *
 * Restores MERGE: rows already on the device are never touched, duplicates are skipped by a
 * natural key (timestamp + content), and everything is written in one transaction so a bad
 * file can't leave half a restore behind.
 */
object BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    /** A backup bigger than this is not a Forge backup; refuse it without reading it all into memory. */
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    /** CSV exports are read whole as text, so their cap is lower to bound the String copies. */
    private const val MAX_TEXT_IMPORT_BYTES = 16L * 1024 * 1024

    private fun db(context: Context) = ForgeDatabase.get(context.applicationContext)

    // ================================================================== export

    suspend fun snapshot(context: Context): ForgeBackup = withContext(Dispatchers.IO) {
        val dao = db(context).backupDao()
        ForgeBackup(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            food = dao.allFood().map { it.toDto() },
            weights = dao.allWeights().map { it.toDto() },
            measurements = dao.allMeasurements().map { it.toDto() },
            sessions = dao.allSessions().map { it.toDto() },
            sets = dao.allSets().map { it.toDto() },
            health = dao.allHealth().map { it.toDto() },
            plans = dao.allPlans().map { it.toDto() },
            checkins = dao.allCheckins().map { it.toDto() },
            programs = dao.allPrograms().map { it.toDto() },
            programDays = dao.allProgramDays().map { it.toDto() },
            programExercises = dao.allProgramExercises().map { it.toDto() },
            trainingMaxes = dao.allTrainingMaxes().map { it.toDto() },
            chat = dao.allChat().map { it.toDto() }
        )
    }

    /** Writes everything to [uri] as one JSON file. Returns the number of records written. */
    suspend fun exportJson(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val backup = snapshot(context)
        val text = json.encodeToString(ForgeBackup.serializer(), backup)
        writeText(context, uri, text)
        countOf(backup).total
    }

    /** Writes one flat CSV. Returns the number of data rows. */
    suspend fun exportCsv(context: Context, uri: Uri, dataset: CsvDataset): Int = withContext(Dispatchers.IO) {
        val dao = db(context).backupDao()
        val zone = ZoneId.systemDefault()
        val lines = mutableListOf<String>()
        when (dataset) {
            CsvDataset.FOOD -> {
                lines += Csv.line(
                    listOf(
                        "date", "time", "meal", "name", "brand", "serving", "quantity", "kcal",
                        "protein_g", "carbs_g", "fat_g", "fiber_g", "source", "confidence"
                    )
                )
                dao.allFood().forEach { f ->
                    lines += Csv.line(
                        listOf(
                            f.localDate, time(f.timestamp, zone), f.mealSlot, f.name, f.brand,
                            f.servingDesc, num(f.quantity), num(f.kcal), num(f.proteinG), num(f.carbsG),
                            num(f.fatG), num(f.fiberG), f.source, f.confidence
                        )
                    )
                }
            }
            CsvDataset.WEIGHTS -> {
                lines += Csv.line(listOf("date", "time", "weight_kg", "body_fat_pct", "source", "note"))
                dao.allWeights().forEach { w ->
                    lines += Csv.line(
                        listOf(w.localDate, time(w.timestamp, zone), num(w.kg), num(w.bodyFatPct), w.source, w.note)
                    )
                }
            }
            CsvDataset.WORKOUTS -> {
                lines += Csv.line(
                    listOf(
                        "date", "start", "end", "workout", "exercise", "exercise_id", "set", "warmup",
                        "weight_kg", "reps", "rir", "rpe", "workout_notes"
                    )
                )
                val setsBySession = dao.allSets().groupBy { it.sessionId }
                dao.allSessions().forEach { s ->
                    val sets = setsBySession[s.id].orEmpty().sortedWith(compareBy({ it.timestamp }, { it.id }))
                    if (sets.isEmpty()) {
                        // Keep empty sessions visible so the export is a complete history.
                        lines += Csv.line(
                            listOf(
                                s.localDate, dateTime(s.startedAt, zone), s.endedAt?.let { dateTime(it, zone) },
                                s.title, null, null, null, null, null, null, null, null, s.notes
                            )
                        )
                    }
                    sets.forEach { l ->
                        lines += Csv.line(
                            listOf(
                                s.localDate, dateTime(s.startedAt, zone), s.endedAt?.let { dateTime(it, zone) },
                                s.title, l.exerciseName, l.exerciseId, (l.setIdx + 1).toString(),
                                if (l.isWarmup) "1" else "0", num(l.weightKg), l.reps.toString(),
                                num(l.rir), num(l.rpe), s.notes
                            )
                        )
                    }
                }
            }
            CsvDataset.MEASUREMENTS -> {
                lines += Csv.line(
                    listOf(
                        "date", "time", "waist_cm", "neck_cm", "hips_cm", "chest_cm", "arm_cm",
                        "thigh_cm", "calf_cm", "body_fat_pct", "body_fat_method", "note"
                    )
                )
                dao.allMeasurements().forEach { m ->
                    lines += Csv.line(
                        listOf(
                            m.localDate, time(m.timestamp, zone), num(m.waistCm), num(m.neckCm), num(m.hipsCm),
                            num(m.chestCm), num(m.armCm), num(m.thighCm), num(m.calfCm), num(m.bodyFatPct),
                            m.bodyFatMethod, m.note
                        )
                    )
                }
            }
        }
        // CRLF line ends and a BOM so Excel opens UTF-8 names (dal, roti, paneer) correctly.
        writeText(context, uri, "﻿" + lines.joinToString("\r\n") + "\r\n")
        lines.size - 1
    }

    // ================================================================== restore

    /** Reads and validates a Forge backup without touching the database. */
    suspend fun readBackup(context: Context, uri: Uri): ForgeBackup = withContext(Dispatchers.IO) {
        val backup = try {
            // Decoded straight from the stream: no full-file byte array or String copy.
            openCapped(context, uri, MAX_IMPORT_BYTES).use { json.decodeFromStream(ForgeBackup.serializer(), it) }
        } catch (e: SerializationException) {
            throw IOException("That file isn't a Forge backup (it couldn't be read as one).")
        } catch (e: IllegalArgumentException) {
            throw IOException("That file isn't a Forge backup (it couldn't be read as one).")
        }
        if (backup.format != ForgeBackup.FORMAT) {
            throw IOException("That file isn't a Forge backup.")
        }
        if (backup.version > ForgeBackup.VERSION) {
            throw IOException("This backup was made by a newer version of Forge. Update the app, then try again.")
        }
        backup
    }

    suspend fun previewRestore(context: Context, backup: ForgeBackup): RestorePreview =
        withContext(Dispatchers.IO) {
            val plan = plan(context, backup)
            RestorePreview(
                exportedAt = backup.exportedAt,
                appVersion = backup.appVersion,
                inFile = countOf(backup),
                toAdd = plan.counts()
            )
        }

    /** Merges [backup] into the database. Returns what was actually added. */
    suspend fun applyRestore(context: Context, backup: ForgeBackup): RestoreCounts = withContext(Dispatchers.IO) {
        val database = db(context)
        val dao = database.backupDao()
        database.withTransaction {
            // Re-plan inside the transaction so nothing logged since the preview is duplicated.
            val p = plan(context, backup)

            // Programs -> days -> exercises, remapping ids as we go.
            val programMap = HashMap<Long, Long>(p.existingProgramIds)
            val dayMap = HashMap<Long, Long>(p.existingDayIds)
            var activeTaken = p.localHasActiveProgram
            p.newPrograms.forEach { prog ->
                val activate = prog.isActive && !activeTaken
                if (activate) activeTaken = true
                programMap[prog.id] = dao.insertProgram(prog.toEntity(active = activate))
            }
            val newProgramOldIds = p.newPrograms.map { it.id }.toSet()
            val newDays = backup.programDays.filter { it.programId in newProgramOldIds }
            newDays.forEach { d ->
                val localProgram = programMap[d.programId] ?: return@forEach
                dayMap[d.id] = dao.insertProgramDay(d.toEntity(localProgram))
            }
            // Only exercises of newly inserted days: an existing program already has its own.
            val newDayOldIds = newDays.mapTo(HashSet()) { it.id }
            val exercises = backup.programExercises.mapNotNull { e ->
                if (e.dayId !in newDayOldIds) return@mapNotNull null
                dayMap[e.dayId]?.let { e.toEntity(it) }
            }
            if (exercises.isNotEmpty()) dao.insertProgramExercises(exercises)

            // Sessions, then their sets.
            val sessionMap = HashMap<Long, Long>(p.existingSessionIds)
            p.newSessions.forEach { s ->
                sessionMap[s.id] = dao.insertSession(
                    s.toEntity(
                        programDayId = s.programDayId?.let { dayMap[it] },
                        programId = s.programId?.let { programMap[it] }
                    )
                )
            }
            val sets = p.sets.mapNotNull { set -> sessionMap[set.sessionId]?.let { set.toEntity(it) } }
            if (sets.isNotEmpty()) dao.insertSets(sets)

            if (p.food.isNotEmpty()) dao.insertFood(p.food.map { it.toEntity() })
            if (p.weights.isNotEmpty()) dao.insertWeights(p.weights.map { it.toEntity() })
            if (p.measurements.isNotEmpty()) dao.insertMeasurements(p.measurements.map { it.toEntity() })
            if (p.health.isNotEmpty()) dao.insertHealthIfAbsent(p.health.map { it.toEntity() })
            if (p.checkins.isNotEmpty()) dao.insertCheckins(p.checkins.map { it.toEntity() })
            if (p.trainingMaxes.isNotEmpty()) dao.insertTrainingMaxesIfAbsent(p.trainingMaxes.map { it.toEntity() })
            if (p.chat.isNotEmpty()) dao.insertChat(p.chat.map { it.toEntity() })
            if (p.plans.isNotEmpty()) {
                // At most one active plan: the device's own, else the newest active one in the file.
                val activeCandidate = if (p.localHasActivePlan) null
                else p.plans.filter { it.isActive }.maxByOrNull { it.createdAt }
                dao.insertPlans(p.plans.map { it.toEntity(active = it === activeCandidate) })
            }

            p.counts().copy(sets = sets.size)
        }
    }

    private class RestorePlan(
        val food: List<FoodDto>,
        val weights: List<WeightDto>,
        val measurements: List<MeasurementDto>,
        val health: List<HealthDto>,
        val plans: List<PlanDto>,
        val checkins: List<CheckinDto>,
        val trainingMaxes: List<TrainingMaxDto>,
        val chat: List<ChatDto>,
        val newPrograms: List<ProgramDto>,
        val existingProgramIds: Map<Long, Long>,
        val existingDayIds: Map<Long, Long>,
        val newSessions: List<SessionDto>,
        val existingSessionIds: Map<Long, Long>,
        val sets: List<SetDto>,
        val localHasActiveProgram: Boolean,
        val localHasActivePlan: Boolean
    ) {
        fun counts() = RestoreCounts(
            food = food.size, weights = weights.size, measurements = measurements.size,
            sessions = newSessions.size, sets = sets.size, health = health.size, plans = plans.size,
            checkins = checkins.size, programs = newPrograms.size, trainingMaxes = trainingMaxes.size,
            chat = chat.size
        )
    }

    /** Works out what's new. Each `seen` set also swallows duplicates inside the file itself. */
    private suspend fun plan(context: Context, b: ForgeBackup): RestorePlan {
        val dao = db(context).backupDao()

        val foodSeen = dao.allFood().mapTo(HashSet()) { foodKey(it.timestamp, it.name, it.kcal) }
        val food = b.food.filter { foodSeen.add(foodKey(it.timestamp, it.name, it.kcal)) }

        val weightSeen = dao.allWeights().mapTo(HashSet()) { weightKey(it.timestamp, it.kg) }
        val weights = b.weights.filter { weightSeen.add(weightKey(it.timestamp, it.kg)) }

        val measSeen = dao.allMeasurements().mapTo(HashSet()) { it.timestamp }
        val measurements = b.measurements.filter { measSeen.add(it.timestamp) }

        val healthSeen = dao.allHealth().mapTo(HashSet()) { it.localDate }
        val health = b.health.filter { it.localDate.isNotBlank() && healthSeen.add(it.localDate) }

        val localPlans = dao.allPlans()
        val planSeen = localPlans.mapTo(HashSet()) { "${it.createdAt}|${it.phase}" }
        val plans = b.plans.filter { planSeen.add("${it.createdAt}|${it.phase}") }

        val checkinSeen = dao.allCheckins().mapTo(HashSet()) { "${it.weekStart}|${it.createdAt}" }
        val checkins = b.checkins.filter { checkinSeen.add("${it.weekStart}|${it.createdAt}") }

        val tmSeen = dao.allTrainingMaxes().mapTo(HashSet()) { it.exerciseKey }
        val tms = b.trainingMaxes.filter { it.exerciseKey.isNotBlank() && tmSeen.add(it.exerciseKey) }

        val chatSeen = dao.allChat().mapTo(HashSet()) { chatKey(it.timestamp, it.role, it.content) }
        val chat = b.chat.filter { chatSeen.add(chatKey(it.timestamp, it.role, it.content)) }

        // Programs: same name + creation time = the same program.
        val localPrograms = dao.allPrograms()
        val localProgramByKey = localPrograms.associateBy { "${it.name}|${it.createdAt}" }
        val existingProgramIds = HashMap<Long, Long>()
        val newPrograms = mutableListOf<ProgramDto>()
        val programKeysSeen = HashSet<String>()
        b.programs.forEach { prog ->
            val key = "${prog.name}|${prog.createdAt}"
            val local = localProgramByKey[key]
            when {
                local != null -> existingProgramIds[prog.id] = local.id
                programKeysSeen.add(key) -> newPrograms += prog
            }
        }
        // Days of programs that already exist map onto the local day at the same position.
        val localDays = dao.allProgramDays()
        val existingDayIds = HashMap<Long, Long>()
        b.programDays.forEach { d ->
            val localProgram = existingProgramIds[d.programId] ?: return@forEach
            localDays.firstOrNull { it.programId == localProgram && it.orderIdx == d.orderIdx }
                ?.let { existingDayIds[d.id] = it.id }
        }

        // Sessions: same start time + title = the same workout.
        val localSessions = dao.allSessions()
        val localSessionByKey = localSessions.associateBy { "${it.startedAt}|${it.title}" }
        val existingSessionIds = HashMap<Long, Long>()
        val newSessions = mutableListOf<SessionDto>()
        val sessionKeysSeen = HashSet<String>()
        b.sessions.forEach { s ->
            val key = "${s.startedAt}|${s.title}"
            val local = localSessionByKey[key]
            when {
                local != null -> existingSessionIds[s.id] = local.id
                sessionKeysSeen.add(key) -> newSessions += s
            }
        }
        val newSessionIds = newSessions.mapTo(HashSet()) { it.id }

        // Sets of new sessions all go in; sets of existing sessions only when missing there.
        val localSetKeys = dao.allSets().mapTo(HashSet()) { setKey(it.sessionId, it.exerciseName, it.setIdx, it.timestamp) }
        val sets = b.sets.filter { set ->
            when {
                set.sessionId in newSessionIds -> true
                else -> {
                    val local = existingSessionIds[set.sessionId] ?: return@filter false
                    localSetKeys.add(setKey(local, set.exerciseName, set.setIdx, set.timestamp))
                }
            }
        }

        return RestorePlan(
            food = food, weights = weights, measurements = measurements, health = health,
            plans = plans, checkins = checkins, trainingMaxes = tms, chat = chat,
            newPrograms = newPrograms, existingProgramIds = existingProgramIds,
            existingDayIds = existingDayIds, newSessions = newSessions,
            existingSessionIds = existingSessionIds, sets = sets,
            localHasActiveProgram = localPrograms.any { it.isActive },
            localHasActivePlan = localPlans.any { it.isActive }
        )
    }

    private fun foodKey(ts: Long, name: String, kcal: Double) = "$ts|$name|${(kcal * 10).roundToLong()}"
    private fun weightKey(ts: Long, kg: Double) = "$ts|${(kg * 1000).roundToLong()}"
    private fun chatKey(ts: Long, role: String, content: String) = "$ts|$role|${content.hashCode()}"
    private fun setKey(session: Long, name: String, idx: Int, ts: Long) = "$session|$name|$idx|$ts"

    private fun countOf(b: ForgeBackup) = RestoreCounts(
        food = b.food.size, weights = b.weights.size, measurements = b.measurements.size,
        sessions = b.sessions.size, sets = b.sets.size, health = b.health.size, plans = b.plans.size,
        checkins = b.checkins.size, programs = b.programs.size, trainingMaxes = b.trainingMaxes.size,
        chat = b.chat.size
    )

    // ================================================================== Hevy / Strong

    suspend fun readWorkoutCsv(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        readText(context, uri)
    }

    /** Two sessions starting within a minute of each other are the same workout. */
    private const val SAME_SESSION_WINDOW_MS = 60_000L

    suspend fun previewWorkouts(
        context: Context,
        result: WorkoutCsvResult,
        resolve: (String) -> String?
    ): WorkoutImportPreview = withContext(Dispatchers.IO) {
        val existingStarts = db(context).backupDao().allSessions().map { it.startedAt }.sorted()
        val fresh = result.workouts.filterNot { w -> isDuplicate(w.startedAt, existingStarts) }
        val names = result.workouts.flatMap { it.exerciseNames }.distinct()
        val matched = names.filter { WorkoutCsvImport.resolveId(it, resolve) != null }
        WorkoutImportPreview(
            source = result.source,
            workoutsInFile = result.workouts.size,
            newWorkouts = fresh.size,
            duplicates = result.workouts.size - fresh.size,
            newSets = fresh.sumOf { it.sets.size },
            firstStartedAt = result.workouts.minOfOrNull { it.startedAt },
            lastStartedAt = result.workouts.maxOfOrNull { it.startedAt },
            exercises = names.size,
            matchedExercises = matched.size,
            unmatchedSample = (names - matched.toSet()).take(8),
            skippedRows = result.skippedRows,
            unitFromFile = result.unitFromFile,
            warnings = result.warnings
        )
    }

    /** Inserts the workouts that aren't already here. Returns (workouts, sets) added. */
    suspend fun applyWorkouts(
        context: Context,
        result: WorkoutCsvResult,
        resolve: (String) -> String?
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val database = db(context)
        val dao = database.backupDao()
        val zone = ZoneId.systemDefault()
        val ids = result.workouts.flatMap { it.exerciseNames }.distinct()
            .associateWith { WorkoutCsvImport.resolveId(it, resolve) }
        database.withTransaction {
            val existingStarts = dao.allSessions().map { it.startedAt }.sorted().toMutableList()
            var workouts = 0
            var sets = 0
            result.workouts.forEach { w ->
                if (isDuplicate(w.startedAt, existingStarts)) return@forEach
                val sessionId = dao.insertSession(
                    WorkoutSession(
                        title = w.title,
                        startedAt = w.startedAt,
                        endedAt = w.endedAt ?: w.startedAt,
                        localDate = Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalDate().toString(),
                        notes = listOfNotNull("Imported from ${result.source.label}", w.notes).joinToString("\n")
                    )
                )
                val rows = w.sets.mapIndexed { i, s ->
                    SetLog(
                        sessionId = sessionId,
                        exerciseName = s.exerciseName,
                        setIdx = s.setIdx,
                        weightKg = s.weightKg,
                        reps = s.reps,
                        // RIR-based RPE scale: RPE 10 = 0 reps in reserve, RPE 8 = 2.
                        rir = s.rpe?.let { (10.0 - it).coerceAtLeast(0.0) },
                        isWarmup = s.isWarmup,
                        // The exports don't time individual sets; keep them ordered within the session.
                        timestamp = w.startedAt + i * 1_000L,
                        exerciseId = ids[s.exerciseName],
                        rpe = s.rpe
                    )
                }
                if (rows.isNotEmpty()) dao.insertSets(rows)
                existingStarts += w.startedAt
                existingStarts.sort()
                workouts++
                sets += rows.size
            }
            workouts to sets
        }
    }

    private fun isDuplicate(start: Long, sortedStarts: List<Long>): Boolean {
        if (sortedStarts.isEmpty()) return false
        var idx = sortedStarts.binarySearch(start)
        if (idx >= 0) return true
        idx = -idx - 1
        val before = sortedStarts.getOrNull(idx - 1)
        val after = sortedStarts.getOrNull(idx)
        return (before != null && abs(start - before) <= SAME_SESSION_WINDOW_MS) ||
            (after != null && abs(after - start) <= SAME_SESSION_WINDOW_MS)
    }

    // ================================================================== io helpers

    private fun writeText(context: Context, uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Couldn't open that location for writing.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Open [uri] for reading, refusing it up front when the provider reports a size over
     * [limit] and, when it doesn't, as soon as reading passes [limit].
     */
    private fun openCapped(context: Context, uri: Uri, limit: Long): java.io.InputStream {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getLong(i) else null
            }
        }.getOrNull()
        if (size != null && size > limit) throw IOException(CappedInputStream.TOO_LARGE)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Couldn't open that file.")
        return CappedInputStream(input.buffered(), limit)
    }

    private fun readText(context: Context, uri: Uri): String =
        openCapped(context, uri, MAX_TEXT_IMPORT_BYTES).use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun time(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)
    private fun dateTime(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateTimeFmt)

    /** Locale-independent numbers ("82.5", never "82,5") so every spreadsheet reads them. */
    private fun num(v: Double?): String? = v?.let {
        if (it.isNaN() || it.isInfinite()) null
        else BigDecimal(it).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
