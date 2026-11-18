package com.laksh.forge.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.program.LibraryProgram
import com.laksh.forge.domain.program.ProgramExplainer
import com.laksh.forge.domain.program.ProgramLibrary
import com.laksh.forge.domain.program.ProgramStore
import com.laksh.forge.domain.program.SetupInput
import com.laksh.forge.domain.program.Strength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One number the adopt sheet asks for, with what the lifter's own logs suggest. */
data class SetupField(
    val input: SetupInput,
    /** Prefill, in kg: an existing training max or an estimate from logged sets. */
    val prefillKg: Double?,
    /** Shown under the field: where the prefill or hint came from. */
    val hint: String?
)

data class DetailState(
    val loading: Boolean = true,
    val error: String? = null,
    val program: LibraryProgram? = null,
    val explanation: List<String> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val fields: List<SetupField> = emptyList(),
    val hasActiveProgram: Boolean = false,
    val adopting: Boolean = false,
    val adoptError: String? = null,
    val adopted: Boolean = false
)

class ProgramDetailViewModel(
    private val repo: ForgeRepository,
    private val programId: String
) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        runCatching {
            withContext(Dispatchers.IO) {
                val p = ProgramLibrary.template(programId)
                    ?: error("This program isn't in the library any more.")
                val unit = repo.settings().weightUnit
                val active = repo.programDao.observeActive().first()
                Triple(p, unit, active != null) to fields(p)
            }
        }.onSuccess { (meta, fields) ->
            val (p, unit, hasActive) = meta
            _state.update {
                it.copy(
                    loading = false,
                    program = p,
                    explanation = ProgramExplainer.explain(p),
                    unit = unit,
                    fields = fields,
                    hasActiveProgram = hasActive
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: "Could not open this program.") }
        }
    }

    /** Suggestions come only from this person's own data: stored TMs and logged sets. */
    private suspend fun fields(p: LibraryProgram): List<SetupField> {
        val training = ProgramStore.training
        val tms = training.trainingMaxes().associate { it.exerciseKey to it.kg }
        val since = System.currentTimeMillis() - 120L * 86_400_000
        return p.engine.setup.map { input ->
            val liftId = input.exerciseId ?: input.key.takeIf { '_' in it }
            val best = training.allWorkingSets(liftId, input.label, excludeSession = -1L)
                .filter { it.timestamp >= since && it.reps in 1..12 && it.weightKg > 0 }
                .maxOfOrNull { Strength.e1rm(it.weightKg, it.reps) }
            val existingTm = if (input.basis == "TM") tms[input.key] else null
            val estimateNote = best?.let { "From your logs: estimated max ~${Strength.fmt(Strength.roundTo(it, 2.5))} kg" }
            when (input.basis) {
                "TM" -> when {
                    existingTm != null -> SetupField(input, existingTm, "Your current training max")
                    best != null && p.engine.tmOfMax != null -> SetupField(
                        input,
                        Strength.roundTo(best * p.engine.tmOfMax, 2.5),
                        "$estimateNote × ${Strength.fmt(p.engine.tmOfMax * 100)}%"
                    )
                    best != null -> SetupField(input, null, "$estimateNote. The program doesn't say how to set a TM - choose one you can hit for every prescribed rep.")
                    else -> SetupField(input, null, null)
                }
                "1RM" -> SetupField(input, best?.let { Strength.roundTo(it, 2.5) }, estimateNote)
                "5RM" -> SetupField(
                    input,
                    best?.let { Strength.roundTo(Strength.weightForReps(it, 5), 2.5) },
                    best?.let { "From your logs: estimated 5-rep max" }
                )
                else -> SetupField(
                    input, null,
                    best?.let {
                        "Your estimated ${input.reps}-rep max is ~${Strength.fmt(Strength.roundTo(Strength.weightForReps(it, input.reps), 2.5))} kg. Check the program's own advice on how light to start."
                    }
                )
            }
        }
    }

    /** [values] maps "BASIS|key" to kilograms. */
    fun adopt(values: Map<String, Double>, roundingKg: Double) {
        val p = _state.value.program ?: return
        if (_state.value.adopting) return
        _state.update { it.copy(adopting = true, adoptError = null) }
        viewModelScope.launch {
            runCatching { ProgramStore.adopt(repo, p, values, roundingKg) }
                .onSuccess { _state.update { it.copy(adopting = false, adopted = true) } }
                .onFailure { e ->
                    _state.update { it.copy(adopting = false, adoptError = e.message ?: "Could not start this program.") }
                }
        }
    }

    companion object {
        fun factory(programId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProgramDetailViewModel(ForgeApp.instance.repository, programId) }
        }
    }
}
