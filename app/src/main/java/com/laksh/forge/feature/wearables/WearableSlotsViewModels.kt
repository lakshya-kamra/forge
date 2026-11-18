package com.laksh.forge.feature.wearables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.wearables.HealthField
import com.laksh.forge.data.wearables.HealthLayerStore
import com.laksh.forge.data.wearables.Layer
import com.laksh.forge.domain.readiness.Readiness
import com.laksh.forge.domain.readiness.ReadinessEstimate
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// ====================================================================== readiness

sealed interface ReadinessUi {
    data object Loading : ReadinessUi
    /** No wearable rows at all in the baseline window: nothing to compare with. */
    data object NoData : ReadinessUi
    data class Ready(
        val estimate: ReadinessEstimate,
        /** WHOOP's own recovery score for today (or yesterday), exactly as reported. */
        val whoopRecovery: Double?,
        val whoopRecoveryIsToday: Boolean,
        /** True when no RMSSD source has ever been seen, so the HRV hint can name the sources. */
        val noRmssdSource: Boolean
    ) : ReadinessUi
}

class ReadinessViewModel(@Suppress("UNUSED_PARAMETER") repo: ForgeRepository) : ViewModel() {
    private val dao = ForgeDatabase.get(ForgeApp.instance).wearableDao()

    val state: StateFlow<ReadinessUi> = dao
        .observeRange(
            Dates.daysAgo(Readiness.BASELINE_DAYS + Readiness.HRV_WINDOW_DAYS + 2),
            Dates.today()
        )
        .map { rows -> compute(rows) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessUi.Loading)

    private fun compute(rows: List<HealthDaily>): ReadinessUi {
        if (rows.isEmpty()) return ReadinessUi.NoData
        val today = LocalDate.now()
        val estimate = Readiness.estimate(Readiness.fromHealthDaily(rows), today)
        val byDate = rows.associateBy { it.localDate }
        val todayRecovery = byDate[today.toString()]?.recoveryScore
        val yesterdayRecovery = byDate[today.minusDays(1).toString()]?.recoveryScore
        return ReadinessUi.Ready(
            estimate = estimate,
            whoopRecovery = todayRecovery ?: yesterdayRecovery,
            whoopRecoveryIsToday = todayRecovery != null,
            noRmssdSource = rows.none { it.hrvMs != null }
        )
    }

    companion object {
        val Factory = forgeFactory { ReadinessViewModel(it) }
    }
}

// ====================================================================== trends

enum class TrendWindow(val days: Int, val label: String) {
    D30(30, "30 days"), D90(90, "90 days"), Y1(365, "1 year")
}

data class TrendSeries(
    val points: List<ChartPoint> = emptyList(),
    val average: Double? = null,
    val latest: Double? = null
) {
    val isEmpty: Boolean get() = points.isEmpty()
}

data class WearableTrendsUi(
    val loading: Boolean = true,
    val window: TrendWindow = TrendWindow.D90,
    val hasData: Boolean = false,
    val hrv: TrendSeries = TrendSeries(),
    val restingHr: TrendSeries = TrendSeries(),
    /** Hours. Weekly averages for the 1-year window so bars stay readable on a phone. */
    val sleepHours: TrendSeries = TrendSeries(),
    /** Weekly averages for the 1-year window. */
    val steps: TrendSeries = TrendSeries(),
    val vo2max: TrendSeries = TrendSeries(),
    val recovery: TrendSeries = TrendSeries(),
    /** Apple's SDNN, from the Apple import layer only - never merged with RMSSD. */
    val appleSdnn: TrendSeries = TrendSeries(),
    val stepsTarget: Int? = null,
    val weekly: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class WearableTrendsViewModel(private val repo: ForgeRepository) : ViewModel() {
    private val dao = ForgeDatabase.get(ForgeApp.instance).wearableDao()
    private val window = MutableStateFlow(TrendWindow.D90)

    val state: StateFlow<WearableTrendsUi> = window
        .flatMapLatest { w ->
            val from = Dates.daysAgo(w.days - 1)
            val to = Dates.today()
            combine(dao.observeRange(from, to), repo.settingsStore.settings) { rows, settings ->
                Triple(w, rows, settings.stepsTarget)
            }.map { (w2, rows, target) ->
                val sdnn = runCatching {
                    HealthLayerStore.series(ForgeApp.instance, Layer.APPLE, HealthField.HRV_SDNN, from, to)
                }.getOrDefault(emptyList())
                build(w2, rows, sdnn, target)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearableTrendsUi())

    fun setWindow(w: TrendWindow) {
        window.value = w
    }

    private fun build(
        w: TrendWindow,
        rows: List<HealthDaily>,
        sdnn: List<Pair<String, Double>>,
        stepsTarget: Int
    ): WearableTrendsUi {
        val weekly = w == TrendWindow.Y1
        fun daily(pick: (HealthDaily) -> Double?): List<Pair<LocalDate, Double>> = rows.mapNotNull { r ->
            val v = pick(r)?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            val d = runCatching { LocalDate.parse(r.localDate) }.getOrNull() ?: return@mapNotNull null
            d to v
        }

        val hrv = series(daily { it.hrvMs })
        val rhr = series(daily { it.restingHr?.toDouble() })
        val sleep = daily { it.sleepMinutes?.let { m -> m / 60.0 } }
        val steps = daily { it.steps?.toDouble() }
        val vo2 = series(daily { it.vo2max })
        val recovery = series(rows.mapNotNull { r ->
            val v = r.recoveryScore?.takeIf { it.isFinite() } ?: return@mapNotNull null
            runCatching { LocalDate.parse(r.localDate) }.getOrNull()?.let { it to v }
        })
        val sdnnSeries = series(sdnn.mapNotNull { (date, v) ->
            runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to v }
        })

        return WearableTrendsUi(
            loading = false,
            window = w,
            hasData = rows.isNotEmpty() || sdnn.isNotEmpty(),
            hrv = hrv,
            restingHr = rhr,
            sleepHours = series(if (weekly) weeklyAverage(sleep) else sleep),
            steps = series(if (weekly) weeklyAverage(steps) else steps),
            vo2max = vo2,
            recovery = recovery,
            appleSdnn = sdnnSeries,
            stepsTarget = stepsTarget.takeIf { it > 0 },
            weekly = weekly
        )
    }

    private fun series(values: List<Pair<LocalDate, Double>>): TrendSeries {
        if (values.isEmpty()) return TrendSeries()
        val sorted = values.sortedBy { it.first }
        return TrendSeries(
            points = sorted.map { (d, v) ->
                ChartPoint(x = d.toEpochDay().toFloat(), y = v.toFloat(), label = Dates.shortLabel(d.toString()))
            },
            average = sorted.map { it.second }.average(),
            latest = sorted.last().second
        )
    }

    /** Mean of the days that have data in each Monday-started week (missing days aren't zeros). */
    private fun weeklyAverage(values: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> =
        values.groupBy { it.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .map { (week, list) -> week to list.map { it.second }.average() }

    companion object {
        val Factory = forgeFactory { WearableTrendsViewModel(it) }
    }
}
