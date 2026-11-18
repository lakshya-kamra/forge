package com.laksh.forge.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayState(
    val date: String = Dates.today(),
    val entries: List<FoodEntry> = emptyList(),
    val totals: DayTotals? = null,
    val targets: MacroTargets? = null,
    val latestWeight: WeightEntry? = null,
    val trendWeight: Double? = null,
    val health: HealthDaily? = null,
    val settings: ForgeSettings = ForgeSettings(),
    val recent: List<FoodEntry> = emptyList()
) {
    val kcal: Double get() = totals?.kcal ?: 0.0
    val remaining: Int get() = ((targets?.kcal ?: 0) - kcal).toInt()
    val isToday: Boolean get() = date == Dates.today()
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val selectedDate = MutableStateFlow(Dates.today())

    private val dayData = selectedDate.flatMapLatest { date ->
        combine(
            repo.foodDao.observeDay(date),
            repo.foodDao.observeTotals(date),
            repo.healthDao.observeDay(date)
        ) { entries, totals, health -> Triple(entries, totals, health) }
    }

    val state: StateFlow<TodayState> = combine(
        selectedDate,
        dayData,
        repo.weightDao.observeLatest(),
        repo.settingsStore.settings,
        repo.foodDao.observeRecent(24)
    ) { date, day, latestWeight, settings, recent ->
        val (entries, totals, health) = day
        val weightKg = latestWeight?.kg ?: 75.0
        val targets = NutritionMath.targets(
            settings,
            weightKg,
            settings.learnedTdee.takeIf { it > 0 }
        )
        TodayState(
            date = date,
            entries = entries,
            totals = totals,
            targets = targets,
            latestWeight = latestWeight,
            health = health,
            settings = settings,
            recent = recent
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    fun selectDate(date: String) {
        if (!Dates.isFuture(date)) selectedDate.value = date
    }

    fun shiftDay(delta: Int) {
        val next = Dates.plusDays(selectedDate.value, delta)
        if (!Dates.isFuture(next)) selectedDate.value = next
    }

    fun deleteEntry(entry: FoodEntry) = viewModelScope.launch {
        repo.deleteFood(entry)
    }

    fun repeat(entry: FoodEntry) = viewModelScope.launch {
        repo.repeatEntry(entry, Dates.mealSlotNow())
    }

    fun logWeight(value: Double, bodyFat: Double?) = viewModelScope.launch {
        val s = repo.settings()
        repo.logWeight(value, s.weightUnit, bodyFat, null)
    }

    companion object {
        val Factory = forgeFactory { TodayViewModel(it) }
    }
}
