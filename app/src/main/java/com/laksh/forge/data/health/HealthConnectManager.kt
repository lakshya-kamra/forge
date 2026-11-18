package com.laksh.forge.data.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.wearables.DayValues
import com.laksh.forge.data.wearables.HealthField
import com.laksh.forge.data.wearables.Layer
import com.laksh.forge.data.wearables.LayerValue
import com.laksh.forge.data.wearables.WearableMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, NOT_SUPPORTED }

data class WearableWeight(val timeMillis: Long, val kg: Double)

/** Which optional Health Connect features this phone's Health Connect build supports. */
data class HealthCapabilities(
    val history: Boolean = false,
    val background: Boolean = false,
    val skinTemperature: Boolean = false
)

/**
 * One Health Connect read, already reduced to one value per field per local day.
 * [window] is every date the read covered, so dates that no longer have data can be cleared
 * from the Health Connect layer instead of lingering.
 */
data class HealthSnapshot(
    val days: Map<String, DayValues>,
    val window: Set<String>,
    val origins: Set<String>,
    val errors: List<String>
)

/**
 * Reads wearable data out of Health Connect.
 *
 * Most brands reach Forge this way (docs/research/wearables.md): the vendor app writes to Health
 * Connect, and Forge reads from there. Sharing must be switched on inside each vendor's own app.
 */
class HealthConnectManager(private val context: Context) {

    /** The v1 set. [hasAllPermissions] still means "these are granted", so Settings keeps working. */
    val corePermissions: Set<String> = setOf(
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

    /**
     * Every data type Forge reads. The v2 additions are the record types the research lists as
     * written by at least one brand and readable in connect-client 1.1.0. They have no aggregates,
     * so they're read raw and reduced per day below.
     */
    val permissions: Set<String> = corePermissions + setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class)
    )

    val skinTemperaturePermission: String = HealthPermission.getReadPermission(SkinTemperatureRecord::class)
    val historyPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    val backgroundPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Human names for the permission screen summary, in display order. */
    val permissionLabels: List<Pair<String, String>> = listOf(
        HealthPermission.getReadPermission(StepsRecord::class) to "Steps",
        HealthPermission.getReadPermission(HeartRateRecord::class) to "Heart rate",
        HealthPermission.getReadPermission(RestingHeartRateRecord::class) to "Resting heart rate",
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) to "HRV (RMSSD)",
        HealthPermission.getReadPermission(SleepSessionRecord::class) to "Sleep",
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) to "Active calories",
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) to "Total calories",
        HealthPermission.getReadPermission(ExerciseSessionRecord::class) to "Workouts",
        HealthPermission.getReadPermission(DistanceRecord::class) to "Distance",
        HealthPermission.getReadPermission(Vo2MaxRecord::class) to "VO2 max",
        HealthPermission.getReadPermission(OxygenSaturationRecord::class) to "Blood oxygen",
        HealthPermission.getReadPermission(RespiratoryRateRecord::class) to "Respiratory rate",
        HealthPermission.getReadPermission(WeightRecord::class) to "Weight",
        HealthPermission.getReadPermission(BodyFatRecord::class) to "Body fat",
        HealthPermission.getReadPermission(LeanBodyMassRecord::class) to "Lean mass",
        skinTemperaturePermission to "Skin temperature",
        historyPermission to "Past data",
        backgroundPermission to "Background access"
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
        grantedPermissions().containsAll(corePermissions)
    }

    suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext emptySet()
        runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
    }

    suspend fun capabilities(): HealthCapabilities = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext HealthCapabilities()
        fun has(feature: Int) = runCatching {
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        HealthCapabilities(
            history = has(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY),
            background = has(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
            skinTemperature = has(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
        )
    }

    /**
     * Everything worth asking for on this phone. Feature-gated permissions are only included
     * when the installed Health Connect supports them; asking for an unsupported one fails.
     */
    suspend fun requestablePermissions(): Set<String> {
        val caps = capabilities()
        return buildSet {
            addAll(permissions)
            if (caps.skinTemperature) add(skinTemperaturePermission)
            if (caps.history) add(historyPermission)
            if (caps.background) add(backgroundPermission)
        }
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
     * Legacy entry point (ForgeRepository.syncHealth upserts what this returns). It now goes through
     * the layered merge and returns the merged rows, so that caller can no longer wipe WHOOP or
     * Apple fields by upserting Health Connect-only rows.
     */
    suspend fun readDailies(days: Int = 30): List<HealthDaily> = withContext(Dispatchers.IO) {
        val granted = grantedPermissions()
        if (granted.isEmpty()) return@withContext emptyList()
        val snap = readSnapshot(days, granted)
        if (snap.window.isEmpty()) return@withContext emptyList()
        WearableMerger.applyLayer(context, Layer.HEALTH_CONNECT, snap.days, snap.window)
        ForgeDatabase.get(context).wearableDao().range(snap.window.min(), snap.window.max())
    }

    /**
     * Reads the last [days] local days. Only types in [granted] are touched: asking Health
     * Connect for an ungranted type throws, and one denied type must not sink the whole sync.
     */
    suspend fun readSnapshot(days: Int, granted: Set<String>): HealthSnapshot = withContext(Dispatchers.IO) {
        val client = clientOrNull()
            ?: return@withContext HealthSnapshot(emptyMap(), emptySet(), emptySet(), listOf("Health Connect isn't available."))

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays((days.coerceAtLeast(1) - 1).toLong())
        val startLocal: LocalDateTime = firstDay.atStartOfDay()
        val endLocal: LocalDateTime = today.plusDays(1).atStartOfDay()
        val startInstant: Instant = firstDay.atStartOfDay(zone).toInstant()
        val endInstant: Instant = today.plusDays(1).atStartOfDay(zone).toInstant()

        val acc = HashMap<String, MutableMap<HealthField, LayerValue>>()
        val origins = HashSet<String>()
        val errors = ArrayList<String>()
        val caps = capabilities()

        fun put(date: LocalDate, field: HealthField, value: LayerValue) {
            if (date.isBefore(firstDay) || date.isAfter(today) || !value.v.isFinite()) return
            acc.getOrPut(date.toString()) { HashMap() }[field] = value
        }

        // Aggregated metrics: Health Connect de-duplicates overlapping writers itself (by the
        // user's app priority), so the daily total is trusted as-is. One request per metric so
        // each field carries the origins that actually contributed to it.
        suspend fun <T : Any> daily(
            permission: String,
            metric: AggregateMetric<T>,
            field: HealthField,
            label: String,
            convert: (T) -> Double?
        ) {
            if (permission !in granted) return
            runCatching {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(metric),
                        timeRangeFilter = TimeRangeFilter.between(startLocal, endLocal),
                        timeRangeSlicer = Period.ofDays(1)
                    )
                )
            }.onSuccess { groups ->
                groups.forEach { g ->
                    val raw = g.result[metric] ?: return@forEach
                    val v = convert(raw) ?: return@forEach
                    val pkgs = g.result.dataOrigins.map { it.packageName }.filter { it.isNotBlank() }.sorted()
                    origins += pkgs
                    val infos = pkgs.map { HealthOrigins.describe(context, it) }
                    val device = infos.firstOrNull { it.dedicatedDevice }
                    val rank = if (device != null) SourceRank.DEVICE else SourceRank.PHONE
                    val origin = device?.id ?: infos.firstOrNull()?.id ?: HealthOrigins.HEALTH_CONNECT_PACKAGE
                    put(g.startTime.toLocalDate(), field, LayerValue(v, rank.value, origin))
                }
            }.onFailure { errors += "$label: ${it.message ?: it.javaClass.simpleName}" }
        }

        val steps = HealthPermission.getReadPermission(StepsRecord::class)
        daily(steps, StepsRecord.COUNT_TOTAL, HealthField.STEPS, "Steps") { it.toDouble() }
        daily(
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, HealthField.ACTIVE_KCAL, "Active calories"
        ) { it.inKilocalories }
        daily(
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            TotalCaloriesBurnedRecord.ENERGY_TOTAL, HealthField.TOTAL_KCAL, "Total calories"
        ) { it.inKilocalories }
        daily(
            HealthPermission.getReadPermission(DistanceRecord::class),
            DistanceRecord.DISTANCE_TOTAL, HealthField.DISTANCE_M, "Distance"
        ) { it.inMeters }
        daily(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HeartRateRecord.BPM_AVG, HealthField.AVG_HR, "Heart rate"
        ) { it.toDouble() }
        daily(
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            RestingHeartRateRecord.BPM_AVG, HealthField.RESTING_HR, "Resting heart rate"
        ) { it.toDouble() }
        daily(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, HealthField.EXERCISE_MIN, "Workouts"
        ) { it.toMinutes().toDouble() }
        if (caps.skinTemperature) {
            daily(
                skinTemperaturePermission,
                SkinTemperatureRecord.TEMPERATURE_DELTA_AVG, HealthField.SKIN_TEMP_DELTA, "Skin temperature"
            ) { it.inCelsius }
        }

        // Raw records with no aggregates. Within a day only one origin is used per field (the
        // best-ranked, then the one with most samples), so two devices are never averaged into a
        // number neither of them measured.
        suspend fun <T : Record> samples(
            permission: String,
            type: KClass<T>,
            field: HealthField,
            label: String,
            time: (T) -> Instant,
            offset: (T) -> ZoneOffset?,
            value: (T) -> Double?
        ) {
            if (permission !in granted) return
            runCatching { readAll(client, type, startInstant, endInstant) }
                .onSuccess { records ->
                    val list = records.mapNotNull { r ->
                        val v = value(r)?.takeIf { it.isFinite() } ?: return@mapNotNull null
                        val pkg = r.metadata.dataOrigin.packageName
                        val info = HealthOrigins.describe(context, pkg)
                        Sample(
                            date = localDate(time(r), offset(r), zone),
                            value = v,
                            origin = pkg,
                            rank = HealthOrigins.rank(info, r.metadata.device?.type).value
                        )
                    }
                    origins += list.map { it.origin }
                    reduceDaily(list).forEach { (date, lv) -> put(date, field, lv) }
                }
                .onFailure { errors += "$label: ${it.message ?: it.javaClass.simpleName}" }
        }

        samples(
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HeartRateVariabilityRmssdRecord::class, HealthField.HRV_RMSSD, "HRV",
            { it.time }, { it.zoneOffset }, { it.heartRateVariabilityMillis }
        )
        samples(
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            Vo2MaxRecord::class, HealthField.VO2MAX, "VO2 max",
            { it.time }, { it.zoneOffset }, { it.vo2MillilitersPerMinuteKilogram }
        )
        samples(
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            OxygenSaturationRecord::class, HealthField.SPO2, "Blood oxygen",
            { it.time }, { it.zoneOffset }, { it.percentage.value }
        )
        samples(
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            RespiratoryRateRecord::class, HealthField.RESP_RATE, "Respiratory rate",
            { it.time }, { it.zoneOffset }, { it.rate }
        )
        samples(
            HealthPermission.getReadPermission(BodyFatRecord::class),
            BodyFatRecord::class, HealthField.BODY_FAT, "Body fat",
            { it.time }, { it.zoneOffset }, { it.percentage.value }
        )
        samples(
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            LeanBodyMassRecord::class, HealthField.LEAN_MASS, "Lean mass",
            { it.time }, { it.zoneOffset }, { it.mass.inKilograms }
        )

        // Sleep is read as sessions rather than the SLEEP_DURATION_TOTAL aggregate: a daily bucket
        // splits a 23:00-07:00 night across two days, but a night belongs to the morning you wake.
        val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
        if (sleepPermission in granted) {
            runCatching {
                readAll(client, SleepSessionRecord::class, startInstant.minusSeconds(86_400), endInstant)
            }.onSuccess { sessions ->
                val list = sessions.mapNotNull { s ->
                    val minutes = asleepMinutes(s)
                    if (minutes <= 0.0) return@mapNotNull null
                    val pkg = s.metadata.dataOrigin.packageName
                    Sample(
                        date = localDate(s.endTime, s.endZoneOffset, zone),
                        value = minutes,
                        origin = pkg,
                        rank = HealthOrigins.rank(HealthOrigins.describe(context, pkg), s.metadata.device?.type).value
                    )
                }
                origins += list.map { it.origin }
                reduceDaily(list, sum = true).forEach { (date, lv) ->
                    put(date, HealthField.SLEEP_MIN, lv.copy(v = lv.v.coerceAtMost(24 * 60.0)))
                }
            }.onFailure { errors += "Sleep: ${it.message ?: it.javaClass.simpleName}" }
        }

        val window = generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it.toString() }
            .toSet()
        HealthSnapshot(days = acc, window = window, origins = origins, errors = errors)
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

    // ------------------------------------------------------------------ helpers

    private class Sample(val date: LocalDate, val value: Double, val origin: String, val rank: Int)

    /**
     * One value per day: pick the best origin (rank, then sample count, then name for a stable
     * result), then average - or with [sum], total - that origin's samples.
     */
    private fun reduceDaily(samples: List<Sample>, sum: Boolean = false): Map<LocalDate, LayerValue> =
        samples.groupBy { it.date }.mapValues { (_, day) ->
            val best = day.groupBy { it.origin }.entries.maxWith(
                compareBy<Map.Entry<String, List<Sample>>>(
                    { e -> e.value.maxOf { it.rank } },
                    { e -> e.value.size },
                    { e -> e.key }
                )
            )
            val values = best.value.map { it.value }
            LayerValue(
                v = if (sum) values.sum() else values.average(),
                r = best.value.maxOf { it.rank },
                o = best.key
            )
        }

    private fun localDate(instant: Instant, offset: ZoneOffset?, zone: ZoneId): LocalDate =
        instant.atOffset(offset ?: zone.rules.getOffset(instant)).toLocalDate()

    /** Asleep time from stages when the writer recorded them, otherwise the whole session. */
    private fun asleepMinutes(s: SleepSessionRecord): Double {
        val asleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM
        )
        val millis = if (s.stages.isNotEmpty()) {
            s.stages.filter { it.stage in asleepStages }
                .sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()).coerceAtLeast(0) }
        } else {
            (s.endTime.toEpochMilli() - s.startTime.toEpochMilli()).coerceAtLeast(0)
        }
        return millis / 60_000.0
    }

    /** Follows page tokens; capped so a pathological writer can't stall a sync forever. */
    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = token
                )
            )
            out += response.records
            token = response.pageToken
            pages++
        } while (!token.isNullOrEmpty() && pages < 50)
        return out
    }
}
