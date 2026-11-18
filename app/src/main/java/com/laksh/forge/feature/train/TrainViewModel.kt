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
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportPhase { IDLE, READING, PARSING, DONE, ERROR }

data class TrainState(
    val programs: List<Program> = emptyList(),
    val active: Program? = null,
    val days: List<ProgramDay> = emptyList(),
    val sessions: List<WorkoutSession> = emptyList(),
    val openSession: WorkoutSession? = null,
    val importPhase: ImportPhase = ImportPhase.IDLE,
    val importStatus: String = "",
    val importError: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val importState = MutableStateFlow(Triple(ImportPhase.IDLE, "", null as String?))

    private val activeDays = repo.programDao.observeActive().flatMapLatest { active ->
        if (active == null) flowOf(emptyList()) else repo.programDao.observeDays(active.id)
    }

    val state: StateFlow<TrainState> = combine(
        repo.programDao.observePrograms(),
        repo.programDao.observeActive(),
        activeDays,
        repo.workoutDao.observeSessions(30),
        combine(repo.workoutDao.observeOpenSession(), importState) { open, imp -> open to imp }
    ) { programs, active, days, sessions, pair ->
        val (open, imp) = pair
        TrainState(
            programs = programs,
            active = active,
            days = days,
            sessions = sessions,
            openSession = open,
            importPhase = imp.first,
            importStatus = imp.second,
            importError = imp.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainState())

    fun clearImportState() = importState.update { Triple(ImportPhase.IDLE, "", null) }

    /**
     * Read a program PDF and turn it into structured days. Text-layer PDFs go straight to
     * the parser; scanned ones are rasterised and read by vision instead.
     */
    fun importPdf(uri: Uri) = viewModelScope.launch {
        val settings = repo.settings()
        if (!settings.hasApiKey) {
            importState.update {
                Triple(ImportPhase.ERROR, "", "Add your Anthropic API key in Settings to import a program PDF.")
            }
            return@launch
        }

        importState.update { Triple(ImportPhase.READING, "Reading the PDF...", null) }

        runCatching {
            val ctx = ForgeApp.instance
            val name = PdfImport.fileName(ctx, uri)
            when (val content = PdfImport.extract(ctx, uri)) {
                is PdfContent.Text -> {
                    importState.update {
                        Triple(ImportPhase.PARSING, "Reading ${content.pages} pages of training program...", null)
                    }
                    repo.anthropic.parseProgram(content.text, name) to name
                }
                is PdfContent.Pages -> {
                    importState.update {
                        Triple(ImportPhase.PARSING, "Scanned PDF - reading ${content.imagesBase64.size} pages visually...", null)
                    }
                    repo.anthropic.parseProgramFromImages(content.imagesBase64, name) to name
                }
            }
        }.onSuccess { (parsed, name) ->
            repo.saveProgram(parsed, name)
            importState.update {
                Triple(ImportPhase.DONE, "Imported ${parsed.days.size} training days from ${parsed.name}.", null)
            }
        }.onFailure { e ->
            importState.update {
                Triple(ImportPhase.ERROR, "", e.message ?: "Could not read that PDF.")
            }
        }
    }

    fun activate(program: Program) = viewModelScope.launch {
        repo.activateProgram(program.id)
    }

    fun deleteProgram(program: Program) = viewModelScope.launch {
        repo.programDao.deleteProgram(program)
    }

    /** Start a session from a program day, or a free-form one when day is null. */
    fun startSession(day: ProgramDay?, onStarted: (Long) -> Unit) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val id = repo.workoutDao.insertSession(
            WorkoutSession(
                programDayId = day?.id,
                title = day?.name ?: "Free session",
                startedAt = now,
                localDate = Dates.dayOf(now)
            )
        )
        onStarted(id)
    }

    fun resumeSession(onOpen: (Long) -> Unit) {
        state.value.openSession?.let { onOpen(it.id) }
    }

    companion object {
        val Factory = forgeFactory { TrainViewModel(it) }
    }
}
