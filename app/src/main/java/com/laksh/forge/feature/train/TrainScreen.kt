package com.laksh.forge.feature.train

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.program.MaxEntry
import com.laksh.forge.domain.program.ProgressionEngine
import com.laksh.forge.domain.program.Strength
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates

@Composable
fun TrainScreen(
    onOpenSession: (Long) -> Unit,
    vm: TrainViewModel = viewModel(factory = TrainViewModel.Factory),
    onOpenLibrary: () -> Unit = {},
    onOpenExercise: (String) -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var confirmDelete by remember { mutableStateOf<Program?>(null) }
    var editMax by remember { mutableStateOf<MaxEntry?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPdf(uri)
    }
    val importPdf = { pickPdf.launch(arrayOf("application/pdf")) }
    val start: (ProgramDay?) -> Unit = { day ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        vm.startSession(day, onOpenSession)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header", contentType = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Train", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Pill("Import PDF", icon = Icons.Outlined.UploadFile, filled = true, onClick = importPdf)
            }
        }

        if (state.notice.isNotEmpty()) {
            item(key = "notice", contentType = "notice") {
                ForgeCard(Modifier.animateItem(), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            state.notice.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Icon(
                            Icons.Outlined.Close, "Dismiss",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = vm::clearNotice)
                        )
                    }
                }
            }
        }

        state.openSession?.let { open ->
            item(key = "open_${open.id}", contentType = "open") {
                ForgeCard(
                    Modifier.animateItem(),
                    onClick = { onOpenSession(open.id) },
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Session in progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${open.title} · ${Dates.durationLabel(open.startedAt, null)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Pill("Resume", filled = true, onClick = { onOpenSession(open.id) })
                    }
                }
            }
        }

        when (state.importPhase) {
            ImportPhase.READING, ImportPhase.PARSING -> item(key = "import", contentType = "import") {
                ForgeCard(Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(state.importStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            ImportPhase.DONE -> item(key = "import", contentType = "import") {
                ForgeCard(Modifier.animateItem()) {
                    Text(state.importStatus, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Pill("Dismiss", onClick = vm::clearImportState)
                }
            }
            ImportPhase.ERROR -> item(key = "import", contentType = "import") {
                ForgeCard(Modifier.animateItem(), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)) {
                    Text(state.importError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Dismiss", onClick = vm::clearImportState)
                        Pill("Browse free programs", filled = true, onClick = onOpenLibrary)
                    }
                }
            }
            ImportPhase.IDLE -> Unit
        }

        val active = state.active
        if (state.loading) {
            item(key = "loading", contentType = "loading") {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp).animateItem(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(Modifier.size(56.dp))
                }
            }
        } else if (active == null) {
            item(key = "library_hero", contentType = "hero") {
                LibraryHero(onOpenLibrary = onOpenLibrary, modifier = Modifier.animateItem())
            }
            item(key = "pdf_card", contentType = "pdf") {
                ForgeCard(Modifier.animateItem()) {
                    EmptyState(
                        icon = Icons.Outlined.FitnessCenter,
                        title = "Have a program PDF?",
                        body = "Import the PDF of the program you're running - a coach's plan, a paid block - and it becomes loggable training days.",
                        action = { Pill("Import a PDF", icon = Icons.Outlined.UploadFile, filled = true, onClick = importPdf) }
                    )
                    Pill(
                        "Start a free session",
                        icon = Icons.Outlined.PlayArrow,
                        onClick = { start(null) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        } else {
            item(key = "active_${active.program.id}", contentType = "active") {
                ActiveProgramCard(
                    info = active,
                    onStart = start,
                    onRounding = vm::setRounding,
                    modifier = Modifier.animateItem()
                )
            }
            if (active.maxes.isNotEmpty()) {
                item(key = "maxes", contentType = "maxes") {
                    MaxesCard(active.maxes, state.unit, onEdit = { editMax = it }, modifier = Modifier.animateItem())
                }
            }
            if (active.checklist.isNotEmpty()) {
                item(key = "checklist", contentType = "checklist") {
                    DeloadChecklist(active.checklist, onDecide = vm::answerChecklist, modifier = Modifier.animateItem())
                }
            } else if (active.optionalWeek && active.config?.state?.deloadAcceptedCycle != active.config?.state?.cycle) {
                item(key = "skip", contentType = "skip") {
                    ForgeCard(Modifier.animateItem()) {
                        Text("Optional deload week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This week is a deload the program marks as optional. Skip it to start the next cycle now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Pill("Skip deload week", icon = Icons.Outlined.SkipNext, filled = true, onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.skipWeek()
                        })
                    }
                }
            }
            active.program.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                item(key = "notes", contentType = "notes") {
                    ForgeCard(Modifier.animateItem()) {
                        Text("Program notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(notes, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(key = "days_header", contentType = "header") {
                SectionHeader("ALL DAYS", modifier = Modifier.animateItem())
            }
            items(active.days, key = { "day_${it.id}" }, contentType = { "day" }) { day ->
                DayRow(
                    day = day,
                    isNext = day.id == active.nextDay?.id,
                    onStart = { start(day) },
                    modifier = Modifier.animateItem()
                )
            }
            item(key = "secondary", contentType = "secondary") {
                FlowRow(
                    Modifier.animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Pill("Free session", icon = Icons.Outlined.PlayArrow, onClick = { start(null) })
                    Pill("Browse free programs", icon = Icons.AutoMirrored.Outlined.LibraryBooks, onClick = onOpenLibrary)
                }
            }
        }

        if (state.programs.size > 1 || (state.programs.isNotEmpty() && active == null)) {
            item(key = "programs_header", contentType = "header") {
                SectionHeader("YOUR PROGRAMS", modifier = Modifier.animateItem())
            }
            items(state.programs, key = { "prog_${it.id}" }, contentType = { "program" }) { p ->
                ForgeCard(Modifier.animateItem(), onClick = { if (!p.isActive) vm.activate(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    p.author?.let { append("$it · ") }
                                    append(if (p.isActive) "Active" else "Tap to make active")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Pill("Delete", color = MaterialTheme.colorScheme.error, onClick = { confirmDelete = p })
                    }
                }
            }
        }

        val history = state.sessions.filter { it.endedAt != null }
        if (history.isNotEmpty()) {
            item(key = "history_header", contentType = "header") {
                SectionHeader("HISTORY", modifier = Modifier.animateItem())
            }
            items(history, key = { "hist_${it.id}" }, contentType = { "history" }) { s ->
                ForgeCard(
                    Modifier.animateItem(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append(Dates.dayLabel(s.localDate))
                            append(" · ")
                            append(Dates.durationLabel(s.startedAt, s.endedAt))
                            s.weekIndex?.let { append(" · week $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete program?") },
            text = { Text("${p.name} and its training days will be removed. Logged sessions are kept.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProgram(p); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    editMax?.let { entry ->
        EditMaxDialog(
            entry = entry,
            unit = state.unit,
            onDismiss = { editMax = null },
            onSave = { v ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.updateMax(entry, v)
                editMax = null
            }
        )
    }
}

@Composable
private fun LibraryHero(onOpenLibrary: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier, onClick = onOpenLibrary, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) {
        Icon(
            Icons.Outlined.AutoAwesome, null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "No PDF? Start a proven program",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Free programs from their authors - strength, hypertrophy and powerbuilding, 2 to 6 days a week. " +
                "Pick one, enter a few numbers, and every session tells you what to lift.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onOpenLibrary,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) {
            Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse free programs")
        }
    }
}

@Composable
private fun ActiveProgramCard(
    info: ActiveInfo,
    onStart: (ProgramDay) -> Unit,
    onRounding: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    ForgeCard(modifier, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {
        Text(info.program.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        info.program.author?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        info.weekLabel?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(14.dp))
        val next = info.nextDay
        if (next != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text("Next up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(next.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    next.focus?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onStart(next) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ButtonDefaults.MediumContainerHeight)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start ${next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            Text(
                "This program has no training days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (info.config != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Plate step",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                listOf(ProgressionEngine.DEFAULT_INCREMENT_KG, 1.25).forEach { step ->
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        "${Strength.fmt(step)} kg",
                        filled = info.roundingKg == step,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onRounding(step)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MaxesCard(maxes: List<MaxEntry>, unit: WeightUnit, onEdit: (MaxEntry) -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier) {
        Text(
            when (maxes.first().basis) {
                "TM" -> "Training maxes"
                "TOP" -> "Top sets this week"
                else -> "Maxes"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Prescriptions are worked out from these. Tap to correct one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            maxes.forEach { m ->
                val value = m.valueKg?.let { "${Strength.fmt(round1(NutritionMath.kgToDisplay(it, unit)))} ${unit.label}" }
                Pill(
                    "${m.label} ${basisShort(m.basis)}: ${value ?: "set"}",
                    icon = Icons.Outlined.Edit,
                    filled = m.valueKg == null,
                    color = if (m.valueKg == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    onClick = { onEdit(m) }
                )
            }
        }
    }
}

private fun basisShort(b: String) = when (b) {
    "TOP" -> "top"
    else -> b
}

private fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0

@Composable
private fun DeloadChecklist(questions: List<String>, onDecide: (Int) -> Unit, modifier: Modifier = Modifier) {
    val answers = remember(questions) { mutableStateListOf<Boolean>().apply { repeat(questions.size) { add(false) } } }
    val haptics = LocalHapticFeedback.current
    ForgeCard(modifier) {
        Text("Deload check", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "This week's deload is optional. Two or more \"yes\" answers means take it; otherwise start the next block.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        questions.forEachIndexed { i, q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptics.performHapticFeedback(if (answers[i]) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        answers[i] = !answers[i]
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = answers[i], onCheckedChange = null)
                Text(q, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        val yes = answers.count { it }
        FilledTonalButton(onClick = { onDecide(yes) }, shapes = ButtonDefaults.shapes()) {
            Text(if (yes >= 2) "Take the deload" else "Skip it - start the next block")
        }
    }
}

@Composable
private fun DayRow(day: ProgramDay, isNext: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(
        modifier,
        onClick = onStart,
        color = if (isNext) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                day.focus?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (isNext) Pill("Next", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(6.dp))
            Pill("Start", icon = Icons.Outlined.PlayArrow, filled = true, onClick = onStart)
        }
    }
}

@Composable
private fun EditMaxDialog(entry: MaxEntry, unit: WeightUnit, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember(entry) {
        mutableStateOf(entry.valueKg?.let { Strength.fmt(round1(NutritionMath.kgToDisplay(it, unit))) }.orEmpty())
    }
    val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${entry.label} " + when (entry.basis) {
                    "TM" -> "training max"
                    "1RM" -> "1-rep max"
                    "5RM" -> "5-rep max"
                    "TOP" -> "top set"
                    else -> entry.basis
                }
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                suffix = { Text(unit.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
