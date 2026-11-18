package com.laksh.forge.data.wearables.apple

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.health.HealthOrigins
import com.laksh.forge.data.health.SourceRank
import com.laksh.forge.data.wearables.DayValues
import com.laksh.forge.data.wearables.HealthField
import com.laksh.forge.data.wearables.Layer
import com.laksh.forge.data.wearables.LayerValue
import com.laksh.forge.data.wearables.WearableMerger
import com.laksh.forge.data.wearables.WearableStatusStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.round

class AppleImportException(message: String) : Exception(message)

data class AppleImportSummary(
    val days: Int,
    val firstDate: String?,
    val lastDate: String?,
    val weightsAdded: Int,
    val records: Long,
    val sdnnDays: Int
) {
    fun describe(): String = buildString {
        append("$days days")
        if (firstDate != null && lastDate != null) append(" ($firstDate to $lastDate)")
        append(", $weightsAdded new weigh-ins")
        if (sdnnDays > 0) append(". HRV on $sdnnDays days is Apple's SDNN, kept separate from RMSSD")
        append('.')
    }
}

/**
 * Imports an Apple Health export.zip (or a bare export.xml) picked with the Storage Access
 * Framework. Runs in its own scope so leaving the screen doesn't kill a long import; the
 * screen observes [state] and can [cancel].
 */
object AppleHealthImport {

    sealed interface State {
        data object Idle : State
        /** [fraction] is null when the file size is unknown (some providers don't report it). */
        data class Running(val fraction: Float?, val records: Long, val phase: String) : State
        data class Done(val summary: AppleImportSummary) : State
        data class Failed(val message: String) : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun start(context: Context, uri: Uri) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Running(0f, 0L, "Reading export")
            try {
                _state.value = State.Done(runImport(app, uri))
            } catch (e: CancellationException) {
                _state.value = State.Cancelled
                throw e
            } catch (e: XmlPullParserException) {
                _state.value = State.Failed(
                    "export.xml couldn't be read near line ${e.lineNumber}. The export may be damaged - try exporting again."
                )
            } catch (e: AppleImportException) {
                _state.value = State.Failed(e.message ?: "Import failed.")
            } catch (e: SecurityException) {
                _state.value = State.Failed("Forge no longer has access to that file. Pick it again.")
            } catch (e: IOException) {
                _state.value = State.Failed("Couldn't read the file: ${e.message ?: "I/O error"}")
            } catch (e: Exception) {
                _state.value = State.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears a finished/failed/cancelled result so the card returns to its idle state. */
    fun dismiss() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private suspend fun runImport(context: Context, uri: Uri): AppleImportSummary {
        val resolver = context.contentResolver
        val total = fileSize(resolver, uri)
        val aggregator = AppleDayAggregator()
        val importJob = currentCoroutineContext().job

        val raw = resolver.openInputStream(uri) ?: throw AppleImportException("Couldn't open that file.")
        CountingInputStream(raw).use { counting ->
            val buffered = BufferedInputStream(counting, 256 * 1024)
            val progress: (Long) -> Unit = { n ->
                // Checked every few thousand records so Cancel stops a multi-GB parse promptly.
                importJob.ensureActive()
                val fraction = if (total > 0) (counting.count.toFloat() / total).coerceIn(0f, 1f) else null
                _state.value = State.Running(fraction, n, "Reading export")
            }
            if (looksLikeZip(buffered)) {
                val zip = ZipInputStream(buffered)
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // export_cda.xml is the same data in HL7 CDA form; only export.xml is parsed.
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.equals("export.xml", ignoreCase = true)) {
                        AppleExportParser.parse(AppleExportParser.newParser(zip), aggregator, progress)
                        found = true
                        break
                    }
                }
                if (!found) {
                    throw AppleImportException(
                        "That zip has no export.xml. Pick the export.zip from Health → Export All Health Data."
                    )
                }
            } else {
                AppleExportParser.parse(AppleExportParser.newParser(buffered), aggregator, progress)
            }
        }

        val result = aggregator.result()
        if (result.days.isEmpty() && result.weights.isEmpty()) {
            throw AppleImportException("No supported records were found in this export.")
        }
        importJob.ensureActive()
        _state.value = State.Running(1f, result.recordsSeen, "Saving")

        val layerDays: Map<String, DayValues> = result.days
            .associate { it.date.toString() to it.toLayerValues() }
            .filterValues { it.isNotEmpty() }
        WearableMerger.applyLayer(context, Layer.APPLE, layerDays, layerDays.keys)
        val weightsAdded = importWeights(result)

        val dates = layerDays.keys.sorted()
        val summary = AppleImportSummary(
            days = dates.size,
            firstDate = dates.firstOrNull(),
            lastDate = dates.lastOrNull(),
            weightsAdded = weightsAdded,
            records = result.recordsSeen,
            sdnnDays = result.days.count { it.hrvSdnnMs != null }
        )
        WearableStatusStore.get(context).setAppleImport(System.currentTimeMillis(), summary.describe())
        return summary
    }

    /**
     * One weigh-in per day, and none on a day that already has one (manual, scale or a previous
     * import), so re-importing the same export never duplicates.
     */
    private suspend fun importWeights(result: AppleAggregate): Int {
        val repo = ForgeApp.instance.repository
        val fatByDay = result.days.associate { it.date to it.bodyFatPct }
        var added = 0
        for (w in result.weights) {
            val date = w.date.toString()
            if (repo.weightDao.countOn(date) > 0) continue
            repo.weightDao.insert(
                WeightEntry(
                    timestamp = w.timeMillis,
                    localDate = date,
                    kg = round(w.kg * 100) / 100.0,
                    bodyFatPct = fatByDay[w.date]?.let { round(it * 10) / 10.0 },
                    source = "APPLE_HEALTH"
                )
            )
            added++
        }
        if (added > 0) runCatching { repo.recomputeAdaptiveTdee() }
        return added
    }

    private fun AppleDay.toLayerValues(): DayValues {
        fun lv(v: Double, field: String): LayerValue = LayerValue(
            v = v,
            r = (if (field in watchFields) SourceRank.DEVICE else SourceRank.PHONE).value,
            o = HealthOrigins.APPLE_EXPORT
        )
        return buildMap<HealthField, LayerValue> {
            steps?.let { put(HealthField.STEPS, lv(it, "steps")) }
            activeKcal?.let { put(HealthField.ACTIVE_KCAL, lv(it, "activeKcal")) }
            distanceM?.let { put(HealthField.DISTANCE_M, lv(it, "distance")) }
            avgHr?.let { put(HealthField.AVG_HR, lv(it, "avgHr")) }
            restingHr?.let { put(HealthField.RESTING_HR, lv(it, "restingHr")) }
            hrvSdnnMs?.let { put(HealthField.HRV_SDNN, lv(it, "hrvSdnn")) }
            vo2max?.let { put(HealthField.VO2MAX, lv(it, "vo2max")) }
            spo2Pct?.let { put(HealthField.SPO2, lv(it, "spo2")) }
            respiratoryRate?.let { put(HealthField.RESP_RATE, lv(it, "respiratoryRate")) }
            bodyFatPct?.let { put(HealthField.BODY_FAT, lv(it, "bodyFat")) }
            leanMassKg?.let { put(HealthField.LEAN_MASS, lv(it, "leanMass")) }
            sleepMinutes?.let { put(HealthField.SLEEP_MIN, lv(it, "sleep")) }
            exerciseMinutes?.let { put(HealthField.EXERCISE_MIN, lv(it, "exercise")) }
        }
    }

    private fun looksLikeZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return b0 == 'P'.code && b1 == 'K'.code
    }

    private fun fileSize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** Counts compressed bytes consumed, which is what the file size measures. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count += s
            return s
        }
    }
}
