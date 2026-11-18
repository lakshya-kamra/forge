package com.laksh.forge.feature.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.db.WorkoutSession
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.program.ProgramSchedule
import com.laksh.forge.domain.training.TrainingWeeks
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.program.EngineConfig
import com.laksh.forge.domain.program.LibraryExercise
import com.laksh.forge.domain.program.PlannedSlot
import com.laksh.forge.domain.program.Prescription
import com.laksh.forge.domain.program.ProgramState
import com.laksh.forge.domain.program.ProgramStore
import com.laksh.forge.domain.program.ProgressionConfig
import com.laksh.forge.domain.program.ProgressionEngine
import com.laksh.forge.domain.program.Rules
import com.laksh.forge.domain.program.SessionPerformance
import com.laksh.forge.domain.program.SessionPlan
import com.laksh.forge.domain.program.SlotInput
import com.laksh.forge.domain.program.Strength
import com.laksh.forge.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What this lift looked like the last time it was trained, shown while logging. */
data class LastTime(val label: String, val topWeightKg: Double, val topReps: Int)

/** One exercise card in the session. */
data class SlotUi(
    /** Stable list key: "p_<index>" for program slots, "x_<name>" for added exercises. */
    val key: String,
    /** Program slot index, or -1 for an added exercise. */
    val index: Int,
    /** Name sets are logged under (the substitute's name after a swap). */
    val name: String,
    val swappedFrom: String?,
    val exerciseId: String?,
    val optional: Boolean,
    val prescription: Prescription,
    /** Everything logged for this card in this session, warm-ups included, in order. */
    val sets: List<SetLog>,
    val lastTime: LastTime?,
    val restSeconds: Int,
    val notes: String?
) {
    val working: List<SetLog> get() = sets.filter { !it.isWarmup }
    val warmups: List<SetLog> get() = sets.filter { it.isWarmup }
}

data class PrEvent(
    val id: Long,
    val exercise: String,
    val weightKg: Double,
    val reps: Int,
    val e1rmKg: Double,
    /** True when it beat the heaviest weight ever lifted, false for an estimated-1RM best. */
    val heaviest: Boolean
)

data class SwapOption(val info: ExerciseInfo, val exactMatch: Boolean)

data class SwapSheet(
    val key: String,
    val current: String,
    val canRevert: Boolean,
    val loading: Boolean = true,
    val options: List<SwapOption> = emptyList()
)

data class SessionSummary(
    val workingSets: Int,
    val volumeKg: Double,
    val prs: List<String>,
    val changes: List<String>,
    /** Training guardrail nudges (deload, failure, rep range) from this and recent sessions. */
    val guidance: List<String> = emptyList()
)

data class SessionState(
    val loading: Boolean = true,
    val error: String? = null,
    val session: WorkoutSession? = null,
    val programName: String? = null,
    val weekLabel: String? = null,
    val dayFocus: String? = null,
    val hasProgression: Boolean = false,
    val slots: List<SlotUi> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val roundingKg: Double = 2.5,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    val prSetIds: Set<Long> = emptySet(),
    val pr: PrEvent? = null,
    val swap: SwapSheet? = null,
    val finishing: Boolean = false,
    val summary: SessionSummary? = null,
    val finished: Boolean = false,
    /** safety_medical_red_flags_stop: workouts are locked until clearance is confirmed. */
    val medicalHold: Boolean = false,
    /** safety_pregnancy_no_deficit: warning-sign checklist and supine swaps apply. */
    val pregnant: Boolean = false,
    val pregnancyChecklistDone: Boolean = false
) {
    val workingSetCount: Int get() = slots.sumOf { it.working.size }
}

/** The rest timer ticks every second, so it lives in its own flow and never recomposes the list. */
data class RestState(val remaining: Int = 0, val total: Int = 0)

private data class SlotData(
    val history: List<SessionPerformance>,
    val startKg: Double?,
    val lastTopForRef: Double?,
    val recentRm: Double?
)

private data class Baseline(val bestE1rm: Double, val heaviest: Double, val hasHistory: Boolean)

class SessionViewModel(
    private val repo: ForgeRepository,
    private val sessionId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _rest = MutableStateFlow(RestState())
    val rest: StateFlow<RestState> = _rest.asStateFlow()

    private var restJob: Job? = null

    private var plan: SessionPlan? = null
    private var slotData: Map<Int, SlotData> = emptyMap()
    private var tms: Map<String, Double> = emptyMap()
    private var sets: List<SetLog> = emptyList()
    private var swaps: Map<Int, ExerciseInfo> = emptyMap()
    private var extras: List<String> = emptyList()
    /** Catalog ids for added exercises, resolved once off the main thread (null = no match). */
    private val extraIds = mutableMapOf<String, String?>()
    private val extraIdsRequested = mutableSetOf<String>()
    private var lastTime: Map<String, LastTime> = emptyMap()
    private val lastTimeRequested = mutableSetOf<String>()
    private var prescriptions: Map<Int, Prescription> = emptyMap()
    private val baselines = mutableMapOf<String, Baseline>()
    private var prSetIds: Set<Long> = emptySet()
    private val prLabels = mutableListOf<String>()
    private var loaded = false

    init {
        viewModelScope.launch {
            runCatching { load() }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Could not open this session.") }
            }
        }
        viewModelScope.launch {
            repo.workoutDao.observeSets(sessionId).collect { list ->
                sets = list
                if (loaded) rebuild()
            }
        }
    }

    private suspend fun load() {
        val settings = repo.settings()
        val hold = runCatching { PlanProfileStore.current().trainingHold }.getOrDefault(false)
        // Parsed off the main thread before rebuild() needs it.
        runCatching { ExerciseCatalog.preload() }
        val p = ProgramStore.buildSessionPlan(repo, sessionId)
        val config = p.config
        val data = withContext(Dispatchers.IO) {
            tms = ProgramStore.training.trainingMaxes().associate { it.exerciseKey to it.kg }
            p.slots.associate { ps -> ps.index to slotDataFor(ps, config) }
        }
        plan = p
        slotData = data
        lastTime = data.mapNotNull { (idx, d) ->
            val slot = p.slots.getOrNull(idx) ?: return@mapNotNull null
            val last = d.history.firstOrNull() ?: return@mapNotNull null
            val top = last.sets.maxByOrNull { it.weightKg } ?: return@mapNotNull null
            slot.slot.name to LastTime(Dates.shortLabel(last.startedAt), top.weightKg, top.reps)
        }.toMap()
        _state.update {
            it.copy(
                loading = false,
                session = p.session,
                programName = p.program?.name,
                weekLabel = p.weekLabel,
                dayFocus = p.dayFocus,
                hasProgression = config != null,
                unit = settings.weightUnit,
                roundingKg = config?.state?.roundingKg ?: 2.5,
                haptics = settings.haptics,
                reduceMotion = settings.reduceMotion,
                medicalHold = hold,
                pregnant = settings.pregnancyStatus == PregnancyStatus.PREGNANT
            )
        }
        loaded = true
        rebuild()
    }

    private suspend fun slotDataFor(ps: PlannedSlot, config: ProgressionConfig?): SlotData {
        val s = ps.slot
        val history = ProgramStore.slotHistory(s.name, ps.scopeDayId, sessionId)
        val starts = config?.state?.starts.orEmpty()
        val start = starts[ps.startKey] ?: starts[ProgressionConfig.normalize(s.name)]
        val ref = s.ref
        val lastTop = if (ref != null && s.rule == Rules.TOP_SET) ProgramStore.lastTopFor(ref, sessionId) else null
        val recent = if (ref != null && s.rule == Rules.RECENT_RM) ProgramStore.recentRm(ref, sessionId) else null
        return SlotData(history, start, lastTop, recent)
    }

    private fun loadLastTimeForExtras(names: List<String>) = viewModelScope.launch {
        val found = names.mapNotNull { name ->
            val recent = repo.workoutDao.recentSetsFor(name, sessionId)
            val best = recent.maxByOrNull { it.weightKg * 1000 + it.reps } ?: return@mapNotNull null
            name to LastTime(Dates.shortLabel(best.timestamp), best.weightKg, best.reps)
        }
        if (found.isNotEmpty()) {
            lastTime = lastTime + found
            if (loaded) rebuild()
        }
    }

    /** Recompute every card from the current sets. Pure and cheap: the engine does no I/O. */
    private fun rebuild() {
        val p = plan ?: return
        val config = p.config
        val engine = config?.engine ?: EngineConfig()
        val programState = config?.state ?: ProgramState()
        val newPrescriptions = mutableMapOf<Int, Prescription>()
        val cards = p.slots.map { ps ->
            val swap = swaps[ps.index]
            val name = swap?.name ?: ps.slot.name
            val logged = sets.filter { it.exerciseName.equals(name, ignoreCase = true) }
            val prescription = if (swap != null) {
                swappedPrescription(ps.slot, name)
            } else {
                val d = slotData[ps.index]
                ProgressionEngine.prescribe(
                    SlotInput(
                        slot = ps.slot,
                        engine = engine,
                        state = programState,
                        week = p.week,
                        history = d?.history.orEmpty(),
                        trainingMaxes = tms,
                        startKg = d?.startKg,
                        todayTopKg = ps.slot.ref?.let { todayTop(p, it) },
                        lastTopKgForRef = d?.lastTopForRef,
                        recentRmKg = d?.recentRm
                    )
                ).also { newPrescriptions[ps.index] = it }
            }
            SlotUi(
                key = "p_${ps.index}",
                index = ps.index,
                name = name,
                swappedFrom = if (swap != null) ps.slot.name else null,
                exerciseId = swap?.id ?: ps.exerciseId,
                optional = ps.slot.optional,
                prescription = prescription,
                sets = logged,
                lastTime = lastTime[name],
                restSeconds = ps.slot.restSeconds?.takeIf { it > 0 } ?: DEFAULT_REST,
                notes = listOfNotNull(supineNote(name, _state.value.pregnant), ps.slot.notes).joinToString("\n").ifBlank { null }
            )
        }
        // Added exercises: the ones added this visit plus any logged earlier in this session
        // (they survive process death this way, with no extra state to save).
        val taken = cards.map { it.name.lowercase() }.toSet() + p.slots.map { it.slot.name.lowercase() }
        val allExtras = (extras + sets.map { it.exerciseName })
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() !in taken }
        val unknown = allExtras.filter { it !in lastTime && it !in lastTimeRequested }
        if (unknown.isNotEmpty()) {
            lastTimeRequested += unknown
            loadLastTimeForExtras(unknown)
        }
        val unresolved = allExtras.filter { it !in extraIdsRequested }
        if (unresolved.isNotEmpty()) {
            extraIdsRequested += unresolved
            resolveExtras(unresolved)
        }
        val pregnant = _state.value.pregnant
        val extraCards = allExtras.map { name ->
            val logged = sets.filter { it.exerciseName.equals(name, ignoreCase = true) }
            SlotUi(
                key = "x_$name",
                index = -1,
                name = name,
                swappedFrom = null,
                exerciseId = extraIds[name],
                optional = false,
                prescription = Prescription(sets = emptyList(), suggestedKg = lastTime[name]?.topWeightKg),
                sets = logged,
                lastTime = lastTime[name],
                restSeconds = DEFAULT_REST,
                notes = supineNote(name, pregnant)
            )
        }
        prescriptions = newPrescriptions
        _state.update { it.copy(slots = cards + extraCards, prSetIds = prSetIds) }
    }

    /**
     * Fuzzy name matching (typo distance against every catalog key) is too slow for every
     * logged set on the main thread, so each added name is resolved once on Default.
     */
    private fun resolveExtras(names: List<String>) = viewModelScope.launch {
        val found = withContext(Dispatchers.Default) { names.associateWith { ExerciseCatalog.resolve(it)?.id } }
        extraIds.putAll(found)
        if (loaded && found.values.any { it != null }) rebuild()
    }

    /** A substitute keeps the slot's sets and reps; its load comes from its own history. */
    private fun swappedPrescription(slot: LibraryExercise, name: String): Prescription {
        val base = slot.copy(name = name, rule = Rules.NONE, setScheme = slot.setScheme?.map { it.copy(percent = null, of = null, addKg = null) })
        return ProgressionEngine.prescribe(
            SlotInput(base, EngineConfig(), ProgramState(), 1, emptyList())
        ).copy(
            reason = "Substitute: same sets and reps, your choice of load.",
            suggestedKg = lastTime[name]?.topWeightKg
        )
    }

    /** Heaviest working set logged today for the lift a back-off or drop set is based on. */
    private fun todayTop(p: SessionPlan, ref: String): Double? {
        val names = p.slots.filter { ps ->
            ps.slot.rule != Rules.TOP_SET && swaps[ps.index] == null &&
                (ps.slot.exerciseId == ref ||
                    ProgressionConfig.normalize(ps.slot.name.substringBefore('(')) == ref)
        }.map { it.slot.name.lowercase() }.toSet()
        if (names.isEmpty()) return null
        return sets.filter { !it.isWarmup && it.exerciseName.lowercase() in names && it.weightKg > 0 }
            .maxOfOrNull { it.weightKg }
    }

    // ------------------------------------------------------------------ logging

    fun logSet(key: String, weightDisplay: Double, reps: Int, rir: Double?, rpe: Double?, warmup: Boolean) {
        val card = _state.value.slots.firstOrNull { it.key == key } ?: return
        if (reps <= 0 || weightDisplay < 0) return
        viewModelScope.launch {
            val unit = _state.value.unit
            val kg = NutritionMath.displayToKg(weightDisplay, unit)
            val target = if (warmup) null else card.prescription.sets.getOrNull(card.working.size)
            val now = System.currentTimeMillis()
            val id = repo.workoutDao.insertSet(
                SetLog(
                    sessionId = sessionId,
                    exerciseName = card.name,
                    setIdx = card.sets.size + 1,
                    weightKg = kg,
                    reps = reps,
                    rir = rir ?: rpe?.let { (10.0 - it).coerceAtLeast(0.0) },
                    isWarmup = warmup,
                    timestamp = now,
                    exerciseId = card.exerciseId,
                    targetReps = target?.reps,
                    // A substitute has no prescribed load, so a miss can't be judged against one.
                    targetWeightKg = if (card.swappedFrom == null) target?.weightKg else null,
                    rpe = rpe
                )
            )
            if (!warmup) {
                checkPr(card, id, kg, reps)
                startRest(card.restSeconds)
            }
        }
    }

    private suspend fun checkPr(card: SlotUi, setId: Long, kg: Double, reps: Int) {
        if (kg <= 0) return
        val key = card.exerciseId ?: card.name.lowercase()
        val base = baselines[key] ?: withContext(Dispatchers.IO) {
            val earlier = ProgramStore.training.allWorkingSets(card.exerciseId, card.name, sessionId)
                .filter { it.weightKg > 0 }
            Baseline(
                bestE1rm = earlier.filter { it.reps in 1..MAX_PR_REPS }.maxOfOrNull { Strength.e1rm(it.weightKg, it.reps) } ?: 0.0,
                heaviest = earlier.maxOfOrNull { it.weightKg } ?: 0.0,
                hasHistory = earlier.isNotEmpty()
            )
        }
        val e1 = Strength.e1rm(kg, reps)
        // Epley overstates maxes from long sets, so only sets of 12 or fewer count for e1RM PRs.
        val heaviest = base.hasHistory && kg > base.heaviest + 0.01
        val e1Pr = base.hasHistory && reps <= MAX_PR_REPS && e1 > base.bestE1rm + 0.01
        baselines[key] = Baseline(
            bestE1rm = if (reps <= MAX_PR_REPS) maxOf(base.bestE1rm, e1) else base.bestE1rm,
            heaviest = maxOf(base.heaviest, kg),
            hasHistory = true
        )
        if (heaviest || e1Pr) {
            prSetIds = prSetIds + setId
            val label = "${card.name}: ${Strength.fmt(kg)} kg × $reps" +
                if (heaviest) " (heaviest ever)" else " (best estimated 1RM, ${Strength.fmt(Strength.roundTo(e1, 0.5))} kg)"
            prLabels.removeAll { it.startsWith("${card.name}:") }
            prLabels += label
            _state.update {
                it.copy(
                    prSetIds = prSetIds,
                    pr = PrEvent(setId, card.name, kg, reps, e1, heaviest)
                )
            }
        }
    }

    fun clearPr() = _state.update { it.copy(pr = null) }

    fun deleteSet(set: SetLog) = viewModelScope.launch {
        repo.workoutDao.deleteSet(set)
        if (set.id in prSetIds) {
            prSetIds = prSetIds - set.id
            // The baseline included this set; drop it so the next set is judged fairly.
            baselines.remove(set.exerciseId ?: set.exerciseName.lowercase())
        }
    }

    fun addExercise(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val existing = _state.value.slots.any { it.name.equals(trimmed, ignoreCase = true) }
        if (existing) return
        extras = extras + trimmed
        rebuild()
    }

    // ------------------------------------------------------------------ swaps

    fun openSwap(key: String) {
        val card = _state.value.slots.firstOrNull { it.key == key } ?: return
        if (card.index < 0) return
        _state.update { it.copy(swap = SwapSheet(key, card.name, canRevert = card.swappedFrom != null)) }
        viewModelScope.launch {
            val original = plan?.slots?.getOrNull(card.index)
            val options = withContext(Dispatchers.Default) {
                alternatives(original?.exerciseId, original?.slot?.name ?: card.name)
            }
            _state.update { st -> st.swap?.takeIf { it.key == key }?.let { st.copy(swap = it.copy(loading = false, options = options)) } ?: st }
        }
    }

    fun closeSwap() = _state.update { it.copy(swap = null) }

    /** Alternatives with the same primary muscles first, then ones sharing the main muscle. */
    private fun alternatives(exerciseId: String?, name: String): List<SwapOption> {
        val current = exerciseId?.let { ExerciseCatalog.get(it) } ?: ExerciseCatalog.resolve(name) ?: return emptyList()
        val primary = current.primaryMuscles.toSet()
        if (primary.isEmpty()) return emptyList()
        val all = ExerciseCatalog.all().filter { it.id != current.id && it.primaryMuscles.isNotEmpty() }
        val exact = all.filter { it.primaryMuscles.toSet() == primary }
            .sortedBy { candidate -> -candidate.equipment.count { it in current.equipment } }
        val partial = all.filter { it !in exact && current.primaryMuscles.first() in it.primaryMuscles }
            .sortedByDescending { c -> c.primaryMuscles.count { it in primary } }
        return (exact.map { SwapOption(it, true) } + partial.map { SwapOption(it, false) }).take(20)
    }

    fun swap(key: String, info: ExerciseInfo?) {
        val card = _state.value.slots.firstOrNull { it.key == key } ?: return
        if (card.index < 0) return
        swaps = if (info == null) swaps - card.index else swaps + (card.index to info)
        _state.update { it.copy(swap = null) }
        if (info != null && info.name !in lastTime && info.name !in lastTimeRequested) {
            lastTimeRequested += info.name
            loadLastTimeForExtras(listOf(info.name))
        }
        rebuild()
    }

    // ------------------------------------------------------------------ rest timer

    fun startRest(seconds: Int) {
        restJob?.cancel()
        _rest.value = RestState(seconds, seconds)
        restJob = viewModelScope.launch {
            var left = seconds
            while (left > 0 && isActive) {
                delay(1000)
                left--
                _rest.update { it.copy(remaining = left) }
            }
        }
    }

    fun addRest(seconds: Int) {
        val cur = _rest.value
        if (cur.remaining <= 0) return
        val total = cur.total + seconds
        val left = cur.remaining + seconds
        restJob?.cancel()
        _rest.value = RestState(left, total)
        restJob = viewModelScope.launch {
            var l = left
            while (l > 0 && isActive) {
                delay(1000)
                l--
                _rest.update { it.copy(remaining = l) }
            }
        }
    }

    fun stopRest() {
        restJob?.cancel()
        _rest.value = RestState()
    }

    // ------------------------------------------------------------------ finish

    fun finish(notes: String?) {
        if (_state.value.finishing || _state.value.summary != null) return
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            val session = repo.workoutDao.session(sessionId)
            if (session == null) {
                _state.update { it.copy(finishing = false, finished = true) }
                return@launch
            }
            val alreadyFinished = session.endedAt != null
            repo.workoutDao.updateSession(
                session.copy(
                    endedAt = session.endedAt ?: System.currentTimeMillis(),
                    notes = notes?.ifBlank { null } ?: session.notes
                )
            )
            stopRest()
            val p = plan
            // Progression advances once per session; re-finishing an old session changes nothing.
            val changes = if (!alreadyFinished && p?.config != null) {
                runCatching { ProgramStore.finishProgramSession(p, prescriptions, sets) }
                    .getOrElse { listOf("Progression wasn't updated: ${it.message ?: "unknown error"}") }
            } else emptyList()
            val working = sets.filter { !it.isWarmup }
            val guidance = runCatching { guidance(p, working) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    finishing = false,
                    summary = SessionSummary(
                        workingSets = working.size,
                        volumeKg = working.sumOf { s -> s.weightKg * s.reps },
                        prs = prLabels.toList(),
                        changes = changes,
                        guidance = guidance
                    )
                )
            }
        }
    }

    fun closeSummary() = _state.update { it.copy(finished = true) }

    /** The person confirms a doctor has cleared them; workouts unlock everywhere. */
    fun confirmClearance() = viewModelScope.launch {
        PlanProfileStore.clearTrainingHold()
        _state.update { it.copy(medicalHold = false) }
    }

    fun acknowledgePregnancyChecklist() = _state.update { it.copy(pregnancyChecklistDone = true) }

    /**
     * Training guardrails for the finished session: rep-range and proximity-to-failure nudges
     * from the logged sets, and a deload reminder from how long training has run without a
     * lighter week.
     */
    private suspend fun guidance(p: SessionPlan?, working: List<SetLog>): List<String> = withContext(Dispatchers.IO) {
        val flags = mutableListOf<GuardrailFlag?>()
        working.forEach { flags += Guardrails.repRangeFlag(it.reps, it.rir) }
        // training_proximity_to_failure: barbell lifts taken to 0 RIR in 2+ sessions.
        working.filter { it.rir != null && it.rir <= 0.0 }
            .distinctBy { it.exerciseName.lowercase() }
            .forEach { set ->
                val info = set.exerciseId?.let { ExerciseCatalog.get(it) } ?: ExerciseCatalog.resolve(set.exerciseName)
                if (info == null || "barbell" !in info.equipment) return@forEach
                val earlier = ProgramStore.training.allWorkingSets(set.exerciseId, set.exerciseName, sessionId)
                    .filter { it.rir != null && it.rir <= 0.0 && it.timestamp >= System.currentTimeMillis() - 28L * 86_400_000 }
                    .map { it.sessionId }.distinct().size
                flags += Guardrails.barbellFailureFlag(earlier + 1)
            }
        val deloadNow = p?.config != null && ProgramSchedule.isDeloadWeek(p.config)
        if (!deloadNow) {
            val sessions = repo.workoutDao.observeSessions(200).first().filter { it.endedAt != null || it.id == sessionId }
            flags += Guardrails.deloadFlag(TrainingWeeks.consecutiveWeeks(sessions.map { it.startedAt }, System.currentTimeMillis()))
        }
        flags.filterNotNull().distinctBy { it.id }.map { it.message }
    }

    /** Delete a session that was started and never logged into. */
    fun discard() = viewModelScope.launch {
        val session = repo.workoutDao.session(sessionId) ?: return@launch
        repo.workoutDao.deleteSession(session)
        stopRest()
        _state.update { it.copy(finished = true) }
    }

    override fun onCleared() {
        restJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val DEFAULT_REST = 150
        private const val MAX_PR_REPS = 12

        /**
         * ACOG (safety_pregnancy_no_deficit): avoid lying flat on the back for long, so
         * supine lifts carry a swap hint. Name-based because the catalog has no position field.
         */
        private val SUPINE = Regex(
            "bench press|lying|supine|skull ?crusher|floor press|hip thrust|glute bridge|crunch|dead ?bug|" +
                "(dumbbell|chest|db) fly|pullover",
            RegexOption.IGNORE_CASE
        )
        private val UPRIGHT = Regex("incline|seated|standing", RegexOption.IGNORE_CASE)

        internal fun isSupine(name: String): Boolean = SUPINE.containsMatchIn(name) && !UPRIGHT.containsMatchIn(name)

        private fun supineNote(name: String, pregnant: Boolean): String? =
            if (pregnant && isSupine(name)) {
                "Pregnancy: avoid lying flat on your back for long. Use an incline or seated version (tap Swap)."
            } else null

        fun factory(sessionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(ForgeApp.instance.repository, sessionId) }
        }
    }
}
