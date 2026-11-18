package com.laksh.forge.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laksh.forge.domain.program.LibraryExercise
import com.laksh.forge.domain.program.LibraryProgram
import com.laksh.forge.domain.program.Strength
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill

internal fun categoryLabel(c: String) = when (c.uppercase()) {
    "STRENGTH" -> "Strength"
    "HYPERTROPHY" -> "Hypertrophy"
    "POWERBUILDING" -> "Powerbuilding"
    "GENERAL" -> "General"
    else -> c.lowercase().replaceFirstChar { it.uppercase() }
}

internal fun levelLabel(l: String) = when (l.uppercase()) {
    "BEGINNER" -> "Beginner"
    "INTERMEDIATE" -> "Intermediate"
    "ADVANCED" -> "Advanced"
    else -> l.lowercase().replaceFirstChar { it.uppercase() }
}

internal fun equipmentLabel(e: String) = when (e) {
    "barbell" -> "Barbell"
    "rack" -> "Squat rack"
    "bench" -> "Bench"
    "dumbbells" -> "Dumbbells"
    "cable" -> "Cables"
    "machines" -> "Machines"
    "pullup_bar" -> "Pull-up bar"
    else -> e.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** "3 days · ~60 min · 12 weeks" */
internal fun programMeta(p: LibraryProgram): String = buildList {
    add("${p.daysPerWeek} days/wk")
    p.sessionMinutes?.let { add("~$it min") }
    p.durationWeeks?.let { add("$it weeks") } ?: add("open-ended")
}.joinToString(" · ")

/** Compact sets/reps/intensity line for a template exercise. */
internal fun describeExercise(ex: LibraryExercise): String {
    val scheme = ex.setScheme.orEmpty()
    val withPct = scheme.filter { it.percent != null }
    val core = if (withPct.isNotEmpty() && withPct.size == scheme.size) {
        val basis = scheme.firstNotNullOfOrNull { it.of } ?: ""
        // Collapse runs of identical sets: "3×5 @ 85%" instead of three entries.
        val parts = mutableListOf<String>()
        var i = 0
        while (i < scheme.size) {
            val s = scheme[i]
            var j = i
            while (j + 1 < scheme.size && scheme[j + 1] == s) j++
            val count = j - i + 1
            val reps = "${s.reps ?: ex.repsLow ?: "?"}${if (s.amrap) "+" else ""}"
            val pct = Strength.fmt((s.percent ?: 0.0) * 100) + "%"
            val add = s.addKg?.takeIf { it != 0.0 }?.let { (if (it > 0) " +" else " −") + Strength.fmt(kotlin.math.abs(it)) + " kg" }.orEmpty()
            parts += (if (count > 1) "$count×" else "") + "$reps @ $pct$add"
            i = j + 1
        }
        val basisLabel = when (basis) {
            "TOP_SET" -> " of top set"
            "RECENT_RM" -> " of your 3-5RM"
            "" -> ""
            else -> " $basis"
        }
        parts.joinToString(", ") + basisLabel
    } else if (scheme.isNotEmpty() && scheme.any { it.repsLow != null || it.reps != null }) {
        scheme.joinToString(", ") { s ->
            val lo = s.repsLow ?: s.reps
            val hi = s.repsHigh
            if (hi != null && lo != null && hi > lo) "$lo-$hi" else "${lo ?: "?"}${if (s.amrap) "+" else ""}"
        }.let { "${scheme.size} sets: $it" }
    } else {
        val lo = ex.repsLow
        val hi = ex.repsHigh
        val reps = when {
            lo == null && hi == null -> "max reps"
            lo != null && hi != null && hi > lo -> "$lo-$hi"
            else -> "${lo ?: hi}"
        }
        "${ex.sets ?: 1} × $reps${if (ex.lastSetAmrap) " (last set AMRAP)" else ""}"
    }
    val effort = when (ex.intensity?.type) {
        "RIR" -> ex.intensity?.value?.let { " · ${Strength.fmt(it)} RIR" }
        "RPE" -> ex.intensity?.value?.let { " · RPE ${Strength.fmt(it)}" }
        else -> null
    }.orEmpty()
    val note1rm = ex.note1rm?.let { " · $it" }.orEmpty()
    return core + effort + note1rm
}

internal fun restLabel(seconds: Int): String =
    if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} rest" else "${seconds}s rest"

/** Sources open in a Custom Tab so the user stays in context; a plain VIEW intent is the fallback. */
internal fun openUrl(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
internal fun FreeBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Verified, null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "Free - published by the author",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun ProgramCard(
    program: LibraryProgram,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    compact: Boolean = false
) {
    ForgeCard(
        modifier = modifier,
        onClick = onClick,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill(categoryLabel(program.category), filled = true, color = categoryColor(program.category))
            Pill(levelLabel(program.level))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            program.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            program.author,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.CalendarMonth, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                programMeta(program),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (compact) program.summary else program.evidenceNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (compact) 3 else 4,
            overflow = TextOverflow.Ellipsis
        )
        if (program.isFree && !compact) {
            Spacer(Modifier.height(12.dp))
            Box { FreeBadge() }
        }
    }
}

/** Category accents come from the theme roles so dynamic colour keeps them harmonious. */
@Composable
internal fun categoryColor(category: String): Color = when (category.uppercase()) {
    "STRENGTH" -> MaterialTheme.colorScheme.primary
    "HYPERTROPHY" -> MaterialTheme.colorScheme.tertiary
    "POWERBUILDING" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
