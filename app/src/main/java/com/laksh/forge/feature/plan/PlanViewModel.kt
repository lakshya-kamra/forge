package com.laksh.forge.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.data.db.WeeklyCheckin
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.WeightTrend
import com.laksh.forge.domain.plan.PlanDetails
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.details
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Days from [from] to [to] (yyyy-MM-dd); 0 when either is unreadable. */
internal fun daysBetween(from: String, to: String): Long =
    runCatching { ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) }.getOrDefault(0L)

/** Check-ins that belong to [plan] (created after it started). */
internal fun checkinsFor(plan: PhasePlan, all: List<WeeklyCheckin>): List<WeeklyCheckin> =
    all.filter { it.createdAt >= plan.createdAt }.sortedByDescending { it.createdAt }

/** Weekly cadence: a week after the last check-in for this plan, or after the start. */
internal fun nextCheckinDate(plan: PhasePlan, all: List<WeeklyCheckin>): String {
    val last = checkinsFor(plan, all).firstOrNull()
    val base = last?.let { Dates.dayOf(it.createdAt) } ?: plan.startDate
    return Dates.plusDays(base, 7)
}

data class PlanUiState(
    val loading: Boolean = true,
    val plan: PhasePlan? = null,
    val details: PlanDetails? = null,
    val settings: ForgeSettings = ForgeSettings(),
    val checkins: List<WeeklyCheckin> = emptyList(),
    val trend: WeightTrend? = null,
    val daysIn: Long = 0,
    val totalDays: Long = 0,
    val nextCheckin: String? = null,
    val checkinDue: Boolean = false,
    val checkedInThisWeek: Boolean = false,
    val error: String? = null
) {
    /** Observed trend as % of bodyweight per week, when there is enough data. */
    val observedPct: Double?
        get() {
            val t = trend ?: return null
            val w = t.latestSmoothed ?: return null
            if (t.points.size < 4 || w <= 0) return null
            return t.kgPerWeek / w * 100.0
        }
}

class PlanViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val weightsForPlan: Flow<Pair<PhasePlan?, List<WeightEntry>>> =
        PlanStore.planDao.observeActivePlan().flatMapLatest { plan ->
            if (plan == null) flowOf(null to emptyList())
            else repo.weightDao.observeSince(Dates.startOfDayMillis(plan.startDate) - 7L * 86_400_000L)
                .map { plan to it }
        }

    val state: StateFlow<PlanUiState> = combine(
        weightsForPlan,
        PlanStore.planDao.observeCheckins(),
        repo.settingsStore.settings
    ) { (plan, weights), checkins, settings ->
        if (plan == null) {
            PlanUiState(loading = false, settings = settings)
        } else {
            val today = Dates.today()
            val details = plan.details()
            val next = nextCheckinDate(plan, checkins)
            val total = (details?.plannedWeeks ?: 12) * 7L
            PlanUiState(
                loading = false,
                plan = plan,
                details = details,
                settings = settings,
                checkins = checkinsFor(plan, checkins),
                trend = NutritionMath.trend(weights),
                daysIn = (daysBetween(plan.startDate, today) + 1).coerceAtLeast(1),
                totalDays = total,
                nextCheckin = next,
                checkinDue = daysBetween(next, today) >= 0,
                checkedInThisWeek = settings.lastCheckinWeek ==
                    com.laksh.forge.domain.plan.CheckinEngine.weekStart(LocalDate.parse(today))
            )
        }
    }
        .catch { e -> emit(PlanUiState(loading = false, error = e.message ?: "Couldn't load your plan.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    companion object {
        val Factory = forgeFactory { PlanViewModel(it) }
    }
}

/** What the Today card needs, nothing more, so it stays cheap to collect. */
sealed interface PlanSummary {
    data object Loading : PlanSummary
    data object None : PlanSummary
    data class Active(
        val phase: String,
        val source: String,
        val daysIn: Long,
        val totalDays: Long,
        val nextCheckin: String,
        val checkinDue: Boolean,
        val kcal: Int,
        val proteinG: Int,
        val hideNumbers: Boolean
    ) : PlanSummary
}

class PlanSummaryViewModel(repo: ForgeRepository) : ViewModel() {
    val state: StateFlow<PlanSummary> = combine(
        PlanStore.planDao.observeActivePlan(),
        PlanStore.planDao.observeCheckins(),
        repo.settingsStore.settings
    ) { plan, checkins, settings ->
        if (plan == null) PlanSummary.None
        else {
            val today = Dates.today()
            val next = nextCheckinDate(plan, checkins)
            PlanSummary.Active(
                phase = plan.phase,
                source = plan.source,
                daysIn = (daysBetween(plan.startDate, today) + 1).coerceAtLeast(1),
                totalDays = ((plan.details()?.plannedWeeks ?: 12) * 7L),
                nextCheckin = next,
                checkinDue = daysBetween(next, today) >= 0,
                kcal = plan.kcal,
                proteinG = plan.proteinG,
                hideNumbers = settings.hideNumbers
            )
        }
    }
        .catch { emit(PlanSummary.None) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanSummary.Loading)

    companion object {
        val Factory = forgeFactory { PlanSummaryViewModel(it) }
    }
}
