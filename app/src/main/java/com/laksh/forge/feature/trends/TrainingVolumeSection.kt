package com.laksh.forge.feature.trends

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Severity
import com.laksh.forge.domain.training.MuscleVolume
import com.laksh.forge.domain.training.VolumeAnalyzer
import com.laksh.forge.domain.training.VolumeReport
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
sealed interface VolumeUiState {
    data object Loading : VolumeUiState
    data class Ready(val report: VolumeReport, val scaleMax: Float) : VolumeUiState
    data class Error(val message: String) : VolumeUiState
}

class TrainingVolumeViewModel(repo: ForgeRepository) : ViewModel() {

    private val today = LocalDate.now()
    private val insights = ForgeDatabase.get(ForgeApp.instance).insightsDao()

    val state: StateFlow<VolumeUiState> = combine(
        insights.observeWorkingSets(today.minusDays(13).toString(), today.toString()),
        repo.settingsStore.settings.map { it.trainingAge }.distinctUntilChanged()
    ) { sets, trainingAge ->
        val report = VolumeAnalyzer.analyzeRollingWeek(sets, today, trainingAge)
        // One shared scale so bars compare across muscles; it always reaches past the 30-set
        // warning line so the bands keep their position from week to week.
        val top = report.muscles.maxOfOrNull { max(it.sets, it.previousSets) } ?: 0.0
        val scale = max(Guardrails.WARN_ABOVE_SETS + 2.0, top * 1.08)
        VolumeUiState.Ready(report, scale.toFloat()) as VolumeUiState
    }
        // Catalog lookup and counting stay off the main thread.
        .flowOn(Dispatchers.Default)
        .catch { emit(VolumeUiState.Error(it.message ?: "Couldn't load training volume.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VolumeUiState.Loading)

    companion object {
        val Factory = forgeFactory { TrainingVolumeViewModel(it) }
    }
}

/**
 * Weekly hard sets per muscle against the evidence band (training_weekly_sets_per_muscle):
 * minimum effective 4, typical 10-20, warning above 30, with the guardrail notes underneath.
 */
@Composable
fun TrainingVolumeSection(
    modifier: Modifier = Modifier,
    vm: TrainingVolumeViewModel = viewModel(factory = TrainingVolumeViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        SectionHeader("Hard sets per muscle · last 7 days")
        ForgeCard {
            when (val s = state) {
                VolumeUiState.Loading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator(Modifier.size(48.dp)) }

                is VolumeUiState.Error -> Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.ErrorOutline, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                }

                is VolumeUiState.Ready -> if (!s.report.hasData) {
                    EmptyState(
                        icon = Icons.Outlined.FitnessCenter,
                        title = "No working sets this week",
                        body = "Log a session and each muscle's weekly hard sets appear here, measured against the 10-20 set range most people grow well on."
                    )
                } else {
                    VolumeContent(s.report, s.scaleMax)
                }
            }
        }
    }
}

@Composable
private fun VolumeContent(report: VolumeReport, scaleMax: Float) {
    val trained = report.muscles.filter { it.sets > 0.0 }
    if (trained.isEmpty()) {
        Text(
            "No logged sets counted as hard sets this week.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Legend()
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            trained.forEach { m -> MuscleBar(m, scaleMax) }
        }
    }

    if (report.flags.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            report.flags.forEach { FlagRow(it) }
        }
    }

    Spacer(Modifier.height(14.dp))
    val notes = buildList {
        add("${report.hardSets} hard sets counted. A set counts fully for the muscles an exercise targets and half for muscles it works indirectly; sets with 5 or more reps in reserve don't count.")
        if (report.easySetsExcluded > 0) {
            add("${report.easySetsExcluded} ${if (report.easySetsExcluded == 1) "set was" else "sets were"} logged too far from failure to count.")
        }
        if (report.unresolved.isNotEmpty()) {
            add("Not matched to the exercise library, so not counted: ${report.unresolved.take(6).joinToString(", ")}${if (report.unresolved.size > 6) " and ${report.unresolved.size - 6} more" else ""}.")
        }
        if (report.untrained.isNotEmpty() && trained.isNotEmpty()) {
            add("No hard sets: ${report.untrained.joinToString(", ")}.")
        }
    }
    notes.forEach {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
    Text(
        "Bands: 4 minimum (Iversen 2021); 10-20 typical (Muscle and Strength Pyramid; Baz-Valle 2022); diminishing returns past 20 (Schoenfeld 2017; Pelland 2025). The 30-set warning is a Forge convention.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    )
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), "10-20 typical")
        LegendItem(MaterialTheme.colorScheme.outline, "4 minimum", dashed = true)
        LegendItem(MaterialTheme.colorScheme.error, "30+ warning")
    }
}

@Composable
private fun LegendItem(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = if (dashed) 3.dp else 12.dp, height = 12.dp)
                .clip(if (dashed) CircleShape else RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MuscleBar(m: MuscleVolume, scaleMax: Float) {
    val colors = MaterialTheme.colorScheme
    val barColor = when {
        m.sets > Guardrails.WARN_ABOVE_SETS -> colors.error
        m.sets > Guardrails.TYPICAL_MAX_SETS -> colors.tertiary
        m.sets >= Guardrails.TYPICAL_MIN_SETS -> colors.primary
        m.sets >= Guardrails.MIN_EFFECTIVE_SETS -> colors.secondary
        else -> colors.outline
    }
    val track = colors.surfaceContainerHighest
    val band = colors.primary.copy(alpha = 0.16f)
    val minLine = colors.outline
    val warnLine = colors.error.copy(alpha = 0.8f)
    val fraction = (m.sets.toFloat() / scaleMax).coerceIn(0f, 1f)
    // Spatial spring from the theme; the value is read in the draw phase only, so the bar
    // animates without recomposing the row.
    val animated = animateFloatAsState(
        targetValue = fraction,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "volume-${m.muscle}"
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            m.label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(92.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(14.dp)
                .drawBehind {
                    val r = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    val x10 = size.width * (Guardrails.TYPICAL_MIN_SETS.toFloat() / scaleMax)
                    val x20 = size.width * (Guardrails.TYPICAL_MAX_SETS.toFloat() / scaleMax)
                    drawRect(band, topLeft = Offset(x10, 0f), size = Size(x20 - x10, size.height))
                    val fill = size.width * animated.value.coerceIn(0f, 1f)
                    if (fill > 0f) {
                        drawRoundRect(
                            barColor,
                            size = Size(max(fill, size.height), size.height),
                            cornerRadius = r
                        )
                    }
                    val x4 = size.width * (Guardrails.MIN_EFFECTIVE_SETS.toFloat() / scaleMax)
                    drawLine(
                        minLine, Offset(x4, -2f), Offset(x4, size.height + 2f),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                    )
                    val x30 = size.width * (Guardrails.WARN_ABOVE_SETS.toFloat() / scaleMax)
                    drawLine(warnLine, Offset(x30, -2f), Offset(x30, size.height + 2f), strokeWidth = 1.5.dp.toPx())
                }
        )
        Column(Modifier.width(58.dp), horizontalAlignment = Alignment.End) {
            Text(
                fmtSets(m.sets),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            val delta = m.sets - m.previousSets
            if (m.previousSets > 0.0 && kotlin.math.abs(delta) >= 0.5) {
                Text(
                    (if (delta > 0) "+" else "") + fmtSets(delta),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FlagRow(flag: GuardrailFlag) {
    val (icon, tint) = when (flag.severity) {
        Severity.BLOCK -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        Severity.CAUTION -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
        Severity.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(flag.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun fmtSets(v: Double): String {
    val rounded = (v * 2).roundToInt() / 2.0   // fractional counting moves in half sets
    return if (rounded == rounded.roundToInt().toDouble()) rounded.roundToInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
}
