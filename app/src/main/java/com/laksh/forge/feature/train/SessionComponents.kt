package com.laksh.forge.feature.train

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toPath
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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Severity
import com.laksh.forge.domain.program.Prescription
import com.laksh.forge.domain.program.SetTarget
import com.laksh.forge.domain.program.Strength
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill

// ------------------------------------------------------------------ formatting

internal fun displayKg(kg: Double, unit: WeightUnit): String {
    val v = NutritionMath.kgToDisplay(kg, unit)
    return Strength.fmt(kotlin.math.round(v * 10) / 10.0)
}

internal fun targetText(t: SetTarget, unit: WeightUnit): String {
    val reps = buildString {
        append(t.reps)
        t.repsHigh?.takeIf { it > t.reps }?.let { append("-").append(it) }
        if (t.amrap) append("+")
    }
    return if (t.weightKg != null) "${displayKg(t.weightKg, unit)} ${unit.label} × $reps" else "$reps reps"
}

/** One line for the whole prescription: runs of identical sets collapse ("3 × 5 · 100 kg"). */
internal fun prescriptionLine(p: Prescription, unit: WeightUnit): String? {
    if (p.sets.isEmpty()) return null
    if (p.workUp) return p.sets.first().label?.let { "Work up to a $it" } ?: "Work up to a heavy set"
    val parts = mutableListOf<String>()
    var i = 0
    while (i < p.sets.size) {
        val s = p.sets[i]
        var j = i
        while (j + 1 < p.sets.size && p.sets[j + 1].copy(label = null) == s.copy(label = null)) j++
        val n = j - i + 1
        val reps = "${s.reps}${s.repsHigh?.takeIf { it > s.reps }?.let { "-$it" }.orEmpty()}${if (s.amrap) "+" else ""}"
        parts += "$n × $reps" + (s.weightKg?.let { " · ${displayKg(it, unit)} ${unit.label}" }.orEmpty())
        i = j + 1
    }
    val rir = p.sets.firstNotNullOfOrNull { it.rir }?.let { " · ${Strength.fmt(it)} RIR" }.orEmpty()
    return parts.joinToString(", ") + rir
}

// ------------------------------------------------------------------ rest timer

@Composable
internal fun RestBanner(rest: RestState, onSkip: () -> Unit, onAdd: () -> Unit) {
    val target = if (rest.total == 0) 0f else rest.remaining.toFloat() / rest.total
    // Ticks arrive once a second; a no-bounce spring turns them into continuous motion.
    val progress by animateFloatAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "rest")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Rest  ${rest.remaining / 60}:${(rest.remaining % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Pill("+30s", onClick = onAdd)
            Spacer(Modifier.width(6.dp))
            Pill("Skip", onClick = onSkip)
        }
        Spacer(Modifier.height(10.dp))
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

// ------------------------------------------------------------------ PR celebration

/** Built once and shared: the morph is immutable and only the progress changes. */
private val PrMorph: Morph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie12Sided) }

@Composable
internal fun PrCelebration(event: PrEvent, unit: WeightUnit, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(event.id) {
        if (reduceMotion) progress.snapTo(1f)
        else {
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }
    val badgeColor = MaterialTheme.colorScheme.tertiary
    val iconTint = MaterialTheme.colorScheme.onTertiary
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 6.dp
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                // Read the animated value only in draw/layer lambdas so the badge never recomposes per frame.
                Canvas(Modifier.size(52.dp).graphicsLayer { rotationZ = progress.value * 45f }) {
                    val f = progress.value.coerceIn(0f, 1f)
                    PrMorph.toPath(f, path)
                    matrix.reset()
                    matrix.scale(size.width, size.height)
                    path.transform(matrix)
                    drawPath(path, badgeColor)
                }
                Icon(
                    Icons.Outlined.EmojiEvents, null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            val s = progress.value.coerceIn(0f, 1.2f)
                            scaleX = s
                            scaleY = s
                        }
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (event.heaviest) "New heaviest lift" else "New estimated 1RM",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "${event.exercise} · ${displayKg(event.weightKg, unit)} ${unit.label} × ${event.reps}" +
                        if (!event.heaviest) " (~${displayKg(event.e1rmKg, unit)} ${unit.label})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ------------------------------------------------------------------ exercise card

@Composable
internal fun SlotCard(
    slot: SlotUi,
    unit: WeightUnit,
    roundingKg: Double,
    prSetIds: Set<Long>,
    haptics: Boolean,
    onLog: (weight: Double, reps: Int, rir: Double?, rpe: Double?, warmup: Boolean) -> Unit,
    onDelete: (SetLog) -> Unit,
    onHowTo: () -> Unit,
    onSwap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val p = slot.prescription
    var showNotes by rememberSaveable(slot.key) { mutableStateOf(false) }
    ForgeCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(slot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                slot.swappedFrom?.let {
                    Text("Instead of $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (slot.optional) {
                Pill("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("How to", icon = Icons.Outlined.PlayCircle, onClick = onHowTo)
            if (onSwap != null) Pill("Swap", icon = Icons.Outlined.SwapHoriz, onClick = onSwap)
        }
        prescriptionLine(p, unit)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        p.reason?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.flag?.let { flag ->
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (flag.severity == Severity.INFO) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.WarningAmber, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(6.dp))
                Text(flag.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        slot.lastTime?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "Last time (${it.label}): ${displayKg(it.topWeightKg, unit)} ${unit.label} × ${it.topReps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        slot.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(6.dp))
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = if (showNotes) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showNotes = !showNotes }
            )
        }

        Spacer(Modifier.height(10.dp))
        slot.warmups.forEach { s ->
            LoggedRow(set = s, label = "W", target = null, unit = unit, isPr = false, onDelete = { onDelete(s) })
        }
        val working = slot.working
        val planned = p.sets.size
        val rows = maxOf(planned, working.size)
        for (i in 0 until rows) {
            val logged = working.getOrNull(i)
            val target = p.sets.getOrNull(i)
            when {
                logged != null -> LoggedRow(logged, "${i + 1}", target, unit, logged.id in prSetIds, onDelete = { onDelete(logged) })
                i == working.size -> SetEditor(
                    editorKey = "${slot.key}_$i",
                    title = "Set ${i + 1} of $planned" + (target?.label?.let { " · $it" } ?: ""),
                    target = target,
                    prefillKg = target?.weightKg ?: working.lastOrNull()?.weightKg ?: p.suggestedKg ?: slot.lastTime?.topWeightKg,
                    prefillReps = target?.reps ?: working.lastOrNull()?.reps ?: slot.lastTime?.topReps,
                    unit = unit,
                    roundingKg = roundingKg,
                    haptics = haptics,
                    onLog = onLog
                )
                else -> PendingRow("${i + 1}", target, unit)
            }
        }
        if (working.size >= planned) {
            SetEditor(
                editorKey = "${slot.key}_extra_${working.size}",
                title = if (planned == 0) "Set ${working.size + 1}" else "Extra set",
                target = null,
                prefillKg = working.lastOrNull()?.weightKg ?: p.suggestedKg ?: slot.lastTime?.topWeightKg,
                prefillReps = working.lastOrNull()?.reps ?: slot.lastTime?.topReps,
                unit = unit,
                roundingKg = roundingKg,
                haptics = haptics,
                onLog = onLog
            )
        }
    }
}

@Composable
private fun SetBadge(label: String, done: Boolean, warm: Boolean) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                when {
                    warm -> MaterialTheme.colorScheme.surfaceContainerHighest
                    done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                warm -> MaterialTheme.colorScheme.onSurfaceVariant
                done -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun LoggedRow(set: SetLog, label: String, target: SetTarget?, unit: WeightUnit, isPr: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SetBadge(label, done = true, warm = set.isWarmup)
        Spacer(Modifier.width(12.dp))
        Text(
            "${displayKg(set.weightKg, unit)} ${unit.label} × ${set.reps}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        val effort = set.rpe?.let { "RPE ${Strength.fmt(it)}" } ?: set.rir?.let { "${Strength.fmt(it)} RIR" }
        effort?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        val missed = target != null && set.reps < target.reps
        if (missed) {
            Spacer(Modifier.width(8.dp))
            Text("target ${target?.reps}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (isPr) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.EmojiEvents, "Personal record", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.Close,
            "Delete set",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(5.dp)
        )
    }
}

@Composable
private fun PendingRow(label: String, target: SetTarget?, unit: WeightUnit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SetBadge(label, done = false, warm = false)
        Spacer(Modifier.width(12.dp))
        Text(
            target?.let { targetText(it, unit) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        target?.label?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

private val RIR_OPTIONS = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
private val RPE_OPTIONS = listOf(6.0, 7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0)

/**
 * The current set, prefilled with the prescription so a set done as planned is one tap.
 * Keyed by set so the next set re-prefills once this one is logged.
 */
@Composable
private fun SetEditor(
    editorKey: String,
    title: String,
    target: SetTarget?,
    prefillKg: Double?,
    prefillReps: Int?,
    unit: WeightUnit,
    roundingKg: Double,
    haptics: Boolean,
    onLog: (weight: Double, reps: Int, rir: Double?, rpe: Double?, warmup: Boolean) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    var weight by rememberSaveable(editorKey, prefillKg) { mutableStateOf(prefillKg?.let { displayKg(it, unit) }.orEmpty()) }
    var reps by rememberSaveable(editorKey, prefillReps) { mutableStateOf(prefillReps?.takeIf { it > 0 }?.toString().orEmpty()) }
    var rpeMode by rememberSaveable(editorKey) { mutableStateOf(false) }
    var effort by rememberSaveable(editorKey) { mutableStateOf<Double?>(null) }
    var warmup by rememberSaveable(editorKey) { mutableStateOf(false) }
    val stepDisplay = Strength.stepInDisplay(roundingKg, unit)
    // The text is rounded to 0.1 for display; logging the untouched prefill must store the
    // exact prescribed kg (52.5 kg, not 115.7 lb -> 52.48 kg) so the set isn't judged a miss.
    val prefillText = prefillKg?.let { displayKg(it, unit) }
    fun tick(type: HapticFeedbackType) { if (haptics) hapticFeedback.performHapticFeedback(type) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                target?.let { t ->
                    if (t.amrap) Pill("AMRAP", filled = true, color = MaterialTheme.colorScheme.tertiary)
                    t.rir?.let {
                        Spacer(Modifier.width(6.dp))
                        Pill("${Strength.fmt(it)} RIR target", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (target?.amrap == true) {
                Text(
                    "As many good reps as possible - at least ${target.reps}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = {
                        tick(HapticFeedbackType.SegmentTick)
                        val v = (weight.replace(',', '.').toDoubleOrNull() ?: 0.0) - stepDisplay
                        weight = Strength.fmt(v.coerceAtLeast(0.0))
                    },
                    shapes = IconButtonDefaults.shapes()
                ) { Icon(Icons.Outlined.Remove, "Less weight") }
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text(unit.label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1.2f)
                        .padding(horizontal = 4.dp)
                )
                FilledTonalIconButton(
                    onClick = {
                        tick(HapticFeedbackType.SegmentTick)
                        val v = (weight.replace(',', '.').toDoubleOrNull() ?: 0.0) + stepDisplay
                        weight = Strength.fmt(v)
                    },
                    shapes = IconButtonDefaults.shapes()
                ) { Icon(Icons.Outlined.Add, "More weight") }
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("reps") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.8f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    if (rpeMode) "RPE" else "RIR",
                    filled = true,
                    color = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Outlined.SwapHoriz,
                    onClick = {
                        tick(HapticFeedbackType.SegmentTick)
                        rpeMode = !rpeMode
                        effort = null
                    }
                )
                Spacer(Modifier.width(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    (if (rpeMode) RPE_OPTIONS else RIR_OPTIONS).forEach { v ->
                        val selected = effort == v
                        Pill(
                            if (!rpeMode && v == 5.0) "5+" else Strength.fmt(v),
                            filled = selected,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                tick(HapticFeedbackType.SegmentTick)
                                effort = if (selected) null else v
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    if (warmup) "Warm-up ✓" else "Warm-up",
                    filled = warmup,
                    color = if (warmup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        tick(if (warmup) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        warmup = !warmup
                    }
                )
                Spacer(Modifier.weight(1f))
                val w = weight.replace(',', '.').toDoubleOrNull()
                val r = reps.toIntOrNull()
                val valid = w != null && w >= 0 && r != null && r > 0
                Button(
                    onClick = {
                        if (w != null && r != null && r > 0) {
                            tick(HapticFeedbackType.Confirm)
                            val exact = if (prefillKg != null && weight == prefillText) NutritionMath.kgToDisplay(prefillKg, unit) else w
                            onLog(exact, r, if (rpeMode) null else effort, if (rpeMode) effort else null, warmup)
                            effort = null
                            warmup = false
                        } else {
                            tick(HapticFeedbackType.Reject)
                        }
                    },
                    enabled = valid,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (warmup) "Log warm-up" else "Log set")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ swap sheet

@Composable
internal fun SwapSheetContent(
    sheet: SwapSheet,
    onPick: (com.laksh.forge.domain.exercise.ExerciseInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            item(key = "title") {
                Column {
                    Text("Swap ${sheet.current}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Alternatives that train the same primary muscles. The swap is for this session only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (sheet.canRevert) {
                item(key = "revert") {
                    Pill("Back to the program's exercise", icon = Icons.AutoMirrored.Outlined.Undo, filled = true, onClick = { onPick(null) })
                }
            }
            when {
                sheet.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(48.dp))
                    }
                }
                sheet.options.isEmpty() -> item(key = "empty") {
                    Text(
                        "No catalogued alternatives for this exercise yet. Use \"Add an exercise\" below to log a different lift.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                else -> items(sheet.options, key = { it.info.id }) { opt ->
                    ForgeCard(
                        onClick = { onPick(opt.info) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(opt.info.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append(opt.info.primaryMuscles.joinToString { it.replace('_', ' ') })
                                if (opt.info.equipment.isNotEmpty()) append(" · ").append(opt.info.equipment.joinToString { it.replace('_', ' ') })
                                if (!opt.exactMatch) append(" · partial match")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ add exercise

@Composable
internal fun AddExerciseCard(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    ForgeCard(modifier) {
        Text("Add an exercise", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Cable fly") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Pill("Add", icon = Icons.Outlined.Add, filled = true, onClick = {
                onAdd(name)
                name = ""
            })
        }
    }
}
