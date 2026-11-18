package com.laksh.forge.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ToolTab(val label: String) {
    PLATES("Plates"),
    WARMUP("Warm-up"),
    ONE_RM("1RM"),
    CONVERT("Convert")
}

/** What the person has typed or picked. Kept apart from results so typing never waits on maths. */
data class ToolInputs(
    val tab: ToolTab = ToolTab.PLATES,
    val plateTargetKg: Double? = 100.0,
    val warmupWorkKg: Double? = 100.0,
    val warmupJumps: Int = WarmupGenerator.DEFAULT_JUMPS,
    val warmupWorkReps: Int = 5,
    val ormWeightKg: Double? = 100.0,
    val ormReps: Int? = 5,
    val ormRpe: Double = 10.0,
    val formula: E1rmFormula = E1rmFormula.EPLEY,
    val conversion: Conversion = Conversion.KG_TO_LB,
    val convertValue: Double? = null
)

data class ToolsState(
    val loading: Boolean = true,
    val inputs: ToolInputs = ToolInputs(),
    val profile: GymProfile = GymProfile(),
    val unit: WeightUnit = WeightUnit.KG,
    val haptics: Boolean = true,
    val plate: PlateLoad? = null,
    val maxLoadKg: Double = 0.0,
    val warmup: WarmupPlan? = null,
    val orm: OneRmEstimate? = null,
    val percentRows: List<PercentRow> = emptyList(),
    val rpeTable: RpeTable? = null,
    val converted: Double? = null,
    val error: String? = null
) {
    /** Every denomination the editor shows, merged with what the profile stores. */
    val editablePlates: List<PlateStock>
        get() {
            val stored = profile.plates.associateBy { PlateMath.roundKg(it.kg) }
            val all = (GymProfile.DENOMINATIONS + stored.keys).distinct().sortedDescending()
            return all.map { kg -> stored[kg] ?: PlateStock(kg, 0) }
        }
}

class ToolsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val app = ForgeApp.instance
    private val inputs = MutableStateFlow(ToolInputs())
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ToolsState> = combine(
        inputs,
        GymProfileStore.profile(app),
        repo.settingsStore.settings,
        error
    ) { i, profile, settings, err ->
        compute(i, profile, settings.weightUnit, settings.haptics, err)
    }
        // The knapsack table and RPE grid are cheap, but they still have no business on the
        // main thread while the person is typing.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsState())

    private fun compute(
        i: ToolInputs,
        profile: GymProfile,
        unit: WeightUnit,
        haptics: Boolean,
        err: String?
    ): ToolsState {
        val plate = i.plateTargetKg?.takeIf { it > 0 }?.let { PlateMath.load(it, profile) }
        val warmup = i.warmupWorkKg?.takeIf { it > 0 }?.let {
            WarmupGenerator.generate(it, profile, i.warmupJumps, i.warmupWorkReps)
        }
        val orm = if (i.ormWeightKg != null && i.ormWeightKg > 0 && i.ormReps != null && i.ormReps in 1..20) {
            OneRepMax.estimate(i.ormWeightKg, i.ormReps, i.ormRpe)
        } else null
        val percentRows = orm?.let { OneRepMax.percentTable(it.of(i.formula), profile) } ?: emptyList()
        return ToolsState(
            loading = false,
            inputs = i,
            profile = profile,
            unit = unit,
            haptics = haptics,
            plate = plate,
            maxLoadKg = PlateMath.maxLoadKg(profile),
            warmup = warmup,
            orm = orm,
            percentRows = percentRows,
            rpeTable = OneRepMax.rpeTable(i.formula),
            converted = i.convertValue?.let { i.conversion.apply(it) },
            error = err
        )
    }

    fun selectTab(tab: ToolTab) = inputs.update { it.copy(tab = tab) }
    fun setPlateTarget(kg: Double?) = inputs.update { it.copy(plateTargetKg = kg) }
    fun setWarmupWork(kg: Double?) = inputs.update { it.copy(warmupWorkKg = kg) }
    fun setWarmupJumps(n: Int) = inputs.update { it.copy(warmupJumps = n.coerceIn(2, 6)) }
    fun setWarmupWorkReps(n: Int) = inputs.update { it.copy(warmupWorkReps = n.coerceIn(1, 20)) }
    fun setOrmWeight(kg: Double?) = inputs.update { it.copy(ormWeightKg = kg) }
    fun setOrmReps(reps: Int?) = inputs.update { it.copy(ormReps = reps) }
    fun setOrmRpe(rpe: Double) = inputs.update { it.copy(ormRpe = rpe) }
    fun setFormula(f: E1rmFormula) = inputs.update { it.copy(formula = f) }
    fun setConversion(c: Conversion) = inputs.update { it.copy(conversion = c) }
    fun setConvertValue(v: Double?) = inputs.update { it.copy(convertValue = v) }

    /** Sends the 1RM estimate to the plate tab, so "what's 85 %" turns into plates in one tap. */
    fun loadOnBar(kg: Double) = inputs.update { it.copy(tab = ToolTab.PLATES, plateTargetKg = kg) }

    // ---------------------------------------------------------------- gym profile

    private fun editProfile(transform: (GymProfile) -> GymProfile) = viewModelScope.launch {
        runCatching { GymProfileStore.update(app, transform) }
            .onSuccess { error.value = null }
            .onFailure { e -> error.value = e.message ?: "Couldn't save the gym profile." }
    }

    fun setBar(kg: Double) = editProfile { it.copy(barKg = kg) }
    fun setUseCollars(on: Boolean) = editProfile { it.copy(useCollars = on) }
    fun setCollarKg(kg: Double) = editProfile { it.copy(collarKg = kg.coerceIn(0.0, 5.0)) }

    fun setPlateCount(kg: Double, perSide: Int) = editProfile { p ->
        val key = PlateMath.roundKg(kg)
        val n = perSide.coerceIn(0, 20)
        val exists = p.plates.any { PlateMath.roundKg(it.kg) == key }
        val plates = if (exists) {
            p.plates.map { if (PlateMath.roundKg(it.kg) == key) it.copy(perSide = n) else it }
        } else p.plates + PlateStock(key, n)
        p.copy(plates = plates.sortedByDescending { it.kg })
    }

    fun resetProfile() = viewModelScope.launch {
        runCatching { GymProfileStore.reset(app) }
            .onFailure { e -> error.value = e.message ?: "Couldn't reset the gym profile." }
    }

    fun dismissError() { error.value = null }

    companion object {
        val Factory = forgeFactory { ToolsViewModel(it) }
    }
}
