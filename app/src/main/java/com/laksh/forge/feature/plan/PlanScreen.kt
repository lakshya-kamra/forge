package com.laksh.forge.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.planSourceLabel
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlanScreen(
    onBack: () -> Unit,
    onCheckin: () -> Unit,
    onReplan: () -> Unit,
    onOpenProgram: (String) -> Unit = {}
) {
    val vm: PlanViewModel = viewModel(factory = PlanViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmReplan by rememberSaveable { mutableStateOf(false) }
    val plan = state.plan

    PlanScaffold(
        title = "Your plan",
        subtitle = plan?.let { "${PhaseCopy.name(it.phase)} · ${planSourceLabel(it.source)}" },
        onBack = onBack,
        actions = {
            if (plan != null) {
                IconButton(onClick = { confirmReplan = true }) { Icon(Icons.Outlined.Refresh, "Re-plan") }
            }
        }
    ) { inner ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            state.error != null -> EmptyState(
                icon = Icons.Outlined.Flag,
                title = "Couldn't load your plan",
                body = state.error ?: "",
                modifier = Modifier.padding(inner)
            )
            plan == null -> EmptyState(
                icon = Icons.Outlined.Flag,
                title = "Build your personalised plan",
                body = "Answer a few questions, add tape measurements and optional photos, and Forge recommends a starting " +
                    "phase with daily targets - and explains why.",
                modifier = Modifier.padding(inner),
                action = {
                    Button(onClick = onReplan, shapes = ButtonDefaults.shapes()) { Text("Build my plan") }
                }
            )
            else -> PlanContent(state, inner, onCheckin, onReplan = { confirmReplan = true }, onOpenProgram = onOpenProgram)
        }
    }

    if (confirmReplan) {
        AlertDialog(
            onDismissRequest = { confirmReplan = false },
            title = { Text("Re-plan?") },
            text = {
                Text("You'll go through the questions again and get a fresh recommendation. Your current plan stays until you accept the new one, and your history is kept.")
            },
            confirmButton = {
                TextButton(onClick = { confirmReplan = false; onReplan() }) { Text("Re-plan") }
            },
            dismissButton = { TextButton(onClick = { confirmReplan = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PlanContent(
    state: PlanUiState,
    inner: androidx.compose.foundation.layout.PaddingValues,
    onCheckin: () -> Unit,
    onReplan: () -> Unit,
    onOpenProgram: (String) -> Unit
) {
    val plan = state.plan ?: return
    val d = state.details
    val s = state.settings
    val hide = s.hideNumbers

    LazyColumn(
        contentPadding = inner.plusContent(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "hero", contentType = "hero") {
            Column(
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(22.dp)
            ) {
                Badge(planSourceLabel(plan.source), color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(10.dp))
                Text(
                    PhaseCopy.name(plan.phase),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Day ${state.daysIn} of ${state.totalDays}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(12.dp))
                val fraction = (state.daysIn.toFloat() / state.totalDays.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Started ${Dates.shortLabel(plan.startDate)}" +
                        (plan.reviewDate?.let { " · review ${Dates.shortLabel(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        item(key = "checkin", contentType = "card") {
            ForgeCard(Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EventAvailable, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Weekly check-in", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                state.checkedInThisWeek -> "Done for this week"
                                state.checkinDue -> "Due now"
                                else -> "Next: ${state.nextCheckin?.let { Dates.dayLabel(it) } ?: "-"}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.checkinDue && !state.checkedInThisWeek) {
                        Button(onClick = onCheckin, shapes = ButtonDefaults.shapes()) { Text("Check in") }
                    } else {
                        OutlinedButton(onClick = onCheckin, shapes = ButtonDefaults.shapes()) { Text("Open") }
                    }
                }
            }
        }

        item(key = "why", contentType = "card") {
            ForgeCard(Modifier.animateItem()) {
                Text("Why this phase", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(plan.rationale, style = MaterialTheme.typography.bodyMedium)
                val bf = d?.bodyFat
                if (bf != null && !hide && !s.isMinor) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Body fat at the start: about ${bf.low.roundToInt()}-${bf.high.roundToInt()}% (${bf.method.lowercase()}). " +
                            "A likely range, not an exact figure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (d != null && d.ruleExplanation.isNotBlank() && d.ruleExplanation != plan.rationale) {
                    Spacer(Modifier.height(10.dp))
                    Expandable("What the evidence-based rules said") {
                        Text(d.ruleExplanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item(key = "targets", contentType = "card") {
            ForgeCard(Modifier.animateItem()) {
                Text("Daily targets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                TargetsRow(plan.kcal, plan.proteinG, plan.carbsG, plan.fatG, hideCalories = hide)
                Spacer(Modifier.height(10.dp))
                Text("Fibre: at least ${plan.fiberG} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan.stepsTarget?.let {
                    Text("Steps: about ${"%,d".format(it)} a day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!hide) {
                    Text(
                        "Planned pace: " + rateText(plan.rateKgPerWeek, s),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    d?.let {
                        Text(
                            "Maintenance estimate ${it.tdee} kcal - ${it.tdeeBasis}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (!hide) {
            item(key = "progress", contentType = "chart") {
                ProgressVsPlan(state, Modifier.animateItem())
            }
        }

        if (d != null) {
            item(key = "training", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    Text("Training", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(d.training, style = MaterialTheme.typography.bodyMedium)
                    if (d.programNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Programs that fit",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        d.programNames.forEachIndexed { i, name ->
                            val id = d.programIds.getOrNull(i)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .then(if (id != null) Modifier.clickable { onOpenProgram(id) } else Modifier)
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }
            item(key = "stops", contentType = "card") {
                ForgeCard(Modifier.animateItem()) {
                    Text("When to change course", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    BulletList(d.stopConditions)
                    if (d.nextPhase.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Likely next: ${d.nextPhase}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        val notes = plan.guardrailNotes?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (notes.isNotEmpty()) {
            item(key = "notes", contentType = "note") {
                NoteCard(title = "Safety adjustments", body = "", modifier = Modifier.animateItem()) { BulletList(notes) }
            }
        }

        if (s.edScreenPositive) {
            item(key = "support", contentType = "note") { SupportResources(Modifier.animateItem()) }
        }

        item(key = "historyHeader", contentType = "header") { SectionHeader("Check-in history", Modifier.animateItem()) }
        if (state.checkins.isEmpty()) {
            item(key = "historyEmpty", contentType = "empty") {
                Text(
                    "Your first check-in is a week after you start. Each one looks at your 7-day average weight, logging, sleep and steps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 4.dp)
                )
            }
        } else {
            items(state.checkins, key = { "c${it.id}" }, contentType = { "checkin" }) { c ->
                ForgeCard(Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Week of ${Dates.shortLabel(c.weekStart)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (c.adjustmentKcal != 0 && !hide) {
                            Badge("%+d kcal".format(c.adjustmentKcal))
                        }
                    }
                    if (!hide) {
                        c.weightChangeKg?.let {
                            Text(
                                "7-day average %+.2f %s".format(NutritionMath.kgToDisplay(it, s.weightUnit), s.weightUnit.label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (c.summary.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(c.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item(key = "replan", contentType = "action") {
            FilledTonalButton(
                onClick = onReplan,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Re-plan")
            }
        }
    }
}

@Composable
private fun ProgressVsPlan(state: PlanUiState, modifier: Modifier = Modifier) {
    val plan = state.plan ?: return
    val s = state.settings
    val trend = state.trend
    ForgeCard(modifier) {
        Text("Progress vs plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (trend == null || trend.points.size < 2) {
            Text(
                "Log a few morning weigh-ins and your trend will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@ForgeCard
        }
        val unit = s.weightUnit
        LineChart(
            points = trend.points.map {
                ChartPoint(it.timestamp.toFloat(), NutritionMath.kgToDisplay(it.smoothed, unit).toFloat(), Dates.shortLabel(it.timestamp))
            },
            rawPoints = trend.points.map { ChartPoint(it.timestamp.toFloat(), NutritionMath.kgToDisplay(it.raw, unit).toFloat()) },
            yFormatter = { "%.1f".format(it) },
            height = 190.dp
        )
        Spacer(Modifier.height(8.dp))
        val observed = state.observedPct
        val band = state.details?.band
        Text(
            "Trend: " + rateText(trend.kgPerWeek, s) + " · plan: " + rateText(plan.rateKgPerWeek, s),
            style = MaterialTheme.typography.bodyMedium
        )
        val verdict = when {
            observed == null || band == null -> "A trend needs about two weeks of weigh-ins before it means much."
            band.contains(observed) -> "Within the planned range."
            abs(observed) < 0.05 && band.minPct <= 0 && band.maxPct >= 0 -> "Holding steady."
            else -> "Outside the planned range - your next check-in will look at whether to adjust."
        }
        Text(verdict, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun rateText(kgPerWeek: Double, s: ForgeSettings): String =
    if (abs(kgPerWeek) < 0.005) "steady"
    else "%+.2f %s/week".format(NutritionMath.kgToDisplay(kgPerWeek, s.weightUnit), s.weightUnit.label)
