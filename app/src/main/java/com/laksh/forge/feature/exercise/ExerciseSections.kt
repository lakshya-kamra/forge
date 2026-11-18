package com.laksh.forge.feature.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.exercise.CatalogLicence
import com.laksh.forge.domain.exercise.Creator
import com.laksh.forge.domain.exercise.ExerciseExtras
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.exercise.ExerciseLabels
import com.laksh.forge.domain.exercise.LiftHistory
import com.laksh.forge.domain.exercise.LiftRecord
import com.laksh.forge.domain.exercise.SessionBest
import com.laksh.forge.ui.components.ChartPoint
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.IconBadge
import com.laksh.forge.ui.components.LineChart
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.ShimmerCard
import com.laksh.forge.ui.components.StatTile
import com.laksh.forge.util.Dates
import kotlin.math.roundToInt

// ---------------------------------------------------------------------- muscles & equipment

@Composable
internal fun MuscleCard(info: ExerciseInfo) {
    ForgeCard {
        if (info.primaryMuscles.isNotEmpty()) {
            Label("Primary")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info.primaryMuscles.forEach { Pill(ExerciseLabels.muscle(it), filled = true) }
            }
        }
        if (info.secondaryMuscles.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Label("Secondary")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info.secondaryMuscles.forEach {
                    Pill(ExerciseLabels.muscle(it), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        if (info.equipment.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Label("Equipment")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info.equipment.forEach {
                    Pill(
                        ExerciseLabels.equipment(it),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Outlined.FitnessCenter
                    )
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

// ---------------------------------------------------------------------- cues & mistakes

@Composable
internal fun PointsCard(items: List<String>, icon: ImageVector, tint: Color) {
    ForgeCard {
        items.forEachIndexed { i, text ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 1.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

internal val CueIcon: ImageVector get() = Icons.Outlined.CheckCircle
internal val MistakeIcon: ImageVector get() = Icons.Outlined.WarningAmber

// ---------------------------------------------------------------------- videos

@Composable
internal fun VideoCard(item: VideoItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val v = item.video
    ForgeCard(modifier = modifier, onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            AsyncImage(
                model = v.thumbnailUrl,
                contentDescription = "Video thumbnail: ${v.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                v.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.author,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                item.badgeTier?.let { tier ->
                    Spacer(Modifier.width(8.dp))
                    GvsBadge(tier)
                }
            }
            if (v.why.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(v.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "GVS S" / "GVS A": the creator's tier on Geoffrey Verity Schofield's tier list. */
@Composable
internal fun GvsBadge(tier: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "GVS $tier",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ---------------------------------------------------------------------- photos

@Composable
internal fun PhotoStrip(name: String, images: List<String>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        itemsIndexed(images, key = { _, url -> url }) { i, url ->
            AsyncImage(
                model = url,
                contentDescription = "$name, photo ${i + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(240.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}

/** Used when an exercise has no 3D demo (or it failed to build): the photos take the hero slot. */
@Composable
internal fun PhotoHero(name: String, images: List<String>, note: String?) {
    Column {
        if (images.isEmpty()) {
            ForgeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Image)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        note ?: "No demonstration or photos for this exercise.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            PhotoStrip(name, images)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- history

@Composable
internal fun HistorySection(state: ExerciseDetailState) {
    val h = state.history
    when {
        state.historyLoading -> ShimmerCard(lines = 4)
        state.historyError != null -> ErrorNotice(state.historyError)
        h == null || h.isEmpty -> ForgeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.FitnessCenter)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("No sets logged yet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your best estimated max, records and a trend chart appear here after you log this lift.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        else -> HistoryCard(h, state.unit)
    }
}

@Composable
private fun HistoryCard(h: LiftHistory, unit: WeightUnit) {
    val points = remember(h, unit) {
        h.sessions.mapNotNull { s ->
            val y = if (h.bodyweightOnly) s.maxReps.toDouble() else s.bestE1rmKg?.let { com.laksh.forge.domain.NutritionMath.kgToDisplay(it, unit) }
            y?.let { ChartPoint(x = (s.timestamp / 86_400_000.0).toFloat(), y = it.toFloat(), label = Dates.shortLabel(s.timestamp)) }
        }
    }
    ForgeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (h.bodyweightOnly) {
                RecordTile("Most reps", h.mostReps?.let { "${it.reps}" }, h.mostReps, Modifier.weight(1f))
                RecordTile("Sessions", "${h.sessions.size}", null, Modifier.weight(1f))
                RecordTile("Sets", "${h.totalSets}", null, Modifier.weight(1f))
            } else {
                RecordTile("Best e1RM", h.bestE1rm?.let { ExerciseFormat.weight(it.value, unit) }, h.bestE1rm, Modifier.weight(1f))
                RecordTile("Heaviest", h.heaviest?.let { ExerciseFormat.weight(it.weightKg, unit) }, h.heaviest, Modifier.weight(1f), repsSub = true)
                RecordTile("Most reps", h.mostReps?.let { "${it.reps}" }, h.mostReps, Modifier.weight(1f), weightSub = unit)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (h.bodyweightOnly) "Most reps per session" else "Best estimated 1RM per session (Epley, sets of 12 or fewer)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LineChart(
            points = points,
            yFormatter = { it.roundToInt().toString() },
            height = 180.dp
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Text("Recent sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        h.sessions.asReversed().take(5).forEach { s -> SessionRow(s, unit, h.bodyweightOnly) }
    }
}

@Composable
private fun RecordTile(
    label: String,
    value: String?,
    record: LiftRecord?,
    modifier: Modifier,
    repsSub: Boolean = false,
    weightSub: WeightUnit? = null
) {
    val sub = record?.let { r ->
        val detail = when {
            repsSub -> "× ${r.reps} · "
            weightSub != null && r.weightKg > 0.0 -> "@ ${ExerciseFormat.weight(r.weightKg, weightSub)} · "
            else -> ""
        }
        detail + Dates.shortLabel(r.timestamp)
    }
    StatTile(label = label, value = value ?: "–", modifier = modifier, sub = sub)
}

@Composable
private fun SessionRow(s: SessionBest, unit: WeightUnit, bodyweight: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            Dates.dayLabel(Dates.dayOf(s.timestamp)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        val top = if (bodyweight || s.topWeightKg <= 0.0) "${s.maxReps} reps"
        else "${ExerciseFormat.weight(s.topWeightKg, unit)} × ${s.topWeightReps}"
        Text(top, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(
            "${s.sets} ${if (s.sets == 1) "set" else "sets"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------- unknown lifts

@Composable
internal fun NotInLibraryCard(name: String) {
    ForgeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.Search, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Not in the exercise library", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Forge has no demonstration for “$name”. Your logged sets are below, and the links search trusted coaches' channels for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One row per trusted technique channel, each opening that channel's own search for [query]. */
@Composable
internal fun CreatorSearchCard(query: String, creators: List<Creator>, onOpen: (String) -> Unit) {
    // GVS-ranked creators first, best tier first; unranked keep the catalog's order.
    val ordered = remember(creators) { creators.sortedBy { c -> c.tier?.takeIf { it.length == 1 }?.let { TIER_ORDER.indexOf(it) }?.takeIf { it >= 0 } ?: TIER_ORDER.length } }
    ForgeCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        ordered.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickableRow { onOpen(c.searchUrl(query)) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        c.tier?.takeIf { it == "S" || it == "A" }?.let { tier ->
                            Spacer(Modifier.width(8.dp))
                            GvsBadge(tier)
                        }
                    }
                    Text(
                        "Search “$query” on ${c.channel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private const val TIER_ORDER = "SABCD"

/** Plain clickable with the theme ripple; callers clip first so the ripple follows the shape. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

// ---------------------------------------------------------------------- sources

@Composable
internal fun SourcesCard(extras: ExerciseExtras?, licence: CatalogLicence?, hasMotion: Boolean, onOpen: (String) -> Unit) {
    ForgeCard(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        val small = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (hasMotion && extras != null) {
            Text("3D motion", style = MaterialTheme.typography.labelLarge, color = muted)
            extras.angleBasis?.let { Text("Joint angles: $it.", style = small, color = muted, modifier = Modifier.padding(top = 4.dp)) }
            extras.motionSources.forEach { src ->
                val url = src.url
                Text(
                    src.cite,
                    style = small,
                    color = if (url != null) MaterialTheme.colorScheme.primary else muted,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .then(if (url != null) Modifier.clickableRow { onOpen(url) } else Modifier)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        extras?.imageSource?.let { img ->
            Text("Photos", style = MaterialTheme.typography.labelLarge, color = muted)
            val entry = img.entryId?.let { " entry “$it”" }.orEmpty()
            val match = img.match?.let { " ($it match)" }.orEmpty()
            Text("${img.dataset}$entry$match. ${img.note}".trim(), style = small, color = muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (licence != null) {
            Text("Licences", style = MaterialTheme.typography.labelLarge, color = muted)
            listOf(licence.images, licence.videos, licence.gvs, licence.animation).filter { it.isNotBlank() }.forEach {
                Text(it, style = small, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
            if (licence.imagesUrl.isNotBlank()) {
                Text(
                    licence.imagesUrl,
                    style = small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickableRow { onOpen(licence.imagesUrl) }
                )
            }
        }
    }
}
