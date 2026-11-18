package com.laksh.forge.data.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.laksh.forge.data.db.HealthDaily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, NOT_SUPPORTED }

data class WearableWeight(val timeMillis: Long, val kg: Double)

/**
 * Reads wearable data out of Health Connect.
 *
 * The Amazfit Helio strap has no public API of its own. The supported route is: Zepp writes
 * to Health Connect, and Forge reads from there. Zepp's Health Connect sync must be switched
 * on inside the Zepp app itself (Profile -> Settings -> Health Connect) before anything
 * appears here.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class)
    )

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NOT_INSTALLED
        else -> HealthAvailability.NOT_SUPPORTED
    }

    private fun clientOrNull(): HealthConnectClient? =
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(): Boolean = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext false
        runCatching {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /** Opens Health Connect so the user can install it or manage what Forge may read. */
    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    fun playStoreIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse(
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        )
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * Pull the last [days] days of daily aggregates. Health Connect aggregation is done in
     * local-time buckets so the day boundaries line up with the food log.
     */
    suspend fun readDailies(days: Int = 30): List<HealthDaily> = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay()
        val start = end.minusDays(days.toLong())

        val metrics = setOf(
            StepsRecord.COUNT_TOTAL,
            HeartRateRecord.BPM_AVG,
            RestingHeartRateRecord.BPM_AVG,
            SleepSessionRecord.SLEEP_DURATION_TOTAL,
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
            DistanceRecord.DISTANCE_TOTAL
        )

        val groups = runCatching {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
        }.getOrElse { return@withContext emptyList() }

        val now = System.currentTimeMillis()
        groups.mapNotNull { g ->
            val date = g.startTime.toLocalDate().toString()
            val r = g.result
            val steps = r[StepsRecord.COUNT_TOTAL]
            val activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            val totalKcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            val restingHr = r[RestingHeartRateRecord.BPM_AVG]?.toLong()
            val avgHr = r[HeartRateRecord.BPM_AVG]?.toLong()
            val sleep = r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
            val exercise = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()
            val distance = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters

            // Skip empty buckets so a gap in wearing the strap doesn't create fake zero rows.
            if (steps == null && activeKcal == null && sleep == null &&
                restingHr == null && avgHr == null && exercise == null && distance == null
            ) return@mapNotNull null

            HealthDaily(
                localDate = date,
                steps = steps,
                activeKcal = activeKcal,
                totalKcal = totalKcal,
                restingHr = restingHr,
                avgHr = avgHr,
                sleepMinutes = sleep,
                exerciseMinutes = exercise,
                distanceMeters = distance,
                syncedAt = now
            )
        }
    }

    /** Body-composition readings from a smart scale that syncs into Health Connect. */
    suspend fun readWeights(days: Int = 120): List<WearableWeight> = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val end = LocalDateTime.now(zone)
        val start = end.minusDays(days.toLong())
        runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            ).records.map { WearableWeight(it.time.toEpochMilli(), it.weight.inKilograms) }
        }.getOrDefault(emptyList())
    }
}
