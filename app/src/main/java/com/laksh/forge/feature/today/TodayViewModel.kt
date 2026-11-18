package com.laksh.forge.feature.today

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.wearables.WearableSync
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanTargets
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Meal slots in the order a day happens. */
internal val MEAL_ORDER = listOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")

internal fun mealLabel(slot: String) = when (slot) {
    "BREAKFAST" -> "Breakfast"
    "LUNCH" -> "Lunch"
    "DINNER" -> "Dinner"
    else -> "Snacks"
}

/** One meal in the day's timeline, grouped in the view model so composition never sorts. */
@Immutable
data class MealGroup(
    val slot: String,
    val label: String,
    val kcal: Double,
    val firstTimestamp: Long,
    val entries: List<FoodEntry>
)

@Immutable
data class TodayState(
    /** True until the first database emission, so the screen shows skeletons, not zeros. */
    val loading: Boolean = true,
    val error: String? = null,
    val date: String = Dates.today(),
    /** "Wednesday 18 November", formatted off the main thread. */
    val dateLong: String = "",
    val entries: List<FoodEntry> = emptyList(),
    val meals: List<MealGroup> = emptyList(),
    val totals: DayTotals? = null,
    val targets: MacroTargets? = null,
    /** True when [targets] come from the active plan rather than the generic calculation. */
    val targetsFromPlan: Boolean = false,
    val latestWeight: WeightEntry? = null,
    val health: HealthDaily? = null,
    val settings: ForgeSettings = ForgeSettings(),
    val recent: List<FoodEntry> = emptyList(),
    val refreshing: Boolean = false
) {
    val kcal: Double get() = totals?.kcal ?: 0.0
    val remaining: Int get() = ((targets?.kcal ?: 0) - kcal).toInt()
    val isToday: Boolean get() = date == Dates.today()
    /** The user chose to hide calorie numbers (offered after a positive eating-disorder screen). */
    val hideNumbers: Boolean get() = settings.hideNumbers
}

private data class Day(
    val entries: List<FoodEntry>,
    val totals: DayTotals?,
    val health: HealthDaily?
)

private data class DayContext(
    val weight: WeightEntry?,
    val settings: ForgeSettings,
    val planTargets: MacroTargets?,
    val kidneyDisease: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val selectedDate = MutableStateFlow(Dates.today())
    private val refreshing = MutableStateFlow(false)
    /** Bumped by [retry]; restarts the whole pipeline after a failure. */
    private val reload = MutableStateFlow(0)

    /** One-shot messages (sync results, failures) for the snackbar. */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /**
     * Photos of entries deleted but still undoable. The row is gone from the database at once
     * (so totals update immediately); the file only goes when the undo window closes.
     */
    private val pendingPhotos = mutableMapOf<Long, String>()

    private val longDateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

    val state: StateFlow<TodayState> = reload
        .flatMapLatest { buildState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    private fun buildState(): Flow<TodayState> {
        val day = selectedDate.flatMapLatest { date ->
            combine(
                repo.foodDao.observeDay(date),
                repo.foodDao.observeTotals(date),
                repo.healthDao.observeDay(date)
            ) { entries, totals, health -> Day(entries, totals, health) }
        }
        val context = combine(
            repo.weightDao.observeLatest(),
            repo.settingsStore.settings,
            PlanTargets.observe(repo),
            PlanProfileStore.profile
        ) { weight, settings, plan, profile -> DayContext(weight, settings, plan, profile.kidneyDisease) }

        return combine(
            selectedDate,
            day,
            context,
            repo.foodDao.observeRecent(24),
            refreshing
        ) { date, d, ctx, recent, isRefreshing ->
            // The plan's targets already include check-in adjustments and passed Guardrails;
            // the generic calculation is only the fallback when there is no active plan.
            val targets = ctx.planTargets ?: NutritionMath.targets(
                ctx.settings,
                ctx.weight?.kg ?: 75.0,
                ctx.settings.learnedTdee.takeIf { it > 0 },
                ctx.kidneyDisease
            )
            TodayState(
                loading = false,
                date = date,
                dateLong = runCatching { LocalDate.parse(date).format(longDateFormat) }.getOrDefault(date),
                entries = d.entries,
                meals = groupMeals(d.entries),
                totals = d.totals,
                targets = targets,
                targetsFromPlan = ctx.planTargets != null,
                latestWeight = ctx.weight,
                health = d.health,
                settings = ctx.settings,
                recent = recent,
                refreshing = isRefreshing
            )
        }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                if (e is CancellationException) throw e
                emit(TodayState(loading = false, error = e.message ?: "Couldn't load today's log."))
            }
    }

    private fun groupMeals(entries: List<FoodEntry>): List<MealGroup> {
        if (entries.isEmpty()) return emptyList()
        val bySlot = entries.groupBy { if (it.mealSlot in MEAL_ORDER) it.mealSlot else "SNACK" }
        return MEAL_ORDER.mapNotNull { slot ->
            val items = bySlot[slot] ?: return@mapNotNull null
            MealGroup(
                slot = slot,
                label = mealLabel(slot),
                kcal = items.sumOf { it.kcal },
                firstTimestamp = items.minOf { it.timestamp },
                entries = items
            )
        }
    }

    fun retry() { reload.value++ }

    fun shiftDay(delta: Int) {
        val next = Dates.plusDays(selectedDate.value, delta)
        if (!Dates.isFuture(next)) selectedDate.value = next
    }

    fun selectDate(date: String) {
        if (!Dates.isFuture(date)) selectedDate.value = date
    }

    /** Pull-to-refresh: pull wearable data so steps/sleep on Today are current. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val report = WearableSync.syncAll()
                val message = when {
                    report.errors.isNotEmpty() -> report.errors.joinToString("\n")
                    report.sources.isEmpty() -> "No wearable is connected. Set one up in Settings > Wearables."
                    else -> "Synced ${report.days} days from ${report.sources.joinToString()}."
                }
                messageChannel.trySend(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(e.message ?: "Sync failed.")
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Removes the row now; [restore] puts it back, [finalizeDelete] drops its photo for good. */
    fun delete(entry: FoodEntry) = viewModelScope.launch {
        runCatching { repo.foodDao.delete(entry) }
            .onSuccess {
                entry.photoPath?.let { pendingPhotos[entry.id] = it }
                runCatching { repo.recomputeAdaptiveTdee() }
            }
            .onFailure { e -> messageChannel.trySend(e.message ?: "Couldn't remove that entry.") }
    }

    fun restore(entry: FoodEntry) = viewModelScope.launch {
        // Same primary key, so anything that referenced the entry still resolves.
        runCatching { repo.foodDao.insert(entry) }
            .onSuccess {
                pendingPhotos.remove(entry.id)
                runCatching { repo.recomputeAdaptiveTdee() }
            }
            .onFailure { e -> messageChannel.trySend(e.message ?: "Couldn't restore that entry.") }
    }

    fun finalizeDelete(entry: FoodEntry) {
        pendingPhotos.remove(entry.id)?.let { ImageUtils.deletePhoto(it) }
    }

    /** Quick-add: log [entry] again now; [onAdded] receives the new row for an undo prompt. */
    fun repeat(entry: FoodEntry, onAdded: (FoodEntry) -> Unit) = viewModelScope.launch {
        runCatching {
            val id = repo.repeatEntry(entry, Dates.mealSlotNow())
            repo.recomputeAdaptiveTdee()
            repo.foodDao.byId(id)
        }
            .onSuccess { added -> if (added != null) onAdded(added) }
            .onFailure { e -> messageChannel.trySend(e.message ?: "Couldn't add that.") }
    }

    /** Undo for [repeat]: a repeated entry never has a photo, so a plain delete is complete. */
    fun removeRepeated(entry: FoodEntry) = viewModelScope.launch {
        runCatching {
            repo.foodDao.delete(entry)
            repo.recomputeAdaptiveTdee()
        }.onFailure { e -> messageChannel.trySend(e.message ?: "Couldn't undo that.") }
    }

    fun logWeight(value: Double, bodyFat: Double?) = viewModelScope.launch {
        runCatching {
            val s = repo.settings()
            repo.logWeight(value, s.weightUnit, bodyFat, null)
        }.onFailure { e -> messageChannel.trySend(e.message ?: "Couldn't save that weight.") }
    }

    override fun onCleared() {
        // The undo windows can no longer be answered; finish the deletes they were holding.
        pendingPhotos.values.forEach { ImageUtils.deletePhoto(it) }
        pendingPhotos.clear()
        super.onCleared()
    }

    companion object {
        val Factory = forgeFactory { TodayViewModel(it) }
    }
}
