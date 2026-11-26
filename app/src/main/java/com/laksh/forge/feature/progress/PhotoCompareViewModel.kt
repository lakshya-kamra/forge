package com.laksh.forge.feature.progress

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.plan.BodyChangeAi
import com.laksh.forge.domain.plan.BodyChangeEstimate
import com.laksh.forge.domain.plan.BodyFatMethod
import com.laksh.forge.domain.plan.BodyPhotoGate
import com.laksh.forge.domain.plan.ChangeDateInput
import com.laksh.forge.domain.plan.PhotoBodyFat
import com.laksh.forge.domain.plan.PhotoGate
import com.laksh.forge.domain.plan.PhotoSelection
import com.laksh.forge.domain.plan.PlanAiRejected
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.feature.onboarding.attempt
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * A photo the person picked for this comparison. It exists only in this view model's memory:
 * nothing is copied to Forge's files or cache, and [PhotoCompareViewModel.close] drops it.
 */
class SessionPhoto(
    val id: Long,
    val image: ImageBitmap,
    /** Kept for the one AI encode; the base64 made from it never outlives that call. */
    val bitmap: Bitmap,
    val date: LocalDate?,
    val source: DateSource
)

data class CompareUiState(
    /** Oldest first; undated photos last, so they're easy to spot and fix. */
    val photos: List<SessionPhoto> = emptyList(),
    val loadingCount: Int = 0,
    val error: String? = null,
    val gate: PhotoGate? = null,
    val aiConfigured: Boolean = false,
    /** "Provider · model" for the consent text, so it names where photos actually go. */
    val bodyModel: String = "",
    val showBodyFat: Boolean = true,
    /** Asked fresh every time; never remembered between estimates. */
    val consent: Boolean = false,
    val estimating: Boolean = false,
    val estimateError: String? = null,
    val result: BodyChangeEstimate? = null,
    val saved: Boolean = false,
    val saving: Boolean = false
) {
    val distinctDates: Int get() = photos.mapNotNull { it.date }.distinct().size
    val selectionProblem: String? get() = PhotoSelection.problem(photos.map { it.date }, LocalDate.now())
    val canEstimate: Boolean
        get() = gate?.allowed == true && aiConfigured && consent && selectionProblem == null && !estimating && loadingCount == 0
}

class PhotoCompareViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    private var nextId = 1L
    private var estimateJob: Job? = null
    private val loadJobs = mutableListOf<Job>()

    init {
        // The gate follows settings and the plan profile live, so a re-screen in Profile or a
        // new lock applies the moment the person comes back here.
        combine(repo.settingsStore.settings, PlanProfileStore.profile) { s, p ->
            BodyPhotoGate.evaluate(s, p) to (!s.isMinor && !s.hideNumbers && !s.edScreenPositive)
        }
            .onEach { (gate, showBf) ->
                _state.update {
                    it.copy(
                        gate = gate,
                        showBodyFat = showBf,
                        aiConfigured = repo.ai.isConfigured,
                        bodyModel = runCatching { repo.ai.describe(Task.BODY) }.getOrDefault("")
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = PhotoSelection.MAX_PHOTOS - _state.value.photos.size - _state.value.loadingCount
        val take = uris.take(room.coerceAtLeast(0))
        if (take.size < uris.size) {
            _state.update { it.copy(error = "Up to ${PhotoSelection.MAX_PHOTOS} photos at a time - the rest were left out.") }
        } else {
            _state.update { it.copy(error = null) }
        }
        take.forEach { uri ->
            val id = nextId++
            _state.update { it.copy(loadingCount = it.loadingCount + 1) }
            loadJobs += viewModelScope.launch {
                attempt { GalleryPhotos.load(ForgeApp.instance, uri) }
                    .onSuccess { p ->
                        val photo = SessionPhoto(id, p.bitmap.asImageBitmap(), p.bitmap, p.date, p.source)
                        _state.update { it.copy(photos = ordered(it.photos + photo), loadingCount = it.loadingCount - 1, result = null, saved = false) }
                    }
                    .onFailure { e ->
                        _state.update { it.copy(loadingCount = it.loadingCount - 1, error = e.message ?: "One photo couldn't be opened.") }
                    }
            }
        }
    }

    fun remove(id: Long) = _state.update { s ->
        s.copy(photos = s.photos.filterNot { it.id == id }, result = null, saved = false)
    }

    fun setDate(id: Long, date: LocalDate) = _state.update { s ->
        s.copy(
            photos = ordered(s.photos.map { if (it.id == id) SessionPhoto(it.id, it.image, it.bitmap, date, DateSource.USER) else it }),
            result = null,
            saved = false
        )
    }

    fun setConsent(v: Boolean) = _state.update { it.copy(consent = v) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun refreshAi() = _state.update { it.copy(aiConfigured = repo.ai.isConfigured, bodyModel = runCatching { repo.ai.describe(Task.BODY) }.getOrDefault("")) }

    fun estimate() {
        val s = _state.value
        if (!s.canEstimate) return
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            _state.update { it.copy(estimating = true, estimateError = null, result = null, saved = false) }
            attempt { runEstimate(_state.value.photos) }
                .onSuccess { r -> _state.update { it.copy(estimating = false, result = r, consent = false) } }
                .onFailure { e ->
                    _state.update { it.copy(estimating = false, consent = false, estimateError = e.message ?: "The estimate didn't work. Try again.") }
                }
        }
    }

    private suspend fun runEstimate(photos: List<SessionPhoto>): BodyChangeEstimate {
        // Re-checked at the moment of sending: the gate shown on screen may be minutes old.
        val settings = repo.settings()
        val gate = BodyPhotoGate.evaluate(settings, PlanProfileStore.current())
        if (!gate.allowed) throw PlanAiRejected(gate.reason ?: "Photo estimates aren't available right now.")
        if (!repo.ai.isConfigured) throw PlanAiRejected("Add an AI key in Settings first.")
        PhotoSelection.problem(photos.map { it.date }, LocalDate.now())?.let { throw PlanAiRejected(it) }

        val measurements = PlanStore.bodyDao.observeMeasurements().first()
        val groups = PhotoSelection.groupByDate(photos.map { it.date!! to it })
        val weightKg = PlanStore.latestWeightKg(repo)
        val result = run {
            // The base64 copies exist only inside this block: built right before the call,
            // unreachable as soon as it returns or throws.
            val inputs = withContext(Dispatchers.Default) {
                groups.map { g ->
                    ChangeDateInput(
                        date = g.date,
                        imagesBase64 = g.items.map { ImageUtils.toBase64Jpeg(it.bitmap, 85) },
                        tape = PhotoSelection.nearestMeasurement(g.date, measurements)?.let(PhotoSelection::tapeText)
                    )
                }
            }
            BodyChangeAi.estimate(
                ai = repo.ai,
                profile = weightKg?.let { PlanStore.guardrailProfile(settings, it, null) },
                sex = settings.sex,
                age = settings.age,
                heightCm = settings.heightCm,
                dates = inputs
            )
        }
        val now = System.currentTimeMillis()
        // The 14-day spacing counts from any estimate that ran, saved or not.
        PlanProfileStore.update { it.copy(lastPhotoEstimateAt = now, photoConsentAt = now) }
        return result
    }

    /** Only the numbers, only on request: one VISUAL_AI row per date, no image reference. */
    fun saveResult(onSaved: () -> Unit = {}) {
        val r = _state.value.result ?: return
        if (_state.value.saved || _state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            attempt {
                val zone = ZoneId.systemDefault()
                r.perDate.forEach { d ->
                    PlanStore.bodyDao.insertMeasurement(
                        BodyMeasurement(
                            // Midday, so a time-zone change can't move the row to the next day.
                            timestamp = d.date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                            localDate = d.date.toString(),
                            bodyFatPct = (d.estimatePct * 10).roundToInt() / 10.0,
                            bodyFatMethod = BodyFatMethod.VISUAL_AI.dbValue,
                            note = "AI photo estimate, likely ${PhotoBodyFat.label(d.range)} - approximate"
                        )
                    )
                }
            }
                .onSuccess {
                    _state.update { it.copy(saving = false, saved = true) }
                    onSaved()
                }
                .onFailure { e -> _state.update { it.copy(saving = false, estimateError = e.message ?: "Saving didn't work.") } }
        }
    }

    /**
     * Leaving Compare drops every photo. Bitmaps aren't recycle()d: an exit animation may still
     * draw them for a frame, and unreferenced bitmaps are freed by the runtime anyway.
     */
    fun close() {
        estimateJob?.cancel()
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
        val keep = _state.value
        _state.value = CompareUiState(gate = keep.gate, aiConfigured = keep.aiConfigured, bodyModel = keep.bodyModel, showBodyFat = keep.showBodyFat)
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private fun ordered(list: List<SessionPhoto>): List<SessionPhoto> =
        list.sortedWith(compareBy<SessionPhoto> { it.date == null }.thenBy { it.date }.thenBy { it.id })

    companion object {
        val Factory = forgeFactory { PhotoCompareViewModel(it) }
    }
}
