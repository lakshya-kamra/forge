package com.laksh.forge.data.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.feature.widgets.NextWorkoutPicker
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Fires one reminder, if it is still relevant, then books tomorrow's run.
 *
 * Every reminder checks the data first so it never nags about something already done.
 * Copy follows the guardrails messaging rules (docs/research/guardrails.final.json): neutral,
 * no praise for eating less, and never a raw weigh-in number in a notification.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val type = ReminderType.fromName(inputData.getString(ReminderScheduler.KEY_TYPE))
            ?: return Result.success()
        val app = applicationContext
        val repo = ForgeApp.instance.repository
        val settings = repo.settings()

        // Switched off since this run was booked: stop the chain here.
        if (!type.isEnabled(settings)) return Result.success()

        val prefs = ReminderPrefsStore.current(app)
        val targetAt = inputData.getLong(ReminderScheduler.KEY_TARGET_AT, 0L)
        // A weigh-in nudge at 3 pm is noise; if the OS held the job for hours, skip today's.
        val late = targetAt > 0 && System.currentTimeMillis() - targetAt > MAX_LATENESS_MS

        if (!late) {
            try {
                maybeNotify(app, type, settings, prefs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed check must not fail the job: a failed job would break the daily chain.
            }
        }

        ReminderScheduler.scheduleNext(app, type, prefs.minutesFor(type), ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private suspend fun maybeNotify(
        context: Context,
        type: ReminderType,
        settings: ForgeSettings,
        prefs: ReminderPrefs
    ) {
        val repo = ForgeApp.instance.repository
        val today = Dates.today()
        when (type) {
            ReminderType.WEIGH_IN -> {
                // While a positive eating-disorder screen is active the guardrails pause
                // weight-focused displays; a daily scale prompt would work against that.
                if (settings.edScreenPositive) return
                if (repo.weightDao.countOn(today) > 0) return
                ReminderNotifications.post(
                    context, type,
                    title = "Morning weigh-in",
                    text = "A quick weigh-in keeps your trend accurate. Forge reads the weekly average, not any single day.",
                    deepLink = DeepLinks.TODAY
                )
            }

            ReminderType.MEAL -> {
                val items = repo.foodDao.observeTotals(today).first()?.items ?: 0
                if (items >= 2) return
                ReminderNotifications.post(
                    context, type,
                    title = if (items == 0) "Nothing logged yet today" else "Only one item logged today",
                    text = "Add what you've eaten so today's numbers stay useful.",
                    deepLink = DeepLinks.LOG
                )
            }

            ReminderType.WORKOUT -> {
                if (LocalDate.now().dayOfWeek !in prefs.trainingDays) return
                val sessions = repo.workoutDao.observeSessions(60).first()
                if (sessions.any { it.localDate == today }) return
                val active = repo.programDao.observeActive().first()
                val next = active?.let { NextWorkoutPicker.pick(repo.programDao.days(it.id), sessions) }
                ReminderNotifications.post(
                    context, type,
                    title = "Training day",
                    text = when {
                        active != null && next != null -> "Next up: ${next.name} from ${active.name}."
                        active != null -> "Open ${active.name} to start today's session."
                        else -> "Tap to start a session."
                    },
                    deepLink = DeepLinks.TRAIN
                )
            }
        }
    }

    private companion object {
        const val MAX_LATENESS_MS = 3L * 60 * 60 * 1000
    }
}
