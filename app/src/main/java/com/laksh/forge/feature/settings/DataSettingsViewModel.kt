package com.laksh.forge.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.backup.BackupManager
import com.laksh.forge.data.backup.CsvDataset
import com.laksh.forge.data.backup.ForgeBackup
import com.laksh.forge.data.backup.RestorePreview
import com.laksh.forge.data.backup.StorageReport
import com.laksh.forge.data.backup.StorageUsage
import com.laksh.forge.data.backup.WorkoutCsvImport
import com.laksh.forge.data.backup.WorkoutCsvResult
import com.laksh.forge.data.backup.WorkoutImportPreview
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.data.reminders.ReminderPrefs
import com.laksh.forge.data.reminders.ReminderPrefsStore
import com.laksh.forge.data.reminders.ReminderScheduler
import com.laksh.forge.data.reminders.ReminderType
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.feature.widgets.ForgeWidgets
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZoneId

/** What the screen is busy doing; drives the progress card and disables the other actions. */
enum class DataTask(val label: String) {
    EXPORTING("Writing your export..."),
    READING("Reading the file..."),
    RESTORING("Merging the backup..."),
    IMPORTING("Importing workouts..."),
    DELETING("Deleting meal photos...")
}

data class DataUiState(
    val settings: ForgeSettings = ForgeSettings(),
    val reminders: ReminderPrefs = ReminderPrefs(),
    val storage: StorageReport? = null,
    val storageLoading: Boolean = true,
    val storageError: String? = null,
    val task: DataTask? = null,
    val message: String? = null,
    val error: String? = null,
    val restorePreview: RestorePreview? = null,
    val workoutPreview: WorkoutImportPreview? = null,
    /** For Strong files that don't name their weight unit: whether we're reading them as lb. */
    val workoutWeightsInLb: Boolean = false
) {
    val busy: Boolean get() = task != null
}

/** Transient, screen-only state merged with the persisted settings in [DataSettingsViewModel.state]. */
private data class Transient(
    val storage: StorageReport? = null,
    val storageLoading: Boolean = true,
    val storageError: String? = null,
    val task: DataTask? = null,
    val message: String? = null,
    val error: String? = null,
    val restorePreview: RestorePreview? = null,
    val workoutPreview: WorkoutImportPreview? = null,
    val workoutWeightsInLb: Boolean = false
)

class DataSettingsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val app = ForgeApp.instance
    private val transient = MutableStateFlow(Transient())

    // Parsed files are held here between "preview" and "confirm" - never in UI state, where
    // they'd be copied into every emission.
    private var pendingBackup: ForgeBackup? = null
    private var pendingCsvText: String? = null
    private var pendingCsv: WorkoutCsvResult? = null

    val state: StateFlow<DataUiState> = combine(
        repo.settingsStore.settings,
        ReminderPrefsStore.prefs(app),
        transient
    ) { settings, reminders, t ->
        DataUiState(
            settings = settings,
            reminders = reminders,
            storage = t.storage,
            storageLoading = t.storageLoading,
            storageError = t.storageError,
            task = t.task,
            message = t.message,
            error = t.error,
            restorePreview = t.restorePreview,
            workoutPreview = t.workoutPreview,
            workoutWeightsInLb = t.workoutWeightsInLb
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataUiState())

    init {
        refreshStorage()
    }

    /** Runs [block] as the screen's single busy task, turning any failure into a shown message. */
    private fun runTask(task: DataTask, block: suspend () -> Unit) {
        if (transient.value.task != null) return
        viewModelScope.launch {
            transient.update { it.copy(task = task, error = null, message = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transient.update { it.copy(error = e.message ?: "Something went wrong.") }
            } catch (e: OutOfMemoryError) {
                // A huge wrong file picked for import; the buffers are unreachable again here.
                transient.update { it.copy(error = "That file is too large to open on this phone.") }
            } finally {
                transient.update { it.copy(task = null) }
            }
        }
    }

    fun dismissMessage() = transient.update { it.copy(message = null, error = null) }

    fun showError(message: String) = transient.update { it.copy(error = message) }

    // ================================================================ export

    fun exportJson(uri: Uri) = runTask(DataTask.EXPORTING) {
        val n = BackupManager.exportJson(app, uri)
        transient.update { it.copy(message = "Backup saved: ${"%,d".format(n)} records.") }
    }

    fun exportCsv(uri: Uri, dataset: CsvDataset) = runTask(DataTask.EXPORTING) {
        val n = BackupManager.exportCsv(app, uri, dataset)
        transient.update { it.copy(message = "${dataset.label} exported: ${"%,d".format(n)} rows.") }
    }

    // ================================================================ restore

    fun startRestore(uri: Uri) = runTask(DataTask.READING) {
        val backup = BackupManager.readBackup(app, uri)
        val preview = BackupManager.previewRestore(app, backup)
        pendingBackup = backup
        transient.update { it.copy(restorePreview = preview) }
    }

    fun cancelRestore() {
        pendingBackup = null
        transient.update { it.copy(restorePreview = null) }
    }

    fun confirmRestore() {
        val backup = pendingBackup ?: return
        transient.update { it.copy(restorePreview = null) }
        runTask(DataTask.RESTORING) {
            val added = BackupManager.applyRestore(app, backup)
            pendingBackup = null
            afterDataChanged(weightsOrFood = added.food > 0 || added.weights > 0)
            transient.update {
                it.copy(
                    message = if (added.total == 0) "Nothing new - everything in that backup was already here."
                    else "Restored ${"%,d".format(added.total)} records."
                )
            }
        }
    }

    // ================================================================ Hevy / Strong

    private fun resolveExercise(name: String): String? = ExerciseCatalog.resolve(name)?.id

    fun startWorkoutImport(uri: Uri) = runTask(DataTask.READING) {
        val text = BackupManager.readWorkoutCsv(app, uri)
        val assumeLb = repo.settings().weightUnit == WeightUnit.LB
        pendingCsvText = text
        previewCsv(text, assumeLb)
    }

    /** Re-read a unit-less Strong file as lb or kg; cheap, so it just re-parses. */
    fun setWorkoutWeightsInLb(lb: Boolean) {
        val text = pendingCsvText ?: return
        runTask(DataTask.READING) { previewCsv(text, lb) }
    }

    private suspend fun previewCsv(text: String, weightsInLb: Boolean) {
        val result = withContext(Dispatchers.Default) {
            WorkoutCsvImport.parse(text, ZoneId.systemDefault(), weightsInLb)
        }
        if (result.workouts.isEmpty()) {
            pendingCsv = null
            pendingCsvText = null
            throw IllegalStateException(
                "No loggable sets found in that ${result.source.label} export" +
                    if (result.skippedRows > 0) " (${result.skippedRows} rows were timed, distance-only or empty)." else "."
            )
        }
        // Catalog resolution may read assets on first use, so keep it off the main thread.
        val preview = BackupManager.previewWorkouts(app, result, ::resolveExercise)
        pendingCsv = result
        transient.update { it.copy(workoutPreview = preview, workoutWeightsInLb = weightsInLb) }
    }

    fun cancelWorkoutImport() {
        pendingCsv = null
        pendingCsvText = null
        transient.update { it.copy(workoutPreview = null) }
    }

    fun confirmWorkoutImport() {
        val result = pendingCsv ?: return
        transient.update { it.copy(workoutPreview = null) }
        runTask(DataTask.IMPORTING) {
            val (workouts, sets) = BackupManager.applyWorkouts(app, result, ::resolveExercise)
            pendingCsv = null
            pendingCsvText = null
            afterDataChanged(weightsOrFood = false)
            val source = result.source.label
            transient.update {
                it.copy(
                    message = if (workouts == 0) "Nothing new - those $source workouts are already in Forge."
                    else "Imported $workouts ${if (workouts == 1) "workout" else "workouts"} ($sets sets) from $source."
                )
            }
        }
    }

    // ================================================================ reminders

    fun setReminderEnabled(type: ReminderType, on: Boolean) = viewModelScope.launch {
        try {
            when (type) {
                ReminderType.WEIGH_IN -> repo.settingsStore.setWeighInReminder(on)
                ReminderType.MEAL -> repo.settingsStore.setMealReminder(on)
                ReminderType.WORKOUT -> repo.settingsStore.setWorkoutReminder(on)
            }
            // Scheduling also happens from ReminderScheduler.start(); syncing here as well means
            // the toggle takes effect immediately even before that wiring exists.
            ReminderScheduler.sync(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update { it.copy(error = e.message ?: "Couldn't update that reminder.") }
        }
    }

    fun setReminderTime(type: ReminderType, minutesOfDay: Int) = viewModelScope.launch {
        try {
            ReminderPrefsStore.setTime(app, type, minutesOfDay)
            ReminderScheduler.sync(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update { it.copy(error = e.message ?: "Couldn't change the reminder time.") }
        }
    }

    fun toggleTrainingDay(day: DayOfWeek) = viewModelScope.launch {
        try {
            val current = ReminderPrefsStore.current(app).trainingDays
            ReminderPrefsStore.setTrainingDays(app, if (day in current) current - day else current + day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update { it.copy(error = e.message ?: "Couldn't save training days.") }
        }
    }

    // ================================================================ storage

    fun refreshStorage() = viewModelScope.launch {
        transient.update { it.copy(storageLoading = true, storageError = null) }
        try {
            val report = StorageUsage.measure(app)
            transient.update { it.copy(storage = report, storageLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update {
                it.copy(storageLoading = false, storageError = e.message ?: "Couldn't measure storage.")
            }
        }
    }

    fun deleteMealPhotos() = runTask(DataTask.DELETING) {
        val n = StorageUsage.deleteMealPhotos(app)
        transient.update {
            it.copy(message = if (n == 0) "There were no meal photos to delete." else "Deleted $n meal ${if (n == 1) "photo" else "photos"}.")
        }
        refreshStorage()
    }

    private suspend fun afterDataChanged(weightsOrFood: Boolean) {
        if (weightsOrFood) {
            // Imported weigh-ins and intake change the learned maintenance figure.
            try {
                repo.recomputeAdaptiveTdee()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort; it is recomputed again on the next launch.
            }
        }
        ForgeWidgets.requestUpdate(app)
        refreshStorage()
    }

    companion object {
        val Factory = forgeFactory { DataSettingsViewModel(it) }
    }
}
