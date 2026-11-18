package com.laksh.forge.data.wearables

import android.content.Context
import android.util.AtomicFile
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.HealthDaily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToLong

/**
 * Every per-day metric any source can report. Fields marked [inDaily] map onto a health_daily
 * column; the others are kept only in their source layer because they measure something
 * different from the column that looks similar (Apple's SDNN is not RMSSD; WHOOP's skin
 * temperature is absolute, the column is a deviation from baseline).
 */
enum class HealthField(val inDaily: Boolean = true) {
    STEPS, ACTIVE_KCAL, TOTAL_KCAL, RESTING_HR, AVG_HR, SLEEP_MIN, EXERCISE_MIN, DISTANCE_M,
    HRV_RMSSD, VO2MAX, SPO2, RESP_RATE, SKIN_TEMP_DELTA, BODY_FAT, LEAN_MASS, RECOVERY, STRAIN,
    HRV_SDNN(inDaily = false),
    SKIN_TEMP_ABS(inDaily = false);

    companion object {
        private val byName = entries.associateBy { it.name }
        fun of(name: String): HealthField? = byName[name]
    }
}

/**
 * One source's own copy of its data. Each source only ever rewrites its own layer, and
 * health_daily is rebuilt from all layers - which is what stops a Health Connect sync from
 * wiping a WHOOP recovery score, or an Apple import from overwriting a strap's HRV.
 * [priority] breaks ties between two values of equal rank: a vendor's own API is closer to the
 * device than its Health Connect relay, which is closer than a months-old export.
 */
enum class Layer(val priority: Int, val fileName: String) {
    APPLE(1, "apple.json"),
    HEALTH_CONNECT(2, "health_connect.json"),
    WHOOP(3, "whoop.json")
}

/** A value as one source reported it: [v] in the column's unit, [r] the SourceRank, [o] origin id. */
@Serializable
data class LayerValue(val v: Double, val r: Int, val o: String)

@Serializable
internal data class LayerFile(
    val version: Int = 1,
    val days: Map<String, Map<String, LayerValue>> = emptyMap()
)

typealias DayValues = Map<HealthField, LayerValue>

/** Pure merge rules, kept free of Android so they can be unit-tested. */
object HealthMerge {

    /** Highest rank wins; equal ranks fall back to layer priority. */
    fun pick(field: HealthField, layers: Map<Layer, DayValues>): LayerValue? =
        layers.entries
            .mapNotNull { (layer, values) -> values[field]?.let { layer to it } }
            .maxWithOrNull(compareBy<Pair<Layer, LayerValue>>({ it.second.r }, { it.first.priority }))
            ?.second

    /**
     * Rebuild one health_daily row. A field no layer reports keeps whatever the existing row had,
     * so rows written before layering existed (v1 syncs) are never blanked - except the fields
     * in [retracted]: a source used to report them for this day and no longer does (deleted or
     * corrected in Health Connect, rescored by WHOOP), so the stale value must go too.
     */
    fun merge(
        date: String,
        layers: Map<Layer, DayValues>,
        existing: HealthDaily?,
        now: Long,
        retracted: Set<HealthField> = emptySet()
    ): HealthDaily {
        val chosen = HealthField.entries.filter { it.inDaily }.associateWith { pick(it, layers) }
        fun d(f: HealthField): Double? = chosen[f]?.v
        fun l(f: HealthField): Long? = chosen[f]?.v?.roundToLong()
        // A retracted field is written as null rather than falling back to the old value.
        fun <T> or(f: HealthField, chosenValue: T?, old: HealthDaily.() -> T?): T? =
            chosenValue ?: if (f in retracted) null else existing?.old()

        val origins = chosen.values.filterNotNull().map { it.o }.distinct().sorted()
        return HealthDaily(
            localDate = date,
            steps = or(HealthField.STEPS, l(HealthField.STEPS)) { steps },
            activeKcal = or(HealthField.ACTIVE_KCAL, d(HealthField.ACTIVE_KCAL)) { activeKcal },
            totalKcal = or(HealthField.TOTAL_KCAL, d(HealthField.TOTAL_KCAL)) { totalKcal },
            restingHr = or(HealthField.RESTING_HR, l(HealthField.RESTING_HR)) { restingHr },
            avgHr = or(HealthField.AVG_HR, l(HealthField.AVG_HR)) { avgHr },
            sleepMinutes = or(HealthField.SLEEP_MIN, l(HealthField.SLEEP_MIN)) { sleepMinutes },
            exerciseMinutes = or(HealthField.EXERCISE_MIN, l(HealthField.EXERCISE_MIN)) { exerciseMinutes },
            distanceMeters = or(HealthField.DISTANCE_M, d(HealthField.DISTANCE_M)) { distanceMeters },
            syncedAt = now,
            hrvMs = or(HealthField.HRV_RMSSD, d(HealthField.HRV_RMSSD)) { hrvMs },
            vo2max = or(HealthField.VO2MAX, d(HealthField.VO2MAX)) { vo2max },
            spo2Pct = or(HealthField.SPO2, d(HealthField.SPO2)) { spo2Pct },
            respiratoryRate = or(HealthField.RESP_RATE, d(HealthField.RESP_RATE)) { respiratoryRate },
            skinTempDeltaC = or(HealthField.SKIN_TEMP_DELTA, d(HealthField.SKIN_TEMP_DELTA)) { skinTempDeltaC },
            bodyFatPct = or(HealthField.BODY_FAT, d(HealthField.BODY_FAT)) { bodyFatPct },
            leanMassKg = or(HealthField.LEAN_MASS, d(HealthField.LEAN_MASS)) { leanMassKg },
            recoveryScore = or(HealthField.RECOVERY, d(HealthField.RECOVERY)) { recoveryScore },
            strain = or(HealthField.STRAIN, d(HealthField.STRAIN)) { strain },
            sources = if (origins.isNotEmpty()) origins.joinToString(",") else if (retracted.isEmpty()) existing?.sources else null
        )
    }

    /** Drops NaN/infinite values: JSON can't store them and no chart should draw them. */
    fun clean(values: Map<HealthField, LayerValue>): DayValues = values.filterValues { it.v.isFinite() }
}

/**
 * The layer files: one small JSON document per source under filesDir/wearables. They are the
 * only place per-field provenance lives (health_daily has no column for it). Held in memory after
 * first load; every write goes through [mutex] and an AtomicFile so a crash mid-write can never
 * leave a half-written layer behind.
 */
internal object HealthLayerStore {
    private val mutex = Mutex()
    private val cache = HashMap<Layer, MutableMap<String, DayValues>>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(context: Context, layer: Layer): AtomicFile {
        val dir = File(context.filesDir, "wearables").apply { mkdirs() }
        return AtomicFile(File(dir, layer.fileName))
    }

    private fun load(context: Context, layer: Layer): MutableMap<String, DayValues> {
        cache[layer]?.let { return it }
        val parsed = runCatching {
            val f = file(context, layer)
            if (!f.baseFile.exists()) return@runCatching LayerFile()
            json.decodeFromString(LayerFile.serializer(), f.readFully().decodeToString())
        }.getOrDefault(LayerFile())
        val map = HashMap<String, DayValues>(parsed.days.size * 2)
        parsed.days.forEach { (date, fields) ->
            val values = fields.mapNotNull { (k, v) -> HealthField.of(k)?.let { it to v } }.toMap()
            if (values.isNotEmpty()) map[date] = values
        }
        cache[layer] = map
        return map
    }

    private fun save(context: Context, layer: Layer, days: Map<String, DayValues>) {
        val payload = LayerFile(
            days = days.mapValues { (_, v) -> v.mapKeys { it.key.name } }
        )
        val bytes = json.encodeToString(LayerFile.serializer(), payload).encodeToByteArray()
        val f = file(context, layer)
        val out = f.startWrite()
        try {
            out.write(bytes)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    /**
     * Replace this layer's values for every date in [replaceDates] with [days] (a date in
     * [replaceDates] but missing from [days] is cleared - the source no longer reports it).
     * Returns the dates whose layer content actually changed, each with the fields it dropped.
     */
    suspend fun update(
        context: Context,
        layer: Layer,
        days: Map<String, DayValues>,
        replaceDates: Set<String>
    ): Map<String, Set<HealthField>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = load(context, layer)
            // Changed date -> the fields this layer reported before and no longer does.
            val changed = HashMap<String, Set<HealthField>>()
            (replaceDates + days.keys).forEach { date ->
                val next = days[date]?.let { HealthMerge.clean(it) }.orEmpty()
                val prev = current[date]
                if (next.isEmpty()) {
                    if (prev != null) { current.remove(date); changed[date] = prev.keys }
                } else if (prev != next) {
                    current[date] = next
                    changed[date] = prev.orEmpty().keys - next.keys
                }
            }
            if (changed.isNotEmpty()) save(context, layer, current)
            changed
        }
    }

    suspend fun daysFor(context: Context, dates: Collection<String>): Map<String, Map<Layer, DayValues>> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val all = Layer.entries.associateWith { load(context, it) }
                dates.associateWith { date ->
                    Layer.entries.mapNotNull { layer -> all[layer]?.get(date)?.let { layer to it } }.toMap()
                }
            }
        }

    /** One field's values from one layer between two ISO dates (inclusive), sorted by date. */
    suspend fun series(
        context: Context,
        layer: Layer,
        field: HealthField,
        from: String,
        to: String
    ): List<Pair<String, Double>> = mutex.withLock {
        withContext(Dispatchers.IO) {
            load(context, layer).entries
                .filter { it.key in from..to }
                .mapNotNull { (date, values) -> values[field]?.let { date to it.v } }
                .sortedBy { it.first }
        }
    }

    /** Dates covered by a layer, oldest first; used to describe an Apple import. */
    suspend fun dates(context: Context, layer: Layer): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) { load(context, layer).keys.sorted() }
    }
}

/**
 * Applies one source's new layer data and rebuilds the affected health_daily rows through
 * WearableDao. The single [mutex] means an Apple import and a background sync can never
 * interleave their read-merge-write cycles.
 */
internal object WearableMerger {
    private val mutex = Mutex()

    /** Returns the number of health_daily rows written. */
    suspend fun applyLayer(
        context: Context,
        layer: Layer,
        days: Map<String, DayValues>,
        replaceDates: Set<String>
    ): Int = mutex.withLock {
        val affected = HealthLayerStore.update(context, layer, days, replaceDates)
        if (affected.isEmpty()) return@withLock 0

        val dao = ForgeDatabase.get(context).wearableDao()
        val now = System.currentTimeMillis()
        var written = 0
        for (chunk in affected.keys.sorted().chunked(300)) {
            val layered = HealthLayerStore.daysFor(context, chunk)
            val existing = dao.days(chunk).associateBy { it.localDate }
            val rows = chunk.mapNotNull { date ->
                val layers = layered[date].orEmpty().filterValues { it.isNotEmpty() }
                // Nothing reports this day any more and there is no row: leave it absent rather
                // than inserting an empty row that charts would read as a real zero.
                if (layers.isEmpty() && existing[date] == null) null
                else HealthMerge.merge(date, layers, existing[date], now, affected[date].orEmpty())
            }
            if (rows.isNotEmpty()) {
                dao.upsertAll(rows)
                written += rows.size
            }
        }
        written
    }
}
