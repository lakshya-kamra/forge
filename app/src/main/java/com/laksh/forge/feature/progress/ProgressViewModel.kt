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
import androidx.room.withTransaction
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.domain.plan.NavyBodyFat
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.feature.onboarding.attempt
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * Photos Forge v2.0 stored before it stopped keeping them. [rows] are database entries
 * (their files may or may not still exist); [orphanFiles] are files no row points to.
 */
data class LegacyPhotos(val rows: List<ProgressPhoto> = emptyList(), val orphanFiles: List<File> = emptyList()) {
    val count: Int get() = rows.size + orphanFiles.size
    val isEmpty: Boolean get() = count == 0
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
    val legacy: LegacyPhotos = LegacyPhotos(),
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

    /** Set after a successful export, so the legacy card can move on to "delete from Forge". */
    val legacyExported = MutableStateFlow(false)

    private val orphanFiles = MutableStateFlow<List<File>>(emptyList())

    val state: StateFlow<ProgressUiState> = combine(
        PlanStore.bodyDao.observeMeasurements(),
        PlanStore.bodyDao.observePhotos(),
        orphanFiles,
        repo.settingsStore.settings
    ) { measurements, photos, orphans, settings ->
        ProgressUiState(
            loading = false,
            settings = settings,
            measurements = measurements,
            legacy = LegacyPhotos(photos, orphans.filter { f -> photos.none { File(it.path) == f } }),
            navy = navySeries(measurements, settings)
        )
    }
        .catch { e -> emit(ProgressUiState(loading = false, error = e.message ?: "Couldn't load your progress.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    init {
        refreshOrphans()
        // v2.0 onboarding could leave staged body photos in the cache; nobody chose to keep those.
        viewModelScope.launch { runCatching { ProgressPhotoFiles.clearStaging(ForgeApp.instance) } }
    }

    private fun refreshOrphans() = viewModelScope.launch {
        orphanFiles.value = runCatching { ProgressPhotoFiles.legacyFiles(ForgeApp.instance) }.getOrDefault(emptyList())
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

    fun setPhotoReminder(on: Boolean) = viewModelScope.launch {
        attempt { repo.settingsStore.setProgressPhotoReminder(on) }
            .onSuccess { message.value = if (on) "Monthly photo reminder on - first Sunday of each month" else "Monthly photo reminder off" }
            .onFailure { e -> message.value = e.message ?: "Couldn't change the reminder." }
    }

    /**
     * A pose-guide capture goes straight into the person's gallery (Android 10+), then the
     * temporary capture file is deleted - success or not - so Forge keeps nothing.
     */
    fun saveCaptureToGallery(pose: Pose, uri: Uri) = viewModelScope.launch {
        busy.value = true
        try {
            attempt {
                val path = uri.path ?: throw IllegalStateException("The camera didn't return a photo.")
                if (GalleryPhotos.canSaveDirectly) {
                    GalleryPhotos.saveToGallery(
                        ForgeApp.instance,
                        File(path),
                        GalleryPhotos.displayName(LocalDate.now(), pose.label),
                        System.currentTimeMillis()
                    )
                } else {
                    throw IllegalStateException("Saving to the gallery needs Android 10 or newer - use your camera app instead.")
                }
            }.onSuccess { message.value = "${pose.label} photo saved to ${GalleryPhotos.albumLabel}" }
                .onFailure { e -> message.value = e.message ?: "That photo couldn't be saved to your gallery." }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { ImageUtils.discardCapture(ForgeApp.instance, uri) }
            busy.value = false
        }
    }

    /**
     * Copy every v2.0 photo into the gallery album, dated by when it was taken. [tree] is the
     * folder picked on Android 8-9; null uses MediaStore. Nothing is deleted here.
     */
    fun exportLegacy(tree: Uri? = null) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        val legacy = state.value.legacy
        var saved = 0
        var failed = 0
        val zone = ZoneId.systemDefault()
        suspend fun save(file: File, date: LocalDate, label: String, takenAt: Long) {
            attempt {
                val name = GalleryPhotos.displayName(date, label)
                when {
                    tree != null -> GalleryPhotos.saveToFolder(ForgeApp.instance, tree, file, name, takenAt)
                    GalleryPhotos.canSaveDirectly -> GalleryPhotos.saveToGallery(ForgeApp.instance, file, name, takenAt)
                    else -> throw IllegalStateException("Choose a folder to save to first.")
                }
            }.onSuccess { saved++ }.onFailure { failed++ }
        }
        legacy.rows.forEach { row ->
            if (!ProgressPhotoFiles.exists(row.path)) return@forEach
            val date = runCatching { LocalDate.parse(row.localDate) }
                .getOrElse { Instant.ofEpochMilli(row.timestamp).atZone(zone).toLocalDate() }
            save(File(row.path), date, Pose.from(row.pose)?.label ?: "photo", row.timestamp)
        }
        legacy.orphanFiles.forEach { f ->
            save(f, Instant.ofEpochMilli(f.lastModified()).atZone(zone).toLocalDate(), "photo", f.lastModified())
        }
        message.value = when {
            saved == 0 && failed == 0 -> "There were no photo files left to save."
            failed == 0 -> "Saved $saved photo${if (saved == 1) "" else "s"} to ${if (tree != null) "the folder you chose" else GalleryPhotos.albumLabel}"
            else -> "Saved $saved, but $failed couldn't be saved. Your photos are still in Forge - try again."
        }
        if (failed == 0) legacyExported.value = true
        busy.value = false
    }

    /** Remove every v2.0 photo row and file. Only reachable from an explicit confirmation. */
    fun deleteLegacy() = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        attempt {
            val rows = PlanStore.bodyDao.observePhotos().first()
            ForgeDatabase.get(ForgeApp.instance).withTransaction { rows.forEach { PlanStore.bodyDao.deletePhoto(it) } }
            withContext(Dispatchers.IO) { rows.forEach { ImageUtils.deletePhoto(it.path) } }
            ProgressPhotoFiles.deleteAllLegacy(ForgeApp.instance)
            rows.size
        }.onSuccess { n ->
            message.value = if (n > 0) "Removed $n photo${if (n == 1) "" else "s"} from Forge" else "Removed the photos from Forge"
            legacyExported.value = false
        }.onFailure { e -> message.value = e.message ?: "Couldn't remove the photos." }
        refreshOrphans().join()
        busy.value = false
    }

    fun consumeMessage() { message.value = null }

    companion object {
        val Factory = forgeFactory { ProgressViewModel(it) }
    }
}

/** Waist guide label depends on sex: men track at the navel, women at the narrowest point. */
internal fun waistLabel(sex: Sex): String = if (sex == Sex.FEMALE) "Waist (narrowest)" else "Waist (navel)"
