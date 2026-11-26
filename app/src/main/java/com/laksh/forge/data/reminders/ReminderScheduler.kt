package com.laksh.forge.data.reminders

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.laksh.forge.ForgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules each enabled reminder as a one-time WorkManager job at its next clock time; the
 * job reschedules itself for the following day after it runs. One-time jobs rather than a
 * 24 h periodic job because periodic work drifts and can't be pinned to a time of day.
 * WorkManager persists the queue, so reminders survive reboots and app updates without a
 * boot receiver. The monthly progress-photo reminder ([ProgressPhotoReminder]) is kept in
 * step from here too, so the app shell still only calls [start].
 */
object ReminderScheduler {

    internal const val KEY_TYPE = "type"
    internal const val KEY_TARGET_AT = "target_at"
    private const val TAG = "forge_reminder"

    /** Next run must be at least this far away, so a job that fires a little early can't double-post. */
    private val MIN_GAP: Duration = Duration.ofMinutes(1)

    fun workName(type: ReminderType) = "forge_reminder_${type.name.lowercase()}"

    /**
     * Keeps the WorkManager queue in step with the settings for the life of the process. Call
     * once from Application.onCreate; it also re-enqueues (KEEP) every reminder at launch, which
     * heals any job the OS dropped without cancelling one that is running.
     */
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        ReminderNotifications.ensureChannel(app)
        val toggles = ForgeApp.instance.repository.settingsStore.settings
            .map { s -> ReminderType.entries.associateWith { it.isEnabled(s) } }
            .distinctUntilChanged()
        combine(toggles, ReminderPrefsStore.prefs(app).distinctUntilChanged()) { enabled, prefs ->
            enabled to prefs
        }
            .onEach { (enabled, prefs) -> apply(app, enabled, prefs) }
            .launchIn(scope)
        ForgeApp.instance.repository.settingsStore.settings
            .map { ProgressPhotoReminder.isEnabled(it) }
            .distinctUntilChanged()
            .onEach { on -> ProgressPhotoReminder.apply(app, on) }
            .launchIn(scope)
    }

    /** One-shot version of [start]: read the current settings and (re)schedule. */
    suspend fun sync(context: Context) {
        val app = context.applicationContext
        val settings = ForgeApp.instance.repository.settings()
        val prefs = ReminderPrefsStore.current(app)
        apply(app, ReminderType.entries.associateWith { it.isEnabled(settings) }, prefs)
        ProgressPhotoReminder.apply(app, ProgressPhotoReminder.isEnabled(settings))
    }

    /** Minutes-of-day last scheduled per reminder in this process; absent means not yet applied. */
    private val applied = mutableMapOf<ReminderType, Int>()

    private fun apply(context: Context, enabled: Map<ReminderType, Boolean>, prefs: ReminderPrefs) {
        ReminderNotifications.ensureChannel(context)
        enabled.forEach { (type, on) ->
            if (on) {
                val minutes = prefs.minutesFor(type)
                val policy = synchronized(applied) { policyFor(applied.put(type, minutes), minutes) }
                scheduleNext(context, type, minutes, policy)
            } else {
                synchronized(applied) { applied.remove(type) }
                WorkManager.getInstance(context).cancelUniqueWork(workName(type))
            }
        }
    }

    /**
     * KEEP unless the user moved the time while this process is alive. WorkManager often
     * starts the process to run a due reminder, and Application.onCreate re-anchors first:
     * REPLACE there would cancel the very job that woke the app, dropping today's reminder.
     * KEEP still enqueues when the job is missing, finished or cancelled, so launch heals
     * a dropped job.
     */
    internal fun policyFor(previousMinutes: Int?, minutes: Int): ExistingWorkPolicy =
        if (previousMinutes != null && previousMinutes != minutes) ExistingWorkPolicy.REPLACE
        else ExistingWorkPolicy.KEEP

    /**
     * Enqueue the next run of [type]. From inside the running job use APPEND_OR_REPLACE: it
     * chains the next run behind the current one instead of cancelling the job that is
     * asking (which REPLACE would do).
     */
    fun scheduleNext(
        context: Context,
        type: ReminderType,
        minutesOfDay: Int,
        policy: ExistingWorkPolicy,
        now: ZonedDateTime = ZonedDateTime.now()
    ) {
        val target = nextOccurrence(now, minutesOfDay)
        val delay = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    KEY_TYPE to type.name,
                    KEY_TARGET_AT to target.toInstant().toEpochMilli()
                )
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(type), policy, request)
    }

    /** The first time-of-day [minutesOfDay] that is at least [MIN_GAP] after [now], DST-safe. */
    fun nextOccurrence(now: ZonedDateTime, minutesOfDay: Int): ZonedDateTime {
        val m = minutesOfDay.coerceIn(0, 1439)
        var candidate = now.toLocalDate().atTime(m / 60, m % 60).atZone(now.zone)
        if (!candidate.isAfter(now.plus(MIN_GAP))) {
            candidate = now.toLocalDate().plusDays(1).atTime(m / 60, m % 60).atZone(now.zone)
        }
        return candidate
    }

    fun cancelAll(context: Context) {
        ReminderType.entries.forEach { WorkManager.getInstance(context).cancelUniqueWork(workName(it)) }
        ProgressPhotoReminder.cancel(context)
    }

    /** Settings toggles + prefs as one snapshot, for screens that need both. */
    suspend fun enabledTypes(): Set<ReminderType> {
        val s = ForgeApp.instance.repository.settingsStore.settings.first()
        return ReminderType.entries.filter { it.isEnabled(s) }.toSet()
    }
}
