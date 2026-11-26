package com.laksh.forge.feature.progress

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.reminders.ReminderNotifications
import com.laksh.forge.domain.plan.BodyFatMethod
import com.laksh.forge.domain.plan.MeasurementGuides
import com.laksh.forge.domain.plan.PhotoBodyFat
import com.laksh.forge.domain.plan.PhotoProtocol
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.feature.plan.BulletList
import com.laksh.forge.feature.plan.ConnectedChoice
import com.laksh.forge.feature.plan.Expandable
import com.laksh.forge.feature.plan.MeasurementInput
import com.laksh.forge.feature.plan.NoteCard
import com.laksh.forge.feature.plan.PlanScaffold
import com.laksh.forge.feature.plan.SwitchRow
import com.laksh.forge.feature.plan.plusContent
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates

private enum class ProgressTab(val label: String) { MEASUREMENTS("Measurements"), PHOTOS("Photos") }

/** Chart selection: a tape site, or the Navy body-fat trend. */
private const val BODY_FAT_KEY = "BODY_FAT"

/** A saved AI photo estimate: a body-fat number with no tape sites. */
private fun BodyMeasurement.isPhotoEstimate(): Boolean =
    bodyFatMethod == BodyFatMethod.VISUAL_AI.dbValue && MeasureSite.entries.none { it.read(this) != null }

/**
 * Tape measurements, and progress photos that Forge never stores: the person keeps their own
 * in their gallery, and Compare reads them back in memory only.
 *
 * [startOnPhotos] is set when the monthly photo reminder opens this screen. [onOpenAiSetup] and
 * [onOpenProfile] are optional so the screen works before the nav host passes them.
 */
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    startOnPhotos: Boolean = false,
    onOpenAiSetup: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null
) {
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val exported by vm.legacyExported.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(if (startOnPhotos) ProgressTab.PHOTOS else ProgressTab.MEASUREMENTS) }
    var editing by remember { mutableStateOf<MeasurementDraft?>(null) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var confirmLegacyDelete by remember { mutableStateOf(false) }
    var deletingEstimate by remember { mutableStateOf<BodyMeasurement?>(null) }

    // Android 8-9 have no permission-free way into the shared Pictures folder, so the person
    // picks a folder once for the export instead.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.exportLegacy(tree)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.message.value = "Notifications are off for Forge, so the reminder can't show. You can allow them in system settings."
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    if (capturing) {
        PoseCaptureScreen(
            poses = Pose.entries,
            onCaptured = { pose, uri -> vm.saveCaptureToGallery(pose, uri) },
            onClose = { capturing = false }
        )
        return
    }
    if (comparing) {
        PhotoCompareScreen(onClose = { comparing = false }, onOpenAiSetup = onOpenAiSetup, onOpenProfile = onOpenProfile)
        return
    }

    PlanScaffold(title = "Progress", onBack = onBack, snackbarHostState = snackbar) { inner ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            state.error != null -> EmptyState(
                icon = Icons.Outlined.Straighten,
                title = "Couldn't load your progress",
                body = state.error ?: "",
                modifier = Modifier.padding(inner)
            )
            else -> LazyColumn(
                contentPadding = inner.plusContent(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "tabs", contentType = "tabs") {
                    ConnectedChoice(
                        options = ProgressTab.entries,
                        selected = tab,
                        label = { it.label },
                        onSelect = { tab = it },
                        modifier = Modifier.animateItem()
                    )
                }
                if (busy) {
                    item(key = "busy", contentType = "busy") {
                        Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Working...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                when (tab) {
                    ProgressTab.MEASUREMENTS -> measurementItems(
                        state = state,
                        onAdd = { editing = MeasurementDraft() },
                        onEdit = { m -> if (m.isPhotoEstimate()) deletingEstimate = m else editing = MeasurementDraft.from(m) }
                    )
                    ProgressTab.PHOTOS -> photoItems(
                        state = state,
                        busy = busy,
                        exported = exported,
                        onOpenCamera = {
                            try {
                                context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                            } catch (e: ActivityNotFoundException) {
                                vm.message.value = "No camera app was found on this phone."
                            } catch (e: SecurityException) {
                                vm.message.value = "The camera app couldn't be opened."
                            }
                        },
                        onPoseGuide = { capturing = true },
                        onCompare = { comparing = true },
                        onReminder = { on ->
                            if (on && !ReminderNotifications.permissionGranted(context)) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.setPhotoReminder(on)
                        },
                        onExportLegacy = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (GalleryPhotos.canSaveDirectly) vm.exportLegacy() else folderPicker.launch(null)
                        },
                        onDeleteLegacy = { confirmLegacyDelete = true }
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        MeasurementSheet(
            initial = draft,
            sex = state.settings.sex,
            onDismiss = { editing = null },
            onSave = { d ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.saveMeasurement(d) { editing = null }
            },
            onDelete = { id ->
                state.measurements.firstOrNull { it.id == id }?.let { vm.deleteMeasurement(it) }
                editing = null
            }
        )
    }

    deletingEstimate?.let { m ->
        AlertDialog(
            onDismissRequest = { deletingEstimate = null },
            title = { Text("Delete this photo estimate?") },
            text = { Text("This removes the AI body-fat estimate saved for ${Dates.dayLabel(m.localDate)}. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMeasurement(m)
                    deletingEstimate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingEstimate = null }) { Text("Cancel") } }
        )
    }

    if (confirmLegacyDelete) {
        val n = state.legacy.count
        AlertDialog(
            onDismissRequest = { confirmLegacyDelete = false },
            title = { Text("Delete $n photo${if (n == 1) "" else "s"} from Forge?") },
            text = {
                Text(
                    if (exported) "The copies you saved to your gallery stay. This removes Forge's copies and can't be undone."
                    else "You haven't saved these to your gallery yet. Deleting removes them from this phone for good."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLegacyDelete = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.deleteLegacy()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmLegacyDelete = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------- measurements

private fun androidx.compose.foundation.lazy.LazyListScope.measurementItems(
    state: ProgressUiState,
    onAdd: () -> Unit,
    onEdit: (BodyMeasurement) -> Unit
) {
    if (state.measurements.isEmpty()) {
        item(key = "m_empty", contentType = "empty") {
            EmptyState(
                icon = Icons.Outlined.Straighten,
                title = "No measurements yet",
                body = "Waist, neck and a few other sites every 2-4 weeks show change the scale can't - especially when weight barely moves.",
                modifier = Modifier.animateItem(),
                action = {
                    Button(onClick = onAdd, shapes = ButtonDefaults.shapes()) { Text("Add measurements") }
                }
            )
        }
        return
    }
    item(key = "m_chart", contentType = "chart") {
        MeasurementChartCard(state, Modifier.animateItem())
    }
    item(key = "m_add", contentType = "action") {
        FilledTonalButton(
            onClick = onAdd,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add measurements")
        }
    }
    item(key = "m_howto", contentType = "howto") {
        ForgeCard(Modifier.animateItem()) {
            Expandable("How to measure accurately") {
                Text(MeasurementGuides.protocol, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item(key = "m_header", contentType = "header") { SectionHeader("History", Modifier.animateItem()) }
    items(state.measurements, key = { "m_${it.id}" }, contentType = { "measurement" }) { m ->
        ForgeCard(Modifier.animateItem(), onClick = { onEdit(m) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Dates.dayLabel(m.localDate),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.showBodyFat) {
                    val photoPct = m.bodyFatPct?.takeIf { m.bodyFatMethod == BodyFatMethod.VISUAL_AI.dbValue }
                    val text = when {
                        photoPct != null -> "photo BF ~${PhotoBodyFat.label(PhotoBodyFat.range(photoPct))}"
                        else -> state.navy.firstOrNull { it.timestamp == m.timestamp }?.let { "tape BF ~${"%.0f".format(it.pct)}%" }
                    }
                    text?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val sites = MeasureSite.entries.mapNotNull { site ->
                site.read(m)?.let { v ->
                    val label = if (site == MeasureSite.WAIST) waistLabel(state.settings.sex) else site.label
                    "$label ${"%.1f".format(v)}"
                }
            }
            Text(
                if (sites.isNotEmpty()) sites.joinToString(" · ") + " cm"
                else "AI photo estimate - approximate, compare trends not single numbers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MeasurementChartCard(state: ProgressUiState, modifier: Modifier) {
    val sites = state.sitesWithData
    val options = buildList {
        sites.forEach { add(it.name) }
        if (state.showBodyFat && state.navy.size >= 2) add(BODY_FAT_KEY)
    }
    var chosen by rememberSaveable { mutableStateOf(options.firstOrNull() ?: MeasureSite.WAIST.name) }
    // Derived rather than written back, so a site disappearing never writes state mid-composition.
    val selected = if (chosen in options) chosen else options.firstOrNull() ?: chosen

    ForgeCard(modifier) {
        Text("Trends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it }) { key ->
                FilterChip(
                    selected = key == selected,
                    onClick = { chosen = key },
                    label = {
                        Text(
                            if (key == BODY_FAT_KEY) "Body fat (tape)"
                            else MeasureSite.valueOf(key).let { if (it == MeasureSite.WAIST) waistLabel(state.settings.sex) else it.label }
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val points = if (selected == BODY_FAT_KEY) {
            state.navy.map { ChartPoint(it.timestamp.toFloat(), it.pct.toFloat(), Dates.shortLabel(it.date)) }
        } else {
            val site = runCatching { MeasureSite.valueOf(selected) }.getOrNull()
            state.measurements.sortedBy { it.timestamp }.mapNotNull { m ->
                site?.read(m)?.let { ChartPoint(m.timestamp.toFloat(), it.toFloat(), Dates.shortLabel(m.localDate)) }
            }
        }
        LineChart(points = points, yFormatter = { "%.1f".format(it) }, height = 190.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (selected == BODY_FAT_KEY)
                "US Navy tape estimate per measurement day. Each point carries about +/-5 points of error; the direction over months is what matters."
            else "Centimetres. Same tape, same spot, same time of day keeps the trend honest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MeasurementSheet(
    initial: MeasurementDraft,
    sex: Sex,
    onDismiss: () -> Unit,
    onSave: (MeasurementDraft) -> Unit,
    onDelete: (Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var d by remember(initial) { mutableStateOf(initial) }
    var tried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val invalid = d.invalid()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title") {
                Text(
                    if (initial.id == 0L) "New measurements" else "Edit ${Dates.dayLabel(initial.localDate)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Fill in whichever sites you measured today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item(key = "waist") { MeasurementInput(MeasurementGuides.waistFor(sex), d.waist, { d = d.copy(waist = it) }, isError = tried && "waist" in invalid) }
            item(key = "neck") { MeasurementInput(MeasurementGuides.neck, d.neck, { d = d.copy(neck = it) }, isError = tried && "neck" in invalid) }
            item(key = "hips") { MeasurementInput(MeasurementGuides.hips, d.hips, { d = d.copy(hips = it) }, isError = tried && "hips" in invalid) }
            item(key = "chest") { MeasurementInput(MeasurementGuides.chest, d.chest, { d = d.copy(chest = it) }, isError = tried && "chest" in invalid) }
            item(key = "arm") { MeasurementInput(MeasurementGuides.arm, d.arm, { d = d.copy(arm = it) }, isError = tried && "arm" in invalid) }
            item(key = "thigh") { MeasurementInput(MeasurementGuides.thigh, d.thigh, { d = d.copy(thigh = it) }, isError = tried && "thigh" in invalid) }
            item(key = "calf") { MeasurementInput(MeasurementGuides.calf, d.calf, { d = d.copy(calf = it) }, isError = tried && "calf" in invalid) }
            item(key = "note") {
                OutlinedTextField(
                    value = d.note,
                    onValueChange = { d = d.copy(note = it) },
                    label = { Text("Note (optional)") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item(key = "errors") {
                if (tried && (invalid.isNotEmpty() || d.isEmpty)) {
                    Text(
                        if (d.isEmpty) "Add at least one measurement." else "Check the highlighted values - they should be in centimetres.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    if (initial.id != 0L) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                    Button(
                        onClick = {
                            tried = true
                            if (!d.isEmpty && d.invalid().isEmpty()) onSave(d)
                        },
                        shapes = ButtonDefaults.shapes()
                    ) { Text("Save") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete these measurements?") },
            text = { Text("This removes the ${Dates.dayLabel(initial.localDate)} entry. It can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------- photos

private fun androidx.compose.foundation.lazy.LazyListScope.photoItems(
    state: ProgressUiState,
    busy: Boolean,
    exported: Boolean,
    onOpenCamera: () -> Unit,
    onPoseGuide: () -> Unit,
    onCompare: () -> Unit,
    onReminder: (Boolean) -> Unit,
    onExportLegacy: () -> Unit,
    onDeleteLegacy: () -> Unit
) {
    if (!state.legacy.isEmpty) {
        item(key = "p_legacy", contentType = "legacy") {
            LegacyPhotosCard(state.legacy.count, exported, busy, onExportLegacy, onDeleteLegacy, Modifier.animateItem())
        }
    }
    item(key = "p_yours", contentType = "intro") {
        ForgeCard(Modifier.animateItem()) {
            Text("Your photos stay yours", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Forge doesn't store progress photos. Take them with your phone's camera and keep them in your gallery - " +
                    "an album called \"Progress\" works well. Same pose, light and time each month makes them comparable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onOpenCamera, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoCamera, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open camera")
                }
                if (GalleryPhotos.canSaveDirectly) {
                    OutlinedButton(onClick = onPoseGuide, enabled = !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pose guide")
                    }
                }
            }
            if (GalleryPhotos.canSaveDirectly) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The pose guide draws an outline to line you up and saves each shot straight to ${GalleryPhotos.albumLabel} in your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    item(key = "p_protocol", contentType = "howto") {
        ForgeCard(Modifier.animateItem()) {
            Expandable("How to take comparable photos") { BulletList(PhotoProtocol.steps) }
        }
    }
    item(key = "p_reminder", contentType = "reminder") {
        ForgeCard(Modifier.animateItem()) {
            SwitchRow(
                title = "Monthly reminder",
                subtitle = "First Sunday of each month, 8 am: front, side and back.",
                checked = state.settings.progressPhotoReminder,
                onCheckedChange = onReminder
            )
        }
    }
    item(key = "p_compare", contentType = "compare") {
        ForgeCard(Modifier.animateItem()) {
            Text("Compare photos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick photos from your gallery to see them side by side or under a slider. They're read into memory " +
                    "for this screen only - nothing is copied into Forge." +
                    (if (state.showBodyFat) " You can also ask the AI for an approximate body-fat change." else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCompare, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ViewColumn, null)
                Spacer(Modifier.width(8.dp))
                Text("Compare photos")
            }
        }
    }
}

/**
 * One-time card for photos Forge v2.0 stored. Nothing is deleted until the person taps
 * Delete and confirms; saving first is offered but never required.
 */
@Composable
private fun LegacyPhotosCard(
    count: Int,
    exported: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier
) {
    val photos = "$count progress photo${if (count == 1) "" else "s"}"
    NoteCard(
        title = "Forge no longer keeps photos",
        body = if (exported) {
            "Saved to your gallery. You can now delete Forge's copies of your $photos."
        } else {
            "You have $photos saved in Forge from an earlier version. Save them to your gallery " +
                "(${if (GalleryPhotos.canSaveDirectly) GalleryPhotos.albumLabel else "a folder you choose"}) to keep them, then delete them from Forge."
        },
        icon = Icons.Outlined.PhotoLibrary,
        modifier = modifier
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!exported) {
                Button(onClick = onExport, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save to gallery")
                }
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete from Forge")
            }
        }
    }
}
