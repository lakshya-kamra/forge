package com.laksh.forge.feature.progress

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.db.ProgressPhoto
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.domain.plan.NavyBodyFat
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.feature.onboarding.attempt
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Every tape site Forge tracks, with how to read it off a row. */
enum class MeasureSite(val label: String, val read: (BodyMeasurement) -> Double?) {
    WAIST("Waist", { it.waistCm }),
    NECK("Neck", { it.neckCm }),
    HIPS("Hips", { it.hipsCm }),
    CHEST("Chest", { it.chestCm }),
    ARM("Arm", { it.armCm }),
    THIGH("Thigh", { it.thighCm }),
    CALF("Calf", { it.calfCm })
}

/** A day's photos keyed by pose, for the timeline and the compare picker. */
data class PhotoDay(val date: String, val byPose: Map<Pose, ProgressPhoto>, val other: List<ProgressPhoto>) {
    val all: List<ProgressPhoto> get() = byPose.values.toList() + other
}

data class NavyPoint(val timestamp: Long, val date: String, val pct: Double)

/** Text-field state for the add/edit sheet. */
data class MeasurementDraft(
    val id: Long = 0,
    val timestamp: Long = 0,
    val localDate: String = "",
    val waist: String = "",
    val neck: String = "",
    val hips: String = "",
    val chest: String = "",
    val arm: String = "",
    val thigh: String = "",
    val calf: String = "",
    val note: String = ""
) {
    val fields: List<String> get() = listOf(waist, neck, hips, chest, arm, thigh, calf)

    /** Plausible tape values only; blank means "not measured this time". */
    fun invalid(): List<String> = listOf(
        "waist" to waist, "neck" to neck, "hips" to hips, "chest" to chest,
        "arm" to arm, "thigh" to thigh, "calf" to calf
    ).filter { (_, v) -> v.isNotBlank() && v.toDoubleOrNull()?.let { it in 15.0..250.0 } != true }.map { it.first }

    val isEmpty: Boolean get() = fields.all { it.isBlank() }

    companion object {
        private fun fmt(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()

        fun from(m: BodyMeasurement) = MeasurementDraft(
            id = m.id, timestamp = m.timestamp, localDate = m.localDate,
            waist = fmt(m.waistCm), neck = fmt(m.neckCm), hips = fmt(m.hipsCm), chest = fmt(m.chestCm),
            arm = fmt(m.armCm), thigh = fmt(m.thighCm), calf = fmt(m.calfCm), note = m.note.orEmpty()
        )
    }
}

data class ProgressUiState(
    val loading: Boolean = true,
    val settings: ForgeSettings = ForgeSettings(),
    val measurements: List<BodyMeasurement> = emptyList(),
    val photoDays: List<PhotoDay> = emptyList(),
    val navy: List<NavyPoint> = emptyList(),
    val error: String? = null
) {
    /** Body-fat figures are never shown under 18, or when the person chose to hide numbers. */
    val showBodyFat: Boolean get() = !settings.isMinor && !settings.hideNumbers && !settings.edScreenPositive
    val sitesWithData: List<MeasureSite>
        get() = MeasureSite.entries.filter { site -> measurements.any { site.read(it) != null } }
}

class ProgressViewModel(private val repo: ForgeRepository) : ViewModel() {

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ProgressUiState> = combine(
        PlanStore.bodyDao.observeMeasurements(),
        PlanStore.bodyDao.observePhotos(),
        repo.settingsStore.settings
    ) { measurements, photos, settings ->
        ProgressUiState(
            loading = false,
            settings = settings,
            measurements = measurements,
            photoDays = group(photos),
            navy = navySeries(measurements, settings)
        )
    }
        .catch { e -> emit(ProgressUiState(loading = false, error = e.message ?: "Couldn't load your progress.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    private fun group(photos: List<ProgressPhoto>): List<PhotoDay> =
        photos.groupBy { it.localDate }
            .toSortedMap(compareByDescending { it })
            .map { (date, list) ->
                val byPose = mutableMapOf<Pose, ProgressPhoto>()
                val other = mutableListOf<ProgressPhoto>()
                // Newest first, so a retake on the same day wins the pose slot.
                list.sortedByDescending { it.timestamp }.forEach { p ->
                    val pose = Pose.from(p.pose)
                    if (pose != null && pose !in byPose) byPose[pose] = p else other += p
                }
                PhotoDay(date, byPose, other)
            }

    /**
     * Navy estimate per measurement day using today's height and sex, bias-corrected the same
     * way as onboarding. The trend is the useful part; the absolute value carries +/-5 points.
     */
    private fun navySeries(ms: List<BodyMeasurement>, s: ForgeSettings): List<NavyPoint> {
        if (s.isMinor) return emptyList()
        return ms.mapNotNull { m ->
            val raw = NavyBodyFat.estimate(s.sex, m.waistCm, m.neckCm, m.hipsCm, s.heightCm) ?: return@mapNotNull null
            NavyPoint(m.timestamp, m.localDate, NavyBodyFat.range(s.sex, raw).mid)
        }.sortedBy { it.timestamp }
    }

    fun saveMeasurement(d: MeasurementDraft, onSaved: () -> Unit) {
        if (d.isEmpty || d.invalid().isNotEmpty()) return
        viewModelScope.launch {
            busy.value = true
            attempt {
                val s = repo.settings()
                val now = System.currentTimeMillis()
                fun v(x: String) = x.toDoubleOrNull()
                val waist = v(d.waist)
                val neck = v(d.neck)
                val hips = v(d.hips)
                val raw = if (s.isMinor) null else NavyBodyFat.estimate(s.sex, waist, neck, hips, s.heightCm)
                val row = BodyMeasurement(
                    id = d.id,
                    timestamp = if (d.id == 0L) now else d.timestamp,
                    localDate = if (d.id == 0L) Dates.today() else d.localDate,
                    waistCm = waist, neckCm = neck, hipsCm = hips,
                    chestCm = v(d.chest), armCm = v(d.arm), thighCm = v(d.thigh), calfCm = v(d.calf),
                    bodyFatPct = raw?.let { (it * 10).roundToInt() / 10.0 },
                    bodyFatMethod = if (raw != null) "NAVY" else null,
                    note = d.note.trim().ifBlank { null }
                )
                if (d.id == 0L) PlanStore.bodyDao.insertMeasurement(row) else PlanStore.bodyDao.updateMeasurement(row)
            }.onSuccess {
                message.value = if (d.id == 0L) "Measurements saved" else "Measurements updated"
                onSaved()
            }.onFailure { e -> message.value = e.message ?: "Saving failed." }
            busy.value = false
        }
    }

    fun deleteMeasurement(m: BodyMeasurement) = viewModelScope.launch {
        attempt { PlanStore.bodyDao.deleteMeasurement(m) }
            .onSuccess { message.value = "Measurement deleted" }
            .onFailure { e -> message.value = e.message ?: "Couldn't delete." }
    }

    fun addPhoto(pose: Pose, uri: Uri) = viewModelScope.launch {
        busy.value = true
        attempt {
            val path = ProgressPhotoFiles.importPhoto(ForgeApp.instance, uri)
            val now = System.currentTimeMillis()
            val weight = repo.weightDao.observeLatest().first()?.kg
            PlanStore.bodyDao.insertPhoto(
                ProgressPhoto(timestamp = now, localDate = Dates.today(), path = path, pose = pose.name, weightKg = weight)
            )
        }.onFailure { e -> message.value = e.message ?: "That photo couldn't be saved." }
        busy.value = false
    }

    fun deletePhoto(p: ProgressPhoto) = viewModelScope.launch {
        attempt {
            PlanStore.bodyDao.deletePhoto(p)
            ProgressPhotoFiles.delete(p.path)
        }.onSuccess { message.value = "Photo deleted" }
            .onFailure { e -> message.value = e.message ?: "Couldn't delete the photo." }
    }

    fun consumeMessage() { message.value = null }

    companion object {
        val Factory = forgeFactory { ProgressViewModel(it) }
    }
}

/** Waist guide label depends on sex: men track at the navel, women at the narrowest point. */
internal fun waistLabel(sex: Sex): String = if (sex == Sex.FEMALE) "Waist (narrowest)" else "Waist (navel)"
