package com.laksh.forge.feature.plan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.AdjustKind
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.StatTile
import com.laksh.forge.util.Dates
import kotlin.math.roundToInt

@Composable
fun CheckinScreen(onDone: () -> Unit) {
    val vm: CheckinViewModel = viewModel(factory = CheckinViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    PlanScaffold(
        title = "Weekly check-in",
        subtitle = state.data?.let { "Week of ${Dates.shortLabel(it.weekStart)}" },
        onBack = onDone
    ) { inner ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            state.error != null && state.plan == null -> EmptyState(
                icon = Icons.Outlined.EventBusy,
                title = "Couldn't load this week",
                body = state.error ?: "",
                modifier = Modifier.padding(inner),
                action = { Button(onClick = { vm.load() }, shapes = ButtonDefaults.shapes()) { Text("Try again") } }
            )
            state.plan == null -> EmptyState(
                icon = Icons.Outlined.EventBusy,
                title = "No active plan",
                body = "Check-ins adjust a plan's targets. Build a plan first and your first check-in will be a week later.",
                modifier = Modifier.padding(inner),
                action = { Button(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text("Back") } }
            )
            else -> AnimatedContent(
                targetState = state.saved != null || state.alreadyDone != null,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                label = "checkinDone"
            ) { finished ->
                if (finished) DoneContent(state, inner, onDone)
                else CheckinForm(state, vm, inner, onSave = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.save()
                })
            }
        }
    }
}

@Composable
private fun CheckinForm(
    state: CheckinUiState,
    vm: CheckinViewModel,
    inner: androidx.compose.foundation.layout.PaddingValues,
    onSave: () -> Unit
) {
    val data = state.data ?: return
    val s = state.settings
    val hide = s.hideNumbers
    val unit = s.weightUnit
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(inner.plusContent()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader("This week")
        ForgeCard {
            Row(Modifier.fillMaxWidth()) {
                if (!hide) {
                    StatTile(
                        label = "7-DAY AVG",
                        value = data.avgWeightKg?.let { "%.1f".format(NutritionMath.kgToDisplay(it, unit)) } ?: "-",
                        sub = unit.label,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "CHANGE",
                        value = data.weightChangeKg?.let { "%+.2f".format(NutritionMath.kgToDisplay(it, unit)) } ?: "-",
                        sub = "vs last week",
                        modifier = Modifier.weight(1f)
                    )
                }
                StatTile(
                    label = "LOGGED",
                    value = "${data.loggedDays7}/7",
                    sub = "days",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                if (!hide) {
                    StatTile(
                        label = "AVG INTAKE",
                        value = data.avgKcal7?.roundToInt()?.toString() ?: "-",
                        sub = "kcal on logged days",
                        modifier = Modifier.weight(1f)
                    )
                }
                StatTile(
                    label = "SLEEP",
                    value = data.avgSleepMinutes?.let { "${(it / 60).toInt()}h ${(it % 60).roundToInt()}m" } ?: "-",
                    sub = "average",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "STEPS",
                    value = data.avgSteps?.let { "%,d".format(it.roundToInt()) } ?: "-",
                    sub = "a day",
                    modifier = Modifier.weight(1f)
                )
            }
            if (data.avgSleepMinutes == null && data.avgSteps == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sleep and steps appear here once a wearable syncs through Health Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionHeader("How did it feel?")
        ForgeCard {
            Rating("Energy", "1 = drained, 5 = great", state.energy, vm::setEnergy)
            Spacer(Modifier.height(14.dp))
            Rating("Hunger", "1 = rarely hungry, 5 = hungry most of the day", state.hunger, vm::setHunger)
            Spacer(Modifier.height(14.dp))
            Rating("Training performance", "1 = struggling, 5 = hitting PRs", state.performance, vm::setPerformance)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = state.notes,
                onValueChange = vm::setNotes,
                label = { Text("Notes (optional)") },
                minLines = 2,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.scoffNeeded) {
            SectionHeader("Quick eating check")
            Text(
                "A few quick questions about eating, asked every 12 weeks during a cut. There are no right or wrong answers, and your answers stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Guardrails.SCOFF_QUESTIONS.forEachIndexed { i, q ->
                ForgeCard {
                    Text(q, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    ConnectedChoice(
                        options = listOf(true, false),
                        selected = state.scoff.getOrNull(i),
                        label = { if (it) "Yes" else "No" },
                        onSelect = { vm.setScoff(i, it) }
                    )
                }
            }
        }

        val proposal = state.proposal
        if (proposal != null && !hide) {
            SectionHeader("What Forge suggests")
            ProposalCard(state)
        } else if (proposal != null) {
            SectionHeader("What happens next")
            NoteCard(
                title = if (proposal.kcalDelta == 0) "No changes this week" else "A small adjustment",
                body = if (proposal.kcalDelta == 0) "Your plan stays as it is." else "Forge will make a small change to your targets based on your trend.",
                tone = NoteTone.SUPPORT
            )
        }
        proposal?.notes?.forEach { NoteCard(title = "Worth knowing", body = it, tone = NoteTone.SUPPORT) }

        state.error?.let { NoteCard(title = "Saving didn't work", body = it, tone = NoteTone.CAUTION) }

        Button(
            onClick = onSave,
            enabled = !state.saving && state.scoffComplete,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ButtonDefaults.MediumContainerHeight)
        ) {
            if (state.saving) {
                LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    proposal?.kcalDelta?.let { it != 0 } == true -> "Save and apply"
                    proposal?.kind == AdjustKind.END_CUT -> "Save and switch to maintenance"
                    proposal?.stepsDelta?.let { it != 0 } == true -> "Save and update steps"
                    else -> "Save check-in"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (!state.scoffComplete) {
            Text(
                "Please answer the eating questions to finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProposalCard(state: CheckinUiState) {
    val proposal = state.proposal ?: return
    val plan = state.plan ?: return
    val preview = state.preview
    ForgeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.TrendingFlat, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                when (proposal.kind) {
                    AdjustKind.INCREASE -> "Add ${proposal.kcalDelta} kcal a day"
                    AdjustKind.DECREASE -> "Remove ${-proposal.kcalDelta} kcal a day"
                    AdjustKind.STEPS_FIRST -> if (proposal.stepsDelta > 0) "Add ${proposal.stepsDelta} steps a day" else "Steps first"
                    AdjustKind.END_CUT -> "Switch to maintenance"
                    AdjustKind.ON_TRACK -> "On track - no change"
                    else -> "Hold steady this week"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(proposal.message, style = MaterialTheme.typography.bodyMedium)
        if (preview != null) {
            Spacer(Modifier.height(12.dp))
            Text("New daily targets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            TargetsRow(preview.kcal, preview.proteinG, preview.carbsG, preview.fatG, hideCalories = false)
            Text(
                "Was ${plan.kcal} kcal. Protein stays at ${preview.proteinG} g.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (preview.notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                BulletList(preview.notes)
            }
        }
    }
}

@Composable
private fun Rating(title: String, hint: String, value: Int?, onSelect: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ConnectedChoice(options = (1..5).toList(), selected = value, label = { it.toString() }, onSelect = onSelect)
    }
}

@Composable
private fun DoneContent(state: CheckinUiState, inner: androidx.compose.foundation.layout.PaddingValues, onDone: () -> Unit) {
    val c = state.saved ?: state.alreadyDone
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(inner.plusContent()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(80.dp))
        Text(
            if (state.saved != null) "Check-in saved" else "You've checked in this week",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        state.outcome?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (state.support) {
            SupportResources(
                intro = "It sounds like things may be hard right now, and you don't have to handle this alone. If you're in immediate danger, call 112."
            )
        } else if (c != null && c.summary.isNotBlank()) {
            ForgeCard { Text(c.summary, style = MaterialTheme.typography.bodyMedium) }
        }
        if (state.saved == null) {
            Text(
                "Your next check-in opens next week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
