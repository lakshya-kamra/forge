package com.laksh.forge.domain.program

import androidx.room.withTransaction
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.ProgramExercise
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.db.TrainingDao
import com.laksh.forge.data.db.TrainingMax
import com.laksh.forge.data.db.WorkoutSession
import com.laksh.forge.domain.exercise.ExerciseCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** One slot of a session, ready for the engine. */
data class PlannedSlot(
    val index: Int,
    val slot: LibraryExercise,
    /** Catalog id when the name resolves, for "How to" and swaps. */
    val exerciseId: String?,
    val startKey: String,
    /** Restrict history to this training day (programs whose days progress separately). */
    val scopeDayId: Long?
)

data class SessionPlan(
    val session: WorkoutSession?,
    val program: Program?,
    val config: ProgressionConfig?,
    val week: Int,
    val weekLabel: String?,
    val dayName: String?,
    val dayFocus: String?,
    val slots: List<PlannedSlot>
)

/** A logged set as the engine sees it; RPE converts with the RIR-based scale (RPE 10 = 0 RIR). */
fun SetLog.toLogged() = LoggedSet(
    weightKg = weightKg,
    reps = reps,
    rir = rir ?: rpe?.let { (10.0 - it).coerceAtLeast(0.0) },
    targetReps = targetReps,
    targetWeightKg = targetWeightKg
)

/** A number shown and edited on the Train screen: a TM, 1RM, 5RM, top set or start weight. */
data class MaxEntry(val key: String, val basis: String, val label: String, val valueKg: Double?)

/**
 * Database side of the program feature: adopting a template, building a session's plan,
 * reading slot history, and saving what a finished session changed.
 */
object ProgramStore {

    /** Writes keep defaults so stored JSON is self-describing ("amrap": false included). */
    val storeJson: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val db: ForgeDatabase get() = ForgeDatabase.get(ForgeApp.instance)
    val training: TrainingDao get() = db.trainingDao()

    // ------------------------------------------------------------------ codecs

    fun decodeConfig(json: String?): ProgressionConfig? = json?.takeIf { it.isNotBlank() }?.let {
        runCatching { storeJson.decodeFromString(ProgressionConfig.serializer(), it) }.getOrNull()
    }

    fun encodeConfig(c: ProgressionConfig): String = storeJson.encodeToString(ProgressionConfig.serializer(), c)

    fun decodeWeeks(json: String?): List<LibraryWeek>? = json?.takeIf { it.isNotBlank() }?.let {
        runCatching { storeJson.decodeFromString(ListSerializer(LibraryWeek.serializer()), it) }.getOrNull()
    }

    private fun decodeScheme(json: String?): List<SetSpec>? = json?.takeIf { it.isNotBlank() }?.let {
        runCatching { storeJson.decodeFromString(ListSerializer(SetSpec.serializer()), it) }.getOrNull()
    }

    /** Catalog id for a template slot: its own id if the catalog knows it, else the name's match. */
    fun resolveId(templateId: String?, name: String): String? =
        templateId?.let { ExerciseCatalog.get(it)?.id }
            ?: ExerciseCatalog.resolve(name)?.id
            ?: ExerciseCatalog.resolve(name.substringBefore('(').trim())?.id
            ?: templateId

    // ------------------------------------------------------------------ adopt

    /**
     * Create the program from a template and make it active. [values] maps "BASIS|key" (as in
     * [SetupInput]) to kilograms; blanks are simply absent.
     */
    suspend fun adopt(
        repo: ForgeRepository,
        t: LibraryProgram,
        values: Map<String, Double>,
        roundingKg: Double
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val maxes = mutableMapOf<String, Double>()
        val starts = mutableMapOf<String, Double>()
        val tms = mutableMapOf<String, Double>()
        t.engine.setup.forEach { input ->
            val v = values["${input.basis}|${input.key}"] ?: return@forEach
            when (input.basis) {
                "TM" -> tms[input.key] = v
                "START" -> starts[input.key] = v
                else -> {
                    maxes["${input.basis}:${input.key}"] = v
                    // Madcow: "put your current 5RMs at week 4 and work backwards 2.5% per week".
                    if (t.engine.model == "MADCOW" && input.basis == "5RM") {
                        maxes["TOP:${input.key}"] = Strength.roundTo(v / ProgressionEngine.MADCOW_WEEKLY.pow(3), roundingKg)
                    }
                }
            }
        }
        val slots = linkedMapOf<String, LibraryExercise>()
        t.days.forEach { d -> d.exercises.forEach { ex -> slots.putIfAbsent(ProgressionConfig.slotKey(d.name, ex.name), ex) } }
        val tmIncrements = linkedMapOf<String, Double>()
        if (t.engine.model == "WENDLER") {
            (t.days + t.weeks.orEmpty().flatMap { it.days }).forEach { d ->
                d.exercises.filter { it.rule == Rules.PERCENT && it.maxKey != null }.forEach { ex ->
                    tmIncrements.putIfAbsent(ex.maxKey!!, ex.incrementKg ?: ProgressionEngine.DEFAULT_INCREMENT_KG)
                }
            }
        }
        val config = ProgressionConfig(
            libraryId = t.id,
            engine = t.engine,
            slots = slots,
            tmIncrements = tmIncrements,
            weekDayCounts = t.weeks.orEmpty().map { it.days.size },
            daysPerWeek = t.daysPerWeek,
            state = ProgramState(
                weekStartedAt = now,
                roundingKg = roundingKg,
                maxes = maxes,
                starts = starts
            )
        )
        val weeksJson = t.weeks?.takeIf { it.isNotEmpty() }?.let {
            storeJson.encodeToString(ListSerializer(LibraryWeek.serializer()), it)
        }
        db.withTransaction {
            val programId = repo.programDao.insertProgram(
                Program(
                    name = t.name,
                    author = t.author,
                    notes = t.notes,
                    createdAt = now,
                    libraryId = t.id,
                    category = t.category,
                    level = t.level,
                    daysPerWeek = t.daysPerWeek,
                    schedule = t.schedule,
                    progressionJson = encodeConfig(config),
                    weeksJson = weeksJson
                )
            )
            t.allDayNames().forEachIndexed { di, dayName ->
                val tmpl = t.dayTemplate(dayName) ?: return@forEachIndexed
                val dayId = repo.programDao.insertDay(
                    ProgramDay(programId = programId, name = dayName, orderIdx = di, focus = tmpl.focus)
                )
                tmpl.exercises.forEachIndexed { ei, ex -> repo.programDao.insertExercise(toRow(dayId, ei, ex)) }
            }
            tms.forEach { (key, kg) ->
                training.upsertTrainingMax(TrainingMax(exerciseKey = key, kg = kg, updatedAt = now, source = "MANUAL"))
            }
            repo.programDao.clearActive()
            repo.programDao.setActive(programId)
            programId
        }
    }

    private fun toRow(dayId: Long, idx: Int, ex: LibraryExercise): ProgramExercise {
        val low = ex.repsLow ?: ex.setScheme?.mapNotNull { it.reps }?.minOrNull() ?: 0
        val type = ex.intensity?.type?.takeIf { it != "NONE" }
        return ProgramExercise(
            dayId = dayId,
            name = ex.name,
            orderIdx = idx,
            sets = ex.setCount,
            repLow = low,
            repHigh = ex.repsHigh ?: low,
            rirTarget = ex.rirTarget,
            restSeconds = ex.restSeconds,
            notes = ex.notes?.ifBlank { null },
            exerciseId = resolveId(ex.exerciseId, ex.name),
            setSchemeJson = ex.setScheme?.takeIf { it.isNotEmpty() }?.let {
                storeJson.encodeToString(ListSerializer(SetSpec.serializer()), it)
            },
            intensityType = type ?: "NONE",
            intensityValue = if (type == null) null else ex.intensity?.value,
            lastSetAmrap = ex.lastSetAmrap,
            isOptional = ex.optional
        )
    }

    suspend fun saveConfig(programId: Long, config: ProgressionConfig) = withContext(Dispatchers.IO) {
        training.setProgressionJson(programId, encodeConfig(config))
    }

    // ------------------------------------------------------------------ PDF imports

    /** After a PDF import, attach catalog ids so history, swaps and "How to" work. */
    suspend fun resolveImportedExerciseIds(programId: Long) = withContext(Dispatchers.IO) {
        training.exercisesForProgram(programId).forEach { row ->
            if (row.exerciseId == null) {
                ExerciseCatalog.resolve(row.name)?.id?.let { training.setExerciseId(row.id, it) }
            }
        }
    }

    // ------------------------------------------------------------------ maxes

    fun maxEntries(config: ProgressionConfig, tms: Map<String, Double>): List<MaxEntry> =
        config.engine.setup.mapNotNull { s ->
            when (s.basis) {
                "TM" -> MaxEntry(s.key, "TM", s.label, tms[s.key])
                "START" -> null
                else -> if (config.engine.model == "MADCOW") {
                    MaxEntry(s.key, "TOP", s.label, config.state.maxes["TOP:${s.key}"])
                } else {
                    MaxEntry(s.key, s.basis, s.label, config.state.maxes["${s.basis}:${s.key}"])
                }
            }
        }

    suspend fun updateMax(programId: Long, config: ProgressionConfig, entry: MaxEntry, kg: Double) =
        withContext(Dispatchers.IO) {
            if (entry.basis == "TM") {
                training.upsertTrainingMax(
                    TrainingMax(entry.key, kg, System.currentTimeMillis(), source = "MANUAL")
                )
            } else {
                val maxes = config.state.maxes + ("${entry.basis}:${entry.key}" to kg)
                saveConfig(programId, config.copy(state = config.state.copy(maxes = maxes)))
            }
        }

    // ------------------------------------------------------------------ session plan

    suspend fun buildSessionPlan(repo: ForgeRepository, sessionId: Long): SessionPlan = withContext(Dispatchers.IO) {
        val session = repo.workoutDao.session(sessionId)
        val day = session?.programDayId?.let { repo.programDao.day(it) }
        val program = (session?.programId ?: day?.programId)?.let { repo.programDao.program(it) }
        val config = decodeConfig(program?.progressionJson)
        val weeks = decodeWeeks(program?.weeksJson)
        val week = session?.weekIndex ?: config?.state?.week ?: 1
        val rows = day?.let { repo.programDao.exercises(it.id) }.orEmpty()

        val templates: List<Pair<LibraryExercise, ProgramExercise?>> = if (day == null) {
            emptyList()
        } else if (!weeks.isNullOrEmpty() && config != null) {
            val weekDay = weeks.getOrNull(week - 1)?.days?.firstOrNull { it.name == day.name }
            weekDay?.exercises?.map { ex -> ex to rows.firstOrNull { it.name == ex.name } }
                ?: rows.map { fromRow(it, config, day.name) to it }
        } else {
            rows.map { fromRow(it, config, day.name) to it }
        }
        val daySlots = config?.engine?.scope == "DAY_SLOT"
        val slots = templates.mapIndexed { idx, (ex, row) ->
            PlannedSlot(
                index = idx,
                slot = ex,
                exerciseId = row?.exerciseId ?: resolveId(ex.exerciseId, ex.name),
                startKey = if (daySlots && day != null) ProgressionConfig.slotKey(day.name, ex.name)
                else ProgressionConfig.normalize(ex.name),
                scopeDayId = if (daySlots) day?.id else null
            )
        }
        SessionPlan(
            session = session,
            program = program,
            config = config,
            week = week,
            weekLabel = config?.let {
                ProgramSchedule.weekLabel(it.copy(state = it.state.copy(week = week)), weeks)
            },
            dayName = day?.name,
            dayFocus = day?.focus ?: weeks?.getOrNull(week - 1)?.days?.firstOrNull { it.name == day?.name }?.focus,
            slots = slots
        )
    }

    /** A DB row as a template: the stored rule when the program has one, else log-only. */
    private fun fromRow(row: ProgramExercise, config: ProgressionConfig?, dayName: String): LibraryExercise {
        val stored = config?.slots?.get(ProgressionConfig.slotKey(dayName, row.name))
            ?: config?.slots?.entries?.firstOrNull { (k, _) ->
                k.substringAfter('|') == ProgressionConfig.normalize(row.name)
            }?.value
        return stored ?: LibraryExercise(
            exerciseId = row.exerciseId,
            name = row.name,
            sets = row.sets,
            repsLow = row.repLow.takeIf { it > 0 },
            repsHigh = row.repHigh.takeIf { it > 0 },
            setScheme = decodeScheme(row.setSchemeJson),
            intensity = when {
                row.intensityType != null && row.intensityType != "NONE" -> Intensity(row.intensityType, row.intensityValue)
                row.rirTarget != null -> Intensity("RIR", row.rirTarget)
                else -> null
            },
            lastSetAmrap = row.lastSetAmrap,
            restSeconds = row.restSeconds,
            notes = listOfNotNull(row.notes, row.substitutions?.let { "Alternatives: $it" }).joinToString("\n").ifBlank { null },
            optional = row.isOptional,
            rule = Rules.NONE
        )
    }

    // ------------------------------------------------------------------ history

    suspend fun slotHistory(name: String, scopeDayId: Long?, excludeSession: Long): List<SessionPerformance> =
        withContext(Dispatchers.IO) {
            val sets = training.slotSets(name, excludeSession)
            if (sets.isEmpty()) return@withContext emptyList()
            val sessions = training.sessionsByIds(sets.map { it.sessionId }.distinct()).associateBy { it.id }
            sets.groupBy { it.sessionId }.mapNotNull { (sid, list) ->
                val s = sessions[sid] ?: return@mapNotNull null
                if (s.endedAt == null) return@mapNotNull null
                if (scopeDayId != null && s.programDayId != scopeDayId) return@mapNotNull null
                SessionPerformance(
                    sessionId = sid,
                    startedAt = s.startedAt,
                    weekIndex = s.weekIndex,
                    dayId = s.programDayId,
                    sets = list.sortedBy { it.id }.map { it.toLogged() }
                )
            }.sortedByDescending { it.startedAt }
        }

    /** Heaviest set of [ref] (an id or a name) in its most recent earlier session. */
    suspend fun lastTopFor(ref: String, excludeSession: Long): Double? = withContext(Dispatchers.IO) {
        val sets = training.workingSets(ref, ref, 60).filter { it.sessionId != excludeSession }
        val latest = sets.firstOrNull()?.sessionId ?: return@withContext null
        sets.filter { it.sessionId == latest }.maxOfOrNull { it.weightKg }
    }

    /** Heaviest 3-5 rep set of [ref] in the last eight weeks. */
    suspend fun recentRm(ref: String, excludeSession: Long): Double? = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 56L * 86_400_000
        training.workingSets(ref, ref, 200)
            .filter { it.sessionId != excludeSession && it.timestamp >= since && it.reps in 3..5 }
            .maxOfOrNull { it.weightKg }
    }

    // ------------------------------------------------------------------ finish

    /**
     * Apply the program's rules to a finished session: new TMs go to TrainingDao, everything
     * else into the program's state. Returns plain-language changes for the summary.
     */
    suspend fun finishProgramSession(
        plan: SessionPlan,
        prescriptions: Map<Int, Prescription>,
        logged: List<SetLog>
    ): List<String> = withContext(Dispatchers.IO) {
        val program = plan.program ?: return@withContext emptyList()
        // Re-read: another screen (TM edit, plate step) may have changed it since the plan was built.
        val fresh = ForgeDatabase.get(ForgeApp.instance).programDao().program(program.id)
        val config = decodeConfig(fresh?.progressionJson) ?: return@withContext emptyList()
        val now = System.currentTimeMillis()
        val finished = plan.slots.map { ps ->
            FinishedSlot(
                slot = ps.slot,
                targets = prescriptions[ps.index]?.sets.orEmpty(),
                sets = logged.filter { !it.isWarmup && it.exerciseName.equals(ps.slot.name, ignoreCase = true) }
                    .sortedBy { it.id }
                    .map { it.toLogged() },
                startKey = ps.startKey
            )
        }
        val tms = training.trainingMaxes().associate { it.exerciseKey to it.kg }
        val result = ProgressionAdvance.onSessionFinished(config, plan.week, finished, tms, now)
        db.withTransaction {
            result.tmUpdates.forEach { (key, kg) ->
                training.upsertTrainingMax(TrainingMax(key, kg, now, source = "AMRAP_PROGRESSION"))
            }
            training.setProgressionJson(program.id, encodeConfig(config.copy(state = result.state)))
        }
        result.messages
    }

    /** Skip the rest of the current week (an optional deload the lifter doesn't need). */
    suspend fun skipWeek(program: Program): List<String> = withContext(Dispatchers.IO) {
        val config = decodeConfig(program.progressionJson) ?: return@withContext emptyList()
        val tms = training.trainingMaxes().associate { it.exerciseKey to it.kg }
        val now = System.currentTimeMillis()
        val result = ProgressionAdvance.advanceWeek(config, tms, now)
        db.withTransaction {
            result.tmUpdates.forEach { (key, kg) ->
                training.upsertTrainingMax(TrainingMax(key, kg, now, source = "AMRAP_PROGRESSION"))
            }
            training.setProgressionJson(program.id, encodeConfig(config.copy(state = result.state)))
        }
        result.messages
    }

    suspend fun setRounding(program: Program, step: Double) = withContext(Dispatchers.IO) {
        val config = decodeConfig(program.progressionJson) ?: return@withContext
        saveConfig(program.id, config.copy(state = config.state.copy(roundingKg = step)))
    }
}
