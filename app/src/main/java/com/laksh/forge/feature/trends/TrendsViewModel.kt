package com.laksh.forge.feature.trends

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.WeightTrend
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanTargets
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class Window(val days: Int, val label: String) {
    M1(30, "30d"), M3(90, "3m"), M6(180, "6m"), Y1(365, "1y")
}

@Immutable
data class TrendsState(
    val loading: Boolean = true,
    val error: String? = null,
    val window: Window = Window.M3,
    val weights: List<WeightEntry> = emptyList(),
    val trend: WeightTrend? = null,
    val dayTotals: List<DayTotals> = emptyList(),
    val settings: ForgeSettings = ForgeSettings(),
    val targets: MacroTargets? = null,
    val targetsFromPlan: Boolean = false,
    val feedback: String? = null,
    // Chart series and averages are built in the view model so composition only draws.
    val trendPoints: List<ChartPoint> = emptyList(),
    val rawPoints: List<ChartPoint> = emptyList(),
    val intakePoints: List<ChartPoint> = emptyList(),
    val avgKcal: Double? = null,
    val avgProtein: Double? = null,
    val loggedDays: Int = 0
)

private data class Windowed(val weights: List<WeightEntry>, val totals: List<DayTotals>)

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val window = MutableStateFlow(Window.M3)
    private val reload = MutableStateFlow(0)

    val state: StateFlow<TrendsState> = reload
        .flatMapLatest { buildState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())

    private fun buildState(): Flow<TrendsState> {
        val windowed = window.flatMapLatest { w ->
            combine(
                repo.weightDao.observeSince(System.currentTimeMillis() - w.days * 86_400_000L),
                repo.foodDao.observeTotalsRange(Dates.daysAgo(w.days), Dates.today())
            ) { weights, totals -> Windowed(weights, totals) }
        }
        return combine(
            window,
            windowed,
            repo.settingsStore.settings,
            PlanTargets.observe(repo),
            PlanProfileStore.profile
        ) { w, data, settings, planTargets, profile ->
            val unit = settings.weightUnit
            val trend = NutritionMath.trend(data.weights)
            val weightKg = data.weights.maxByOrNull { it.timestamp }?.kg ?: 75.0
            val targets = planTargets
                ?: NutritionMath.targets(settings, weightKg, settings.learnedTdee.takeIf { it > 0 }, profile.kidneyDisease)
            // Days under 400 kcal are almost always partial logs, not real intake.
            val logged = data.totals.filter { it.kcal > 400 }

            TrendsState(
                loading = false,
                window = w,
                weights = data.weights,
                trend = trend,
                dayTotals = data.totals,
                settings = settings,
                targets = targets,
                targetsFromPlan = planTargets != null,
                feedback = NutritionMath.goalFeedback(settings.goal, trend, weightKg),
                trendPoints = trend.points.map {
                    ChartPoint(
                        x = it.timestamp.toFloat(),
                        y = NutritionMath.kgToDisplay(it.smoothed, unit).toFloat(),
                        label = Dates.shortLabel(it.timestamp)
                    )
                },
                rawPoints = trend.points.map {
                    ChartPoint(
                        x = it.timestamp.toFloat(),
                        y = NutritionMath.kgToDisplay(it.raw, unit).toFloat()
                    )
                },
                intakePoints = data.totals.map {
                    ChartPoint(0f, it.kcal.toFloat(), Dates.shortLabel(it.localDate))
                },
                avgKcal = logged.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kcal } / l.size },
                avgProtein = logged.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.proteinG } / l.size },
                loggedDays = logged.size
            )
        }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                if (e is CancellationException) throw e
                emit(TrendsState(loading = false, window = window.value, error = e.message ?: "Couldn't load trends."))
            }
    }

    fun setWindow(w: Window) { window.value = w }

    fun retry() { reload.value++ }

    companion object {
        val Factory = forgeFactory { TrendsViewModel(it) }
    }
}
