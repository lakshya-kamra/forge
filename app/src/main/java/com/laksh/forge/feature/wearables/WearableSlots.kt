package com.laksh.forge.feature.wearables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.readiness.ReadinessComponent
import com.laksh.forge.domain.readiness.ReadinessLevel
import com.laksh.forge.domain.readiness.ReadinessMetric
import com.laksh.forge.domain.readiness.Signal
import com.laksh.forge.ui.components.BarChart
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.StatTile
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ====================================================================== readiness card

/**
 * Today's readiness: each component against the person's own baseline, plus WHOOP's recovery
 * exactly as WHOOP reported it. Tapping opens the Wearables screen.
 */
@Composable
fun ReadinessCard(modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val vm: ReadinessViewModel = viewModel(factory = ReadinessViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()

    ForgeCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.MonitorHeart, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Readiness",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val ready = state as? ReadinessUi.Ready
            ready?.estimate?.level?.let { Pill(levelShort(it), color = levelColor(it), filled = true) }
        }
        Spacer(Modifier.height(10.dp))

        when (val s = state) {
            ReadinessUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Reading your wearable data…", style = MaterialTheme.typography.bodyMedium)
            }

            ReadinessUi.NoData -> {
                Text(
                    "Connect a wearable to see how today compares with your own baseline.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Pill("Set up wearables", icon = Icons.Outlined.Watch, onClick = onOpen)
            }

            is ReadinessUi.Ready -> ReadinessBody(s)
        }
    }
}

@Composable
private fun ReadinessBody(s: ReadinessUi.Ready) {
    val estimate = s.estimate
    Column {
        Text(
            estimate.level?.let { levelHeadline(it) } ?: "Not enough history yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        s.whoopRecovery?.let { rec ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "WHOOP recovery ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${rec.roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (s.whoopRecoveryIsToday) "  as reported by WHOOP" else "  yesterday, as reported by WHOOP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (estimate.components.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                estimate.components.forEach { ComponentRow(it, estimate.today) }
            }
        }

        if (estimate.missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            estimate.missing.forEach { m ->
                // Hanging indent: wrapped lines align with the text, not under the bullet.
                Row {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        m.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (s.noRmssdSource && estimate.missing.any { it.metric == ReadinessMetric.HRV }) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "RMSSD reaches Forge from Pixel Watch/Fitbit or Oura via Health Connect, or from WHOOP's API. Apple's SDNN can't be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Each line compares you only with your own history; \"usual\" is your average ± one standard deviation. " +
                "The overall label needs at least two lines and goes with whichever way more of them lean. Not medical advice.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ComponentRow(c: ReadinessComponent, today: LocalDate) {
    val title = when (c.metric) {
        ReadinessMetric.HRV -> "HRV (RMSSD), ${c.currentDays}-day avg"
        ReadinessMetric.RESTING_HR -> if (c.currentDate == today) "Resting HR today" else "Resting HR yesterday"
        ReadinessMetric.SLEEP -> if (c.currentDate == today) "Sleep last night" else "Sleep (night before)"
    }
    val value = formatValue(c.metric, c.current)
    val range = "usual ${formatValue(c.metric, c.usualLow)}–${formatValue(c.metric, c.usualHigh)}"
    val verdict = when {
        c.signal == Signal.USUAL && c.metric == ReadinessMetric.SLEEP && c.z >= 1.0 -> "longer than usual"
        c.signal == Signal.USUAL -> "within usual"
        c.z > 0 -> "above usual"
        else -> "below usual"
    }
    val tint = when (c.signal) {
        Signal.FAVOURABLE -> MaterialTheme.colorScheme.primary
        Signal.USUAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Signal.UNFAVOURABLE -> MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$range · ${c.baselineDays}-day baseline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(verdict, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

private fun formatValue(metric: ReadinessMetric, v: Double): String = when (metric) {
    ReadinessMetric.HRV -> "${v.roundToInt()} ms"
    ReadinessMetric.RESTING_HR -> "${v.roundToInt()} bpm"
    ReadinessMetric.SLEEP -> hoursMinutes(v)
}

private fun hoursMinutes(minutes: Double): String {
    val total = minutes.roundToLong().coerceAtLeast(0)
    return "${total / 60}h ${total % 60}m"
}

private fun levelHeadline(level: ReadinessLevel): String = when (level) {
    ReadinessLevel.ABOVE_USUAL -> "Better than your usual"
    ReadinessLevel.USUAL -> "Within your usual range"
    ReadinessLevel.BELOW_USUAL -> "Below your usual"
}

private fun levelShort(level: ReadinessLevel): String = when (level) {
    ReadinessLevel.ABOVE_USUAL -> "Above usual"
    ReadinessLevel.USUAL -> "Usual"
    ReadinessLevel.BELOW_USUAL -> "Below usual"
}

@Composable
private fun levelColor(level: ReadinessLevel): Color = when (level) {
    ReadinessLevel.ABOVE_USUAL -> MaterialTheme.colorScheme.primary
    ReadinessLevel.USUAL -> MaterialTheme.colorScheme.secondary
    ReadinessLevel.BELOW_USUAL -> MaterialTheme.colorScheme.tertiary
}

// ====================================================================== trends section

/** Wearable charts for Trends: HRV, resting HR, sleep, steps, VO2 max, plus WHOOP/Apple extras. */
@Composable
fun WearableTrendsSection(modifier: Modifier = Modifier) {
    val vm: WearableTrendsViewModel = viewModel(factory = WearableTrendsViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Wearables")
        WindowToggle(state.window) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            vm.setWindow(it)
        }

        when {
            state.loading -> ForgeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Loading wearable trends…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            !state.hasData -> ForgeCard {
                EmptyState(
                    icon = Icons.Outlined.Watch,
                    title = "No wearable data in this window",
                    body = "Connect Health Connect or WHOOP, or import an Apple Health export, from the Wearables screen."
                )
            }

            else -> {
                LineMetricCard(
                    title = "HRV (RMSSD)",
                    series = state.hrv,
                    unit = "ms",
                    color = MaterialTheme.colorScheme.primary,
                    note = "Overnight RMSSD from Health Connect or WHOOP. Day-to-day swings are normal; read the trend."
                )
                LineMetricCard(
                    title = "Resting heart rate",
                    series = state.restingHr,
                    unit = "bpm",
                    color = MaterialTheme.colorScheme.tertiary,
                    note = null
                )
                BarMetricCard(
                    title = if (state.weekly) "Sleep (weekly average)" else "Sleep",
                    series = state.sleepHours,
                    color = MaterialTheme.colorScheme.secondary,
                    target = null,
                    format = { hoursMinutes(it * 60.0) },
                    axis = { "%.1fh".format(it) }
                )
                BarMetricCard(
                    title = if (state.weekly) "Steps (weekly average)" else "Steps",
                    series = state.steps,
                    color = MaterialTheme.colorScheme.primary,
                    target = state.stepsTarget?.toFloat(),
                    format = { "%,d".format(it.roundToLong()) },
                    axis = { if (it >= 1000f) "${(it / 1000f).roundToInt()}k" else it.roundToInt().toString() }
                )
                if (!state.vo2max.isEmpty) {
                    LineMetricCard(
                        title = "VO2 max",
                        series = state.vo2max,
                        unit = "mL/kg/min",
                        color = MaterialTheme.colorScheme.secondary,
                        note = "As estimated by your device; brands estimate it differently."
                    )
                }
                if (!state.recovery.isEmpty) {
                    LineMetricCard(
                        title = "WHOOP recovery",
                        series = state.recovery,
                        unit = "%",
                        color = MaterialTheme.colorScheme.primary,
                        note = "Exactly as reported by WHOOP."
                    )
                }
                if (!state.appleSdnn.isEmpty) {
                    LineMetricCard(
                        title = "HRV (SDNN, Apple Health import)",
                        series = state.appleSdnn,
                        unit = "ms",
                        color = MaterialTheme.colorScheme.tertiary,
                        note = "Apple measures SDNN, a different statistic from RMSSD, so it is charted on its own and not used for readiness."
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowToggle(selected: TrendWindow, onSelect: (TrendWindow) -> Unit) {
    val windows = TrendWindow.entries
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        windows.forEachIndexed { i, w ->
            ToggleButton(
                checked = w == selected,
                onCheckedChange = { onSelect(w) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    windows.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton }
            ) {
                Text(w.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LineMetricCard(
    title: String,
    series: TrendSeries,
    unit: String,
    color: Color,
    note: String?
) {
    ForgeCard {
        MetricHeader(
            title = title,
            latest = series.latest?.let { "${it.roundToInt()} $unit" },
            average = series.average?.let { "${it.roundToInt()} $unit" }
        )
        Spacer(Modifier.height(12.dp))
        LineChart(
            points = series.points,
            lineColor = color,
            yFormatter = { it.roundToInt().toString() },
            height = 160.dp
        )
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarMetricCard(
    title: String,
    series: TrendSeries,
    color: Color,
    target: Float?,
    format: (Double) -> String,
    axis: (Float) -> String
) {
    ForgeCard {
        MetricHeader(
            title = title,
            latest = series.latest?.let(format),
            average = series.average?.let(format)
        )
        Spacer(Modifier.height(12.dp))
        // overColor = barColor: for steps and sleep, beating the line is not a warning.
        BarChart(
            points = series.points,
            target = target,
            barColor = color,
            overColor = color,
            height = 160.dp,
            yFormatter = axis
        )
    }
}

@Composable
private fun MetricHeader(title: String, latest: String?, average: String?) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        StatTile(label = "LATEST", value = latest ?: "-", modifier = Modifier.weight(1f))
        StatTile(label = "AVERAGE", value = average ?: "-", modifier = Modifier.weight(1f))
    }
}
