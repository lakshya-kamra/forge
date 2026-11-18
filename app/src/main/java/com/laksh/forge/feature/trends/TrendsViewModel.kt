package com.laksh.forge.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.VolumePoint
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.WeightTrend
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

enum class Window(val days: Int, val label: String) {
    M1(30, "30d"), M3(90, "3m"), M6(180, "6m"), Y1(365, "1y")
}

data class TrendsState(
    val window: Window = Window.M3,
    val weights: List<WeightEntry> = emptyList(),
    val trend: WeightTrend? = null,
    val dayTotals: List<DayTotals> = emptyList(),
    val volume: List<VolumePoint> = emptyList(),
    val health: List<HealthDaily> = emptyList(),
    val settings: ForgeSettings = ForgeSettings(),
    val targets: MacroTargets? = null,
    val feedback: String? = null,
    val syncing: Boolean = false,
    val syncMessage: String? = null
) {
    val avgKcal: Double?
        get() = dayTotals.filter { it.kcal > 400 }.takeIf { it.isNotEmpty() }
            ?.let { list -> list.sumOf { it.kcal } / list.size }

    val avgProtein: Double?
        get() = dayTotals.filter { it.kcal > 400 }.takeIf { it.isNotEmpty() }
            ?.let { list -> list.sumOf { it.proteinG } / list.size }

    val avgSteps: Long?
        get() = health.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.toLong()

    val avgSleepMinutes: Long?
        get() = health.mapNotNull { it.sleepMinutes }.takeIf { it.isNotEmpty() }?.average()?.toLong()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val window = MutableStateFlow(Window.M3)
    private val syncing = MutableStateFlow(false to null as String?)

    private val windowed = window.flatMapLatest { w ->
        val from = Dates.daysAgo(w.days)
        val to = Dates.today()
        combine(
            repo.weightDao.observeSince(System.currentTimeMillis() - w.days * 86_400_000L),
            repo.foodDao.observeTotalsRange(from, to),
            repo.workoutDao.observeVolume(from, to),
            repo.healthDao.observeRange(from, to)
        ) { weights, totals, volume, health ->
            listOf(weights, totals, volume, health)
        }
    }

    val state: StateFlow<TrendsState> = combine(
        window,
        windowed,
        repo.settingsStore.settings,
        syncing
    ) { w, data, settings, sync ->
        @Suppress("UNCHECKED_CAST")
        val weights = data[0] as List<WeightEntry>
        @Suppress("UNCHECKED_CAST")
        val totals = data[1] as List<DayTotals>
        @Suppress("UNCHECKED_CAST")
        val volume = data[2] as List<VolumePoint>
        @Suppress("UNCHECKED_CAST")
        val health = data[3] as List<HealthDaily>

        val trend = NutritionMath.trend(weights)
        val weightKg = weights.maxByOrNull { it.timestamp }?.kg ?: 75.0
        val targets = NutritionMath.targets(settings, weightKg, settings.learnedTdee.takeIf { it > 0 })

        TrendsState(
            window = w,
            weights = weights,
            trend = trend,
            dayTotals = totals,
            volume = volume,
            health = health,
            settings = settings,
            targets = targets,
            feedback = NutritionMath.goalFeedback(settings.goal, trend, weightKg),
            syncing = sync.first,
            syncMessage = sync.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())

    fun setWindow(w: Window) { window.value = w }

    fun syncHealth() = viewModelScope.launch {
        syncing.value = true to null
        runCatching { repo.syncHealth() }
            .onSuccess { count ->
                syncing.value = false to
                    if (count == 0) "No wearable data found. Check that Zepp's Health Connect sync is on."
                    else "Synced $count days from Health Connect."
            }
            .onFailure { e ->
                syncing.value = false to (e.message ?: "Sync failed.")
            }
    }

    fun clearSyncMessage() { syncing.value = syncing.value.first to null }

    companion object {
        val Factory = forgeFactory { TrendsViewModel(it) }
    }
}
