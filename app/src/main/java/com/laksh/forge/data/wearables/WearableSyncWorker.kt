package com.laksh.forge.data.wearables

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic wearable sync: every 6 hours, only when the battery isn't low. No network constraint,
 * because Health Connect is on-device; WHOOP simply reports an error if offline and catches up
 * next run (each sync re-reads a few days of overlap).
 */
class WearableSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Partial failures (one source offline, a permission revoked) are in the stored report;
        // retrying would only spend WHOOP's rate budget, so the run counts as done either way.
        runCatching { WearableSync.syncInBackground() }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "forge.wearable_sync"

        /** Idempotent: an existing schedule is kept, so calling this often is free. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WearableSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
