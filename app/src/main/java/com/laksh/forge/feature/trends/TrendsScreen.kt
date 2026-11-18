package com.laksh.forge.feature.trends

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.components.BarChart
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.StatTile
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.util.Dates
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(vm: TrendsViewModel = viewModel(factory = TrendsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val unit = state.settings.weightUnit

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Trends",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        item { WindowTabs(state.window, vm::setWindow) }

        state.feedback?.let { fb ->
            item {
                ForgeCard(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Insights, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(fb, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // -------------------------------------------------- weight
        item { SectionHeader("Bodyweight") }
        item {
            ForgeCard {
                val trend = state.trend
                if (trend == null || trend.points.size < 2) {
                    EmptyState(
                        icon = Icons.Outlined.Insights,
                        title = "Log a few weigh-ins",
                        body = "Two or more readings and the trend line appears. Daily is best - the smoothing handles the noise."
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(
                            label = "TREND",
                            value = "%.1f %s".format(
                                NutritionMath.kgToDisplay(trend.latestSmoothed ?: 0.0, unit),
                                unit.label
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "RATE",
                            value = "%+.2f".format(NutritionMath.kgToDisplay(trend.kgPerWeek, unit)),
                            sub = "${unit.label}/week",
                            accent = rateColor(trend.kgPerWeek),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "CHANGE",
                            value = trend.changeOverWindow?.let {
                                "%+.1f".format(NutritionMath.kgToDisplay(it, unit))
                            } ?: "-",
                            sub = state.window.label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    val smoothed = trend.points.map {
                        ChartPoint(
                            x = it.timestamp.toFloat(),
                            y = NutritionMath.kgToDisplay(it.smoothed, unit).toFloat(),
                            label = Dates.shortLabel(it.timestamp)
                        )
                    }
                    val raw = trend.points.map {
                        ChartPoint(
                            x = it.timestamp.toFloat(),
                            y = NutritionMath.kgToDisplay(it.raw, unit).toFloat()
                        )
                    }
                    LineChart(
                        points = smoothed,
                        rawPoints = raw,
                        lineColor = MaterialTheme.colorScheme.primary,
                        yFormatter = { "%.1f".format(it) },
                        height = 210.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Dots are individual weigh-ins; the line is the smoothed trend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }

        // -------------------------------------------------- calories
        item { SectionHeader("Intake") }
        item {
            ForgeCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile(
                        label = "AVG INTAKE",
                        value = state.avgKcal?.roundToInt()?.toString() ?: "-",
                        sub = "kcal/day",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "AVG PROTEIN",
                        value = state.avgProtein?.roundToInt()?.toString() ?: "-",
                        sub = "g/day",
                        accent = Macro.protein,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "MAINTENANCE",
                        value = state.settings.learnedTdee.takeIf { it > 0 }?.roundToInt()?.toString() ?: "-",
                        sub = if (state.settings.learnedTdee > 0) "learned" else "need more data",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(18.dp))
                BarChart(
                    points = state.dayTotals.map {
                        ChartPoint(0f, it.kcal.toFloat(), Dates.shortLabel(it.localDate))
                    },
                    target = state.targets?.kcal?.toFloat(),
                    height = 190.dp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dashed line is your daily target.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }

        // -------------------------------------------------- training volume
        if (state.volume.isNotEmpty()) {
            item { SectionHeader("Training volume") }
            item {
                ForgeCard {
                    val totalSets = state.volume.sumOf { it.sets }
                    val totalVolume = state.volume.sumOf { it.volume }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(
                            label = "SESSIONS",
                            value = state.volume.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "WORKING SETS",
                            value = totalSets.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "TONNAGE",
                            value = "${(totalVolume / 1000).roundToInt()}t",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(
                        points = state.volume.map {
                            ChartPoint(0f, it.volume.toFloat(), Dates.shortLabel(it.localDate))
                        },
                        target = null,
                        barColor = Macro.fiber,
                        height = 160.dp,
                        yFormatter = { "${(it / 1000).roundToInt()}t" }
                    )
                }
            }
        }

        // -------------------------------------------------- wearable
        item { SectionHeader("Wearable") }
        item {
            ForgeCard {
                if (state.health.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Sync,
                        title = "No wearable data",
                        body = "Turn on Health Connect sync inside the Zepp app, then pull the data in here.",
                        action = {
                            Pill(
                                if (state.syncing) "Syncing..." else "Sync now",
                                icon = Icons.Outlined.Sync,
                                filled = true,
                                onClick = vm::syncHealth
                            )
                        }
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(
                            label = "AVG STEPS",
                            value = state.avgSteps?.toString() ?: "-",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "AVG SLEEP",
                            value = state.avgSleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "-",
                            accent = Macro.fiber,
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = "RESTING HR",
                            value = state.health.mapNotNull { it.restingHr }
                                .takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toString() ?: "-",
                            accent = Macro.fat,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(
                        points = state.health.mapNotNull { h ->
                            h.steps?.let { ChartPoint(0f, it.toFloat(), Dates.shortLabel(h.localDate)) }
                        },
                        target = null,
                        barColor = Macro.protein,
                        height = 150.dp,
                        yFormatter = { "${(it / 1000).roundToInt()}k" }
                    )
                    Spacer(Modifier.height(14.dp))
                    Pill(
                        if (state.syncing) "Syncing..." else "Sync now",
                        icon = Icons.Outlined.Sync,
                        onClick = vm::syncHealth
                    )
                }

                state.syncMessage?.let { msg ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { vm.clearSyncMessage() }
                    )
                }
            }
        }
    }
}

@Composable
private fun rateColor(kgPerWeek: Double): Color = when {
    abs(kgPerWeek) < 0.1 -> MaterialTheme.colorScheme.onSurface
    kgPerWeek < 0 -> Macro.protein
    else -> Macro.carbs
}

@Composable
private fun WindowTabs(current: Window, onSelect: (Window) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Window.entries.forEach { w ->
            val selected = w == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelect(w) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    w.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
