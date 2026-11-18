package com.laksh.forge.feature.trends

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.feature.wearables.WearableTrendsSection
import com.laksh.forge.ui.components.BarChart
import com.laksh.forge.ui.components.ConnectedChoiceGroup
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.IconBadge
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.LocalFloatingBarClearance
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.ShimmerCard
import com.laksh.forge.ui.components.StatTile
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.ui.theme.accentFor
import com.laksh.forge.ui.theme.tintedContainer
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TrendsScreen(vm: TrendsViewModel = viewModel(factory = TrendsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    TrendsContent(
        state = state,
        onWindow = vm::setWindow,
        onRetry = vm::retry,
        trainingSlot = { TrainingVolumeSection() },
        wearableSlot = { WearableTrendsSection() }
    )
}

/** Stateless Trends; the training and wearable sections are slots owned by their features. */
@Composable
fun TrendsContent(
    state: TrendsState,
    onWindow: (Window) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    trainingSlot: @Composable () -> Unit = {},
    wearableSlot: @Composable () -> Unit = {}
) {
    val clearance = LocalFloatingBarClearance.current
    val unit = state.settings.weightUnit

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = clearance + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "title", contentType = "title") {
            Text(
                "Trends",
                style = MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item(key = "window", contentType = "window") {
            ConnectedChoiceGroup(
                options = Window.entries,
                selected = state.window,
                onSelect = onWindow,
                label = { it.label }
            )
        }

        if (state.error != null) {
            item(key = "error", contentType = "error") { ErrorNotice(state.error, onRetry = onRetry) }
            return@LazyColumn
        }

        if (state.loading) {
            item(key = "loading", contentType = "loading") { ShimmerCard(lines = 6) }
            return@LazyColumn
        }

        state.feedback?.let { fb ->
            item(key = "feedback", contentType = "feedback") {
                ForgeCard(
                    color = MaterialTheme.colorScheme.tintedContainer(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.animateItem()
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconBadge(Icons.Outlined.Insights, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(fb, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // -------------------------------------------------- weight
        item(key = "weight_header", contentType = "section") { SectionHeader("Bodyweight") }
        item(key = "weight", contentType = "chart_card") {
            ForgeCard {
                val trend = state.trend
                if (trend == null || state.trendPoints.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Insights,
                        title = "Log a few weigh-ins",
                        body = "Two or more readings and the trend line appears. Daily is best - the smoothing handles the noise."
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatTile(
                            label = "Trend",
                            value = "%.1f %s".format(
                                NutritionMath.kgToDisplay(trend.latestSmoothed ?: 0.0, unit),
                                unit.label
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (trend.hasRate) {
                            StatTile(
                                label = "Rate",
                                value = "%+.2f".format(NutritionMath.kgToDisplay(trend.kgPerWeek, unit)),
                                sub = "${unit.label}/week",
                                accent = rateColor(trend.kgPerWeek),
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            // One day of readings has no rate; "+0.00" would read as "holding steady".
                            StatTile(
                                label = "Rate",
                                value = "N/A",
                                sub = "needs 2+ days",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        StatTile(
                            label = "Change",
                            value = trend.changeOverWindow?.let {
                                "%+.1f".format(NutritionMath.kgToDisplay(it, unit))
                            } ?: "-",
                            sub = state.window.label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    if (trend.hasRate) {
                        LineChart(
                            points = state.trendPoints,
                            rawPoints = state.rawPoints,
                            lineColor = MaterialTheme.colorScheme.primary,
                            yFormatter = { "%.1f".format(it) },
                            height = 210.dp
                        )
                    } else {
                        // Readings from a single day: show where you are, not a line through it.
                        LineChart(
                            points = listOf(state.trendPoints.last()),
                            lineColor = MaterialTheme.colorScheme.primary,
                            yFormatter = { "%.1f".format(it) },
                            height = 210.dp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (trend.hasRate) "Dots are individual weigh-ins; the line is the smoothed trend."
                        else "Log weigh-ins on a few more days and your trend line will appear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // -------------------------------------------------- intake
        item(key = "intake_header", contentType = "section") { SectionHeader("Intake") }
        item(key = "intake", contentType = "chart_card") {
            ForgeCard {
                when {
                    state.settings.hideNumbers -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.VisibilityOff, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Calorie numbers are hidden. You logged on ${state.loggedDays} days in this window.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    state.dayTotals.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Restaurant,
                        title = "No food logged yet",
                        body = "Log meals on Today and your daily intake builds up here against your target."
                    )

                    else -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile(
                                label = "Avg intake",
                                value = state.avgKcal?.roundToInt()?.toString() ?: "-",
                                sub = "kcal/day",
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Avg protein",
                                value = state.avgProtein?.roundToInt()?.toString() ?: "-",
                                sub = "g/day",
                                accent = MaterialTheme.colorScheme.accentFor(Macro.protein),
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Maintenance",
                                value = state.settings.learnedTdee.takeIf { it > 0 }?.roundToInt()?.toString() ?: "-",
                                sub = if (state.settings.learnedTdee > 0) "learned" else "need more data",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        BarChart(
                            points = state.intakePoints,
                            target = state.targets?.kcal?.toFloat(),
                            height = 190.dp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.targetsFromPlan) "Dashed line is your plan's daily target."
                            else "Dashed line is your daily target.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // -------------------------------------------------- feature sections
        item(key = "training", contentType = "slot") { trainingSlot() }
        item(key = "wearables", contentType = "slot") { wearableSlot() }
    }
}

@Composable
private fun rateColor(kgPerWeek: Double): Color = when {
    abs(kgPerWeek) < 0.1 -> MaterialTheme.colorScheme.onSurface
    kgPerWeek < 0 -> MaterialTheme.colorScheme.accentFor(Macro.protein)
    else -> MaterialTheme.colorScheme.accentFor(Macro.carbs)
}
