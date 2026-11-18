package com.laksh.forge.feature.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ExerciseDao
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.exercise.CatalogLicence
import com.laksh.forge.domain.exercise.Creator
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.exercise.ExerciseExtras
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.exercise.ExerciseMotion
import com.laksh.forge.domain.exercise.LiftHistory
import com.laksh.forge.domain.exercise.LiftMath
import com.laksh.forge.domain.exercise.LiftSet
import com.laksh.forge.domain.exercise.TutorialVideo
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tutorial with the creator's GVS tier when it earns a badge (S or A only, per GVS). */
data class VideoItem(val video: TutorialVideo, val badgeTier: String?)

data class ExerciseDetailState(
    val key: String,
    val loading: Boolean = true,
    /** Catalog failure; the screen still shows history and search links. */
    val error: String? = null,
    val info: ExerciseInfo? = null,
    val motion: ExerciseMotion? = null,
    val extras: ExerciseExtras? = null,
    val videos: List<VideoItem> = emptyList(),
    val licence: CatalogLicence? = null,
    /** Trusted technique channels, for lifts the catalog does not cover. */
    val creators: List<Creator> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val historyLoading: Boolean = true,
    val history: LiftHistory? = null,
    val historyError: String? = null
) {
    val title: String get() = info?.name ?: key
}

class ExerciseDetailViewModel(
    private val repo: ForgeRepository,
    private val key: String
) : ViewModel() {

    private val dao: ExerciseDao = ForgeDatabase.get(ForgeApp.instance).exerciseDao()
    private val _state = MutableStateFlow(ExerciseDetailState(key = key))
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()
    private var historyJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repo.settingsStore.settings
                .map { it.weightUnit }
                .distinctUntilChanged()
                .catch { /* Keep kg: a settings read failure must not blank the screen. */ }
                .collect { unit -> _state.update { it.copy(unit = unit) } }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    ExerciseCatalog.preload()
                    val info = ExerciseCatalog.get(key) ?: ExerciseCatalog.resolve(key)
                    val videos = info?.videos.orEmpty().map { v ->
                        VideoItem(v, ExerciseCatalog.tierOf(v.author)?.takeIf { it == "S" || it == "A" })
                    }
                    Loaded(
                        error = ExerciseCatalog.loadError(),
                        info = info,
                        motion = info?.let { ExerciseCatalog.motion(it.id) },
                        extras = info?.let { ExerciseCatalog.extras(it.id) },
                        videos = videos,
                        licence = ExerciseCatalog.licence().takeIf { it.images.isNotEmpty() },
                        creators = ExerciseCatalog.techniqueCreators()
                    )
                }
            }
            loaded.onSuccess { l ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = l.error,
                        info = l.info,
                        motion = l.motion,
                        extras = l.extras,
                        videos = l.videos,
                        licence = l.licence,
                        creators = l.creators
                    )
                }
                observeHistory(l.info)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Could not open the exercise library.") }
                observeHistory(null)
            }
        }
    }

    /**
     * Sets logged under this lift. Logs name lifts however the program PDF or the user spelt
     * them, so every distinct logged name is resolved and the ones that map here are included.
     */
    private fun observeHistory(info: ExerciseInfo?) {
        historyJob?.cancel()
        val id = info?.id
        val own = buildList {
            add(key)
            if (info != null) {
                add(info.name)
                addAll(info.aliases)
            }
        }.distinctBy { it.lowercase() }
        _state.update { it.copy(historyLoading = true, historyError = null) }
        historyJob = viewModelScope.launch {
            dao.observeLoggedNames()
                .map { logged ->
                    if (id == null) own
                    else (own + logged.filter { n -> ExerciseCatalog.resolve(n)?.id == id }).distinctBy { it.lowercase() }
                }
                .distinctUntilChanged()
                .flatMapLatest { names -> dao.observeHistoryForNames(id, names) }
                .map { sets -> LiftMath.summarize(sets.map { LiftSet(it.sessionId, it.weightKg, it.reps, it.timestamp) }) }
                .flowOn(Dispatchers.Default)
                .catch { e ->
                    _state.update { it.copy(historyLoading = false, historyError = e.message ?: "Could not read your logged sets.") }
                }
                .collect { h -> _state.update { it.copy(historyLoading = false, history = h, historyError = null) } }
        }
    }

    private class Loaded(
        val error: String?,
        val info: ExerciseInfo?,
        val motion: ExerciseMotion?,
        val extras: ExerciseExtras?,
        val videos: List<VideoItem>,
        val licence: CatalogLicence?,
        val creators: List<Creator>
    )

    companion object {
        fun factory(key: String): ViewModelProvider.Factory =
            forgeFactory { repo -> ExerciseDetailViewModel(repo, key) }
    }
}
