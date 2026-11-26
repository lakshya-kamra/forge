package com.laksh.forge.feature.progress

import androidx.activity.compose.BackHandler
import com.laksh.forge.ui.components.SecureWindow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.plan.BodyChangeAi
import com.laksh.forge.domain.plan.BodyChangeEstimate
import com.laksh.forge.domain.plan.PhotoBodyFat
import com.laksh.forge.domain.plan.PhotoSelection
import com.laksh.forge.feature.plan.ConnectedChoice
import com.laksh.forge.feature.plan.NoteCard
import com.laksh.forge.feature.plan.NoteTone
import com.laksh.forge.feature.plan.PlanScaffold
import com.laksh.forge.feature.plan.plusContent
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.SectionHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Compare the person's own gallery photos side by side or under a slider, and optionally ask
 * the AI for an approximate body-fat change. Photos come in through the system Photo Picker
 * (no storage permission), live only in [PhotoCompareViewModel]'s memory, and are dropped by
 * [onClose]. Shown as a full-screen layer of the Progress screen, like the pose camera.
 */
@Composable
fun PhotoCompareScreen(
    onClose: () -> Unit,
    onOpenAiSetup: (() -> Unit)? = null,
    onOpenProfile: (() -> Unit)? = null
) {
    SecureWindow()
    val vm: PhotoCompareViewModel = viewModel(factory = PhotoCompareViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var datingId by remember { mutableStateOf<Long?>(null) }
    var mode by rememberSaveable { mutableStateOf(CompareMode.SIDE_BY_SIDE) }

    val close = {
        vm.close()
        onClose()
    }
    BackHandler(onBack = close)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoSelection.MAX_PHOTOS)) { uris ->
        vm.add(uris)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    // Coming back from AI setup: pick up a key added there.
    LaunchedEffect(Unit) { vm.refreshAi() }

    PlanScaffold(title = "Compare photos", onBack = close, snackbarHostState = snackbar) { inner ->
        LazyColumn(
            contentPadding = inner.plusContent(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "privacy", contentType = "note") {
                NoteCard(
                    title = "Only on this screen",
                    body = "Photos you pick are shown from memory and dropped when you close Compare. Forge doesn't copy or keep them.",
                    icon = Icons.Outlined.Lock,
                    modifier = Modifier.animateItem()
                )
            }

            if (state.photos.isEmpty() && state.loadingCount == 0) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "Pick photos to compare",
                        body = "Choose 2-${PhotoSelection.MAX_PHOTOS} photos from your gallery - for example front and side from a few different months.",
                        modifier = Modifier.animateItem(),
                        action = { Button(onClick = pick, shapes = ButtonDefaults.shapes()) { Text("Choose photos") } }
                    )
                }
                return@LazyColumn
            }

            item(key = "picked", contentType = "picked") {
                PickedRow(
                    photos = state.photos,
                    canAdd = state.photos.size + state.loadingCount < PhotoSelection.MAX_PHOTOS,
                    onAdd = pick,
                    onRemove = vm::remove,
                    onDate = { datingId = it },
                    modifier = Modifier.animateItem()
                )
            }
            if (state.loadingCount > 0) {
                item(key = "loading", contentType = "busy") {
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Opening ${state.loadingCount} photo${if (state.loadingCount == 1) "" else "s"}...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            val undated = state.photos.count { it.date == null }
            if (undated > 0) {
                item(key = "undated", contentType = "note") {
                    NoteCard(
                        title = "Date needed",
                        body = "$undated photo${if (undated == 1) " has" else "s have"} no capture date. Tap \"Set date\" under it so the photos line up in order.",
                        tone = NoteTone.CAUTION,
                        icon = Icons.Outlined.Event,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (state.photos.size >= 2) {
                item(key = "mode", contentType = "mode") {
                    ConnectedChoice(CompareMode.entries, mode, { it.label }, { mode = it }, Modifier.animateItem())
                }
                item(key = "compare", contentType = "compare") {
                    when (mode) {
                        CompareMode.SIDE_BY_SIDE -> Column(Modifier.animateItem()) {
                            SideBySideStrip(state.photos)
                            if (state.photos.size > 2) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Swipe to line up any two dates.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        CompareMode.SLIDER -> SliderSection(state.photos, Modifier.animateItem())
                    }
                }
            } else if (state.photos.size == 1) {
                item(key = "one", contentType = "hint") {
                    Text(
                        "Add at least one more photo to compare.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (state.photos.isNotEmpty()) {
                item(key = "estimate_header", contentType = "header") { SectionHeader("Estimate my change", Modifier.animateItem()) }
                item(key = "estimate", contentType = "estimate") {
                    EstimateSection(
                        state = state,
                        onConsent = vm::setConsent,
                        onEstimate = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.estimate()
                        },
                        onSave = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.saveResult()
                        },
                        onOpenAiSetup = onOpenAiSetup,
                        onOpenProfile = onOpenProfile,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    // Derived, so a photo removed while its dialog is open just closes the dialog.
    val dating = datingId?.let { id -> state.photos.firstOrNull { it.id == id } }
    if (dating != null) {
        PhotoDateDialog(
            initial = dating.date,
            onDismiss = { datingId = null },
            onSet = { date ->
                vm.setDate(dating.id, date)
                datingId = null
            }
        )
    }
}

@Composable
private fun PickedRow(
    photos: List<SessionPhoto>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onDate: (Long) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Text(
            "${photos.size} of ${PhotoSelection.MAX_PHOTOS} photos, oldest first",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(photos, key = { it.id }, contentType = { "thumb" }) { photo ->
                Column(Modifier.width(96.dp).animateItem()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Image(
                            bitmap = photo.image,
                            contentDescription = "Photo from ${photoDateLabel(photo.date)}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        FilledTonalIconButton(
                            onClick = { onRemove(photo.id) },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                        ) { Icon(Icons.Outlined.Close, "Remove this photo", Modifier.size(14.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (photo.date == null) "Set date" else photoDateLabel(photo.date),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (photo.date == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDate(photo.id) }
                            .padding(vertical = 2.dp)
                    )
                    Text(photo.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canAdd) {
                item(key = "add", contentType = "add") {
                    Box(
                        Modifier
                            .width(96.dp)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable(onClick = onAdd)
                            .animateItem(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("Add", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderSection(photos: List<SessionPhoto>, modifier: Modifier) {
    // Defaults: oldest vs newest, the comparison people almost always want first.
    var beforeId by rememberSaveable { mutableStateOf(photos.first().id) }
    var afterId by rememberSaveable { mutableStateOf(photos.last().id) }
    val before = photos.firstOrNull { it.id == beforeId } ?: photos.first()
    val after = photos.firstOrNull { it.id == afterId } ?: photos.last()
    Column(modifier) {
        PhotoChips("Before", photos, before.id) { beforeId = it }
        PhotoChips("After", photos, after.id) { afterId = it }
        Spacer(Modifier.height(8.dp))
        SliderCompare(before, after)
    }
}

@Composable
private fun PhotoChips(label: String, photos: List<SessionPhoto>, selected: Long, onSelect: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(photos, key = { it.id }) { p ->
                FilterChip(
                    selected = p.id == selected,
                    onClick = { onSelect(p.id) },
                    label = { Text("${photoDateLabel(p.date)} · ${photos.indexOf(p) + 1}") }
                )
            }
        }
    }
}

@Composable
private fun EstimateSection(
    state: CompareUiState,
    onConsent: (Boolean) -> Unit,
    onEstimate: () -> Unit,
    onSave: () -> Unit,
    onOpenAiSetup: (() -> Unit)?,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier
) {
    val gate = state.gate
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            gate == null -> Unit
            !gate.allowed -> NoteCard(
                title = "Not available right now",
                body = gate.reason ?: "Photo estimates aren't available right now.",
                tone = NoteTone.SUPPORT
            ) {
                if (gate.needsRescreen && onOpenProfile != null) {
                    OutlinedButton(onClick = onOpenProfile, shapes = ButtonDefaults.shapes()) { Text("Answer the eating questions") }
                }
            }
            !state.aiConfigured -> NoteCard(
                title = "Connect an AI provider",
                body = "An approximate body-fat change needs an AI key. Comparing photos works without one."
            ) {
                if (onOpenAiSetup != null) {
                    OutlinedButton(onClick = onOpenAiSetup, shapes = ButtonDefaults.shapes()) { Text("Add an AI key") }
                }
            }
            else -> ForgeCard {
                Text(
                    "Forge sends the photos with their dates (and any tape measurements from the same weeks) in one request " +
                        "and gets back a likely range for each date and the overall direction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.selectionProblem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !state.estimating) { onConsent(!state.consent) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(checked = state.consent, onCheckedChange = onConsent, enabled = !state.estimating)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Send these ${state.photos.size} photos to ${state.bodyModel.ifBlank { "my AI provider" }} for this one estimate. " +
                            "They're sent once and then discarded - Forge never saves them, and only the numbers are kept if I tap Save.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onEstimate,
                    enabled = state.canEstimate,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Insights, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Estimate my change")
                }
                if (state.estimating) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Comparing your photos...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        state.estimateError?.let { NoteCard(title = "That didn't work", body = it, tone = NoteTone.CAUTION) }
        state.result?.let { ResultCard(it, saved = state.saved, saving = state.saving, onSave = onSave) }
    }
}

@Composable
private fun ResultCard(r: BodyChangeEstimate, saved: Boolean, saving: Boolean, onSave: () -> Unit) {
    ForgeCard {
        Text(r.direction.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(BodyChangeAi.summary(r), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        r.perDate.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(photoDateLabel(d.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("likely ${PhotoBodyFat.label(d.range)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (r.photoIssues.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Photo notes: ${r.photoIssues}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Approximate - compare trends, not single numbers. Photo estimates are unvalidated and can be off by 6 points or more; " +
                "light, pose and clothing move them. Your tape measurements are the steadier signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (saved) {
            Text("Saved to Measurements - just the ranges, no photos.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onSave, enabled = !saving, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Saving..." else "Save these estimates")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Saves one body-fat entry per date (marked as an AI photo estimate). The photos are never saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Material's date picker works in UTC midnight millis; the conversion stays in UTC both ways. */
@Composable
private fun PhotoDateDialog(initial: LocalDate?, onDismiss: () -> Unit, onSet: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val todayEndUtc = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayEndUtc
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        onSet(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                }
            ) { Text("Set date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = pickerState)
    }
}
