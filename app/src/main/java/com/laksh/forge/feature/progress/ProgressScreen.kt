package com.laksh.forge.feature.progress

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Straighten
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.db.ProgressPhoto
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.domain.plan.MeasurementGuides
import com.laksh.forge.domain.plan.PhotoProtocol
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.feature.plan.BulletList
import com.laksh.forge.feature.plan.ConnectedChoice
import com.laksh.forge.feature.plan.Expandable
import com.laksh.forge.feature.plan.MeasurementInput
import com.laksh.forge.feature.plan.PlanScaffold
import com.laksh.forge.feature.plan.plusContent
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates
import java.io.File

private enum class ProgressTab(val label: String) { MEASUREMENTS("Measurements"), PHOTOS("Photos") }

/** Chart selection: a tape site, or the Navy body-fat trend. */
private const val BODY_FAT_KEY = "BODY_FAT"

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(ProgressTab.MEASUREMENTS) }
    var editing by remember { mutableStateOf<MeasurementDraft?>(null) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ProgressPhoto?>(null) }
    var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingGalleryUri = uri
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
            onCaptured = { pose, uri -> vm.addPhoto(pose, uri) },
            onClose = { capturing = false }
        )
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
                            Text("Saving...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                when (tab) {
                    ProgressTab.MEASUREMENTS -> measurementItems(
                        state = state,
                        onAdd = { editing = MeasurementDraft() },
                        onEdit = { editing = MeasurementDraft.from(it) }
                    )
                    ProgressTab.PHOTOS -> photoItems(
                        state = state,
                        onCapture = { capturing = true },
                        onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onView = { viewing = it }
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

    pendingGalleryUri?.let { uri ->
        PosePickerDialog(
            onPick = { pose ->
                vm.addPhoto(pose, uri)
                pendingGalleryUri = null
            },
            onDismiss = { pendingGalleryUri = null }
        )
    }

    viewing?.let { photo ->
        PhotoViewer(
            photo = photo,
            onDelete = {
                vm.deletePhoto(photo)
                viewing = null
            },
            onDismiss = { viewing = null }
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
                    state.navy.firstOrNull { it.timestamp == m.timestamp }?.let {
                        Text(
                            "tape BF ~${"%.0f".format(it.pct)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                MeasureSite.entries.mapNotNull { site ->
                    site.read(m)?.let { v ->
                        val label = if (site == MeasureSite.WAIST) waistLabel(state.settings.sex) else site.label
                        "$label ${"%.1f".format(v)}"
                    }
                }.joinToString(" · ") + " cm",
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
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onView: (ProgressPhoto) -> Unit
) {
    item(key = "p_actions", contentType = "action") {
        Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onCapture, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PhotoCamera, null)
                Spacer(Modifier.width(8.dp))
                Text("Take photos")
            }
            OutlinedButton(onClick = onGallery, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PhotoLibrary, null)
                Spacer(Modifier.width(8.dp))
                Text("From gallery")
            }
        }
    }
    item(key = "p_protocol", contentType = "howto") {
        ForgeCard(Modifier.animateItem()) {
            Expandable("How to take comparable photos") { BulletList(PhotoProtocol.steps) }
        }
    }
    if (state.photoDays.isEmpty()) {
        item(key = "p_empty", contentType = "empty") {
            EmptyState(
                icon = Icons.Outlined.AddAPhoto,
                title = "No progress photos yet",
                body = "Optional, private and never backed up. Take front, side and back about once a month with the pose guide.",
                modifier = Modifier.animateItem()
            )
        }
        return
    }
    if (state.photoDays.size >= 2) {
        item(key = "p_compare", contentType = "compare") { CompareCard(state.photoDays, Modifier.animateItem()) }
    }
    item(key = "p_header", contentType = "header") { SectionHeader("Timeline", Modifier.animateItem()) }
    items(state.photoDays, key = { "p_${it.date}" }, contentType = { "photoDay" }) { day ->
        ForgeCard(Modifier.animateItem()) {
            Text(Dates.dayLabel(day.date), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pose.entries.forEach { pose ->
                    Thumb(day.byPose[pose], pose.label, onView, Modifier.weight(1f))
                }
            }
            if (day.other.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    day.other.take(3).forEach { Thumb(it, "Extra", onView, Modifier.weight(1f)) }
                    repeat(3 - day.other.take(3).size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Thumb(photo: ProgressPhoto?, label: String, onView: (ProgressPhoto) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (photo != null) Modifier.clickable { onView(photo) } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                AsyncImage(
                    model = File(photo.path),
                    contentDescription = "$label photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompareCard(days: List<PhotoDay>, modifier: Modifier) {
    // Defaults: oldest vs newest, the comparison people almost always want first.
    var beforeChoice by rememberSaveable { mutableStateOf(days.last().date) }
    var afterChoice by rememberSaveable { mutableStateOf(days.first().date) }
    var pose by rememberSaveable { mutableStateOf(Pose.FRONT) }
    var mode by rememberSaveable { mutableStateOf(CompareMode.SLIDER) }
    val dates = days.map { it.date }
    val beforeDate = if (beforeChoice in dates) beforeChoice else days.last().date
    val afterDate = if (afterChoice in dates) afterChoice else days.first().date

    ForgeCard(modifier) {
        Text("Compare", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ConnectedChoice(Pose.entries, pose, { it.label }, { pose = it })
        Spacer(Modifier.height(8.dp))
        DateChips("Before", dates, beforeDate) { beforeChoice = it }
        DateChips("After", dates, afterDate) { afterChoice = it }
        Spacer(Modifier.height(8.dp))
        ConnectedChoice(CompareMode.entries, mode, { it.label }, { mode = it })
        Spacer(Modifier.height(12.dp))
        val before = days.firstOrNull { it.date == beforeDate }?.byPose?.get(pose)
        val after = days.firstOrNull { it.date == afterDate }?.byPose?.get(pose)
        PhotoCompareView(before, after, mode)
        if (before == null || after == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "One of these days has no ${pose.label.lowercase()} photo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DateChips(label: String, dates: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(dates, key = { it }) { date ->
                FilterChip(selected = date == selected, onClick = { onSelect(date) }, label = { Text(Dates.shortLabel(date)) })
            }
        }
    }
}

@Composable
private fun PosePickerDialog(onPick: (Pose) -> Unit, onDismiss: () -> Unit) {
    var pose by remember { mutableStateOf(Pose.FRONT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which pose is this?") },
        text = {
            Column {
                Text("Photos are compared pose by pose.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                ConnectedChoice(Pose.entries, pose, { it.label }, { pose = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(pose) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PhotoViewer(photo: ProgressPhoto, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            Text(
                "${Pose.from(photo.pose)?.label ?: "Photo"} · ${Dates.dayLabel(photo.localDate)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            AsyncImage(
                model = File(photo.path),
                contentDescription = "Progress photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { confirm = true }) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this photo?") },
            text = { Text("It's removed from this phone permanently.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
