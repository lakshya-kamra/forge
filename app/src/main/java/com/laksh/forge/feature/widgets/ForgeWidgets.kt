package com.laksh.forge.feature.widgets

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.laksh.forge.ForgeApp
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The one place that refreshes Forge's home-screen widgets.
 *
 * - [start]: call once from Application.onCreate. It watches the tables the widgets read and
 *   refreshes them (debounced) whenever something changes, so no feature has to remember to.
 * - [updateAll] / [requestUpdate]: explicit refresh for code that knows it changed something.
 *
 * Periodic refresh (date rollover, a plan's targets changing overnight) comes from
 * updatePeriodMillis in the widget-info XML. Widget failures are swallowed: a widget problem
 * must never fail the save that triggered it.
 */
object ForgeWidgets {

    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Lets several saves in a row (a whole meal, a restore) cost one redraw. */
    private const val DEBOUNCE_MS = 1_500L

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        refresh(app, TodayWidget(), TodayWidget::class.java)
        refresh(app, NextWorkoutWidget(), NextWorkoutWidget::class.java)
    }

    /** Fire-and-forget [updateAll] for non-suspending callers. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        fallbackScope.launch { updateAll(app) }
    }

    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val repo = ForgeApp.instance.repository
        // Room re-emits these on ANY write to their tables, which is exactly the trigger we want
        // (the date argument doesn't narrow the invalidation).
        merge(
            repo.foodDao.observeTotals(Dates.today()).map { },
            repo.weightDao.observeLatest().map { },
            repo.workoutDao.observeSessions(1).map { },
            repo.workoutDao.observeOpenSession().map { },
            repo.programDao.observeActive().map { },
            repo.settingsStore.settings.map { }
        )
            .debounce(DEBOUNCE_MS)
            .onEach { updateAll(app) }
            .launchIn(scope)
    }

    private suspend fun <T : GlanceAppWidget> refresh(context: Context, widget: T, type: Class<T>) {
        try {
            // Skip the work entirely when that widget isn't on the home screen.
            if (GlanceAppWidgetManager(context).getGlanceIds(type).isEmpty()) return
            widget.updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Widget problems must never surface as app errors.
        }
    }
}
