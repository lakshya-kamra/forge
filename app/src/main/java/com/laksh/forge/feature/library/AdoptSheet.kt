package com.laksh.forge.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.program.LibraryProgram
import com.laksh.forge.domain.program.Strength
import com.laksh.forge.ui.components.Pill

@Composable
internal fun AdoptSheet(
    program: LibraryProgram,
    fields: List<SetupField>,
    unit: WeightUnit,
    hasActiveProgram: Boolean,
    adopting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStart: (values: Map<String, Double>, roundingKg: Double) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    var rounding by rememberSaveable { mutableDoubleStateOf(2.5) }
    // Text per field, in the user's display unit, keyed "BASIS|key" like the stored values.
    val text = remember(fields) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { f ->
                f.prefillKg?.let { put(fieldKey(f), Strength.fmt(round1(NutritionMath.kgToDisplay(it, unit)))) }
            }
        }
    }
    val bases = fields.map { it.input.basis }.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            item(key = "title") {
                Column {
                    Text(
                        "Set up ${program.name}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        setupIntro(bases, program),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "rounding") {
                Column {
                    Text("Smallest jump you can load", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2.5 to "2.5 kg", 1.25 to "1.25 kg (small plates)").forEach { (v, label) ->
                            FilterChip(
                                selected = rounding == v,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    rounding = v
                                },
                                label = { Text(label) },
                                leadingIcon = if (rounding == v) {
                                    { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                                shape = MaterialTheme.shapes.extraLarge
                            )
                        }
                    }
                }
            }

            if (fields.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        "Nothing to enter: this program runs on rep ranges and effort. Your first session " +
                            "finds your working weights, and every session after builds on what you log.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            items(fields, key = { fieldKey(it) }) { f ->
                SetupFieldRow(
                    field = f,
                    value = text[fieldKey(f)].orEmpty(),
                    onValue = { text[fieldKey(f)] = it },
                    unit = unit,
                    tmOfMax = program.engine.tmOfMax,
                    roundingKg = rounding
                )
            }

            if (hasActiveProgram) {
                item(key = "replace") {
                    Text(
                        "This becomes your active program. Your current one stays in your list and all logged sessions are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            error?.let {
                item(key = "error") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }

            item(key = "start") {
                Button(
                    onClick = {
                        val values = fields.mapNotNull { f ->
                            val raw = text[fieldKey(f)]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
                                ?: return@mapNotNull null
                            fieldKey(f) to NutritionMath.displayToKg(raw, unit)
                        }.toMap()
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onStart(values, rounding)
                    },
                    enabled = !adopting,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ButtonDefaults.MediumContainerHeight)
                ) {
                    if (adopting) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start program", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private fun fieldKey(f: SetupField) = "${f.input.basis}|${f.input.key}"

private fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0

private fun setupIntro(bases: Set<String>, p: LibraryProgram): String = when {
    "TM" in bases -> buildString {
        append("Every set is a percentage of a training max (TM) for each main lift.")
        p.engine.tmOfMax?.let { append(" The program sets the TM at ${Strength.fmt(it * 100)}% of your max - use the estimate helper if you don't know it.") }
    }
    "1RM" in bases -> "Weights are percentages of your current 1-rep max for each lift. Use a recent max, or estimate one from a hard set."
    "5RM" in bases -> "The program works from your current 5-rep max on each lift. Use a recent one, or estimate it from a hard set."
    "START" in bases -> "Optional: starting weights for the main lifts. Leave any blank and your first session finds it."
    else -> "This program is driven by rep ranges and effort, so there is nothing to enter."
}

private fun basisTitle(basis: String) = when (basis) {
    "TM" -> "training max"
    "1RM" -> "1-rep max"
    "5RM" -> "5-rep max"
    else -> "starting weight"
}

@Composable
private fun SetupFieldRow(
    field: SetupField,
    value: String,
    onValue: (String) -> Unit,
    unit: WeightUnit,
    tmOfMax: Double?,
    roundingKg: Double
) {
    var estimating by rememberSaveable(fieldKey(field)) { mutableStateOf(false) }
    var setWeight by rememberSaveable(fieldKey(field) + "_w") { mutableStateOf("") }
    var setReps by rememberSaveable(fieldKey(field) + "_r") { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current

    Column {
        Text(
            "${field.input.label} - ${basisTitle(field.input.basis)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
            label = { Text(if (field.input.basis == "START") "Optional" else basisTitle(field.input.basis).replaceFirstChar { it.uppercase() }) },
            suffix = { Text(unit.label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        field.hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Pill(
            if (estimating) "Hide estimate" else "Estimate from a recent set",
            icon = Icons.Outlined.Calculate,
            onClick = { estimating = !estimating }
        )
        AnimatedVisibility(
            visible = estimating,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
        ) {
            val w = setWeight.replace(',', '.').toDoubleOrNull()
            val r = setReps.toIntOrNull()
            // Epley gets unreliable past about 12 reps, so the helper stops there.
            val e1 = if (w != null && w > 0 && r != null && r in 1..12) {
                Strength.e1rm(NutritionMath.displayToKg(w, unit), r)
            } else null
            val resultKg = e1?.let {
                val raw = when (field.input.basis) {
                    "TM" -> it * (tmOfMax ?: 1.0)
                    "1RM" -> it
                    "5RM" -> Strength.weightForReps(it, 5)
                    else -> Strength.weightForReps(it, field.input.reps)
                }
                Strength.roundTo(raw, roundingKg)
            }
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = setWeight,
                        onValueChange = { setWeight = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        label = { Text("Weight") },
                        suffix = { Text(unit.label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = setReps,
                        onValueChange = { setReps = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Reps") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (resultKg != null && e1 != null) {
                    val shown = Strength.fmt(round1(NutritionMath.kgToDisplay(resultKg, unit)))
                    Text(
                        buildString {
                            append("Estimated max ${Strength.fmt(round1(NutritionMath.kgToDisplay(e1, unit)))} ${unit.label} (Epley)")
                            when (field.input.basis) {
                                "TM" -> append(
                                    if (tmOfMax != null) " → TM at ${Strength.fmt(tmOfMax * 100)}%: $shown ${unit.label}"
                                    else " → use $shown ${unit.label} or a little less as your TM"
                                )
                                "1RM" -> append(" → $shown ${unit.label}")
                                "5RM" -> append(" → 5-rep max about $shown ${unit.label}")
                                else -> append(" → ${field.input.reps}-rep max about $shown ${unit.label}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Pill("Use $shown ${unit.label}", filled = true, icon = Icons.Outlined.Check, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onValue(shown)
                        estimating = false
                    })
                } else {
                    Text(
                        "Enter a recent hard set of 1-12 reps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
