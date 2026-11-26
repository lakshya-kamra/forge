package com.laksh.forge.data.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.edScreenLockActive
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * The monthly "time for this month's progress photos" nudge. Forge doesn't keep the photos
 * (people take them with their own camera and keep them in their gallery), so this reminder
 * is the only thing Forge contributes between comparisons: a steady cadence.
 *
 * It fires on the first Sunday of each month at 08:00 local time. A weekend morning suits the
 * photo protocol (first thing, before eating), and a fixed weekday keeps the gap between
 * sets close to a month. Like the daily reminders it is a self-rescheduling one-time job,
 * because periodic work can't be pinned to a date and time.
 *
 * Kept separate from [ReminderType] on purpose: other screens switch exhaustively over that
 * enum, and a fourth entry would break them.
 */
object ProgressPhotoReminder {

    const val NOTIFICATION_ID = 4104
    internal const val KEY_TARGET_AT = "target_at"
    private const val WORK_NAME = "forge_reminder_progress_photos"
    private const val TAG = "forge_reminder"

    private const val HOUR = 8
    private val DAY: DayOfWeek = DayOfWeek.SUNDAY
    private val MIN_GAP: Duration = Duration.ofMinutes(1)

    fun isEnabled(s: ForgeSettings): Boolean = s.progressPhotoReminder

    /**
     * On: KEEP, so a launch never cancels the very job that woke the process (see
     * [ReminderScheduler.policyFor]); the time never changes, so REPLACE is never needed.
     */
    fun apply(context: Context, enabled: Boolean) {
        if (enabled) scheduleNext(context, ExistingWorkPolicy.KEEP)
        else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun scheduleNext(context: Context, policy: ExistingWorkPolicy, now: ZonedDateTime = ZonedDateTime.now()) {
        val target = nextOccurrence(now)
        val delay = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ProgressPhotoReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_TARGET_AT to target.toInstant().toEpochMilli()))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    /** The first Sunday 08:00 of this month, or of next month once that is (nearly) past. DST-safe. */
    fun nextOccurrence(now: ZonedDateTime): ZonedDateTime {
        fun inMonth(anyDay: LocalDate): ZonedDateTime =
            anyDay.with(TemporalAdjusters.firstInMonth(DAY)).atTime(HOUR, 0).atZone(now.zone)

        val thisMonth = inMonth(now.toLocalDate())
        return if (thisMonth.isAfter(now.plus(MIN_GAP))) thisMonth
        else inMonth(now.toLocalDate().withDayOfMonth(1).plusMonths(1))
    }
}

/**
 * Posts the monthly photo reminder unless it would work against a safety rule, then books
 * next month's. The onboarding skips photos for under-18s and while an eating-concern lock
 * is active (to avoid body-checking), so the reminder stays quiet for them too.
 */
class ProgressPhotoReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = ForgeApp.instance.repository.settings()
        if (!ProgressPhotoReminder.isEnabled(settings)) return Result.success()

        val targetAt = inputData.getLong(ProgressPhotoReminder.KEY_TARGET_AT, 0L)
        // A Sunday-morning nudge that the OS held until evening is still useful; one held
        // into the next day is not, and next month's run is already the better prompt.
        val late = targetAt > 0 && System.currentTimeMillis() - targetAt > MAX_LATENESS_MS

        if (!late) {
            try {
                if (!quiet(settings)) {
                    ReminderNotifications.post(
                        app,
                        ProgressPhotoReminder.NOTIFICATION_ID,
                        title = "Time for this month's progress photos",
                        text = "Front, side, back - same spot, light and time as last month. Take them with your " +
                            "camera and keep them in your gallery (an album called \"Progress\" works well); " +
                            "Forge doesn't store them.",
                        deepLink = DeepLinks.PROGRESS
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed check must not fail the job: a failed job would break the monthly chain.
            }
        }

        ProgressPhotoReminder.scheduleNext(app, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private suspend fun quiet(s: ForgeSettings): Boolean {
        if (s.isMinor || s.edScreenPositive || edScreenLockActive(s)) return true
        val profile = runCatching { PlanProfileStore.current() }.getOrNull() ?: return false
        return profile.crisisLockActive() || profile.safetyEventOpen()
    }

    private companion object {
        const val MAX_LATENESS_MS = 14L * 60 * 60 * 1000
    }
}
