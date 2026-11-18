package com.laksh.forge.feature.train

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.ProgramExercise
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.util.Dates

@Composable
fun SessionScreen(
    sessionId: Long,
    onExit: () -> Unit,
    vm: SessionViewModel = viewModel(
        key = "session_$sessionId",
        factory = SessionViewModel.factory(sessionId)
    ),
    onOpenExercise: (String) -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFinish by remember { mutableStateOf(false) }
    var newExercise by remember { mutableStateOf("") }

    LaunchedEffect(state.finished) {
        if (state.finished) onExit()
    }

    Column(Modifier.fillMaxWidth()) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.session?.title ?: "Session",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildString {
                        state.session?.let { append(Dates.durationLabel(it.startedAt, null)) }
                        val working = state.sets.count { !it.isWarmup }
                        if (working > 0) append(if (working == 1) " · 1 set" else " · $working sets")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Pill("Finish", icon = Icons.Outlined.Check, filled = true, onClick = { showFinish = true })
        }

        AnimatedVisibility(
            visible = state.restRemaining > 0,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            RestBanner(
                remaining = state.restRemaining,
                total = state.restTotal,
                onSkip = vm::stopRest,
                onAdd = { vm.startRest(state.restRemaining + 30) }
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.exercises, key = { "p_${it.id}" }) { ex ->
                ExerciseCard(
                    name = ex.name,
                    prescription = prescriptionOf(ex),
                    notes = ex.notes,
                    substitutions = ex.substitutions,
                    sets = state.setsFor(ex.name),
                    last = state.lastTime[ex.name],
                    unit = state.unit,
                    onAddSet = { w, r, rir, warm -> vm.addSet(ex.name, w, r, rir, warm) },
                    onDeleteSet = vm::deleteSet
                )
            }

            items(state.extraExercises, key = { "x_$it" }) { name ->
                ExerciseCard(
                    name = name,
                    prescription = null,
                    notes = null,
                    substitutions = null,
                    sets = state.setsFor(name),
                    last = state.lastTime[name],
                    unit = state.unit,
                    onAddSet = { w, r, rir, warm -> vm.addSet(name, w, r, rir, warm) },
                    onDeleteSet = vm::deleteSet
                )
            }

            item {
                ForgeCard {
                    Text(
                        "Add an exercise",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newExercise,
                            onValueChange = { newExercise = it },
                            placeholder = { Text("Cable fly") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Pill(
                            "Add",
                            icon = Icons.Outlined.Add,
                            filled = true,
                            onClick = {
                                vm.addExercise(newExercise)
                                newExercise = ""
                            }
                        )
                    }
                }
            }
        }
    }

    if (showFinish) {
        FinishDialog(
            hasSets = state.sets.isNotEmpty(),
            onDismiss = { showFinish = false },
            onFinish = { notes -> showFinish = false; vm.finish(notes) },
            onDiscard = { showFinish = false; vm.discard() }
        )
    }
}

private fun prescriptionOf(ex: ProgramExercise): String = buildString {
    append("${ex.sets} × ")
    if (ex.repLow == ex.repHigh) append("${ex.repLow}") else append("${ex.repLow}-${ex.repHigh}")
    ex.rirTarget?.let { append(" @ ${trimNumber(it)} RIR") }
    ex.restSeconds?.let { append(" · ${it / 60}:${(it % 60).toString().padStart(2, '0')} rest") }
}

private fun trimNumber(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun RestBanner(remaining: Int, total: Int, onSkip: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Timer, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Rest  ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Pill("+30s", onClick = onAdd)
            Spacer(Modifier.width(6.dp))
            Pill("Skip", onClick = onSkip)
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else remaining.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun ExerciseCard(
    name: String,
    prescription: String?,
    notes: String?,
    substitutions: String?,
    sets: List<SetLog>,
    last: LastTime?,
    unit: WeightUnit,
    onAddSet: (Double, Int, Double?, Boolean) -> Unit,
    onDeleteSet: (SetLog) -> Unit
) {
    // Pre-fill from the previous set so repeat sets are one tap. `last` is loaded
    // asynchronously, so it has to be part of the key or the prefill never lands.
    val lastSet = sets.lastOrNull { !it.isWarmup }
    var weight by remember(lastSet?.id, name, last?.topWeightKg) {
        mutableStateOf(
            lastSet?.let { trimNumber(NutritionMath.kgToDisplay(it.weightKg, unit)) }
                ?: last?.let { trimNumber(NutritionMath.kgToDisplay(it.topWeightKg, unit)) }
                ?: ""
        )
    }
    var reps by remember(lastSet?.id, name, last?.topReps) {
        mutableStateOf((lastSet?.reps ?: last?.topReps)?.toString() ?: "")
    }
    var rir by remember(name) { mutableStateOf("") }
    var warmup by remember(name) { mutableStateOf(false) }

    ForgeCard {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        prescription?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        last?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Last time (${it.label}): ${trimNumber(NutritionMath.kgToDisplay(it.topWeightKg, unit))} ${unit.label} × ${it.topReps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        substitutions?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Alt: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }

        if (sets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            sets.forEachIndexed { i, s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (s.isWarmup) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (s.isWarmup) "W" else "${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.isWarmup) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${trimNumber(NutritionMath.kgToDisplay(s.weightKg, unit))} ${unit.label} × ${s.reps}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    s.rir?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${trimNumber(it)} RIR",
                            style = MaterialTheme.typography.labelSmall,
                            color = Macro.carbs
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.Close,
                        "Delete set",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { onDeleteSet(s) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumInput(weight, { weight = it }, unit.label, Modifier.weight(1.1f))
            Spacer(Modifier.width(8.dp))
            NumInput(reps, { reps = it }, "reps", Modifier.weight(0.9f))
            Spacer(Modifier.width(8.dp))
            NumInput(rir, { rir = it }, "RIR", Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                if (warmup) "Warm-up ✓" else "Warm-up",
                filled = warmup,
                color = if (warmup) Macro.carbs else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { warmup = !warmup }
            )
            Spacer(Modifier.weight(1f))
            val w = weight.replace(',', '.').toDoubleOrNull()
            val r = reps.toIntOrNull()
            Pill(
                "Log set",
                icon = Icons.Outlined.Add,
                filled = true,
                onClick = {
                    if (w != null && r != null && r > 0) {
                        onAddSet(w, r, rir.replace(',', '.').toDoubleOrNull(), warmup)
                        rir = ""
                        warmup = false
                    }
                }
            )
        }
    }
}

@Composable
private fun NumInput(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal
        ),
        modifier = modifier
    )
}

@Composable
private fun FinishDialog(
    hasSets: Boolean,
    onDismiss: () -> Unit,
    onFinish: (String?) -> Unit,
    onDiscard: () -> Unit
) {
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasSets) "Finish session?" else "Nothing logged") },
        text = {
            if (hasSets) {
                Column {
                    Text(
                        "Add a note about how it went - useful context for the coach later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        placeholder = { Text("Felt strong, left shoulder tight") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            } else {
                Text("This session has no sets. Discard it?")
            }
        },
        confirmButton = {
            if (hasSets) {
                TextButton(onClick = { onFinish(notes) }) { Text("Finish") }
            } else {
                TextButton(onClick = onDiscard) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep going") }
        }
    )
}
