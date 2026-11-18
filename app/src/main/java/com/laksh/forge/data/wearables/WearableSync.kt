package com.laksh.forge.data.wearables

import android.content.Context
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.health.HealthAvailability
import com.laksh.forge.data.health.HealthOrigins
import com.laksh.forge.data.wearables.whoop.WhoopAuth
import com.laksh.forge.data.wearables.whoop.WhoopSync
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

/** Result of one sync: distinct days written, the sources that contributed, and readable errors. */
data class SyncReport(val days: Int, val sources: List<String>, val errors: List<String>)

/**
 * The one entry point for refreshing wearable data (Health Connect, then WHOOP if connected).
 * The shell, Today and Settings call [syncAll]; the 6-hourly WorkManager job calls
 * [syncInBackground]. Overlapping calls share the sync already in flight instead of racing it.
 */
object WearableSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var inFlight: Deferred<SyncReport>? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Days re-read when the last sync is recent: vendors write late (a night's sleep, a scale). */
    private const val OVERLAP_DAYS = 3L
    private const val MIN_WINDOW_DAYS = 7
    /** A first sync reaches back this far; past ~30 days Health Connect also needs the history permission. */
    private const val FIRST_SYNC_DAYS = 90

    suspend fun syncAll(): SyncReport = launchOrJoin(background = false)

    /** Background variant: Health Connect reads are skipped unless background access is granted. */
    suspend fun syncInBackground(): SyncReport = launchOrJoin(background = true)

    private suspend fun launchOrJoin(background: Boolean): SyncReport {
        val job = lock.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async { performSync(background) }.also { inFlight = it }
        }
        return job.await()
    }

    private suspend fun performSync(background: Boolean): SyncReport {
        _running.value = true
        try {
            val context: Context = ForgeApp.instance
            val repo = ForgeApp.instance.repository
            val settings = repo.settingsStore.settings.first()
            val status = WearableStatusStore.get(context)
            val now = System.currentTimeMillis()

            val errors = ArrayList<String>()
            val sources = LinkedHashSet<String>()
            val touched = HashSet<String>()
            val originsSeen = HashSet<String>()
            var healthConnectOk = false
            var configured = false

            // ---------------------------------------------------------- Health Connect
            val hc = repo.health
            when (hc.availability()) {
                HealthAvailability.AVAILABLE -> {
                    val granted = hc.grantedPermissions()
                    val readable = granted.intersect(hc.permissions + hc.skinTemperaturePermission)
                    val caps = hc.capabilities()
                    when {
                        readable.isEmpty() -> if (settings.healthConnectEnabled) {
                            errors += "Health Connect: no data types are allowed. Open Wearables to grant access."
                        }
                        background && caps.background && hc.backgroundPermission !in granted -> {
                            configured = true
                            errors += "Health Connect: background access isn't allowed, so it only syncs while Forge is open."
                        }
                        else -> {
                            configured = true
                            val days = windowDays(settings.lastHealthSync, now)
                            val snap = hc.readSnapshot(days, granted)
                            errors += snap.errors.map { "Health Connect - $it" }
                            WearableMerger.applyLayer(context, Layer.HEALTH_CONNECT, snap.days, snap.window)
                            touched += snap.days.keys
                            originsSeen += snap.origins
                            snap.origins
                                .map { HealthOrigins.describe(context, it) }
                                .filter { it.id != HealthOrigins.HEALTH_CONNECT_PACKAGE }
                                .forEach { sources += it.label }
                            importHealthConnectWeights(days, snap.days)
                            healthConnectOk = true
                        }
                    }
                }
                HealthAvailability.NOT_INSTALLED -> if (settings.healthConnectEnabled) {
                    errors += "Health Connect needs installing or updating."
                }
                HealthAvailability.NOT_SUPPORTED -> Unit
            }

            // ---------------------------------------------------------- WHOOP
            if (WhoopAuth.isConnected()) {
                configured = true
                try {
                    val zone = ZoneId.systemDefault()
                    val from = LocalDate.now(zone).minusDays((windowDays(settings.lastWhoopSync, now) - 1).toLong())
                    val result = WhoopSync.fetch(from, zone)
                    WearableMerger.applyLayer(context, Layer.WHOOP, result.days, result.window)
                    touched += result.days.keys
                    if (result.days.isNotEmpty()) sources += HealthOrigins.describe(context, HealthOrigins.WHOOP_API).label
                    repo.settingsStore.setLastWhoopSync(now)
                } catch (e: Exception) {
                    errors += "WHOOP: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            if (healthConnectOk) repo.settingsStore.setLastHealthSync(now)
            val report = SyncReport(days = touched.size, sources = sources.toList(), errors = errors)
            status.recordSync(now, report.days, report.sources, report.errors, originsSeen)

            // Scheduling is idempotent (KEEP), so doing it after every foreground sync guarantees the
            // periodic job exists as soon as any source is set up.
            if (configured && !background) WearableSyncWorker.schedule(context)
            return report
        } finally {
            _running.value = false
        }
    }

    /** First sync reaches back [FIRST_SYNC_DAYS]; later ones re-read since the last sync plus overlap. */
    private fun windowDays(lastSync: Long, now: Long): Int {
        if (lastSync <= 0L) return FIRST_SYNC_DAYS
        val since = ((now - lastSync) / 86_400_000L) + OVERLAP_DAYS
        return since.coerceIn(MIN_WINDOW_DAYS.toLong(), FIRST_SYNC_DAYS.toLong()).toInt()
    }

    /**
     * Scale weigh-ins from Health Connect, keeping v1's rule: the day's first reading, and nothing
     * on a day that already has an entry. Body fat from the same day rides along.
     */
    private suspend fun importHealthConnectWeights(days: Int, layerDays: Map<String, DayValues>) {
        val repo = ForgeApp.instance.repository
        val readings = repo.health.readWeights(days)
            .groupBy { Dates.dayOf(it.timeMillis) }
            .mapValues { (_, list) -> list.minBy { it.timeMillis } }
        var imported = 0
        for ((date, w) in readings.toSortedMap()) {
            if (repo.weightDao.countOn(date) > 0) continue
            repo.weightDao.insert(
                WeightEntry(
                    timestamp = w.timeMillis,
                    localDate = date,
                    kg = round(w.kg * 100) / 100.0,
                    bodyFatPct = layerDays[date]?.get(HealthField.BODY_FAT)?.v?.let { round(it * 10) / 10.0 },
                    source = "HEALTH_CONNECT"
                )
            )
            imported++
        }
        if (imported > 0) runCatching { repo.recomputeAdaptiveTdee() }
    }
}
