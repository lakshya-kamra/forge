package com.laksh.forge.feature.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.exercise.ExerciseLabels
import com.laksh.forge.domain.exercise.ExerciseNameMatcher
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val muscle: String? = null,
    val equipment: String? = null,
    val results: List<ExerciseInfo> = emptyList(),
    /** Muscle ids that some exercise trains as a primary mover, in anatomical order. */
    val muscles: List<String> = emptyList(),
    /** Equipment ids, most common first. */
    val equipmentOptions: List<String> = emptyList(),
    val total: Int = 0,
    /** Names from the user's own logs that match nothing in the catalog. */
    val unmatchedLogged: List<String> = emptyList()
) {
    val filtered: Boolean get() = query.isNotBlank() || muscle != null || equipment != null
}

class ExerciseBrowserViewModel : ViewModel() {

    private val dao = ForgeDatabase.get(ForgeApp.instance).exerciseDao()
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val query = MutableStateFlow("")
    private val muscle = MutableStateFlow<String?>(null)
    private val equipment = MutableStateFlow<String?>(null)
    private val ready = MutableStateFlow(false)

    init {
        load()
        viewModelScope.launch {
            combine(ready, query, muscle, equipment) { r, q, m, e -> Filter(r, q, m, e) }
                .mapLatest { f ->
                    if (!f.ready) null
                    else withContext(Dispatchers.Default) {
                        ExerciseCatalog.search(f.query)
                            .filter { f.muscle == null || f.muscle in it.primaryMuscles }
                            .filter { f.equipment == null || f.equipment in it.equipment }
                    }
                }
                .collect { results ->
                    if (results != null) _state.update { it.copy(results = results) }
                }
        }
        viewModelScope.launch {
            combine(ready, dao.observeLoggedNames(), query) { r, names, q -> Triple(r, names, q) }
                .map { (r, names, q) ->
                    if (!r) emptyList()
                    else {
                        val words = ExerciseNameMatcher.tokens(q)
                        names.filter { n ->
                            n.isNotBlank() && ExerciseCatalog.resolve(n) == null &&
                                (words.isEmpty() || ExerciseNameMatcher.tokens(n).let { t -> words.all { w -> t.any { it.startsWith(w) } } })
                        }
                    }
                }
                .flowOn(Dispatchers.Default)
                .catch { emit(emptyList()) }
                .collect { names -> _state.update { it.copy(unmatchedLogged = names) } }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ExerciseCatalog.preload()
                    val all = ExerciseCatalog.all()
                    val primaries = all.flatMap { it.primaryMuscles }.toSet()
                    val muscles = ExerciseLabels.MUSCLES.filter { it in primaries }
                    val equipment = all.flatMap { it.equipment }.groupingBy { it }.eachCount()
                        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .map { it.key }
                    Triple(ExerciseCatalog.loadError(), all.size, muscles to equipment)
                }
            }.onSuccess { (error, total, options) ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = error,
                        total = total,
                        muscles = options.first,
                        equipmentOptions = options.second
                    )
                }
                ready.value = true
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Could not open the exercise library.") }
            }
        }
    }

    fun setQuery(q: String) {
        query.value = q
        _state.update { it.copy(query = q) }
    }

    fun setMuscle(m: String?) {
        muscle.value = m
        _state.update { it.copy(muscle = m) }
    }

    fun setEquipment(e: String?) {
        equipment.value = e
        _state.update { it.copy(equipment = e) }
    }

    fun clearFilters() {
        setQuery("")
        setMuscle(null)
        setEquipment(null)
    }

    private data class Filter(val ready: Boolean, val query: String, val muscle: String?, val equipment: String?)

    companion object {
        val factory: ViewModelProvider.Factory = forgeFactory { ExerciseBrowserViewModel() }
    }
}
