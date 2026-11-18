package com.laksh.forge.feature.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ProgramExercise
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.db.WorkoutSession
import com.laksh.forge.data.prefs.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

/** What this lift looked like the last time it was trained, shown while logging. */
data class LastTime(val label: String, val topWeightKg: Double, val topReps: Int)

data class SessionState(
    val session: WorkoutSession? = null,
    val exercises: List<ProgramExercise> = emptyList(),
    val sets: List<SetLog> = emptyList(),
    val lastTime: Map<String, LastTime> = emptyMap(),
    val unit: WeightUnit = WeightUnit.KG,
    val restRemaining: Int = 0,
    val restTotal: Int = 0,
    val finished: Boolean = false,
    /** Free-form exercises added on top of the program day. */
    val extraExercises: List<String> = emptyList()
) {
    val allExerciseNames: List<String>
        get() = exercises.map { it.name } + extraExercises

    fun setsFor(name: String): List<SetLog> = sets.filter { it.exerciseName == name }
}

class SessionViewModel(
    private val repo: ForgeRepository,
    private val sessionId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var restJob: Job? = null

    init {
        viewModelScope.launch {
            val session = repo.workoutDao.session(sessionId)
            val exercises = session?.programDayId?.let { repo.programDao.exercises(it) } ?: emptyList()
            val unit = repo.settings().weightUnit
            _state.update { it.copy(session = session, exercises = exercises, unit = unit) }
            loadLastTime(exercises.map { it.name })
        }
        viewModelScope.launch {
            repo.workoutDao.observeSets(sessionId).collect { sets ->
                _state.update { st ->
                    // Surface any lift logged that isn't part of the program day.
                    val extras = sets.map { it.exerciseName }
                        .distinct()
                        .filter { name -> st.exercises.none { it.name == name } }
                    st.copy(sets = sets, extraExercises = extras)
                }
            }
        }
    }

    private fun loadLastTime(names: List<String>) = viewModelScope.launch {
        val map = mutableMapOf<String, LastTime>()
        names.forEach { name ->
            val recent = repo.workoutDao.recentSetsFor(name, sessionId)
            val best = recent.maxByOrNull { it.weightKg * 1000 + it.reps }
            if (best != null) {
                map[name] = LastTime(
                    label = com.laksh.forge.util.Dates.shortLabel(best.timestamp),
                    topWeightKg = best.weightKg,
                    topReps = best.reps
                )
            }
        }
        _state.update { it.copy(lastTime = it.lastTime + map) }
    }

    fun addSet(exerciseName: String, weightDisplay: Double, reps: Int, rir: Double?, warmup: Boolean) =
        viewModelScope.launch {
            val unit = _state.value.unit
            val kg = com.laksh.forge.domain.NutritionMath.displayToKg(weightDisplay, unit)
            val idx = _state.value.setsFor(exerciseName).size + 1
            repo.workoutDao.insertSet(
                SetLog(
                    sessionId = sessionId,
                    exerciseName = exerciseName,
                    setIdx = idx,
                    weightKg = kg,
                    reps = reps,
                    rir = rir,
                    isWarmup = warmup,
                    timestamp = System.currentTimeMillis()
                )
            )
            if (!warmup) {
                val rest = _state.value.exercises
                    .firstOrNull { it.name == exerciseName }?.restSeconds ?: 150
                startRest(rest)
            }
        }

    fun deleteSet(set: SetLog) = viewModelScope.launch { repo.workoutDao.deleteSet(set) }

    fun addExercise(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _state.update {
            if (it.allExerciseNames.any { n -> n.equals(trimmed, ignoreCase = true) }) it
            else it.copy(extraExercises = it.extraExercises + trimmed)
        }
        loadLastTime(listOf(trimmed))
    }

    fun startRest(seconds: Int) {
        restJob?.cancel()
        _state.update { it.copy(restRemaining = seconds, restTotal = seconds) }
        restJob = viewModelScope.launch {
            var left = seconds
            while (left > 0 && isActive) {
                delay(1000)
                left--
                _state.update { it.copy(restRemaining = left) }
            }
        }
    }

    fun stopRest() {
        restJob?.cancel()
        _state.update { it.copy(restRemaining = 0, restTotal = 0) }
    }

    fun finish(notes: String?) = viewModelScope.launch {
        val session = repo.workoutDao.session(sessionId) ?: return@launch
        repo.workoutDao.updateSession(
            session.copy(endedAt = System.currentTimeMillis(), notes = notes?.ifBlank { null })
        )
        _state.update { it.copy(finished = true) }
    }

    /** Delete a session that was started and never logged into. */
    fun discard() = viewModelScope.launch {
        val session = repo.workoutDao.session(sessionId) ?: return@launch
        repo.workoutDao.deleteSession(session)
        _state.update { it.copy(finished = true) }
    }

    override fun onCleared() {
        restJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(sessionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(ForgeApp.instance.repository, sessionId) }
        }
    }
}
