package com.laksh.forge.feature.settings

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.backup.CsvDataset
import com.laksh.forge.data.backup.RestorePreview
import com.laksh.forge.data.backup.StorageReport
import com.laksh.forge.data.backup.StorageUsage
import com.laksh.forge.data.backup.WorkoutImportPreview
import com.laksh.forge.data.reminders.ReminderNotifications
import com.laksh.forge.data.reminders.ProgressPhotoReminder
import com.laksh.forge.data.reminders.ReminderType
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    vm: DataSettingsViewModel = viewModel(factory = DataSettingsViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.background

    // ------------------------------------------------------------ SAF launchers
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportJson(uri) }

    var pendingCsv by rememberSaveable { mutableStateOf<CsvDataset?>(null) }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val dataset = pendingCsv
        pendingCsv = null
        if (uri != null && dataset != null) vm.exportCsv(uri, dataset)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startRestore(uri) }

    val workoutCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startWorkoutImport(uri) }

    // ------------------------------------------------------------ notification permission
    var permissionGranted by remember { mutableStateOf(ReminderNotifications.permissionGranted(context)) }
    var canPost by remember { mutableStateOf(ReminderNotifications.canPost(context)) }
    // The person may flip notifications in system settings and come back; re-read on resume.
    LifecycleResumeEffect(Unit) {
        permissionGranted = ReminderNotifications.permissionGranted(context)
        canPost = ReminderNotifications.canPost(context)
        onPauseOrDispose { }
    }
    var pendingReminder by rememberSaveable { mutableStateOf<ReminderType?>(null) }
    // The monthly photo reminder isn't a ReminderType (see ProgressPhotoReminder), so it has
    // its own pending flag for the permission round trip.
    var pendingPhotoReminder by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val type = pendingReminder
        val photo = pendingPhotoReminder
        pendingReminder = null
        pendingPhotoReminder = false
        permissionGranted = granted
        canPost = ReminderNotifications.canPost(context)
        if (granted && type != null) {
            vm.setReminderEnabled(type, true)
        } else if (granted && photo) {
            vm.setProgressPhotoReminder(true)
        } else if (!granted) {
            vm.showError("Reminders need notification permission. You can allow it for Forge in system settings.")
        }
    }
    val onToggleReminder: (ReminderType, Boolean) -> Unit = { type, on ->
        if (state.settings.haptics) {
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        }
        if (on && ReminderNotifications.needsRuntimePermission() && !permissionGranted) {
            pendingReminder = type
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setReminderEnabled(type, on)
        }
    }
    val onTogglePhotoReminder: (Boolean) -> Unit = { on ->
        if (state.settings.haptics) {
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        }
        if (on && ReminderNotifications.needsRuntimePermission() && !permissionGranted) {
            pendingPhotoReminder = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setProgressPhotoReminder(on)
        }
    }

    // ------------------------------------------------------------ dialogs
    var editingTime by rememberSaveable { mutableStateOf<ReminderType?>(null) }
    var confirmDeletePhotos by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        if (!state.settings.haptics) return@LaunchedEffect
        when {
            state.error != null -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            state.message != null -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        LargeFlexibleTopAppBar(
            title = { Text("Data & reminders") },
            subtitle = { Text("Backup, import, notifications, storage") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = background,
                scrolledContainerColor = background
            ),
            scrollBehavior = scrollBehavior
        )

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // ---------------------------------------------------- status
            state.task?.let { task ->
                item(key = "busy", contentType = "status") {
                    ForgeCard(Modifier.animateItem()) {
                        Text(task.label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            state.error?.let { err ->
                item(key = "error", contentType = "status") {
                    StatusCard(
                        text = err,
                        icon = Icons.Outlined.ErrorOutline,
                        isError = true,
                        onDismiss = vm::dismissMessage,
                        modifier = Modifier.animateItem()
                    )
                }
            }
            state.message?.let { msg ->
                item(key = "message", contentType = "status") {
                    StatusCard(
                        text = msg,
                        icon = Icons.Outlined.CheckCircle,
                        isError = false,
                        onDismiss = vm::dismissMessage,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // ---------------------------------------------------- backup
            item(key = "h_backup", contentType = "header") { SectionHeader("BACKUP & EXPORT", Modifier.animateItem()) }
            item(key = "backup_json", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    CardTitle(Icons.Outlined.Backup, "Full backup")
                    Spacer(Modifier.height(6.dp))
                    BodyText(
                        "Everything Forge has logged, in one JSON file you can restore on this or another phone. " +
                            "Photos and API keys are never included."
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { exportJsonLauncher.launch("forge-backup-${LocalDate.now()}.json") },
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text("Export backup") }
                }
            }
            item(key = "backup_csv", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    CardTitle(Icons.Outlined.TableChart, "Spreadsheet exports (CSV)")
                    Spacer(Modifier.height(4.dp))
                    BodyText("One file per log, readable by Excel, Sheets or Numbers.")
                    Spacer(Modifier.height(8.dp))
                    val counts = state.storage?.counts
                    CsvDataset.entries.forEach { ds ->
                        val count = when (ds) {
                            CsvDataset.FOOD -> counts?.food
                            CsvDataset.WEIGHTS -> counts?.weights
                            CsvDataset.WORKOUTS -> counts?.sets
                            CsvDataset.MEASUREMENTS -> counts?.measurements
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ds.label, style = MaterialTheme.typography.bodyLarge)
                                if (count != null) {
                                    Text(
                                        when (ds) {
                                            CsvDataset.WORKOUTS -> "${"%,d".format(count)} sets"
                                            else -> "${"%,d".format(count)} rows"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Pill(
                                "Export",
                                onClick = {
                                    if (!state.busy) {
                                        pendingCsv = ds
                                        exportCsvLauncher.launch("${ds.fileStem}-${LocalDate.now()}.csv")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------- import
            item(key = "h_import", contentType = "header") { SectionHeader("IMPORT", Modifier.animateItem()) }
            item(key = "import_backup", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    CardTitle(Icons.Outlined.Restore, "Restore a Forge backup")
                    Spacer(Modifier.height(6.dp))
                    BodyText(
                        "Adds anything in the backup that isn't on this phone yet. Nothing already here is " +
                            "changed or deleted, and you'll see what will be added before anything happens."
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text("Choose backup file") }
                }
            }
            item(key = "import_csv", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    CardTitle(Icons.Outlined.UploadFile, "Workout history from Hevy or Strong")
                    Spacer(Modifier.height(6.dp))
                    BodyText(
                        "Export your workouts as CSV from Hevy or Strong, then pick the file. Exercise names are " +
                            "kept exactly as written and linked to Forge's exercise library when they match. " +
                            "Workouts already in Forge are skipped."
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            workoutCsvLauncher.launch(
                                arrayOf(
                                    "text/csv", "text/comma-separated-values", "application/csv",
                                    "text/plain", "application/vnd.ms-excel", "application/octet-stream"
                                )
                            )
                        },
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text("Choose CSV file") }
                }
            }

            // ---------------------------------------------------- reminders
            item(key = "h_reminders", contentType = "header") { SectionHeader("REMINDERS", Modifier.animateItem()) }
            val anyOn = ReminderType.entries.any { it.isEnabled(state.settings) } ||
                ProgressPhotoReminder.isEnabled(state.settings)
            if (anyOn && !canPost) {
                item(key = "notif_off", contentType = "status") {
                    ForgeCard(Modifier.animateItem(), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.NotificationsOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Notifications are off for Forge, so these reminders can't appear.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                runCatching { context.startActivity(ReminderNotifications.appSettingsIntent(context)) }
                                    .onFailure { vm.showError("Couldn't open system settings.") }
                            }
                        ) { Text("Open notification settings") }
                    }
                }
            }
            ReminderType.entries.forEach { type ->
                item(key = "rem_${type.name}", contentType = "reminder") {
                    ReminderCard(
                        type = type,
                        enabled = type.isEnabled(state.settings),
                        minutes = state.reminders.minutesFor(type),
                        trainingDays = state.reminders.trainingDays,
                        pausedBySafety = type == ReminderType.WEIGH_IN && state.settings.edScreenPositive,
                        onToggle = { onToggleReminder(type, it) },
                        onEditTime = { editingTime = type },
                        onToggleDay = { vm.toggleTrainingDay(it) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
            item(key = "rem_progress_photos", contentType = "reminder") {
                ProgressPhotoReminderCard(
                    enabled = state.settings.progressPhotoReminder,
                    onToggle = onTogglePhotoReminder,
                    modifier = Modifier.animateItem()
                )
            }
            item(key = "rem_note", contentType = "note") {
                Text(
                    "Android batches background work to save battery, so a reminder can arrive a few minutes late.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 4.dp)
                )
            }

            // ---------------------------------------------------- storage
            item(key = "h_storage", contentType = "header") {
                SectionHeader("STORAGE", Modifier.animateItem(), action = "Refresh", onAction = { vm.refreshStorage() })
            }
            item(key = "storage", contentType = "card") {
                StorageCard(
                    report = state.storage,
                    loading = state.storageLoading,
                    error = state.storageError,
                    busy = state.busy,
                    onRetry = { vm.refreshStorage() },
                    onDeletePhotos = { confirmDeletePhotos = true },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    // ------------------------------------------------------------ dialogs
    state.restorePreview?.let { preview ->
        RestoreDialog(preview, onConfirm = vm::confirmRestore, onDismiss = vm::cancelRestore)
    }
    state.workoutPreview?.let { preview ->
        WorkoutImportDialog(
            preview = preview,
            weightsInLb = state.workoutWeightsInLb,
            busy = state.busy,
            onWeightsInLb = vm::setWorkoutWeightsInLb,
            onConfirm = vm::confirmWorkoutImport,
            onDismiss = vm::cancelWorkoutImport
        )
    }
    editingTime?.let { type ->
        TimeDialog(
            title = type.title,
            initialMinutes = state.reminders.minutesFor(type),
            onPick = { minutes ->
                editingTime = null
                vm.setReminderTime(type, minutes)
            },
            onDismiss = { editingTime = null }
        )
    }
    if (confirmDeletePhotos) {
        val report = state.storage
        AlertDialog(
            onDismissRequest = { confirmDeletePhotos = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete meal photos?") },
            text = {
                Text(
                    buildString {
                        if (report != null && report.mealPhotoFiles > 0) {
                            append("${report.mealPhotoFiles} photos (${StorageUsage.formatBytes(report.mealPhotoBytes)}) will be removed. ")
                        }
                        append("Your food entries and their numbers stay - only the pictures go. ")
                        append("Progress photos aren't touched. This can't be undone.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeletePhotos = false
                        vm.deleteMealPhotos()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete photos") }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePhotos = false }) { Text("Cancel") } }
        )
    }
}

// ====================================================================== pieces

@Composable
private fun CardTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusCard(
    text: String,
    icon: ImageVector,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    ForgeCard(
        modifier,
        color = if (isError) scheme.errorContainer else scheme.secondaryContainer,
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        val onColor = if (isError) scheme.onErrorContainer else scheme.onSecondaryContainer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = onColor)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = onColor, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK", color = onColor) }
        }
    }
}

@Composable
private fun timeLabel(minutes: Int): String {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    return remember(minutes, is24) {
        LocalTime.of(minutes / 60, minutes % 60)
            .format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()))
    }
}

@Composable
private fun ProgressPhotoReminderCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Monthly progress photos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "First Sunday of each month, 8 am - front, side, back. Photos stay in your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ReminderCard(
    type: ReminderType,
    enabled: Boolean,
    minutes: Int,
    trainingDays: Set<DayOfWeek>,
    pausedBySafety: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (type) {
        ReminderType.WEIGH_IN -> Icons.Outlined.MonitorWeight
        ReminderType.MEAL -> Icons.Outlined.Restaurant
        ReminderType.WORKOUT -> Icons.Outlined.FitnessCenter
    }
    ForgeCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(type.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(type.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Pill(
                "At ${timeLabel(minutes)}",
                icon = Icons.Outlined.Schedule,
                onClick = onEditTime
            )
            if (pausedBySafety) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paused while the eating check is active, so the scale isn't pushed at you every morning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (type == ReminderType.WORKOUT) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in trainingDays,
                            onClick = { onToggleDay(day) },
                            label = { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }
                if (trainingDays.isEmpty()) {
                    Text(
                        "No days picked, so this reminder won't fire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageCard(
    report: StorageReport?,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onRetry: () -> Unit,
    onDeletePhotos: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier) {
        when {
            report == null && loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center
            ) { LoadingIndicator(Modifier.size(48.dp)) }

            report == null -> {
                Text(
                    error ?: "Storage details aren't available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            }

            else -> {
                val c = report.counts
                StorageRow(
                    "Logged data",
                    StorageUsage.formatBytes(report.databaseBytes),
                    "${"%,d".format(c.food)} food entries · ${"%,d".format(c.sessions)} workouts · ${"%,d".format(c.weights)} weigh-ins"
                )
                StorageRow(
                    "Meal photos",
                    StorageUsage.formatBytes(report.mealPhotoBytes),
                    if (report.mealPhotoFiles == 1) "1 photo" else "${report.mealPhotoFiles} photos"
                )
                StorageRow(
                    "Progress photos",
                    StorageUsage.formatBytes(report.progressPhotoBytes),
                    "Private to this phone, never in backups"
                )
                StorageRow("Settings", StorageUsage.formatBytes(report.settingsBytes), null)
                StorageRow("Temporary files", StorageUsage.formatBytes(report.cacheBytes), null)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        StorageUsage.formatBytes(report.totalBytes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = onDeletePhotos,
                    enabled = !busy && (report.mealPhotoFiles > 0 || c.mealPhotos > 0),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete meal photos")
                }
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String, detail: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val dialogDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun dateOf(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(dialogDate)

@Composable
private fun RestoreDialog(preview: RestorePreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val nothingNew = preview.toAdd.total == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
        title = { Text(if (nothingNew) "Already up to date" else "Restore this backup?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (preview.exportedAt > 0) {
                    Text(
                        "Made on ${dateOf(preview.exportedAt)}" + (preview.appVersion?.let { " with Forge $it" } ?: "") + ".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (nothingNew) {
                    Text("Everything in this backup is already on this phone.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Will add:", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    preview.toAdd.lines().forEach { (label, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("%,d".format(n), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (preview.alreadyHere > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${"%,d".format(preview.alreadyHere)} items are already here and will be skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nothing on this phone is changed or deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (nothingNew) TextButton(onClick = onDismiss) { Text("OK") }
            else TextButton(onClick = onConfirm) { Text("Restore") }
        },
        dismissButton = {
            if (!nothingNew) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun WorkoutImportDialog(
    preview: WorkoutImportPreview,
    weightsInLb: Boolean,
    busy: Boolean,
    onWeightsInLb: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val source = preview.source.label
    val nothingNew = preview.newWorkouts == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
        title = { Text(if (nothingNew) "Already imported" else "Import from $source?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (nothingNew) {
                    Text(
                        "All ${preview.workoutsInFile} workouts in this file are already in Forge.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "${preview.newWorkouts} ${if (preview.newWorkouts == 1) "workout" else "workouts"} · " +
                            "${"%,d".format(preview.newSets)} sets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val first = preview.firstStartedAt
                    val last = preview.lastStartedAt
                    if (first != null && last != null) {
                        Text(
                            if (dateOf(first) == dateOf(last)) dateOf(first) else "${dateOf(first)} – ${dateOf(last)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (preview.duplicates > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${preview.duplicates} already in Forge will be skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "${preview.matchedExercises} of ${preview.exercises} exercises linked to Forge's library. " +
                        "Names stay exactly as $source wrote them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preview.unmatchedSample.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Not linked: " + preview.unmatchedSample.joinToString(", ") +
                            if (preview.exercises - preview.matchedExercises > preview.unmatchedSample.size) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preview.skippedRows > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${preview.skippedRows} rows without reps (timed holds, cardio, rest rows) are left out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                preview.warnings.forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!preview.unitFromFile) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Weights are in pounds", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "This file doesn't say. Match what $source was set to.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = weightsInLb, onCheckedChange = onWeightsInLb, enabled = !busy)
                    }
                }
            }
        },
        confirmButton = {
            if (nothingNew) TextButton(onClick = onDismiss) { Text("OK") }
            else TextButton(onClick = onConfirm, enabled = !busy) { Text("Import") }
        },
        dismissButton = {
            if (!nothingNew) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TimeDialog(title: String, initialMinutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onPick(pickerState.hour * 60 + pickerState.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
