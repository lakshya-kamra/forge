package com.laksh.forge.feature.train

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.util.Dates
import kotlinx.coroutines.delay

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
    val haptics = LocalHapticFeedback.current
    var showFinish by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onExit()
    }

    // A personal record gets a double pulse and a short-lived badge, then gets out of the way.
    val pr = state.pr
    LaunchedEffect(pr?.id) {
        if (pr != null) {
            if (state.haptics) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                delay(120)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            delay(2_800)
            vm.clearPr()
        }
    }
    // Keep the last event on screen while the exit animation runs.
    // A plain holder, not state: it only needs to outlive the event, not trigger recomposition.
    val lastPr = remember { PrHolder() }
    if (pr != null) lastPr.event = pr

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SessionHeader(state = state, onFinish = { showFinish = true })
            RestArea(vm)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(64.dp))
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "Couldn't open this session",
                        body = state.error.orEmpty(),
                        action = { Pill("Back", filled = true, onClick = onExit) }
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.slots, key = { it.key }, contentType = { if (it.index >= 0) "slot" else "extra" }) { slot ->
                        SlotCard(
                            slot = slot,
                            unit = state.unit,
                            roundingKg = state.roundingKg,
                            prSetIds = state.prSetIds,
                            haptics = state.haptics,
                            onLog = { w, r, rir, rpe, warm -> vm.logSet(slot.key, w, r, rir, rpe, warm) },
                            onDelete = { vm.deleteSet(it) },
                            onHowTo = { onOpenExercise(slot.exerciseId ?: ExerciseCatalog.keyFor(slot.name)) },
                            onSwap = if (slot.index >= 0) ({ vm.openSwap(slot.key) }) else null,
                            modifier = Modifier.animateItem()
                        )
                    }
                    item(key = "add", contentType = "add") {
                        AddExerciseCard(onAdd = vm::addExercise, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = pr != null,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = 0.8f) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.9f) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
        ) {
            lastPr.event?.let { PrCelebration(it, state.unit, state.reduceMotion) }
        }
    }

    if (showFinish) {
        FinishDialog(
            hasSets = state.slots.any { it.sets.isNotEmpty() },
            onDismiss = { showFinish = false },
            onFinish = { notes ->
                showFinish = false
                if (state.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.finish(notes)
            },
            onDiscard = {
                showFinish = false
                vm.discard()
            }
        )
    }

    if (state.finishing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Saving session") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Updating your progression...")
                }
            }
        )
    }

    state.summary?.let { summary ->
        SummaryDialog(summary, state.unit, onDone = vm::closeSummary)
    }

    state.swap?.let { sheet ->
        SwapSheetContent(
            sheet = sheet,
            onPick = { info ->
                if (state.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.swap(sheet.key, info)
            },
            onDismiss = vm::closeSwap
        )
    }
}

@Composable
private fun SessionHeader(state: SessionState, onFinish: () -> Unit) {
    // The elapsed label only needs minute resolution; tick twice a minute, not every frame.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
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
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(
                state.programName,
                state.weekLabel
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                buildString {
                    state.session?.let { append(Dates.durationLabel(it.startedAt, now)) }
                    val n = state.workingSetCount
                    if (n > 0) append(if (n == 1) " · 1 set" else " · $n sets")
                    state.dayFocus?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Pill("Finish", icon = Icons.Outlined.Check, filled = true, onClick = onFinish)
    }
}

/** Collects the rest flow on its own so the per-second tick recomposes only this banner. */
@Composable
private fun RestArea(vm: SessionViewModel) {
    val rest by vm.rest.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(rest.remaining) {
        if (rest.remaining > 0) wasRunning = true
        else if (wasRunning) {
            wasRunning = false
            // Only a timer that ran out (not one that was skipped) buzzes.
            if (rest.total > 0 && vm.state.value.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    AnimatedVisibility(
        visible = rest.remaining > 0,
        enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
    ) {
        RestBanner(rest = rest, onSkip = vm::stopRest, onAdd = { vm.addRest(30) })
    }
}

@Composable
private fun FinishDialog(
    hasSets: Boolean,
    onDismiss: () -> Unit,
    onFinish: (String?) -> Unit,
    onDiscard: () -> Unit
) {
    var notes by rememberSaveable { mutableStateOf("") }
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
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep going") } }
    )
}

@Composable
private fun SummaryDialog(summary: SessionSummary, unit: WeightUnit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Session saved") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${summary.workingSets} working set${if (summary.workingSets == 1) "" else "s"} · " +
                        "${displayKg(summary.volumeKg, unit)} ${unit.label} total volume",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (summary.prs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EmojiEvents, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Personal records", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    summary.prs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (summary.changes.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Program updates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    summary.changes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } }
    )
}

private class PrHolder {
    var event: PrEvent? = null
}
