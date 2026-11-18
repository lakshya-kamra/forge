package com.laksh.forge.feature.train

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.WorkoutSession
import com.laksh.forge.data.pdf.PdfContent
import com.laksh.forge.data.pdf.PdfImport
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.program.LibraryWeek
import com.laksh.forge.domain.program.MaxEntry
import com.laksh.forge.domain.program.ProgramSchedule
import com.laksh.forge.domain.program.ProgramStore
import com.laksh.forge.domain.program.ProgressionConfig
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportPhase { IDLE, READING, PARSING, DONE, ERROR }

/** The active program as the Train screen shows it. */
data class ActiveInfo(
    val program: Program,
    val config: ProgressionConfig?,
    val days: List<ProgramDay>,
    val nextDay: ProgramDay?,
    val weekLabel: String?,
    val optionalWeek: Boolean,
    /** Author's deload checklist for an optional deload week, unless already answered. */
    val checklist: List<String>,
    val maxes: List<MaxEntry>,
    val roundingKg: Double
)

data class TrainState(
    val loading: Boolean = true,
    val programs: List<Program> = emptyList(),
    val active: ActiveInfo? = null,
    val sessions: List<WorkoutSession> = emptyList(),
    val openSession: WorkoutSession? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val importPhase: ImportPhase = ImportPhase.IDLE,
    val importStatus: String = "",
    val importError: String? = null,
    /** What the last program action changed (skip week, max edit), shown once. */
    val notice: List<String> = emptyList(),
    /** safety_medical_red_flags_stop: no new session until clearance is confirmed. */
    val trainingHold: Boolean = false
)

private data class ImportState(val phase: ImportPhase = ImportPhase.IDLE, val status: String = "", val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val importState = MutableStateFlow(ImportState())
    private val notice = MutableStateFlow<List<String>>(emptyList())

    /** Decoded weeks are cached by program id: J&T 2's twelve weeks are ~200 KB of JSON. */
    @Volatile private var weeksCache: Pair<Long, List<LibraryWeek>?>? = null

    private fun weeksFor(p: Program): List<LibraryWeek>? {
        weeksCache?.let { (id, w) -> if (id == p.id) return w }
        return ProgramStore.decodeWeeks(p.weeksJson).also { weeksCache = p.id to it }
    }

    private val activeInfo: Flow<ActiveInfo?> = repo.programDao.observeActive().flatMapLatest { p ->
        if (p == null) flowOf(null)
        else combine(
            repo.programDao.observeDays(p.id),
            ProgramStore.training.observeFinishedForProgram(p.id),
            ProgramStore.training.observeTrainingMaxes()
        ) { days, finished, tms ->
            val config = ProgramStore.decodeConfig(p.progressionJson)
            val weeks = weeksFor(p)
            val byId = days.associateBy { it.id }
            val recent = finished.mapNotNull { s -> s.programDayId?.let { byId[it]?.name } }
            val nextName = ProgramSchedule.nextDayName(config, weeks, days.map { it.name }, recent)
            val optional = ProgramSchedule.isOptionalWeek(config)
            val checklist = config?.takeIf {
                optional && it.engine.deloadChecklist.isNotEmpty() &&
                    it.state.deloadAcceptedCycle != it.state.cycle
            }?.engine?.deloadChecklist.orEmpty()
            ActiveInfo(
                program = p,
                config = config,
                days = days,
                nextDay = days.firstOrNull { it.name == nextName },
                weekLabel = ProgramSchedule.weekLabel(config, weeks),
                optionalWeek = optional,
                checklist = checklist,
                maxes = config?.let { c -> ProgramStore.maxEntries(c, tms.associate { it.exerciseKey to it.kg }) }.orEmpty(),
                roundingKg = config?.state?.roundingKg ?: 2.5
            )
        }
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<TrainState> = combine(
        repo.programDao.observePrograms(),
        activeInfo,
        repo.workoutDao.observeSessions(30),
        combine(repo.workoutDao.observeOpenSession(), repo.settingsStore.settings.map { it.weightUnit }) { o, u -> o to u },
        combine(importState, notice, PlanProfileStore.trainingHold) { i, n, h -> Triple(i, n, h) }
    ) { programs, active, sessions, (open, unit), (imp, note, hold) ->
        TrainState(
            loading = false,
            programs = programs,
            active = active,
            sessions = sessions,
            openSession = open,
            unit = unit,
            importPhase = imp.phase,
            importStatus = imp.status,
            importError = imp.error,
            notice = note,
            trainingHold = hold
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainState())

    fun clearImportState() = importState.update { ImportState() }
    fun clearNotice() = notice.update { emptyList() }

    /**
     * Read a program PDF and turn it into structured days. Text-layer PDFs go straight to
     * the parser; scanned ones are rasterised and read by vision instead. Exercise names are
     * then matched to the catalog so history, swaps and "How to" work for imports too.
     */
    fun importPdf(uri: Uri) = viewModelScope.launch {
        val settings = repo.settings()
        if (!settings.hasApiKey) {
            importState.update {
                ImportState(ImportPhase.ERROR, error = "Add an AI provider key in Settings to import a program PDF - or browse the free programs instead.")
            }
            return@launch
        }
        importState.update { ImportState(ImportPhase.READING, "Reading the PDF...") }
        runCatching {
            val ctx = ForgeApp.instance
            val name = PdfImport.fileName(ctx, uri)
            when (val content = PdfImport.extract(ctx, uri)) {
                is PdfContent.Text -> {
                    importState.update { ImportState(ImportPhase.PARSING, "Reading ${content.pages} pages of training program...") }
                    repo.ai.parseProgram(content.text, name) to name
                }
                is PdfContent.Pages -> {
                    importState.update { ImportState(ImportPhase.PARSING, "Scanned PDF - reading ${content.imagesBase64.size} pages visually...") }
                    repo.ai.parseProgramFromImages(content.imagesBase64, name) to name
                }
            }
        }.mapCatching { (parsed, name) ->
            val id = repo.saveProgram(parsed, name)
            runCatching { ProgramStore.resolveImportedExerciseIds(id) }
            parsed
        }.onSuccess { parsed ->
            importState.update {
                ImportState(ImportPhase.DONE, "Imported ${parsed.days.size} training days from ${parsed.name}.")
            }
        }.onFailure { e ->
            importState.update { ImportState(ImportPhase.ERROR, error = e.message ?: "Could not read that PDF.") }
        }
    }

    fun activate(program: Program) = viewModelScope.launch { repo.activateProgram(program.id) }

    fun deleteProgram(program: Program) = viewModelScope.launch { repo.programDao.deleteProgram(program) }

    fun confirmClearance() = viewModelScope.launch { PlanProfileStore.clearTrainingHold() }

    /** Start a session from a program day, or a free-form one when day is null. */
    fun startSession(day: ProgramDay?, onStarted: (Long) -> Unit) = viewModelScope.launch {
        if (PlanProfileStore.current().trainingHold) return@launch
        val now = System.currentTimeMillis()
        val active = state.value.active
        val inProgram = day != null && active != null && day.programId == active.program.id
        val id = repo.workoutDao.insertSession(
            WorkoutSession(
                programDayId = day?.id,
                title = day?.name ?: "Free session",
                startedAt = now,
                localDate = Dates.dayOf(now),
                programId = day?.programId,
                weekIndex = if (inProgram) active?.config?.state?.week else null
            )
        )
        onStarted(id)
    }

    /** Skip the rest of an optional (deload) week and move on. */
    fun skipWeek() = viewModelScope.launch {
        val p = state.value.active?.program ?: return@launch
        val msgs = runCatching { ProgramStore.skipWeek(p) }.getOrElse { listOf(it.message ?: "Could not skip the week.") }
        notice.value = msgs
    }

    /**
     * The author's deload checklist: two or more "yes" answers means take the deload; otherwise
     * skip it and start the next block.
     */
    fun answerChecklist(yesCount: Int) = viewModelScope.launch {
        val p = state.value.active?.program ?: return@launch
        if (yesCount >= 2) {
            val config = ProgramStore.decodeConfig(p.progressionJson) ?: return@launch
            ProgramStore.saveConfig(p.id, config.copy(state = config.state.copy(deloadAcceptedCycle = config.state.cycle)))
            notice.value = listOf("Deload week: lighter loads and about a third fewer sets.")
        } else {
            notice.value = runCatching { ProgramStore.skipWeek(p) }.getOrElse { listOf(it.message ?: "Could not skip the week.") }
        }
    }

    fun setRounding(step: Double) = viewModelScope.launch {
        val p = state.value.active?.program ?: return@launch
        ProgramStore.setRounding(p, step)
    }

    fun updateMax(entry: MaxEntry, displayValue: Double) = viewModelScope.launch {
        val active = state.value.active ?: return@launch
        val config = active.config ?: return@launch
        val kg = NutritionMath.displayToKg(displayValue, state.value.unit)
        if (kg <= 0) return@launch
        runCatching { ProgramStore.updateMax(active.program.id, config, entry, kg) }
            .onFailure { e -> notice.value = listOf(e.message ?: "Could not save that number.") }
    }

    companion object {
        val Factory = forgeFactory { TrainViewModel(it) }
    }
}
